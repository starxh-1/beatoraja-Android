# 三个未修复 Bug 的根因分析

> 日期: 2026-07-20
> 范围: BGA 视频回退 / 选歌界面 txt / songdb 增量扫描丢父目录
> 目的: 把每个 bug 的可疑代码路径和验证点固化下来,再决定怎么修。

---

## Bug 1 — BGA 视频在播放结束后回退到前面的某一帧

### 现象
视频/BGA 播放完毕后画面"闪现"——画面突然回到一个比当前游戏时间早得多的位置,看上去像是"倒带"。

### 代码路径
文件: `core/src/main/java/bms/player/beatoraja/play/bga/GdxVideoProcessor.java`

`BMSPlayer` 每帧调用 `BGAProcessor.prepareBGA(time)`,后者对每个 BGA 事件 TimeLine 调用 `movies[bga].play(tl.getMilliTime(), false)`(BGAProcessor.java:356)。`GdxVideoProcessor.synchronizeVideo(long gameTime)`(`GdxVideoProcessor.java:183-237`)是实际控制进度的关键方法:

```java
// GdxVideoProcessor.java:183-195
private void synchronizeVideo(long gameTime) {
    long targetVideoTime = gameTime - gameStartTime;
    if (targetVideoTime < 0) return;

    if (videoDuration > 0 && targetVideoTime > videoDuration) {
        if (loop) {
            targetVideoTime = targetVideoTime % videoDuration;
        } else {
            // 非循环视频:游戏时间已超过视频时长,视频自然结束。
            // 停止 seek 以避免 MediaPlayer "Attempt to seek to past end of file" 和异常帧闪现。
            return;                              // ← 关键: 只是 return,没有 pause()/stop()
        }
    }

    long currentVideoTime = videoPlayer.getCurrentTimestamp();
    long drift = currentVideoTime - targetVideoTime;

    // ... 首次同步忽略 ...

    // 异常大漂移 (> 1s): 通常是 pause/resume 后或解码卡顿
    if (Math.abs(drift) > SEVERE_DRIFT_THRESHOLD) {  // 1000 ms
        // ...
        videoPlayer.seek(target);                    // ← 这里会 seek 回目标时间
    }
}
```

### 根因
**视频结束 (EOF) 后,`synchronizeVideo()` 只 `return` 不做任何清理**——native `VideoPlayer` (MediaCodec/MediaPlayer 后端) 继续解码到最后一帧,然后停在末尾,但 `getCurrentTimestamp()` 在 EOF 之后返回的值不可靠(回 0、-1、或上一帧的剩余时间)。

下一个 200 ms 同步周期到来:
1. `targetVideoTime = gameTime - gameStartTime`,例如 60_000 ms(早已超过视频时长 30_000 ms)
2. EOF 判断命中 `return` 一次;但只要 `gameTime` 还在涨(歌还在播),下一次同步会再进来
3. 如果 native `getCurrentTimestamp()` 返回 0(或异常值),`drift = 0 - 60000 = -60000 ms`
4. `Math.abs(drift) > 1000` → 进入 severe-drift 分支
5. `videoPlayer.seek(target)` → MediaPlayer 被 seek 到 60_000 ms,但视频只有 30_000 ms,实际效果是**把播放头拉回到视频末尾附近一个看似合理的随机位置**

注释说"停止 seek",但实现只是 `return`,从来没真的"停止"过。

### 验证手段
1. 在 `synchronizeVideo` 早返回位置(行 193)加日志:`"video ended at gameTime=" + gameTime + ", duration=" + videoDuration`,确认是否每次 BGA 结束都会进这个分支。
2. 在 `seek(target)` 调用前(行 227)加日志,记录是否触发 severe-drift 反向 seek:出现 `target > videoDuration` 且 `currentVideoTime ≈ 0` 的组合就是回退源头。

### 修复方向(待用户确认)
最小修复: 在 EOF 后 `return` 之前调用 `videoPlayer.pause()` 并把内部 `playing` 置 false,这样:
- `getCurrentTimestamp()` 不会再返回随机值
- 后续同步周期即使再进来也不会触发 severe-drift seek

