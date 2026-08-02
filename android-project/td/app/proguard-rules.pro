
-keep class org.libsdl.app.** { *; }
-keep class com.vanilla_conquer.td.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn org.libsdl.app.**
