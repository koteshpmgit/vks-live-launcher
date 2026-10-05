package com.klive.launchers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * K Live Launchers.
 *
 * One activity hosts the whole experience in a WebView:
 *  - Studio mode: design a launcher from your images (colours, screens, icons, motion).
 *  - Launcher mode: the design becomes your real home screen, with your installed apps.
 *
 * The page talks to Android through [NativeBridge] (window.KLiveNative in JavaScript).
 */
class MainActivity : ComponentActivity() {

    lateinit var web: WebView
        private set

    /** System bar insets in dp, handed to the page so the dock and pages avoid them. */
    @Volatile var insetTopDp = 0f
    @Volatile var insetBottomDp = 0f

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveText: String? = null
    private var pageReady = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            val uris = mutableListOf<Uri>()
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
            }
            cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = pendingSaveText
            pendingSaveText = null
            val uri = res.data?.data
            if (res.resultCode != Activity.RESULT_OK || uri == null || text == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                toastJs("Theme file saved")
            } catch (e: Exception) {
                toastJs("Could not save the file. Try another folder.")
            }
        }

    private val roleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            js("window.klResume&&klResume()")
        }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            js("window.klAppsChanged&&klAppsChanged()")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge to edge: the launcher wallpaper runs behind the status and navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfigHelper.isDebuggable(this))
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F1F3F7"))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
        }

        // Serve the bundled page from a secure https origin so storage, canvas and clipboard all work.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
                // Web search and other links open in the phone's browser.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushInsets()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    pickImages.launch(Intent.createChooser(pick, getString(R.string.pick_images)))
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(NativeBridge(this), "KLiveNative")

        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            insetTopDp = bars.top / d
            insetBottomDp = bars.bottom / d
            pushInsets()
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.klBack?klBack():'exit'") { result ->
                    if (result?.contains("exit") == true && !isDefaultLauncher()) finish()
                }
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        }
        if (web.url == null) {
            web.loadUrl("https://$ASSET_HOST/assets/www/index.html")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while K Live is the launcher: close overlays and go to the first screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) js("window.klHome&&klHome()")
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        js("window.klResume&&klResume()")
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
        web.destroy()
        super.onDestroy()
    }

    // ---- helpers used by NativeBridge ----

    fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    fun toastJs(message: String) {
        js("window.klToast&&klToast(${JSONObject.quote(message)})")
    }

    private fun pushInsets() {
        if (pageReady) js("window.klInsets&&klInsets($insetTopDp,$insetBottomDp)")
    }

    fun setBarsLight(lightBackground: Boolean) {
        runOnUiThread {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.isAppearanceLightStatusBars = lightBackground
            c.isAppearanceLightNavigationBars = lightBackground
        }
    }

    fun saveDocument(name: String, mime: String, text: String) {
        runOnUiThread {
            pendingSaveText = text
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            }
            try {
                createDocument.launch(i)
            } catch (e: Exception) {
                pendingSaveText = null
                toastJs("No file app is available to save the theme.")
            }
        }
    }

    fun isDefaultLauncher(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    fun requestDefaultLauncher() {
        runOnUiThread {
            if (isDefaultLauncher()) {
                toastJs("K Live is already your home screen")
                return@runOnUiThread
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    try {
                        roleRequest.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                        return@runOnUiThread
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
    }
}

/** Small helper so the debug flag works without generated BuildConfig. */
object BuildConfigHelper {
    fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