```java
if (videoDuration > 0 && targetVideoTime > videoDuration) {
    if (loop) {
        targetVideoTime = targetVideoTime % videoDuration;
    } else {
        if (playing) {
            videoPlayer.pause();
            playing = false;
        }
        return;
    }
}
```

---

## Bug 2 — 选歌界面"可以阅读 txt 文本"

### 现象描述(由用户给出)
> 游戏内的 select 界面可以阅读 txt 文本

我需要先和用户确认意图:是已经能选 txt 直接显示文本内容(那是要做内置查看器),还是说希望在 select 列表里能"打开 README"跳到系统应用(那是 Intent 跳转)。

### 当前代码状态
文件: `core/src/main/java/bms/player/beatoraja/skin/property/EventFactory.java`

**`open_document` 事件 (id 17, EventFactory.java:142-157)** — 已经是设计好的"打开 README"功能:

```java
open_document(17, (state) -> {
    if (!isDesktopSupported()) {       // ← Android 上永远 false
        return;
    }
    if (state instanceof MusicSelector selector
        && selector.getBarManager().getSelected() instanceof SongBar songbar
        && songbar.existsSong()) {
        File parent = new File(songbar.getSongData().getPath()).getParentFile();
        File[] files = parent.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".txt")) {
                    openFile(f);     // ← 也是 Desktop only
                }
            }
        }
    }
}),
```

**`isDesktopSupported()` (EventFactory.java:795-803)** 用反射检查 `java.awt.Desktop`:
- Android 上没有 `java.awt.Desktop` 类 → 反射抛 `ClassNotFoundException` → catch 后返回 false
- 所以 `open_document` 在 Android 上**直接 return,什么都不做**

**`openFile()` (EventFactory.java:829-837)** 也是 `java.awt.Desktop.open()` 调用,Android 走不通。

**对比参照: `browse()` (EventFactory.java:805-827)** — 同一个文件里已经有跨平台 URL 跳转模板:

```java
private static void browse(String url, String message) {
    if (url == null || url.isEmpty()) return;
    try {
        com.badlogic.gdx.Application app = com.badlogic.gdx.Gdx.app;
        if (app != null && app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
            Object launcher = app;
            // 反射调用 AndroidLauncher.openUrl(String, String)
            Method method = launcher.getClass().getMethod("openUrl", String.class, String.class);
            method.invoke(launcher, url, message);
        } else {
            // Desktop
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            // ...
        }
    } catch (Throwable e) { e.printStackTrace(); }
}
```

`AndroidLauncher.openUrlDirect()` (`AndroidLauncher.java:1158-1164`) 是这个反射调用的实际实现:`ACTION_VIEW` + `Uri.parse(url)` + `FLAG_ACTIVITY_NEW_TASK` + `startActivity`。

**没有任何内置 txt 阅读器**:
- `SkinProperty.TIMER_README_BEGIN/END` 是空 stub
- LR2 的 `SRC_README` / `DST_README` 命令在 `LR2SelectSkinLoader.java:559-568` 也是空实现
- SQLite 扫描器 `AndroidSQLiteSongDatabaseAccessor.java:1412-1424,1647` 只记录 `hasTxt` 布尔标志位(用来显示图标),**从不读取或展示内容**

### 结论
**用户看到的不是 select 界面"内置阅读 txt",而是 `open_document` 事件触发后没反应**。如果没有给 LR2 皮肤某个键位绑定 `open_document` (id 17),那是事件根本不会触发;如果绑定了,在 Android 上由于 `isDesktopSupported()` 提前 return,事件无操作,没有任何"阅读"行为。

### 建议方案(待用户确认)
**采用"跳转外部应用打开",不做内置阅读器**。理由:
- 项目里已经有 `browse()` 的反射+Intent 模板,沿用最小改动
- BMS README 常常很大、有 ANSI 颜色、表格,内置渲染需要字体/分页/滚动
- 系统文本查看器(wps、Jota、Markdown 等)已经够用

具体改动:
1. 在 `AndroidLauncher` 加 `openFileDirect(String path)` 方法:
   - 构造 `Intent(ACTION_VIEW)`,MIME `text/plain`
   - 用 `FileProvider.getUriForFile()` 获取 content://Uri(Android 7+ 必须用 FileProvider,不能直接传 file://)
   - 加 `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`
   - `startActivity`
