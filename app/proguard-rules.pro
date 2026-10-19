-keep class com.milan.music.data.model.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# R8 does not strip Log by default (proguard-android-optimize.txt only removes what
# is provably unused). PlayerController.refresh() runs on every Media3 event — several
# per playback second — and each run built four interpolated strings for a Log.d that
# no release build can read. Assuming the call has no side effects lets R8 drop both
# the call and the string building. Do not add -dontoptimize: it disables this rule.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
