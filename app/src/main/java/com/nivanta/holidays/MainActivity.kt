package com.nivanta.holidays

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val APP_URL = "https://nivanta-holidays-updated-3.vercel.app/"
        const val CHANNEL_ID = "nivanta_default"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout

    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // ---- Permission / result launchers ----
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)

        createNotificationChannel()
        askNotificationPermission()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)   // payment popups open in same view
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString NivantaApp/1.0"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // needed by payment gateways
        }

        // JS bridge: website can call  Android.showNotification("Title","Body")
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean =
                handleUrl(req.url.toString())

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    v.loadData(
                        "<html><body style='font-family:sans-serif;text-align:center;padding:48px'>" +
                            "<h3>No internet connection</h3><p>Check your network and pull down to retry.</p></body></html>",
                        "text/html", "UTF-8"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }

            // Location
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                val fine = Manifest.permission.ACCESS_FINE_LOCATION
                if (ContextCompat.checkSelfPermission(this@MainActivity, fine) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    locationPermission.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            // File upload (e.g. ID proof)
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    fileChooser.launch(params.createIntent()); true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null; false
                }
            }
        }

        swipe.setOnRefreshListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) webView.loadUrl(APP_URL) else webView.restoreState(savedInstanceState)
    }

    // ---- URL handling: UPI / Google Pay / WhatsApp / call / mail ----
    private fun handleUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (uri.host == "wa.me" || uri.host == "api.whatsapp.com") { openExternal(Intent(Intent.ACTION_VIEW, uri)); true }
                else false // load inside WebView (payment gateway pages stay in-app)
            }
            "intent" -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        if (fallback != null) webView.loadUrl(fallback)
                        else intent.`package`?.let {
                            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")))
                        }
                    }
                } catch (_: Exception) { }
                true
            }
            "upi", "tez", "phonepe", "paytmmp", "gpay", "whatsapp", "tel", "mailto", "sms", "geo", "market" -> {
                openExternal(Intent(Intent.ACTION_VIEW, uri)); true
            }
            else -> false
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No app found to open this", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Notifications ----
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Nivanta Updates", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = Intent(this@MainActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = android.app.PendingIntent.getActivity(
                this@MainActivity, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this@MainActivity, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setColor(0xFF2B484C.toInt())
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(this@MainActivity).notify(System.currentTimeMillis().toInt(), n)
        }

        @JavascriptInterface
        fun isApp(): Boolean = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { webView.onPause(); super.onPause() }
}