2. 在 `EventFactory.openFile()` 末尾加 Android 分支(模仿 `browse()` 的反射),调用 `AndroidLauncher.openFile(path)`
3. 在 `AndroidManifest.xml` 注册 `FileProvider` + `xml/file_paths`
4. 移除 `open_document` 里的 `isDesktopSupported()` 守卫,改为统一走 `openFile()`

确认后实施。

---

## Bug 3 — songdb 增量扫描时 BMS 父目录丢失

### 现象
在 `BMS/21/...` 下添加新歌触发增量扫描后,UI 上 BMS 父目录消失,21 直接暴露在选歌根目录里。重建 songdb 暂时解决,但下次再加新歌又会复发。

### 关键事实

#### 文件结构
- `android/src/main/java/bms/player/beatoraja/song/AndroidSQLiteSongDatabaseAccessor.java`
- `core/src/main/java/bms/player/beatoraja/song/SongUtils.java`
- `core/src/main/java/bms/player/beatoraja/select/bar/FolderBar.java`(本 fork 较 upstream 有较大改动)

#### FolderBar 的根 bar 约定
`BarManager.java:291` 构造根 FolderBar 用固定 CRC = `"e2977170"` (SongUtils.ROOT_CRC):

```java
l.addAll(safeGetChildren(new FolderBar(select, null, "e2977170")));
```

`FolderBar.getChildren()`(`FolderBar.java:109`)查询 `getFolderDatas("parent", "e2977170")`,**只有 parent 字段 = "e2977170" 的 folder 记录才会出现在选歌根目录**。

`SongUtils.crc32()`(SongUtils.java:15-65) 在以下情况返回 `ROOT_CRC`:
```java
// 1. 检查是否是根目录的父目录
if (rootdirs != null) {
    for (String s : rootdirs) {
        // rs = s.replace('\\', '/') 去尾斜杠
        final int lastIndex = rs.lastIndexOf('/');
        final String parent = (lastIndex == -1) ? "" : rs.substring(0, lastIndex);

        if (Objects.equals(parent, normalizedPath)) {
            return ROOT_CRC;          // ← 只有 normalizedPath 是 bmsroot 的父目录时返回
        }
    }
}
```

也就是说:**只有当某个 folder 的 parent (文件系统父目录) 正好等于 bmsroot 的父目录时,它的 parentCrc 才会是 ROOT_CRC**。换言之,如果用户设置 bmsroot = `BMS/`,那么 BMS 的父目录 = bmsroot 的父目录 = `<bmsroot-parent>`,BMS 的 parentCrc = ROOT_CRC,根 bar 才会显示 BMS。

#### 增量扫描流程
1. `updateSongDatas()` (AndroidSQLiteSongDatabaseAccessor.java:750-) 开头:
   - 行 804 `this.bmsroot = paths.clone()` — 把当前进程的 bmsroot 字段更新
   - 行 808-836 删除 `song` 表里不属于新 bmsroot 的"孤儿"
   - 行 850-857 `preScanPathSnapshot = SELECT path FROM song`(旧数据库里所有歌路径),`seenThisScan.clear()`
2. 行 952-964 串行/并行调 `scanFolderRecursively(scanDir, ...)`(scanDir = 每个 bmsroot 路径)
3. `scanFolderRecursively` (行 1397-1471):
   - 列出 children → 区分 BMS / txt / 预览 / 子目录
   - 子目录递归扫描
   - `if (containsBms) insertFolder(folder, db)`(行 1460) — 关键
4. 行 967-1012 增量 Deletion Sync:
   - `candidates = preScanPathSnapshot - seenThisScan` (行 968-969)
   - 对每个 candidate 检查文件是否还存在,不在则从 `song` 表删除
   - 行 1007 **如果有任何删除,`rebuildFolderTable(db)`** — **完全清空 folder 表并按当前文件系统重建**
5. `rebuildFolderTable` (行 1532-1567):
   - `db.delete("folder", null, null)`(行 1534) — **暴力清空整个 folder 表**
   - 对每个 bmsroot,遍历其直接子目录(行 1551-1558,`rootDir.list()`),对每个子目录调 `rebuildFolderTree`
