# Add project specific ProGuard rules here.
-keep class com.lightningstudio.watchrss.phone.** { *; }

# Tink, pulled in by AndroidX Security Crypto, references Error Prone annotations
# that are not needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# OPPO Push SDK (closed-source) — keep everything; it is reflectively invoked.
-keep class com.heytap.msp.** { *; }
-keep class com.nearme.mcs.** { *; }
-dontwarn com.heytap.**
-dontwarn com.nearme.**
