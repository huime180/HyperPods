# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

# Keep Xposed entry point
-keep class com.chenyc.hyperpods.hook.HookEntry { *; }

# Keep all hooker classes (referenced by name in Xposed framework)
-keep class com.chenyc.hyperpods.hook.** { *; }

# Keep Parcelable data classes (used in broadcast extras)
-keep class com.chenyc.hyperpods.utils.miuiStrongToast.data.** { *; }
