# To enable ProGuard in your project, edit project.properties
# to define the proguard.config property as described in that file.
#
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the ProGuard
# include property in project.properties.
#
# For more details, see
#   https://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-verbose

-dontwarn android.support.**
-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication

# Needed by the gdx-controllers official extension.
-keep class com.badlogic.gdx.controllers.android.AndroidControllers

# Needed by the gdx-video official extension (Android hardware video decoding).
-keep class com.badlogic.gdx.video.** { *; }

# Needed by JCodec (pure Java soft decoding for legacy .avi BGA videos).
# Note: JCodec 0.2.5 only supports AVI container. MPEG PS (.mpg) and WMV are handled by gdx-video.
-keep class org.jcodec.** { *; }
-dontwarn org.jcodec.**

# ===========================================================
# R8 Release 构建修复：忽略 Android 不存在的 Java SE 桌面类
# ===========================================================

# LuaJ 脚本引擎（Android 不需要 javax.script）
-dontwarn javax.script.**

# MIDI 输入支持（Android 不支持 javax.sound）
-dontwarn javax.sound.**

# jFLAC 音频解码（javax.sound.sampled SPI 在 Android 上不需要）
-dontwarn javax.sound.sampled.spi.**

# Needed by the Box2D official extension.
-keepclassmembers class com.badlogic.gdx.physics.box2d.World {
   boolean contactFilter(long, long);
   boolean getUseDefaultContactFilter();
   void    beginContact(long);
   void    endContact(long);
   void    preSolve(long, long);
   void    postSolve(long, long);
   boolean reportFixture(long);
   float   reportRayFixture(long, float, float, float, float, float);
}

# You will need the next three lines if you use scene2d for UI or gameplay.
# If you don't use scene2d at all, you can remove or comment out the next line:
-keep public class com.badlogic.gdx.scenes.scene2d.** { *; }
# You will need the next two lines if you use BitmapFont or any scene2d.ui text:
-keep public class com.badlogic.gdx.graphics.g2d.BitmapFont { *; }
# You will probably need this line in most cases:
-keep public class com.badlogic.gdx.graphics.Color { *; }

# These two lines are used with mapping files; see https://developer.android.com/build/shrink-code#retracing
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

# ===========================================================
# 极限瘦身2：R8 混淆规则 - 保护反射/序列化使用的关键类
# ===========================================================

# Jackson JSON 序列化保护（bmson 谱面解析、Config 持久化）
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class bms.model.bmson.** { *; }
-keep class bms.player.beatoraja.** { *; }

# Config 配置类（通过 Jackson 序列化，必须保留字段名）
-keep class bms.player.beatoraja.Config { *; }
-keep class bms.player.beatoraja.AudioConfig { *; }
-keep class bms.player.beatoraja.SkinConfig { *; }
-keep class bms.player.beatoraja.PlayConfig { *; }
-keep class bms.player.beatoraja.PlayModeConfig { *; }
-keep class bms.player.beatoraja.PlayerConfig { *; }
-keep class bms.player.beatoraja.IRConfig { *; }
-keepclassmembers class bms.player.beatoraja.** { *; }

# SQLDroid 数据库驱动
-keep class org.sqldroid.** { *; }
-dontwarn org.sqldroid.**

# libGDX 核心类（场景2D、图形、音频等）
-keep class com.badlogic.gdx.** { *; }
-keepclassmembers class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**
-dontnote com.badlogic.gdx.**

# 枚举保护（混淆可能破坏枚举序列化）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
