# Application class initialization happens before MultiDexApplication.attachBaseContext.
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication$* { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class androidx.multidex.** { *; }
-keep class com.shilapi.xcertplay.LoggingGate { *; }
-keep class com.shilapi.xcertplay.FloatingDotOverlay { *; }

# The BYD dashboard song tool (BydClusterSongTool) runs as a standalone `app_process` with
# CLASSPATH=$apk, which only loads classes.dex (the primary dex) and never the secondary dexes.
# Keep the tool and its direct dependencies in the primary dex so app_process can find them.
-keep class com.shilapi.xcertplay.hud.BydClusterSongTool { *; }
-keep class com.shilapi.xcertplay.hud.BydClusterSong { *; }
-keep class com.shilapi.xcertplay.hud.ClusterSongState { *; }
-keep class com.shilapi.xcertplay.hud.ClusterSongWriteResult { *; }
-keep class com.shilapi.xcertplay.hud.ClusterSong { *; }
