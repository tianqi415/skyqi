# 混淆规则（release 用）
-keepattributes *Annotation*
-keep class com.qiyun.browser.** { *; }
# WebView 兼容
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, android.net.Uri, android.os.Message);
}
-dontwarn android.webkit.**
