// Written by AI

# F2 UPDATE_FOLDER 扫描诊断指南

## 修复内容概述

本次修复针对 F2 触发 UPDATE_FOLDER 后，扫描逻辑被跳过或静默失败的问题，实施了以下三个关键修复：

### 1. ✅ 定位扫描入口并打印真实路径
**文件**: `AndroidSQLiteSongDatabaseAccessor.java` (第 587-645 行)

在真正调用文件树遍历之前，强制输出要扫描的绝对路径，并检查目录状态：

```java
Log.i(TAG, "[ScannerDebug] Preparing to scan directory:");
Log.i(TAG, "[ScannerDebug]   Absolute path: " + scanDir.path());
Log.i(TAG, "[ScannerDebug]   exists: " + scanDir.exists());
Log.i(TAG, "[ScannerDebug]   File.exists(): " + realFile.exists());
Log.i(TAG, "[ScannerDebug]   File.isDirectory(): " + realFile.isDirectory());
Log.i(TAG, "[ScannerDebug]   File.canRead(): " + realFile.canRead());
Log.i(TAG, "[ScannerDebug]   File.canExecute(): " + realFile.canExecute());
Log.i(TAG, "[ScannerDebug]   listFiles() returned: " + (testList == null ? "NULL" : testList.length + " items"));
```

**关键诊断点**:
- 如果 `exists: false` → 路径错误或权限不足
- 如果 `canRead: false` → Android Scoped Storage 权限未授予
- 如果 `listFiles() returned: NULL` → 目录不可访问或权限受限

---

### 2. ✅ 拦截文件遍历的静默异常 (File I/O Exception)
**文件**: `AndroidSQLiteSongDatabaseAccessor.java` (第 692-760 行)

在 `scanFolderRecursively()` 方法中添加了完整的异常捕获和日志输出：

```java
try {
    children = folder.list();
    Log.d(TAG, "[ScanFolder] list() returned for: " + folder.path() + " -> " + (children == null ? "NULL" : children.length + " items"));
} catch (Exception listException) {
    Log.e(TAG, "[ScannerError] Failed to list directory: " + folder.path());
    Log.e(TAG, "[ScannerError] Exception type: " + listException.getClass().getName());
    Log.e(TAG, "[ScannerError] Message: " + listException.getMessage());
    Log.e(TAG, "[ScannerError] Full stack trace:", listException);
    return false;
}
```

**关键诊断点**:
- 任何 `java.nio.file` 相关的异常会立即暴露
- 权限拒绝异常会完整打印堆栈跟踪
- 防止扫描过程静默失败

---

### 3. ✅ 修正更新逻辑顺序 (Ensure Scan before Query)
**文件**: `MainController.java` (第 745-793 行)

在 `SongUpdateThread.run()` 中：
1. **阻塞等待扫描完成** - `getSongDatabase().updateSongDatas()` 是同步调用
2. **扫描完成后刷新 UI** - 使用 `Gdx.app.postRunnable()` 确保在 GL 线程执行
3. **添加完成日志** - 明确标识扫描结束和 UI 刷新时机

```java
public void run() {
    long threadStartTime = System.currentTimeMillis();
    Logger.getGlobal().info("[SongUpdateThread] Starting async scan task");
    
    // 执行扫描 - 这是阻塞调用，会等待扫描完成
    getSongDatabase().updateSongDatas(path, config.getBmsroot(), false, getInfoDatabase());
    
    long elapsed = System.currentTimeMillis() - threadStartTime;
    Logger.getGlobal().info("[SongUpdateThread] Scan task COMPLETED in " + elapsed + "ms");
    
    // 扫描完成后，在 GL 线程中刷新 UI
    Gdx.app.postRunnable(() -> {
        Logger.getGlobal().info("[SongUpdateThread] Executing UI refresh on GL thread");
        if (main instanceof MusicSelector) {
            MusicSelector selector = (MusicSelector) main;
            if (selector.getBarManager() != null) {
                selector.getBarManager().updateBar();
                Logger.getGlobal().info("[SongUpdateThread] BarManager.updateBar() called");
            }
        }
    });
}
```

