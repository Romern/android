package org.cryptomator.data.repository;

import com.google.common.io.BaseEncoding;

import java.io.File;
import java.io.IOException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLHandshakeException;

import org.cryptomator.data.db.Database;
import org.cryptomator.data.db.entities.UpdateCheckEntity;
import org.cryptomator.data.util.UserAgentInterceptor;
import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.exception.FatalBackendException;
import org.cryptomator.domain.exception.update.GeneralUpdateErrorException;
import org.cryptomator.domain.exception.update.SSLHandshakePreAndroid5UpdateCheckException;
import org.cryptomator.domain.repository.UpdateCheckRepository;
import org.cryptomator.domain.usecases.UpdateCheck;
import org.cryptomator.util.Optional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

@Singleton
public class UpdateCheckRepositoryImpl implements UpdateCheckRepository {

	private static final String HOSTNAME_LATEST_VERSION = "https://static.cryptomator.org/android/latest-version.json";

	private final Database database;
	private final OkHttpClient httpClient;

	@Inject
	UpdateCheckRepositoryImpl(Database database) {
		this.httpClient = httpClient();
		this.database = database;
	}

	private OkHttpClient httpClient() {
		return new OkHttpClient //
				.Builder().addInterceptor(new UserAgentInterceptor()) //
						.build();
	}

	@Override
	public Optional<UpdateCheck> getUpdateCheck(final String appVersion) throws BackendException {
		LatestVersion latestVersion = loadLatestVersion();

		if (appVersion.equals(latestVersion.version)) {
			return Optional.empty();
		}

		final UpdateCheckEntity entity = database.load(UpdateCheckEntity.class, 1L);

		if (entity.getVersion() != null && entity.getVersion().equals(latestVersion.version)) {
			return Optional.of(new UpdateCheckImpl("", entity));
		}

		UpdateCheck updateCheck = loadUpdateStatus(latestVersion);
		entity.setUrlToApk(updateCheck.getUrlApk());
		entity.setVersion(updateCheck.getVersion());

		database.store(entity);

		return Optional.of(updateCheck);
	}

	@Override
	public void update(File file) throws GeneralUpdateErrorException {
		try {
			final UpdateCheckEntity entity = database.load(UpdateCheckEntity.class, 1L);

			final Request request = new Request //
					.Builder() //
							.url(entity.getUrlToApk()).build();

			final Response response = httpClient.newCall(request).execute();

			if (response.isSuccessful()) {
				final BufferedSink sink = Okio.buffer(Okio.sink(file));
				sink.writeAll(response.body().source());
				sink.close();
			} else {
				throw new GeneralUpdateErrorException("Failed to load update file, status code is not correct: " + response.code());
			}
		} catch (IOException e) {
			throw new GeneralUpdateErrorException("Failed to load update. General error occurred.", e);
		}
	}

	private LatestVersion loadLatestVersion() throws BackendException {
		try {
			final Request request = new Request //
					.Builder() //
							.url(HOSTNAME_LATEST_VERSION) //
							.build();
			return toLatestVersion(httpClient.newCall(request).execute());
		} catch (SSLHandshakeException e) {
			if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
				throw new SSLHandshakePreAndroid5UpdateCheckException("Failed to update.", e);
			} else {
				throw new GeneralUpdateErrorException("Failed to update. General error occurred.", e);
			}
		} catch (IOException e) {
			throw new GeneralUpdateErrorException("Failed to update. General error occurred.", e);
		}
	}

	private UpdateCheck loadUpdateStatus(LatestVersion latestVersion) throws BackendException {
		try {
			final Request request = new Request //
					.Builder() //
							.url(latestVersion.urlReleaseNote) //
							.build();
			return toUpdateCheck(httpClient.newCall(request).execute(), latestVersion);
		} catch (IOException e) {
			throw new GeneralUpdateErrorException("Failed to update.  General error occurred.", e);
		}
	}

	private LatestVersion toLatestVersion(Response response) throws IOException, GeneralUpdateErrorException {
		if (response.isSuccessful()) {
			return new LatestVersion(response.body().string());
		} else {
			throw new GeneralUpdateErrorException("Failed to update. Wrong status code in response from server: " + response.code());
		}
	}

	private UpdateCheck toUpdateCheck(Response response, LatestVersion latestVersion) throws IOException, GeneralUpdateErrorException {
		if (response.isSuccessful()) {
			final String releaseNote = response.body().string();
			return new UpdateCheckImpl(releaseNote, latestVersion);
		} else {
			throw new GeneralUpdateErrorException("Failed to update. Wrong status code in response from server: " + response.code());
		}
	}

	private class LatestVersion {

		private final String version;
		private final String urlApk;
		private final String urlReleaseNote;

		LatestVersion(String json) throws GeneralUpdateErrorException {
			try {
				Claims jws = Jwts //
						.parserBuilder().setSigningKey(getPublicKey()) //
						.build() //
						.parseClaimsJws(json) //
						.getBody();

				version = jws.get("version", String.class);
				urlApk = jws.get("url", String.class);
				urlReleaseNote = jws.get("release_notes", String.class);
			} catch (Exception e) {
				throw new GeneralUpdateErrorException("Failed to parse latest version", e);
			}
		}
	}

	private static class UpdateCheckImpl implements UpdateCheck {
		private final String releaseNote;
		private final String version;
		private final String urlApk;
		private final String urlReleaseNote;

		private UpdateCheckImpl(String releaseNote, LatestVersion latestVersion) {
			this.releaseNote = releaseNote;
			this.version = latestVersion.version;
			this.urlApk = latestVersion.urlApk;
			this.urlReleaseNote = latestVersion.urlReleaseNote;
		}

		private UpdateCheckImpl(String releaseNote, UpdateCheckEntity updateCheckEntity) {
			this.releaseNote = releaseNote;
			this.version = updateCheckEntity.getVersion();
			this.urlApk = updateCheckEntity.getUrlToApk();
			this.urlReleaseNote = updateCheckEntity.getUrlToReleaseNote();
		}

		@Override
		public String releaseNote() {
			return releaseNote;
		}

		@Override
		public String getVersion() {
			return version;
		}

		@Override
		public String getUrlApk() {
			return urlApk;
		}

		@Override
		public String getUrlReleaseNote() {
			return urlReleaseNote;
		}
	}

	private ECPublicKey getPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
		final byte[] publicKey = BaseEncoding //
				.base64() //
				.decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELOYa5ax7QZvS92HJYCBPBiR2wWfX" + "P9/Oq/yl2J1yg0Vovetp8i1A3yCtoqdHVdVytM1wNV0JXgRbWuNTAr9nlQ==");

		Key key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey));
		if (key instanceof ECPublicKey) {
			return (ECPublicKey) key;
		} else {
			throw new FatalBackendException("Key not an EC public key.");
		}
	}
}
