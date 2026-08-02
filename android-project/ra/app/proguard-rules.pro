
-keep class org.libsdl.app.** { *; }
-keep class com.vanilla_conquer.ra.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn org.libsdl.app.**