---

## 如何查看诊断日志

### 使用 adb logcat 查看实时日志

```bash
# 查看所有相关日志
adb logcat | grep -E "AndroidSongDB|SongUpdateThread|InputDebug|ScannerDebug|ScannerError"

# 或者按标签过滤
adb logcat -s AndroidSongDB:I SongUpdateThread:I InputDebug:D ScannerDebug:I ScannerError:E
```

### 预期日志流程（正常情况）

```
================================================================================
InputDebug: UPDATE_FOLDER activated, starting async update
InputDebug: Current selected bar type: FolderBar
InputDebug: Target folder path: /storage/emulated/0/Download/oraja_bms
================================================================================

================================================================================
[SongUpdateThread] Starting async scan task
[SongUpdateThread] Update path: /storage/emulated/0/Download/oraja_bms
[SongUpdateThread] bmsroot: [/storage/emulated/0/Download/oraja_bms]
================================================================================

================================================================================
Step 2: Starting folder scan with 1 root path(s)
================================================================================

================================================================================
[ScannerDebug] Preparing to scan directory:
[ScannerDebug]   Absolute path: /storage/emulated/0/Download/oraja_bms
[ScannerDebug]   exists: true
[ScannerDebug]   File.exists(): true
[ScannerDebug]   File.isDirectory(): true
[ScannerDebug]   File.canRead(): true
[ScannerDebug]   File.canExecute(): true
[ScannerDebug]   listFiles() returned: 15 items
[ScannerDebug]   forceRefresh: false
================================================================================

Starting recursive scan of: /storage/emulated/0/Download/oraja_bms
[ScanFolder] list() returned for: /storage/emulated/0/Download/oraja_bms -> 15 items
[ProcessBmsFile] New file, need to add: /storage/emulated/0/Download/oraja_bms/song1/test.bms
[ProcessBmsFile] Decoding: /storage/emulated/0/Download/oraja_bms/song1/test.bms
[ProcessBmsFile] Decoded successfully in 45ms: /storage/emulated/0/Download/oraja_bms/song1/test.bms
[ProcessBmsFile] Successfully inserted/updated: Test Song (/storage/emulated/0/Download/oraja_bms/song1/test.bms)

Recursive scan completed for: /storage/emulated/0/Download/oraja_bms in 1250ms

================================================================================
All paths scanned. Transaction committing...
================================================================================

updateSongDatas completed: 12 files processed/updated, 0 files deleted, 0 files skipped (unmodified), total songs now: 12 in 1320ms

================================================================================
[SongUpdateThread] Scan task COMPLETED in 1325ms
[SongUpdateThread] Now triggering UI refresh...
================================================================================

[SongUpdateThread] Executing UI refresh on GL thread
[SongUpdateThread] BarManager.updateBar() called
```

---

## 常见错误诊断

### 错误 1: 路径不存在 (exists: false)

```log
[ScannerDebug]   Absolute path: /storage/emulated/0/Download/oraja_bms
[ScannerDebug]   exists: false
[ERROR] BMS directory does not exist or cannot be accessed: /storage/emulated/0/Download/oraja_bms
[ERROR] This is likely an Android Scoped Storage permission issue!
```

**解决方案**:
1. 检查路径拼写是否正确
2. 确认目录实际存在：`adb shell ls -la /storage/emulated/0/Download/oraja_bms`
3. 检查 MANAGE_EXTERNAL_STORAGE 权限是否授予

---

### 错误 2: 权限不足 (canRead: false)

```log
[ScannerDebug]   File.exists(): true
[ScannerDebug]   File.canRead(): false
[ScannerError] Failed to list directory: /storage/emulated/0/Download/oraja_bms
[ScannerError] Exception type: java.lang.SecurityException
```

**解决方案**:
1. 进入系统设置 → 应用 → beatoraja → 权限
2. 授予"所有文件访问权限" (MANAGE_EXTERNAL_STORAGE)
3. 或者使用 adb 命令授予权限：
   ```bash
   adb shell appops set <package_name> MANAGE_EXTERNAL_STORAGE allow
   ```

---

