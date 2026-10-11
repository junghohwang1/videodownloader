# 라이브러리들이 consumer rules 를 제공하므로 별도 규칙은 필요 없다.
# WebView JavaScript 인터페이스는 사용하지 않는다.

# NewPipe Extractor (유튜브) 와 서명 해독에 쓰는 Rhino 자바스크립트 엔진
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
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
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# ML Kit 번역(자막 번역): R8 전체 모드가 내부 부품을 지우거나 이름을 바꿔 Translation.getClient 에서 NullPointerException 이 났다.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
