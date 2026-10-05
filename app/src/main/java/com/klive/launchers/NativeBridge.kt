package com.klive.launchers

import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Everything the launcher page can ask Android to do. Exposed to JavaScript as window.KLiveNative.
 * Methods annotated with @JavascriptInterface run on a WebView background thread, so anything
 * touching views or starting activities hops to the UI thread.
 */
class NativeBridge(private val act: MainActivity) {

    private val worker = Executors.newSingleThreadExecutor()
    private val iconSizePx = 96

    // ---------------- installed apps ----------------

    /** Loads every launchable app (label, package, activity, icon) and returns it via window.klApps(json). */
    @JavascriptInterface
    fun requestApps() {
        worker.execute {
            val pm = act.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = try {
                pm.queryIntentActivities(main, 0)
            } catch (e: Exception) {
                emptyList()
            }
            val arr = JSONArray()
            for (ri in list) {
                val info = ri.activityInfo ?: continue
                if (info.packageName == act.packageName) continue
                try {
                    val o = JSONObject()
                    o.put("label", ri.loadLabel(pm).toString())
                    o.put("pkg", info.packageName)
                    o.put("cls", info.name)
                    o.put("icon", drawableToDataUrl(ri.loadIcon(pm)))
                    arr.put(o)
                } catch (_: Exception) {
                }
            }
            val payload = JSONObject.quote(arr.toString())
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029")
            act.js("window.klApps&&klApps($payload)")
        }
    }

    @JavascriptInterface
    fun launch(pkg: String, cls: String) {
        act.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                act.startActivity(i)
            } catch (e: Exception) {
                val fallback = act.packageManager.getLaunchIntentForPackage(pkg)
                if (fallback != null) {
                    act.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    act.toastJs("That app could not be opened")
                }
            }
        }
    }

    @JavascriptInterface
    fun appInfo(pkg: String) {
        act.runOnUiThread {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    @JavascriptInterface
    fun uninstall(pkg: String) {
        act.runOnUiThread {
            try {
                act.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                act.toastJs("This app can't be uninstalled")
            }
        }
    }

    // ---------------- home screen role ----------------

    @JavascriptInterface
    fun isDefaultLauncher(): Boolean = act.isDefaultLauncher()

    @JavascriptInterface
    fun requestDefaultLauncher() = act.requestDefaultLauncher()

    // ---------------- device ----------------

    @JavascriptInterface
    fun haptic(kind: String) {
        val constant = when (kind) {
            "heavy" -> HapticFeedbackConstants.LONG_PRESS
            "confirm" -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        act.runOnUiThread { act.web.performHapticFeedback(constant) }
    }

    @JavascriptInterface
    fun battery(): String {
        val bm = act.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        return JSONObject().put("level", level).put("charging", charging).toString()
    }

    @JavascriptInterface
    fun insets(): String =
        JSONObject().put("top", act.insetTopDp.toDouble()).put("bottom", act.insetBottomDp.toDouble()).toString()

    @JavascriptInterface
    fun setBarsLight(lightBackground: Boolean) = act.setBarsLight(lightBackground)

    /** Pulls down the notification shade (works on most phones; silently ignored otherwise). */
    @JavascriptInterface
    fun expandNotifications() {
        act.runOnUiThread {
            try {
                @Suppress("WrongConstant")
                val sbm = act.getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
            } catch (_: Exception) {
            }
        }
    }

    /** Sets a data: URL image as the system wallpaper. which = "home", "lock" or "both". */
    @JavascriptInterface
    fun setWallpaper(dataUrl: String, which: String) {
        worker.execute {
            try {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("bad image")
                val flags = when (which) {
                    "home" -> WallpaperManager.FLAG_SYSTEM
                    "lock" -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                WallpaperManager.getInstance(act).setBitmap(bmp, null, true, flags)
                act.toastJs("Wallpaper set")
            } catch (e: Exception) {
                act.toastJs("Could not set the wallpaper on this phone")
            }
        }
    }

    // ---------------- files & sharing ----------------

    @JavascriptInterface
    fun saveFile(name: String, mime: String, text: String) = act.saveDocument(name, mime, text)

    @JavascriptInterface
    fun shareText(text: String, subject: String) {
        act.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            act.startActivity(Intent.createChooser(send, subject))
        }
    }

    // ---------------- helpers ----------------

    private fun drawableToDataUrl(d: Drawable): String {
        val bmp = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, iconSizePx, iconSizePx)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    private fun hasPackage(pkg: String): Boolean = try {
        act.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
