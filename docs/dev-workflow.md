# 开发工作流 / 本机环境备忘（Windows + Git Bash）

> 原 `MEMORY.md` 里的"构建、校验、调试"配方全部搬到这里，`MEMORY.md` 只留行为规则。
> 环境：`JAVA_HOME=E:\LIBERICAJDK21`、`ANDROID_HOME=E:\Android-SDK`、
> JDK 21、NDK 28.2.13676358 / 29.0.13599879、cmake 3.22.1 / 4.1.2。

## 0. 每个 shell 都要先加 PATH

bash shim 起的 shell 缺 coreutils（否则 `dirname`/`cd` 报错）：

```bash
export PATH="/c/Users/41607/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
# 需要 adb 时再加：
export PATH="$PATH:/c/Users/41607/AppData/Local/Android/Sdk/platform-tools"
```

## 1. 打 APK

```bash
cd E:/beatoraja-Android
export JAVA_HOME="E:/LIBERICAJDK21"
~/.gradle/wrapper/dists/gradle-9.4.0-bin/*/gradle-9.4.0/bin/gradle :android:assembleDebug --offline --console=plain
```

`./gradlew` 在本机用不了，一律用上面这个本地缓存里的 gradle 发行版。

### 1.2 判断"用户手上跑的是哪一次构建"（别急着怀疑旧 APK）

本机同时存在两份产物，**按时间取较新的那个**：

| 路径 | 说明 |
|---|---|
| `android/build/outputs/apk/debug/android-debug.apk` | AGP 的正式输出位 |
| `android/build/intermediates/apk/debug/android-debug.apk` | 中间产物，**用户自己构建时通常更新的是这个** |

用 `find . -name "*.apk" -newermt "<时间>"` 与 `git log -1 --format=%ci HEAD` 对时间。
2026-09-20 那轮就差点误判：`outputs/` 是 19:39（早于提交），而 `intermediates/` 是 20:44
（晚于提交）→ 用户跑的确实是新构建，回归是真的。

