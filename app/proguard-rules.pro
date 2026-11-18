# sherpa-onnx: native code reads/writes these Kotlin config/result classes by field name via JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# pdfbox-android: optional JPEG2000 codec and other optional deps are not on the classpath.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**

# commons-compress optional codecs (xz, zstd, brotli, lz4...) and OSGi/ASM references.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.harmony.**

# juniversalchardet
-dontwarn org.mozilla.universalchardet.**
