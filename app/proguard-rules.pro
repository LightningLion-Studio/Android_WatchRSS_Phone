# Add project specific ProGuard rules here.
-keep class com.lightningstudio.watchrss.phone.** { *; }

# Tink, pulled in by AndroidX Security Crypto, references Error Prone annotations
# that are not needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
