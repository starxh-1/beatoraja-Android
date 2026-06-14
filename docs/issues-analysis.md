# Issues Analysis

## Issue 1: SongData 新增歌曲检测时所有已有歌曲也被当作新歌曲处理

### 问题描述
当检测到新歌曲时，似乎会刷新全曲数据库，然后把所有已有的歌曲也当成 new songs 处理。

### 相关文件
- `endlessdream-upstream-src/bms/player/beatoraja/song/SQLiteSongDatabaseAccessor.java`
- `android/src/main/java/bms/player/beatoraja/song/AndroidSQLiteSongDatabaseAccessor.java`

### 根源分析

#### endlessdream 原版的逻辑

在 `SQLiteSongDatabaseAccessor.java` 的 `SongDatabaseUpdater.processBMSFolder()` 方法中（line 570-722）：

1. **增量更新机制**：通过 `lastModifiedTime` 比对实现
   ```java
   // line 577-593
   for (int i = 0; i < len; i++) {
       final SongData record = records.get(i);
       if (record != null && record.getPath().equals(pathname)) {
           records.set(i, null);  // 标记为已匹配
           if (record.getDate() == lastModifiedTime) {
               update = false;    // 文件未修改，跳过
           }
           break;
       }
   }
   ```

2. **路径存储格式**：使用相对于 `root` 的路径
   ```java
   // line 584
   pathname = (path.startsWith(root) ? root.relativize(path).toString() : path.toString());
   ```

3. **删除孤立记录**：处理完目录后，删除目录中不再存在的记录
   ```java
   // line 713-718
   records.parallelStream().filter(Objects::nonNull).forEach(record -> {
       qr.update(property.conn, "DELETE FROM song WHERE path = ?", record.getPath());
   });
   ```

#### Android 版本的差异

在 `AndroidSQLiteSongDatabaseAccessor.java` 中：

1. **并行扫描时使用 dateCache**（line 1013-1038）：
   ```java
   Map<String, Integer> dateCache = new HashMap<>();
   if (!forceRefresh) {
       try (Cursor cursor = db.rawQuery("SELECT path, date FROM song", null)) {
           while (cursor.moveToNext()) {
               dateCache.put(cursor.getString(0), cursor.getInt(1));
           }
       }
   }
   // ...
   for (FileHandle file : allFiles) {
       String pathName = file.path().replace('\\', '/');
       Integer cachedDate = dateCache.get(pathName);
       int lastModifiedTime = (int) (file.lastModified() / 1000);
       if (cachedDate == null || cachedDate != lastModifiedTime) {
           filesToScan.add(file);
       }
   }
   ```

2. **路径存储格式**：使用**绝对路径**（line 1207）
   ```java
   String pathName = file.path().replace('\\', '/');
   ```

3. **`CONFLICT_REPLACE` 策略**：使用 `insertWithOnConflict(song, CONFLICT_REPLACE)`

### 潜在问题

**关键差异在于路径格式**：

| 版本 | 路径存储方式 | 示例 |
|------|-------------|------|
| endlessdream | 相对路径 (relativize) | `music/xxx.bms` |
| Android | 绝对路径 | `/storage/emulated/0/Music/xxx.bms` |

当 `dateCache` 使用绝对路径作为 key 时，如果路径格式不一致（例如首次扫描时使用某种格式，后续扫描使用不同格式），`dateCache.get(pathName)` 会返回 `null`，导致文件被当作新文件处理。

### 建议修复方向

1. 统一路径存储格式：使用与 endlessdream 相同的相对路径（相对于 bmsroot）
2. 确保 `bmsroot` 配置正确且一致
3. 在 `dateCache` 查询时使用与存储时相同的路径格式

---

## Issue 2: Score.zip 导出只打包 score.db，遗漏 config_player.json 和 replay 文件夹

### 问题描述
SettingsActivity 的 score.zip 导出功能只导出 `score.db`，而无视了 `config_player.json` 和可能会有的 `replay` 文件夹。正确打包应该是把整个 player1 目录打包成 zip，而不是只打包三个文件。

### 相关文件
- `android/src/main/java/com/starxh/beatoraja/android/compose/SettingsActivity.java`

### 现有代码分析

#### 导出逻辑 (line 1040-1058)

```java
private void exportScoreToUri(Uri uri) {
    new Thread(() -> {
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
            File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
            String[] files = {"score.db", "score.db-wal", "score.db-shm"};
            byte[] buf = new byte[8192];
            for (String name : files) {
                File f = new File(playerDir, name);
                if (f.exists()) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(name));
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        int r;
                        while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                    }
                    zos.closeEntry();
                }
            }
            // ...
        }
    }).start();
}
```

#### 导入逻辑 (line 1067-1088)

```java
private void importScoreFromUri(Uri uri) {
    new Thread(() -> {
        try (java.util.zip.ZipInputStream zis =
                new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
            // ...
            while ((entry = zis.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if (!name.equals("score.db") && !name.equals("score.db-wal")
                        && !name.equals("score.db-shm")) {
                    zis.closeEntry();
                    continue;  // 只接受 score.db 相关文件
                }
                // ...
            }
        }
    }).start();
}
```

### Player 目录结构

典型的 player 目录结构：
```
player/player1/
├── score.db          ← 目前唯一导出的文件
├── score.db-wal      ← WAL 日志文件
├── score.db-shm      ← 共享内存文件
├── config_player.json  ← 玩家配置（被忽略）
├── replay/              ← 回放文件夹（被忽略）
│   ├── replay_001.lr2rep
│   ├── replay_002.lr2rep
│   └── ...
└── (其他可能的文件)
```

### 建议修复方向

**导出**：将整个 player 目录打包，而不是只打包特定文件：

```java
private void exportScoreToUri(Uri uri) {
    new Thread(() -> {
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
            File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
            byte[] buf = new byte[8192];
            addDirectoryToZip(zos, playerDir, "", buf);
            // ...
        }
    }).start();
}

private void addDirectoryToZip(ZipOutputStream zos, File dir, String basePath, byte[] buf) throws IOException {
    File[] files = dir.listFiles();
    if (files != null) {
        for (File file : files) {
            String entryPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
            if (file.isDirectory()) {
                addDirectoryToZip(zos, file, entryPath, buf);
            } else {
                zos.putNextEntry(new ZipEntry(entryPath));
                try (FileInputStream fis = new FileInputStream(file)) {
                    int r;
                    while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
    }
}
```

**导入**：支持导入整个目录结构，恢复 `config_player.json` 和 `replay` 文件夹：

```java
private void importScoreFromUri(Uri uri) {
    new Thread(() -> {
        try (java.util.zip.ZipInputStream zis =
                new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
            File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
            byte[] buf = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                File outFile = new File(playerDir, name);
                // 确保父目录存在
                outFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int r;
                    while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                }
                zis.closeEntry();
            }
        }
    }).start();
}
```

### 注意事项

1. **replay 文件可能很大**：需要考虑文件大小和导出时间
2. **文件名编码**：确保使用一致的字符编码（UTF-8）
3. **路径遍历安全**：防止 zip 文件中的路径遍历攻击（`../` 路径注入）