### 错误 3: listFiles() 返回 NULL

```log
[ScannerDebug]   File.exists(): true
[ScannerDebug]   File.canRead(): true
[ScannerDebug]   listFiles() returned: NULL
[ScanFolder] list() returned NULL (directory may be empty or inaccessible)
```

**可能原因**:
1. 目录确实为空（没有文件）
2. Android 11+ 的 Scoped Storage 限制
3. 目录中有大量文件导致超时

**解决方案**:
1. 确认目录中有 BMS 文件：`adb shell ls /storage/emulated/0/Download/oraja_bms`
2. 尝试使用 FORCE_REFRESH（在游戏中设置强制刷新选项）

---

### 错误 4: 扫描耗时过短 (6ms 内完成)

```log
Recursive scan completed for: /storage/emulated/0/Download/oraja_bms in 6ms
updateSongDatas completed: 0 files processed/updated
```

**说明**: 这就是你当前遇到的问题！扫描瞬间完成且没有处理任何文件。

**根据新日志，可以判断**:
- 如果看到 `[ScannerDebug] listFiles() returned: NULL` → 目录不可访问
- 如果看到 `[ScannerDebug] exists: false` → 路径错误
- 如果看到 `[ScannerError]` → 异常被捕获，查看具体错误信息

---

## 下一步行动

1. **重新编译并部署应用**
   ```bash
   ./gradlew :android:assembleDebug
   adb install -r android/build/outputs/apk/debug/android-debug.apk
   ```

2. **启动 logcat 监控**
   ```bash
   adb logcat -c  # 清除旧日志
   adb logcat | grep -E "AndroidSongDB|SongUpdateThread|InputDebug"
   ```

3. **按 F2 触发扫描**
   - 观察日志输出
   - 查找 `[ScannerDebug]` 部分的路径和权限信息
   - 查找 `[ScannerError]` 部分的异常堆栈

4. **提供完整日志**
   - 将 F2 触发后的完整日志发送给我
   - 特别关注 `[ScannerDebug]` 和 `[ScannerError]` 的输出
   - 我会根据日志进一步诊断问题

---

## 技术细节

### 修改的文件清单

1. **MainController.java** (core 模块)
   - 增强 `SongUpdateThread.run()` 方法
   - 添加扫描完成后的 UI 刷新逻辑
   - 添加详细的时序日志

2. **AndroidSQLiteSongDatabaseAccessor.java** (android 模块)
   - 增强 `updateSongDatas()` 方法 - 添加路径诊断
   - 增强 `scanFolderRecursively()` 方法 - 添加异常拦截
   - 增强 `processBmsFile()` 方法 - 添加处理日志

3. **MusicSelectInputProcessor.java** (core 模块)
   - 增强 F2 按键处理 - 添加上下文信息日志

### 关键改进点

- ✅ **诊断点 1**: 扫描前打印真实路径和权限状态
- ✅ **诊断点 2**: 拦截 `FileHandle.list()` 的异常
- ✅ **诊断点 3**: 捕获所有未预期的扫描异常
- ✅ **修复 1**: 确保扫描完成后再刷新 UI
- ✅ **修复 2**: 使用 `Gdx.app.postRunnable()` 在正确线程刷新
- ✅ **修复 3**: 添加完整的错误堆栈跟踪

---

## 常见问题

**Q: 为什么要用 `Gdx.app.postRunnable()` 刷新 UI？**  
A: LibGDX 的 UI 更新必须在 GL 线程（渲染线程）中执行。扫描在后台线程完成，如果不切换到 GL 线程就更新 UI，会导致崩溃或 UI 不刷新。

**Q: 为什么之前的扫描会瞬间完成？**  
A: 很可能是因为 `scanDir.exists()` 返回 false 或 `folder.list()` 返回 null，导致扫描逻辑直接 return，没有真正遍历文件。

**Q: 如何强制重新扫描所有文件？**  
A: 在调用 `updateSongDatas()` 时，将 `forceRefresh` 参数设为 true，会忽略时间戳检查，重新解析所有文件。

---

**祝你调试顺利！有任何问题随时联系。** 🎮
