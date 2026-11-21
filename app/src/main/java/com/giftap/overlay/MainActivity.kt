package com.giftap.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var overlayBtn: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var statusDot: View
    private lateinit var fallbackScroll: ScrollView
    private lateinit var fallbackMessage: TextView
    private lateinit var recentGifsContainer: LinearLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var triedAltUrl = false
    private var pendingSharedText: String? = null
    private var currentLoadedBase: String = ""

    private val prefs by lazy { getSharedPreferences("giftap_prefs", MODE_PRIVATE) }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                confirmAndRequestScreenCapture()
            } else {
                Toast.makeText(
                    this,
                    "Allow 'Display over other apps' so GifTap can float over TikTok.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayBubbleService::class.java).apply {
                    putExtra(OverlayBubbleService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayBubbleService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                updateOverlayButtonState()
                Toast.makeText(
                    this,
                    "Floating GIF bubble is live! Open TikTok comments and tap it.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            proceedToOverlayOrCapture()
        }

    private fun isTrustedBridgeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        val primaryHost = Uri.parse(getSavedOrDefaultPrimaryUrl()).host?.lowercase()
        val altHost = Uri.parse(getString(R.string.giftap_alt_url)).host?.lowercase()
        return host == primaryHost || host == altHost
    }

    inner class WebShareBridge {
        @JavascriptInterface
        fun shareBase64File(base64Data: String, mimeType: String, filename: String) {
            runOnUiThread {
                if (!isTrustedBridgeUrl(webView.url)) return@runOnUiThread
                try {
                    val cleanName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "giftap.gif" }
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val outDir = File(cacheDir, "shared").apply { mkdirs() }
                    val outFile = File(outDir, cleanName)
                    outFile.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        outFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType.ifBlank { "image/gif" }
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share $cleanName"))
                } catch (_: Throwable) {
                    Toast.makeText(this@MainActivity, "Could not open share sheet.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun startFloatingBubble() {
            runOnUiThread {
                if (!isTrustedBridgeUrl(webView.url)) return@runOnUiThread
                if (!OverlayBubbleService.isRunning) {
                    startOverlayFlow()
                }
            }
        }

        @JavascriptInterface
        fun isBubbleRunning(): Boolean = OverlayBubbleService.isRunning
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Modern dark header
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val wordmark = SpannableString("GifTap").apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.parseColor("#D4FF00")), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val brandText = TextView(this).apply {
            text = wordmark
            textSize = 20f
            paint.isFakeBoldText = true
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
            layoutParams = lp
        }

        statusDot = View(this).apply {
            val size = dp(7)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(6)
            }
        }

        statusSubtext = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#A1A1AA"))
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusSubtext)
        brandStack.addView(brandText)
        brandStack.addView(statusRow)

        overlayBtn = TextView(this).apply {
            textSize = 12f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener {
                if (OverlayBubbleService.isRunning) {
                    stopService(Intent(this@MainActivity, OverlayBubbleService::class.java))
                    postDelayed({ updateOverlayButtonState() }, 180)
                } else {
                    startOverlayFlow()
                }
            }
        }

        topBar.addView(brandStack)
        topBar.addView(overlayBtn)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#1F1F23"))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D4FF00"))
            visibility = View.GONE
        }

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            cookieManager.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(WebShareBridge(), "GifTapAndroid")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: ""
                    if (scheme != "http" && scheme != "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        return true
                    }
                    val host = uri.host?.lowercase() ?: return false
                    if (isAllowedInWebView(host)) {
                        return false
                    }
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        val code = errorResponse?.statusCode ?: 0
                        if (code >= 400) {
                            handleLoadFailure("GifTap web server returned HTTP $code.")
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "Unable to connect"
                        handleLoadFailure(desc)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun getDefaultVideoPoster(): Bitmap {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 1..99) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: return false
                    fileChooserLauncher.launch(intent)
                    return true
                }
            }
        }

        fallbackScroll = buildFallbackView()
        contentFrame.addView(webView)
        contentFrame.addView(fallbackScroll)

        root.addView(topBar)
        root.addView(divider)
        root.addView(progressBar)
        root.addView(contentFrame)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fallbackScroll.visibility != View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        updateOverlayButtonState()
        loadInitialUrl(intent)
    }

    private fun isAllowedInWebView(host: String): Boolean {
        val primaryHost = Uri.parse(getSavedOrDefaultPrimaryUrl()).host?.lowercase()
        val altHost = Uri.parse(getString(R.string.giftap_alt_url)).host?.lowercase()
        if (host == primaryHost || host == altHost) return true
        return host.endsWith(".run.app") ||
            host.endsWith(".google.com") ||
            host.endsWith(".gstatic.com") ||
            host.endsWith(".googleusercontent.com")
    }

    private fun buildFallbackView(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dpF(20f)
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }

        val title = TextView(this).apply {
            text = "Floating Overlay Ready (Offline Mode)"
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
        }

        fallbackMessage = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A1A1AA"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
            }
            layoutParams = lp
        }

        val startBubbleCardBtn = makePillButton("Start Floating Bubble Over TikTok", primary = true) {
            if (OverlayBubbleService.isRunning) {
                Toast.makeText(this, "Floating bubble is already active — open TikTok!", Toast.LENGTH_SHORT).show()
            } else {
                startOverlayFlow()
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            layoutParams = lp
        }

        val retryBtn = makePillButton("Retry Web App", primary = false) {
            triedAltUrl = false
            fallbackScroll.visibility = View.GONE
            webView.visibility = View.VISIBLE
            loadUrlWithShareParam(getSavedOrDefaultPrimaryUrl())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val changeUrlBtn = makePillButton("Set App URL", primary = false) {
            showEditUrlDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(retryBtn)
        btnRow.addView(changeUrlBtn)

        card.addView(title)
        card.addView(fallbackMessage)
        card.addView(startBubbleCardBtn)
        card.addView(btnRow)
        wrap.addView(card)

        val recentsHeader = TextView(this).apply {
            text = "Captured Comment GIFs on This Phone"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(22)
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }
        wrap.addView(recentsHeader)

        recentGifsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrap.addView(recentGifsContainer)

        scroll.addView(wrap)
        return scroll
    }

    private fun makePillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpF(999f)
                if (primary) {
                    setColor(Color.parseColor("#D4FF00"))
                } else {
                    setColor(Color.parseColor("#18181B"))
                    setStroke(dp(1), Color.parseColor("#3F3F46"))
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun handleLoadFailure(reason: String) {
        val altBase = getString(R.string.giftap_alt_url).trimEnd('/')
        if (!triedAltUrl && altBase.isNotBlank() && altBase != currentLoadedBase) {
            triedAltUrl = true
            loadUrlWithShareParam(altBase)
            return
        }

        progressBar.visibility = View.GONE
        webView.visibility = View.GONE
        fallbackScroll.visibility = View.VISIBLE
        fallbackMessage.text =
            "Could not load the online GifTap page ($reason). If you haven't published your AI Studio app yet, tap Publish in AI Studio or tap 'Set App URL'.\n\nMeanwhile, the Floating Bubble works 100% offline right here."
        refreshRecentCapturedGifs()
    }

    private fun refreshRecentCapturedGifs() {
        recentGifsContainer.removeAllViews()
        val dir = File(filesDir, "captures")
        val files = dir.listFiles { f -> f.extension.equals("gif", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No captured comment GIFs yet. Tap 'Start Floating Bubble Over TikTok' above, open any TikTok comment GIF, and tap the floating GIF icon."
                textSize = 12f
                setTextColor(Color.parseColor("#71717A"))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dpF(14f)
                    setColor(Color.parseColor("#09090B"))
                    setStroke(dp(1), Color.parseColor("#1F1F23"))
                }
            }
            recentGifsContainer.addView(empty)
            return
        }

        for (file in files.take(10)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dpF(14f)
                    setColor(Color.parseColor("#09090B"))
                    setStroke(dp(1), Color.parseColor("#27272A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val info = TextView(this).apply {
                val kb = (file.length() / 1024L).coerceAtLeast(1L)
                text = "${file.name}\n$kb KB · Animated .GIF"
                textSize = 12f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val shareBtn = makePillButton("Share .GIF", primary = true) {
                shareLocalGifFile(file)
            }

            row.addView(info)
            row.addView(shareBtn)
            recentGifsContainer.addView(row)
        }
    }

    private fun shareLocalGifFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/gif"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share GIF to WhatsApp"))
        } catch (_: Throwable) {
            Toast.makeText(this, "Could not share GIF file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditUrlDialog() {
        val input = EditText(this).apply {
            setText(getSavedOrDefaultPrimaryUrl())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("GifTap Server URL")
            .setMessage("Paste your published https://...run.app GifTap link:")
            .setView(input)
            .setPositiveButton("Save & Load") { _, _ ->
                val cleaned = input.text.toString().trim().trimEnd('/')
                if (cleaned.startsWith("https://")) {
                    prefs.edit().putString("custom_url", cleaned).apply()
                    triedAltUrl = false
                    fallbackScroll.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    loadUrlWithShareParam(cleaned)
                } else {
                    Toast.makeText(this, "Please enter a valid https:// URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadInitialUrl(intent)
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButtonState()
        if (fallbackScroll.visibility == View.VISIBLE) {
            refreshRecentCapturedGifs()
        }
    }

    private fun updateOverlayButtonState() {
        val active = OverlayBubbleService.isRunning
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (active) "#D4FF00" else "#52525B"))
        }
        statusSubtext.text = if (active) "Bubble active over TikTok" else "Tap to float over TikTok"
        statusSubtext.setTextColor(Color.parseColor(if (active) "#D4FF00" else "#A1A1AA"))

        overlayBtn.text = if (active) "Bubble On · Stop" else "Start Bubble"
        overlayBtn.setTextColor(if (active) Color.parseColor("#D4FF00") else Color.BLACK)
        overlayBtn.background = GradientDrawable().apply {
            cornerRadius = dpF(999f)
            if (active) {
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#D4FF00"))
            } else {
                setColor(Color.parseColor("#D4FF00"))
            }
        }
    }

    private fun getSavedOrDefaultPrimaryUrl(): String {
        val saved = prefs.getString("custom_url", null)?.trim()?.trimEnd('/')
        if (!saved.isNullOrBlank()) return saved
        return getString(R.string.giftap_url).trim().trimEnd('/')
    }

    private fun loadInitialUrl(intent: Intent?) {
        pendingSharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        triedAltUrl = false
        fallbackScroll.visibility = View.GONE
        webView.visibility = View.VISIBLE
        loadUrlWithShareParam(getSavedOrDefaultPrimaryUrl())
    }

    private fun loadUrlWithShareParam(baseUrl: String) {
        currentLoadedBase = baseUrl.trimEnd('/')
        val shared = pendingSharedText
        val target = if (!shared.isNullOrBlank()) {
            "$currentLoadedBase/?share=1&text=${URLEncoder.encode(shared, "UTF-8")}"
        } else {
            currentLoadedBase
        }
        webView.loadUrl(target)
    }

    private fun startOverlayFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToOverlayOrCapture()
    }

    private fun proceedToOverlayOrCapture() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        confirmAndRequestScreenCapture()
    }

    private fun confirmAndRequestScreenCapture() {
        if (prefs.getBoolean("explained_capture", false)) {
            requestScreenCaptureAndStartBubble()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ready to Float Over TikTok")
            .setMessage(
                "Android will ask for Screen Capture permission next so the floating GIF icon can grab a 3-second GIF only inside the box you draw over a TikTok comment.\n\nNothing is recorded in the background or uploaded anywhere."
            )
            .setPositiveButton("Continue") { _, _ ->
                prefs.edit().putBoolean("explained_capture", true).apply()
                requestScreenCaptureAndStartBubble()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestScreenCaptureAndStartBubble() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
