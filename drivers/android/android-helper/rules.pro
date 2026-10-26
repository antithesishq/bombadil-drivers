# R8 rules for the helper APK. The system instantiates the instrumentation
# by the name in AndroidManifest.xml; everything else is reached from it.
-keep class me.uzaaft.bombadil.helper.Helper { <init>(); }
# Readable stack traces in logcat; the APK is small either way.
-dontobfuscate
