# 没有 -keep class com.milan.music.data.model.** —— 真正被序列化的是
# LibraryCacheStore.CachedTrack 与 UserDataStore 里的私有模型，它们的 serializer 由编译器
# 生成并被代码直接引用（R8 看得到这条引用，不会当成未使用）。Track 不参与序列化，
# 旧规则纯粹是白留，而且会掩盖"到底谁需要 keep"这个问题。
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
