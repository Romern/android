package org.cryptomator.presentation.ui.layout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import org.cryptomator.presentation.R
import kotlin.math.cos
import kotlin.math.sin

class VaultListCoordinatorLayout : CoordinatorLayout {

	private val pixelsPerDp: Int

	constructor(context: Context) : super(context) {
		val metrics = getContext().resources.displayMetrics
		pixelsPerDp = metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
	}

	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
		val metrics = getContext().resources.displayMetrics
		pixelsPerDp = metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
	}

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
		val metrics = getContext().resources.displayMetrics
		pixelsPerDp = metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
	}

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)
		if (findViewById<View>(R.id.rl_vault_creation_hint).visibility == VISIBLE) {
			drawArcFromHintToFloatingActionButton(canvas)
		}
	}

	private fun drawArcFromHintToFloatingActionButton(canvas: Canvas) {
		val vaultCreationHint = findViewById<View>(R.id.tv_vault_creation_hint)
		val floatingActionButton = findViewById<View>(R.id.fab_vault)
		val centerXOfHint = (vaultCreationHint.left + vaultCreationHint.right) / 2f
		val bottomOfHint = vaultCreationHint.bottom.toFloat()
		val leftOfFloatingActionButton = floatingActionButton.left.toFloat()
		val topOfFloatingActionButton = floatingActionButton.top.toFloat()
		arcFrom(centerXOfHint + dpToPixels(10f), bottomOfHint + dpToPixels(5f)) //
				.to(leftOfFloatingActionButton - dpToPixels(3f), topOfFloatingActionButton + dpToPixels(5f)) //
				.spanningAnAngleOf(60.0f) //
				.build() //
				.draw(canvas, strokeBlackWithWidthOf1f())
	}

	private fun strokeBlackWithWidthOf1f(): Paint {
		val paint = Paint()
		paint.color = Color.BLACK
		paint.strokeWidth = dpToPixels(1f)
		paint.isAntiAlias = true
		paint.style = Paint.Style.STROKE
		return paint
	}

	private fun dpToPixels(dp: Float): Float {
		return dp * pixelsPerDp
	}

	private class ArcBuilder(val x1: Float, val y1: Float) {
		var angle = 0f
		var x2 = 0f
		var y2 = 0f
		fun to(x2: Float, y2: Float): ArcBuilder {
			require(!(x2 < x1 || y2 < y1)) { "Second position must be to the right of and below the first position" }
			this.x2 = x2
			this.y2 = y2
			return this
		}

		fun spanningAnAngleOf(angle: Float): ArcBuilder {
			require(!(angle < 0f || angle > 90f)) { "Angle must be between 0 and 90" }
			this.angle = angle
			return this
		}

		fun build(): Arc {
			return Arc(this)
		}
	}

	private class Arc(b: ArcBuilder) {
		private val left: Float
		private val right: Float
		private val top: Float
		private val bottom: Float
		private val start: Float
		private val angle: Float = b.angle

		fun draw(canvas: Canvas, paint: Paint) {
			val rect = RectF()
			rect[left, top, right] = bottom
			canvas.drawArc(rect, start, angle, false, paint)
		}

		companion object {
			private const val TWO_PI = 2f * Math.PI
		}

		init {
			start = 180f - angle
			val sin = sin(TWO_PI * angle / 360).toFloat()
			val cos = cos(TWO_PI * angle / 360).toFloat()
			val widthCorrection = 1f / cos
			val heightCorrection = 1f / sin
			val w = (b.x2 - b.x1) * 2 * widthCorrection
			val h = (b.y2 - b.y1) * 2 * heightCorrection
			left = b.x1
			right = b.x1 + w
			top = b.y1 - h / 2
			bottom = b.y1 + h / 2
		}
	}

	companion object {
		private fun arcFrom(x1: Float, y1: Float): ArcBuilder {
			return ArcBuilder(x1, y1)
		}
	}
}
