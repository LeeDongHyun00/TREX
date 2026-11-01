# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- TREX release(R8) — 성능 최적화(정본 스펙 §115) ----
# 줄이기·최적화(죽은 코드 제거·인라인, Compose 의 sourceInformation 제거)는 하고, 이름은 바꾸지 않는다:
# 체험판 오류 로그의 스택이 그대로 읽히고, 이름으로 클래스를 찾는 네이티브 코드가 깨질 여지를 줄인다.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# MediaPipe Tasks(자세·얼굴) — 네이티브(JNI)가 자바 클래스·필드를 이름으로 찾고, 결과는 protobuf 로 넘어온다. 통째로 둔다.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# LiteRT(사진 식단 TFLite) — 자바 패키지는 org.tensorflow.lite 그대로이고 네이티브가 이름으로 부른다.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# MediaPipe 가 딸려 오는 AutoValue 의 주석 처리기(빌드 때만 쓰는 javax.lang.model 참조) — 실행 중에는 닿지 않는다
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
