-keepattributes JavascriptInterface,Annotation,*Annotation*
-keep class com.giftap.overlay.MainActivity$WebShareBridge { *; }
-keepclassmembers class com.giftap.overlay.MainActivity$WebShareBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.giftap.overlay.OverlayBubbleService { *; }
