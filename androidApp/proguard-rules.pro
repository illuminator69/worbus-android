-dontobfuscate

-keepattributes SourceFile, LineNumberTable
-keepattributes *Annotation*

-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keepnames class app.worbus.**,dev.zt64.** { *; }
