# libVLC uses JNI callbacks into these classes; R8 must not strip or rename them.
-keep class org.videolan.libvlc.** { *; }