6. `rebuildFolderTree` (行 1575-1609):
   - 后序遍历,先递归子目录
   - 用 `seenThisScan` 判断本目录是否含 BMS(行 1592-1598):
     ```java
     boolean hasBms = false;
     for (String bmsPath : seenThisScan) {
         if (bmsPath.startsWith(dirPath)) { hasBms = true; break; }
     }
     if (hasBms && !insertedPaths.contains(dirPath)) {
         insertFolder(dir, db);
         insertedPaths.add(dirPath);
         count++;
     }
     ```

### 嫌疑点(按可能性排序)

#### 嫌疑 A:`seenThisScan` 覆盖不全 → `rebuildFolderTree` 漏插父目录
**核心问题**: `seenThisScan` 是在 `processBmsFile` 内、行 1268 才会被 `add`。**如果某个 BMS 文件因为某种原因(解码失败、文件锁、权限)没被 `processBmsFile` 处理,它的父目录就不会出现在 `seenThisScan` 的"前缀匹配"里**,在 rebuildFolderTable 时该目录的 `hasBms = false`,目录记录就被丢。

最典型的场景:用户先全量扫描过一遍(那时 folder 表里 BMS/21 都在),后来某个 BMS 文件变成不可读(被加密、文件锁、损坏),再触发增量扫描时:
1. 该不可读文件被加入 deletion candidates(因为 seenThisScan 里没有它)
2. 实际检查发现文件还在(没真删)
3. 由于没有删除发生,**rebuildFolderTable 不会被调** —— 这种情况其实安全

更可能的子场景:用户**先做了一次手工删歌 / 删文件夹(在文件系统层面,没经过 app)**,再触发增量扫描:
1. Deletion Sync 找到 candidates,删除 song 记录
2. 触发 `rebuildFolderTable`
3. 此时 `seenThisScan` 包含所有当前活着的 BMS 路径,**包括 BMS/21/... 里的新歌**
4. rebuildFolderTree 后序遍历 bmsroot/BMS/21/...,由于 seenThisScan 含 BMS/21/foo.bms,`hasBms=true`,BMS/21 被 insert
5. 同理 BMS 被 insert,**应该正常**

那为什么用户看到 BMS 缺失?一种可能:**`seenThisScan` 里某条路径的 startsWith 检查漏掉了 BMS**。比如:
- `seenThisScan` 里某条路径是 `.../BMS/21/foo.bms`,而 BMS 的实际文件系统路径在某些环境下变成了 `.../Bms/21/foo.bms`(大小写不同)
- rebuildFolderTree 检查 `bmsPath.startsWith(dirPath)`,dirPath 来自文件系统 `dir.path()`,大小写敏感
- 不会漏匹配,所以这不是嫌疑

#### 嫌疑 B:用户实际把 `BMS/21` 当成了 bmsroot
如果用户在某个时间点修改过 Settings,把 bmsroot 从 `BMS` 改成了 `BMS/21`:
1. 上次扫描的 song 表里,所有 `BMS/21/...` 之外的歌都被识别为孤儿
2. `db.delete("song", ...)` 删掉 BMS/21/ 之外的所有歌(包括 BMS 目录里其它 BMS,如果有的话)
3. `rebuildFolderTable` 只在 bmsroot = `BMS/21` 下重建 folder 表
4. 21 自己因为 parent = "" (行 1482 `parentHandle` 为 null) 不会被 insert 进来,但它的孙目录会被 insert
5. 实际上 BMS 整条记录都不存在了 —— **但用户看到 21 在根目录,说明 `seenThisScan` 里有 `BMS/21/...` 路径,而根 bar 的"parent = ROOT_CRC" 检查会发现 21 的 parentCrc 计算结果是 `ROOT_CRC`**

让我验证这个:
- 假设 bmsroot = `BMS/21`(用户改过设置)
- 插入 21 的孙子目录 `BMS/21/newbms/foo.bms`:
  - folder = `BMS/21/newbms`,parent = `BMS/21`
  - parentPath = `BMS/21`
  - matchingRoot = `BMS/21`
  - parentCrc = `crc32("BMS/21", bmsroot=["BMS/21"], "BMS/21")`
  - 在 crc32 里:rs = `BMS/21`,parent = `BMS`(lastIndex of '/' = 3)
  - 检查 `parent.equals(normalizedPath)`: `BMS.equals(BMS/21)`? false
  - 走相对路径,rootParent = `BMS`(因为 bmspath = `BMS/21`)
  - 实际上 normalizedPath.startsWith(rootParent) = `BMS/21`.startsWith(`BMS`) = true,sub = `/21`,targetPath = `21`
  - 返回 `crc32("21\\\0")` —— **不是 ROOT_CRC**
