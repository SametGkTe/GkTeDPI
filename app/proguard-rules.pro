# GkTeDPI ProGuard Rules
-keep class com.sametgkte.gktedpi.core.TProxyService { *; }
-keep class com.sametgkte.gktedpi.core.ByeDpiProxy { *; }
-keep class com.sametgkte.gktedpi.fragments.** { *; }
-keepnames class * extends androidx.preference.PreferenceFragmentCompat
