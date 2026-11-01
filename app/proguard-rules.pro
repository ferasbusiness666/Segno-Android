# Keep class names readable in crash reports from users (the APK is small either way).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

## NewPipeExtractor
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

## Rhino, the JavaScript engine NewPipeExtractor uses for YouTube's player code
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**

## jsoup, protobuf and other extractor dependencies reference desktop-only classes
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**

## OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

## Serializable classes from NewPipeExtractor (InfoItem and friends)
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
