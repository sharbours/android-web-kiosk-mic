package org.screenlite.webkiosk.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.webkit.*
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.annotation.RequiresApi
import org.screenlite.webkiosk.MainActivity
import org.screenlite.webkiosk.components.RotatedWebView
import org.screenlite.webkiosk.data.Rotation

class WebViewManager(
    private val context: Context,
    private val onError: (Boolean) -> Unit,
    private val onPageLoading: (Boolean) -> Unit
) {
    private var currentWebView: WebView? = null
    fun createWebView(rotation: Rotation = Rotation.ROTATION_0): WebView {
        val webView = RotatedWebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            appliedRotation = rotation.degrees.toFloat()

            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()

            configureWebViewSettings()
            setupWebViewListeners()
        }

        currentWebView = webView
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureWebViewSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            setWebContentsDebuggingEnabled(true)
            val displayMetrics = context.resources.displayMetrics
            setInitialScale(calculateScale(displayMetrics))

            displayZoomControls = false
            builtInZoomControls = false
            setSupportZoom(false)

            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            useWideViewPort = true
        }
    }

    /**
     * Only the page currently shown in the kiosk (same host) may use the microphone.
     * This stops embedded third-party iframes from silently getting access.
     */
    private fun isTrustedOrigin(origin: Uri): Boolean {
        val pageHost = currentWebView?.url?.let { Uri.parse(it).host } ?: return false
        return origin.host.equals(pageHost, ignoreCase = true)
    }

    private fun calculateScale(displayMetrics: DisplayMetrics): Int {
        val density = displayMetrics.density
        return (100 / density).toInt()
    }

    fun updateRotation(rotation: Rotation) {
        currentWebView?.let { webView ->
            if (webView is RotatedWebView) {
                webView.appliedRotation = rotation.degrees.toFloat()
            }

            ViewportMetaInjector.inject(webView)
        }
    }

    private fun WebView.setupWebViewListeners() {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.visibility = View.INVISIBLE
                onPageLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                ViewportMetaInjector.inject(view)
                view.postDelayed({
                    view.visibility = View.VISIBLE
                    onPageLoading(false)
                }, 1000)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e("WebViewManager", "Legacy page failed: $failingUrl, code=$errorCode, desc=$description")
                onPageLoading(false)
                onError(true)
                super.onReceivedError(view, errorCode, description, failingUrl)
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    onPageLoading(false)
                    onError(true)
                    Log.e(
                        "WebViewManager",
                        "Main page failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                    )
                } else {
                    Log.w(
                        "WebViewManager",
                        "Subresource failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                    )
                }
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                if (newScale != 1.0f) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport

                val tempWebView = WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url.toString()
                            this@WebViewManager.currentWebView?.loadUrl(url)
                            return true
                        }
                    }
                }

                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio || !isTrustedOrigin(request.origin)) {
                    Log.w("WebViewManager", "Denying web permission request: ${request.resources.joinToString()} from ${request.origin}")
                    request.deny()
                    return
                }

                val grantAudio = {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                }

                if (MicrophonePermissionHelper.hasPermission(context)) {
                    grantAudio()
                } else {
                    val activity = context as? MainActivity
                    if (activity == null) {
                        request.deny()
                    } else {
                        activity.requestMicrophonePermission { granted ->
                            if (granted) grantAudio() else request.deny()
                        }
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "WebViewConsole",
                    "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }
    }
}