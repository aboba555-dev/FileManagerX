# libsu ships its own consumer rules; the root service class is instantiated by name inside the root process.
-keep class app.filemanagerx.data.fs.FileSystemService { *; }

# Commons Compress and junrar reference optional codecs and helpers that are not bundled.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.vfs2.**
-dontwarn org.slf4j.impl.**
-dontwarn javax.annotation.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**

# Commons Compress looks up archive/compressor implementations reflectively through ServiceLoader.
-keep class org.apache.commons.compress.archivers.** { *; }
-keep class org.apache.commons.compress.compressors.** { *; }

# Keep readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
