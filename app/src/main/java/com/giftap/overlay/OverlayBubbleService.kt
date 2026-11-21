package com.giftap.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayBubbleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.giftap.overlay.STOP"
        private const val CHANNEL_ID = "giftap_overlay_channel"
        private const val NOTIFICATION_ID = 4102
        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissTargetView: TextView? = null
    private var menuView: View? = null
    private var cropContainer: View? = null
    private var cropOverlayView: CropOverlayView? = null
    private var statusPillView: TextView? = null
    private var previewCardView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDpi = DisplayMetrics.DENSITY_HIGH
    private var isCapturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateRealScreenMetrics()
        createNotificationChannel()
    }

    private fun updateRealScreenMetrics() {
        screenDpi = resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(360)
            screenHeight = bounds.height().coerceAtLeast(640)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels.coerceAtLeast(360)
            screenHeight = dm.heightPixels.coerceAtLeast(640)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)?.apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post { stopSelf() }
                    }
                }, mainHandler)
            }
        }

        if (bubbleView == null) {
            showFloatingBubble()
        }

        return START_STICKY
    }

    private fun showFloatingBubble() {
        val density = resources.displayMetrics.density
        val size = (56 * density).roundToInt()

        val label = TextView(this).apply {
            text = "GIF"
            textSize = 13f
            setTextColor(Color.parseColor("#D4FF00"))
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F009090B"))
                setStroke((2.5f * density).roundToInt(), Color.parseColor("#D4FF00"))
            }
            elevation = 12f * density
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - (12 * density).roundToInt()
            y = (screenHeight * 0.34f).roundToInt()
        }

        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false

        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        if (!moved) {
                            moved = true
                            showDismissTarget()
                        }
                    }
                    params.x = (initialX + dx).coerceIn(0, screenWidth - size)
                    params.y = (initialY + dy).coerceIn(0, screenHeight - size)
                    windowManager.updateViewLayout(label, params)
                    updateDismissTargetHighlight(event.rawY > screenHeight * 0.82f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val droppedToClose = moved && event.rawY > screenHeight * 0.82f
                    hideDismissTarget()
                    if (droppedToClose) {
                        stopSelf()
                    } else if (!moved) {
                        toggleBubbleMenu()
                    } else {
                        val snapRight = params.x + size / 2 > screenWidth / 2
                        params.x = if (snapRight) {
                            screenWidth - size - (12 * density).roundToInt()
                        } else {
                            (12 * density).roundToInt()
                        }
                        windowManager.updateViewLayout(label, params)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissTarget()
                    true
                }
                else -> false
            }
        }

        bubbleParams = params
        bubbleView = label
        windowManager.addView(label, params)
    }

    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        val density = resources.displayMetrics.density
        val pill = TextView(this).apply {
            text = "×  Drop here to hide"
            textSize = 13f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((20 * density).roundToInt(), (11 * density).roundToInt(), (20 * density).roundToInt(), (11 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * density
                setColor(Color.parseColor("#E618181B"))
                setStroke((1.5f * density).roundToInt(), Color.parseColor("#52525B"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (42 * density).roundToInt()
        }
        dismissTargetView = pill
        windowManager.addView(pill, params)
    }

    private fun updateDismissTargetHighlight(active: Boolean) {
        val density = resources.displayMetrics.density
        dismissTargetView?.background = GradientDrawable().apply {
            cornerRadius = 999f * density
            setColor(Color.parseColor(if (active) "#DC2626" else "#E618181B"))
            setStroke((1.5f * density).roundToInt(), Color.parseColor(if (active) "#FCA5A5" else "#52525B"))
        }
    }

    private fun hideDismissTarget() {
        dismissTargetView?.let { runCatching { windowManager.removeView(it) } }
        dismissTargetView = null
    }

    private fun toggleBubbleMenu() {
        if (menuView != null) {
            removeMenuView()
            return
        }

        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(Color.parseColor("#F709090B"))
                setStroke((1.2f * density).roundToInt(), Color.parseColor("#33FFFFFF"))
            }
        }

        val header = TextView(this).apply {
            text = "GifTap Overlay"
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).roundToInt()
            }
            layoutParams = lp
        }
        card.addView(header)

        fun makeActionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                val hp = (16 * density).roundToInt()
                val vp = (12 * density).roundToInt()
                setPadding(hp, vp, hp, vp)
                setTextColor(if (primary) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 999f * density
                    if (primary) {
                        setColor(Color.parseColor("#D4FF00"))
                    } else {
                        setColor(Color.parseColor("#18181B"))
                        setStroke((1 * density).roundToInt(), Color.parseColor("#27272A"))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).roundToInt()
                }
                setOnClickListener {
                    removeMenuView()
                    onClick()
                }
            }
        }

        card.addView(makeActionButton("Capture Comment GIF", true) {
            openCropSelector()
        })
        card.addView(makeActionButton("Open GifTap App", false) {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
        })
        card.addView(makeActionButton("Hide Bubble", false) {
            stopSelf()
        })

        val params = WindowManager.LayoutParams(
            (240 * density).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        menuView = card
        windowManager.addView(card, params)
    }

    private fun openCropSelector() {
        if (isCapturing || cropContainer != null) return
        if (mediaProjection == null) {
            Toast.makeText(this, "Tap Start Bubble in GifTap first.", Toast.LENGTH_SHORT).show()
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
            return
        }

        updateRealScreenMetrics()
        removePreviewCard()
        bubbleView?.visibility = View.GONE
        val density = resources.displayMetrics.density

        val container = FrameLayout(this)
        val cropView = CropOverlayView(this)
        container.addView(
            cropView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val p = (12 * density).roundToInt()
            setPadding(p, p, p, p)
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            setPadding((22 * density).roundToInt(), (12 * density).roundToInt(), (22 * density).roundToInt(), (12 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * density
                setColor(Color.parseColor("#E618181B"))
                setStroke((1 * density).roundToInt(), Color.parseColor("#44FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (12 * density).roundToInt()
            }
            setOnClickListener {
                closeCropSelector()
            }
        }

        val captureBtn = TextView(this).apply {
            text = "Capture 3s GIF"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(Color.BLACK)
            setPadding((24 * density).roundToInt(), (12 * density).roundToInt(), (24 * density).roundToInt(), (12 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * density
                setColor(Color.parseColor("#D4FF00"))
            }
            setOnClickListener {
                val rect = cropView.getCaptureRectInScreen()
                closeCropSelector(keepBubbleHidden = true)
                startTimedCapture(rect)
            }
        }

        controls.addView(cancelBtn)
        controls.addView(captureBtn)

        val controlsLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = (48 * density).roundToInt()
        }
        container.addView(controls, controlsLp)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        cropOverlayView = cropView
        cropContainer = container
        windowManager.addView(container, params)
    }

    private fun closeCropSelector(keepBubbleHidden: Boolean = false) {
        cropContainer?.let { runCatching { windowManager.removeView(it) } }
        cropContainer = null
        cropOverlayView = null
        if (!keepBubbleHidden) {
            bubbleView?.visibility = View.VISIBLE
        }
    }

    private fun showStatusPill(text: String) {
        val density = resources.displayMetrics.density
        val existing = statusPillView
        if (existing != null) {
            existing.text = text
            return
        }
        val pill = TextView(this).apply {
            this.text = text
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#D4FF00"))
            setPadding((16 * density).roundToInt(), (8 * density).roundToInt(), (16 * density).roundToInt(), (8 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * density
                setColor(Color.parseColor("#EE09090B"))
                setStroke((1.5f * density).roundToInt(), Color.parseColor("#D4FF00"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (44 * density).roundToInt()
        }
        statusPillView = pill
        windowManager.addView(pill, params)
    }

    private fun hideStatusPill() {
        statusPillView?.let { runCatching { windowManager.removeView(it) } }
        statusPillView = null
    }

    private fun startTimedCapture(cropRect: Rect) {
        val projection = mediaProjection ?: return
        isCapturing = true

        val captureW = (screenWidth / 2).coerceAtLeast(180)
        val captureH = (screenHeight / 2).coerceAtLeast(320)
        val scaledCrop = Rect(
            (cropRect.left / 2).coerceIn(0, captureW - 16),
            (cropRect.top / 2).coerceIn(0, captureH - 16),
            (cropRect.right / 2).coerceIn(16, captureW),
            (cropRect.bottom / 2).coerceIn(16, captureH)
        )

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "GifTapCapture",
            captureW,
            captureH,
            (screenDpi / 2).coerceAtLeast(DisplayMetrics.DENSITY_LOW),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )

        val capturedFrames = mutableListOf<Bitmap>()
        var lastCroppedBitmap: Bitmap? = null
        val targetFrameCount = 25
        val intervalMs = 120L

        showStatusPill("● Capturing GIF... 3s")

        fun grabNext(index: Int) {
            if (index >= targetFrameCount) {
                showStatusPill("Encoding .GIF...")
                finishCapture(capturedFrames)
                return
            }

            val remainingSec = (((targetFrameCount - index) * intervalMs) / 1000f)
            val secLabel = kotlin.math.ceil(remainingSec.toDouble()).toInt().coerceAtLeast(1)
            showStatusPill("● Capturing GIF... ${secLabel}s")

            mainHandler.postDelayed({
                val img = reader.acquireLatestImage()
                var freshFrame: Bitmap? = null
                if (img != null) {
                    try {
                        val plane = img.planes[0]
                        val buffer = plane.buffer
                        buffer.rewind()
                        val pixelStride = plane.pixelStride.coerceAtLeast(4)
                        val rowStride = plane.rowStride
                        val rowPadding = (rowStride - pixelStride * captureW).coerceAtLeast(0)
                        val strideWidth = captureW + rowPadding / pixelStride
                        val fullBitmap = Bitmap.createBitmap(
                            strideWidth,
                            captureH,
                            Bitmap.Config.ARGB_8888
                        )
                        fullBitmap.copyPixelsFromBuffer(buffer)

                        val safeLeft = scaledCrop.left.coerceIn(0, captureW - 16)
                        val safeTop = scaledCrop.top.coerceIn(0, captureH - 16)
                        val safeW = scaledCrop.width().coerceIn(16, captureW - safeLeft)
                        val safeH = scaledCrop.height().coerceIn(16, captureH - safeTop)

                        val cropped = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeW, safeH)
                        fullBitmap.recycle()

                        val maxDim = 240f
                        val scale = minOf(1f, maxDim / maxOf(safeW, safeH))
                        val outW = (safeW * scale).roundToInt().coerceAtLeast(16)
                        val outH = (safeH * scale).roundToInt().coerceAtLeast(16)
                        val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
                        if (scaled !== cropped) cropped.recycle()

                        freshFrame = scaled
                        lastCroppedBitmap = scaled
                    } catch (_: Throwable) {
                        // Fall back to previous frame
                    } finally {
                        img.close()
                    }
                }

                if (freshFrame != null) {
                    capturedFrames.add(freshFrame)
                } else if (lastCroppedBitmap != null) {
                    val clone = lastCroppedBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                    capturedFrames.add(clone)
                }

                grabNext(index + 1)
            }, intervalMs)
        }

        // Brief 160ms settle so the crop overlay completely vanishes before frame 0
        mainHandler.postDelayed({ grabNext(0) }, 160L)
    }

    private fun finishCapture(frames: List<Bitmap>) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        if (frames.isEmpty()) {
            hideStatusPill()
            isCapturing = false
            bubbleView?.visibility = View.VISIBLE
            Toast.makeText(this, "No screen frames received. Try again over TikTok.", Toast.LENGTH_SHORT).show()
            return
        }

        worker.execute {
            try {
                val gifBytes = GifEncoder.encode(frames, delayCs = 12)
                frames.forEach { if (!it.isRecycled) it.recycle() }

                val captureDir = File(filesDir, "captures").apply { mkdirs() }
                val fileName = "giftap_${System.currentTimeMillis()}.gif"
                val savedFile = File(captureDir, fileName)
                savedFile.writeBytes(gifBytes)

                // Prune older local captures to keep storage small (max 15)
                captureDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach {
                    it.delete()
                }

                val shareDir = File(cacheDir, "shared").apply { mkdirs() }
                val shareFile = File(shareDir, "giftap.gif")
                shareFile.writeBytes(gifBytes)

                val savedToGallery = saveGifToGallery(gifBytes, fileName)

                mainHandler.post {
                    hideStatusPill()
                    isCapturing = false
                    bubbleView?.visibility = View.VISIBLE
                    showCapturedPreviewCard(shareFile, savedToGallery)
                }
            } catch (_: Throwable) {
                frames.forEach { if (!it.isRecycled) it.recycle() }
                mainHandler.post {
                    hideStatusPill()
                    isCapturing = false
                    bubbleView?.visibility = View.VISIBLE
                    Toast.makeText(this, "Failed to encode captured GIF.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveGifToGallery(bytes: ByteArray, fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GifTap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun showCapturedPreviewCard(gifFile: File, savedToGallery: Boolean) {
        removePreviewCard()
        val density = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(Color.parseColor("#FA09090B"))
                setStroke((1.5f * density).roundToInt(), Color.parseColor("#44D4FF00"))
            }
        }

        val title = TextView(this).apply {
            text = if (savedToGallery) "GIF Ready · Saved to Gallery" else "Comment GIF Ready"
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        card.addView(title)

        val previewImg = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (180 * density).roundToInt(),
                (180 * density).roundToInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (12 * density).roundToInt()
                bottomMargin = (14 * density).roundToInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(Color.BLACK)
                setStroke((1 * density).roundToInt(), Color.parseColor("#27272A"))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(gifFile)
                val drawable = ImageDecoder.decodeDrawable(source)
                previewImg.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.start()
                }
            }.onFailure {
                previewImg.setImageBitmap(BitmapFactory.decodeFile(gifFile.absolutePath))
            }
        } else {
            previewImg.setImageBitmap(BitmapFactory.decodeFile(gifFile.absolutePath))
        }
        card.addView(previewImg)

        fun makeCardBtn(text: String, primary: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding((14 * density).roundToInt(), (11 * density).roundToInt(), (14 * density).roundToInt(), (11 * density).roundToInt())
                setTextColor(if (primary) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 999f * density
                    if (primary) {
                        setColor(Color.parseColor("#D4FF00"))
                    } else {
                        setColor(Color.parseColor("#18181B"))
                        setStroke((1 * density).roundToInt(), Color.parseColor("#27272A"))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).roundToInt()
                }
                setOnClickListener { onClick() }
            }
        }

        card.addView(makeCardBtn("Share .GIF to WhatsApp", true) {
            removePreviewCard()
            shareGifFile(gifFile, preferWhatsApp = true)
        })

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val otherBtn = makeCardBtn("Share Other", false) {
            removePreviewCard()
            shareGifFile(gifFile, preferWhatsApp = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * density).roundToInt()
            }
        }

        val retakeBtn = makeCardBtn("Retake", false) {
            removePreviewCard()
            openCropSelector()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * density).roundToInt()
            }
        }

        val doneBtn = makeCardBtn("Done", false) {
            removePreviewCard()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        bottomRow.addView(otherBtn)
        bottomRow.addView(retakeBtn)
        bottomRow.addView(doneBtn)
        card.addView(bottomRow)

        val params = WindowManager.LayoutParams(
            (284 * density).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        previewCardView = card
        windowManager.addView(card, params)
    }

    private fun shareGifFile(gifFile: File, preferWhatsApp: Boolean) {
        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            gifFile
        )
        if (preferWhatsApp) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val waIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/gif"
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (waIntent.resolveActivity(packageManager) != null) {
                    runCatching {
                        startActivity(waIntent)
                        return
                    }
                }
            }
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Share captured GIF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(chooser)
    }

    private fun removePreviewCard() {
        previewCardView?.let { runCatching { windowManager.removeView(it) } }
        previewCardView = null
    }

    private fun removeMenuView() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GifTap Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GifTap Floating Bubble Active")
            .setContentText("Tap the floating GIF icon over TikTok to capture any comment GIF.")
            .setSmallIcon(R.drawable.ic_giftap_launcher)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        removeMenuView()
        removePreviewCard()
        hideStatusPill()
        hideDismissTarget()
        closeCropSelector(keepBubbleHidden = true)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