### 1.1 起不来时的两种症状

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to load native library 'native-platform.dll'` | native 缓存损坏 | `rm -f ~/.gradle/native/*/windows-amd64/native-platform*.dll ~/.gradle/native/*/windows-amd64/*.lock`（Gradle 会自动重新解压） |
| `journal-1.lock (拒绝访问。)`（连 build 都起不来） | 上一轮留下的**空闲 Gradle daemon** 仍占着 journal 锁，**与沙箱无关** | `tasklist /FI "IMAGENAME eq java.exe"` 找 PID → 看 `~/.gradle/daemon/<ver>/daemon-<pid>.out.log` 尾部只有 `periodic daemon health check` 即空闲 → `taskkill /PID <pid> /F` |

## 2. javac 秒级类型检查（不想跑 Gradle 时）

**classpath 太长、带 `;` 时要写成 `@argfile` 让 javac 自己读**，否则 MSYS 会改写参数。
路径一律 `cygpath -m` 转成 `C:/…` 形式。

core 的 classpath（4 项）：

- `core/build/classes/java/main`
- `~/.gradle/caches/modules-2/files-2.1/com.badlogicgames.gdx/gdx/1.14.0/<hash>/gdx-1.14.0.jar`
- `…/com.badlogicgames.gdx/gdx-freetype/1.14.0/<hash>/gdx-freetype-1.14.0.jar`
- `…/com.badlogicgames.gdx-controllers/gdx-controllers-core/2.2.4/<hash>/gdx-controllers-core-2.2.4.jar`

⚠️ **坑**：controllers 的 group 目录名是 **`com.badlogicgames.gdx-controllers` 一个目录**
（不是 `com.badlogicgames.gdx/gdx-controllers`）。写错的表现是编译时报
`无法访问 ControllerAdapter / 找不到 com.badlogic.gdx.controllers.ControllerAdapter 的类文件`，
很容易误判成"缺 jar"。

android 模块另需：`~/AppData/Local/Android/Sdk/platforms/android-36/android.jar`、
`android/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar`、
`…/javac/debug/compileDebugJavaWithJavac/classes`、`annotation-jvm-1.6.0.jar`、
oboe 的 `bundleLibCompileToJarDebug/classes.jar`、transforms 里的
`core-1.15.0-api.jar` / `gdx-backend-android-1.14.0-api.jar`。

**输出目录不要放在带 `$` 的路径下**（写 class 文件会报错）。
判定成功：`exit=0`，且只有"使用了过时的 API"这类提示。

**改了 `Skin.java` 这类被广泛引用的文件时，要把它和它的调用方一起喂给 javac**，
否则会拿 `core/build/classes/java/main` 里的旧 class 去比对新 API。

## 3. 改动资源后必须提升 versionCode

`AndroidLauncher.checkVersionAndCopyAssets` 只在 **versionCode 变化**时把 APK 里的
`assets/skin/**` 覆盖到 `filesDir/skin`。改了皮肤 lua/png 却不动 versionCode，
真机上跑的还是旧资源。当前值见 `android/build.gradle` defaultConfig（**16**）。

## 4. adb / 真机

- **`adb pull <绝对路径>` 会失败**（`cannot create file/directory '/tmp/…'`）——
  Windows 版 adb 不认 MSYS 路径。**先 `cd` 到目标目录，用相对文件名 pull**：
  `cd E:/beatoraja-Android/build/x && adb pull /sdcard/…/a.lua ./a.lua`
- **游戏 Activity `AndroidLauncher` 是 `exported="false"`** → `adb shell am start -n` 被
  `SecurityException: not exported` 拒绝，**adb 拉不起游戏**；唯一 LAUNCHER 是
  `compose.SettingsActivity`。游戏内 UI 只能让用户手工进。
- 截图：`adb shell screencap -p /sdcard/x.png` + pull。
  **截图全黑先查 `dumpsys power | grep mWakefulness`** —— `Asleep` 时 `screencap` 本来就是全黑
  （先 `adb shell input keyevent KEYCODE_WAKEUP`），别误判成崩溃/黑屏 bug。
- 本机设备：`27f4d8d2`（23049RAD8C），游戏包 `com.starxh.beatoraja`，
  皮肤在 `/sdcard/Android/data/com.starxh.beatoraja/files/skin/`，
  玩家配置在 `…/files/player/<name>/config_player.json`。

## 5. core 的日志要桥到 logcat

`java.util.logging` 默认写到 stderr —— 真机上等于黑洞，所以"XX 加载失败"这类 warning
一条都看不到。`AndroidLauncher.onCreate` 里调
`LogcatLogHandler.install()`（`android/.../LogcatLogHandler.java`）把它桥进 logcat，
**tag = `beatoraja`**。真机排查先 `adb logcat -s beatoraja`；
若没有输出，先确认这一步还在。

## 6. oboe AAR 重建

`libgdx-oboe/gradle/wrapper/gradle-wrapper.jar` 虽然在，但会报
`找不到或无法加载主类 org.gradle.wrapper.GradleWrapperMain`。**绕过办法：直接用本地缓存的
gradle 发行版**（8.14.3 已缓存）：

```bash
GRADLE=~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle
"$GRADLE" -p E:/beatoraja-Android/libgdx-oboe :library:assembleRelease --console=plain
# 产物：libgdx-oboe/library/build/outputs/aar/library-release.aar
```

改完 C++ **必须重建 AAR 并替换 `android/libs/libgdx-oboe.aar`**，否则源码与产物不一致。
ABI 只出 `armeabi-v7a` + `arm64-v8a`。

### C++ 改动的离线语法校验（秒级）

```bash
NDKBIN=$(cygpath -m ~/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin)
cd E:/beatoraja-Android/libgdx-oboe/library
"$NDKBIN/armv7a-linux-androideabi22-clang++" -fsyntax-only -std=gnu++17 -Wall -Wextra \
  -I dependencies/libsamplerate/include -I dependencies/fmt/include src/cpp/audio/<file>.cpp
# 64 位换 aarch64-linux-android22-clang++；两个都 exit=0 才算过
```

同一个 `.cpp` 的两个 `-I` 都是必需的，否则卡在 `samplerate.h` / `fmt/format.h`。

## 7. 皮肤 lua 语法校验

机器上没有 lua/luac，用项目自带的 luaj。写个 5 行小程序即可查语法
（`loadfile` 只编译不执行，不会跑皮肤逻辑）：

```java
// LuaSyntax.java
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;
public class LuaSyntax {
  public static void main(String[] a) {
    Globals g = JsePlatform.standardGlobals();
    for (String f : a) try { g.loadfile(f); System.out.println("OK   " + f); }
    catch (Throwable t) { System.out.println("FAIL " + f + " : " + t); }
  }
}
```

```bash
LUAJ=beatoraja-master/lib/luaj-jse-3.0.2-custom.jar
javac -cp "$LUAJ" -d <tmp> LuaSyntax.java && java -cp "<tmp>;$LUAJ" LuaSyntax path/to/play.lua
```

改完 `android/assets/skin/**/*.lua` 都先过一遍。
