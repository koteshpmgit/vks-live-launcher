# Keep every method the web page calls through window.KLiveNative
-keepclassmembers class com.klive.launchers.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
