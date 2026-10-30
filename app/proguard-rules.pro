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
# MediaPipe 의 로거(Flogger)도 통째로 둔다(스펙 §121). FluentLogger.forEnclosingClass() 는 스택에서 자기 프레임 다음 프레임으로
# 호출 클래스를 찾는데, R8 이 그 메서드를 MediaPipe 클래스(위에서 남긴 Graph 등) 안으로 인라인하면 자기 프레임이 사라져
# Graph 정적 초기화가 "no caller found on the stack" 으로 실패하고 PoseLandmarker·FaceLandmarker 를 만들 수 없다 — 카메라 자세 분석 전체가 죽는다.
# 위의 mediapipe keep 은 부르는 쪽만 남길 뿐 불리는 쪽의 인라인은 막지 않는다. 단위 테스트·디버그 빌드에서는 드러나지 않는다(R8 은 release 만).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# LiteRT(사진 식단 TFLite) — 자바 패키지는 org.tensorflow.lite 그대로이고 네이티브가 이름으로 부른다.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# MediaPipe 가 딸려 오는 AutoValue 의 주석 처리기(빌드 때만 쓰는 javax.lang.model 참조) — 실행 중에는 닿지 않는다
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
