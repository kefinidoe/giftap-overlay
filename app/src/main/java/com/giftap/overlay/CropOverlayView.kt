package com.giftap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class CropOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val minSizePx = 88f * density
    private val handleTouchRadius = 30f * density

    private val dimPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4FF00")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4FF00")
        style = Paint.Style.FILL
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 9, 9, 11)
        style = Paint.Style.FILL
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 212, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    val cropRect = RectF()
    private var initialized = false
    private var activeMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class DragMode {
        NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            val boxW = minOf(w * 0.62f, 240f * density)
            val boxH = boxW
            val left = (w - boxW) / 2f
            val top = (h - boxH) / 2f - 28f * density
            cropRect.set(left, top, left + boxW, top + boxH)
            initialized = true
        }
    }

    fun getCaptureRectInScreen(): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect(
            (loc[0] + cropRect.left).toInt().coerceAtLeast(0),
            (loc[1] + cropRect.top).toInt().coerceAtLeast(0),
            (loc[0] + cropRect.right).toInt(),
            (loc[1] + cropRect.bottom).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(cropRect, 14f * density, 14f * density, clearPaint)

        // Subtle 3x3 framing grid
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + thirdW * 2f, cropRect.top, cropRect.left + thirdW * 2f, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2f, cropRect.right, cropRect.top + thirdH * 2f, gridPaint)

        canvas.drawRoundRect(cropRect, 14f * density, 14f * density, borderPaint)

        val r = 7.5f * density
        canvas.drawCircle(cropRect.left, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, handlePaint)

        val label = "Move & resize over the comment GIF"
        val textW = labelPaint.measureText(label)
        val pillW = textW + 28f * density
        val pillH = 30f * density
        val pillCenterY = (cropRect.top - 24f * density).coerceAtLeast(52f * density)
        val pillRect = RectF(
            width / 2f - pillW / 2f,
            pillCenterY - pillH / 2f,
            width / 2f + pillW / 2f,
            pillCenterY + pillH / 2f
        )
        canvas.drawRoundRect(pillRect, 999f, 999f, pillBgPaint)
        canvas.drawRoundRect(pillRect, 999f, 999f, pillStrokePaint)
        canvas.drawText(label, width / 2f, pillCenterY + 4.5f * density, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                activeMode = when {
                    near(x, y, cropRect.left, cropRect.top) -> DragMode.TOP_LEFT
                    near(x, y, cropRect.right, cropRect.top) -> DragMode.TOP_RIGHT
                    near(x, y, cropRect.left, cropRect.bottom) -> DragMode.BOTTOM_LEFT
                    near(x, y, cropRect.right, cropRect.bottom) -> DragMode.BOTTOM_RIGHT
                    cropRect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                return activeMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y

                val maxW = width.toFloat()
                val maxH = height.toFloat()
                when (activeMode) {
                    DragMode.MOVE -> {
                        val nextLeft = (cropRect.left + dx).coerceIn(0f, maxW - cropRect.width())
                        val nextTop = (cropRect.top + dy).coerceIn(0f, maxH - cropRect.height())
                        cropRect.offsetTo(nextLeft, nextTop)
                    }
                    DragMode.TOP_LEFT -> {
                        cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minSizePx)
                        cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minSizePx)
                    }
                    DragMode.TOP_RIGHT -> {
                        cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSizePx, maxW)
                        cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minSizePx)
                    }
                    DragMode.BOTTOM_LEFT -> {
                        cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minSizePx)
                        cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSizePx, maxH)
                    }
                    DragMode.BOTTOM_RIGHT -> {
                        cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSizePx, maxW)
                        cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSizePx, maxH)
                    }
                    DragMode.NONE -> return false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun near(x: Float, y: Float, tx: Float, ty: Float): Boolean {
        return abs(x - tx) <= handleTouchRadius && abs(y - ty) <= handleTouchRadius
    }
}
