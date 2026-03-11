# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK's default ProGuard rules file.

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep MediaCodec / MediaMuxer (already part of Android SDK, but just in case)
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaMuxer { *; }
