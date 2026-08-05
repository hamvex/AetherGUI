# Native JNI entry points are registered by HEV's JNI_OnLoad.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