- 插入 21 自身(folder = `BMS/21`,parent = `BMS`):
  - parentHandle = `BMS`,parentPath = `BMS`,matchingRoot = `BMS/21`
  - parentCrc = `crc32("BMS", bmsroot=["BMS/21"], "BMS/21")`
  - 在 crc32 里:rs = `BMS/21`,parent = `BMS`
  - `parent.equals(normalizedPath)`:`BMS.equals(BMS)` → **true** → 返回 `ROOT_CRC`!

所以:**当 bmsroot = `BMS/21` 时,21 的 parentCrc = ROOT_CRC,会出现在根 bar**。但同时,**21 的 record 不会被 insert**(因为 `parentHandle` 不为 null 且 `parentCrc = ROOT_CRC`,会正常插入)。所以 21 确实出现在根 bar,而 21 下面没有其它目录(因为 21 是新的 bmsroot,21 的子目录用同样逻辑 → 子目录的 parentCrc = ROOT_CRC 也会出现在根 bar)。

但用户描述里 21 是根 bar 中的一员、且 21 下面还有歌曲。这个候选解释要求用户实际上设了 `BMS/21` 为 bmsroot,跟"丢失 BMS 父目录"的症状匹配。

#### 嫌疑 C(最常见):bmsroot 改成更短路径触发 BMS 缺失
更朴素的版本:用户原本 bmsroot = `BMS`,后来改成 `songs`(BMS 的父目录)。增量扫描:
1. 删除 `BMS` 路径下所有 songs(都是孤儿)
2. rebuildFolderTable 在 `songs` 下重建,会找到 `BMS` 作为子目录,但里面所有歌都没了,`seenThisScan` 在 `BMS` 下没匹配,**BMS 不会被 insert**
3. 结果:根 bar 没有 BMS

但用户说 21 仍然存在,这跟嫌疑 C 不完全匹配——除非用户只改了部分 bmsroot(保留了一个、删了一个)。

### 当前最可能的解释
**嫌疑 B 概率最高**:用户在某个时间点(也许是上次"删除 songdb 重新扫描"后)误把 bmsroot 设成了 `BMS/21` 或类似过深的路径。
- 这样会让 21 在根 bar 直接显示
- 加新歌到 BMS/21 下的扫描会正常工作,但 21 仍然以 ROOT_CRC parent 显示在根
- 用户看到的"BMS 父目录丢失"实际上是 **BMS 不再是 bmsroot,而是 21 才是 bmsroot**

### 验证手段
1. 打开 app 的 player/config JSON (`config.json` 或 SQLite),检查 `bmsroot` 字段实际值
2. 在 `scanFolderRecursively` 行 959 加日志:`"Starting recursive scan of: " + scanDir.path()`,确认扫描入口实际是哪个目录
3. 在 `insertFolder` 行 1519 加日志:`"Inserted folder: " + path + " parent=" + parentCrc`,列出所有 insert 的目录和它们的 parentCrc
4. 在 `rebuildFolderTree` 行 1592 加日志:对于 seenThisScan 命中的目录输出 `"Rebuilt folder: " + dirPath`,看哪些目录被跳过

### 修复方向
1. **先验证嫌疑 B**:打印 bmsroot,确认用户的 bmsroot 配置是否符合预期
2. 如果确实是 bmsroot 误设,引导用户在 Settings 改回 `BMS`,删除 songdb,全量扫描
3. 加防御:在 `scanFolderRecursively` 行 961 入口检查,对于 `bmsroot` 自身不递归 — 已经做了(行 1550 有注释),但要在 scan 路径里也跳过(目前 scanFolderRecursively 入口没有 bmsroot 跳过逻辑)
4. 长期:`insertFolder` 检查 `folder.path() == bmsroot` 时直接 return(目前没做),避免把 bmsroot 自身塞进 folder 表