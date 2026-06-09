# API 21 兼容性 - java.nio.file 问题汇总

## 背景

`java.nio.file.Path` 及相关 API 需要 API 26。项目 `minSdk = 21`，需替换为 `java.io.File` API。

---

## 涉及文件清单（38 个）

### 核心类（需优先修复）

| 文件 | 问题 |
|------|------|
| `BeatorajaGame.java` | `rootPath` 类型为 `Path`；`configureSpectrumRenderer()` 多处 NIO |
| `SkinHeader.java` | `getPath()` 返回 `Path`，影响所有调用者 |
| `SkinLoader.java` | `Paths.get()`, `Files.exists()`, `Files.getLastModifiedTime()` |
| `MusicSelector.java` | `Paths.get()`, `Files.exists()` |
| `TableDataAccessor.java` | `Files.list()`, `DirectoryStream` |
| `TableData.java` | `Files.exists()`, `p.toFile()` |
| `CourseDataAccessor.java` | `Paths.get()` |
| `PlayDataAccessor.java` | `Files.exists()`, `Paths.get()` |
| `RivalDataAccessor.java` | `Files.exists()`, `Files.createDirectory()` |
| `SystemSoundManager.java` | `Paths.get()`, `p.toFile()` |
| `BMSPlayer.java` | `Paths.get()` |
| `PlayerResource.java` | `Paths.get()` |
| `BMSResource.java` | `Path` 类型字段 |
| `AbstractAudioDriver.java` | `Paths.get()`, `InvalidPathException` |
| `PracticeConfiguration.java` | `Paths.get()`, `Files.createDirectory()` |
| `BGAProcessor.java` | `Paths.get()` |
| `BGImageProcessor.java` | `Path` 类型 |

### 工具类（Android 可能不执行）

| 文件 |
|------|
| `MusicDownloadProcessor.java` |
| `BMSDecoder.java` |
| `BMSONDecoder.java` |
| `ChartDecoder.java` |
| `ChartInformation.java` |
| `MainLoader.java` |
| `GdxAudioDeviceDriver.java` |
| `SkinConfiguration.java` |

### Skin 相关

| 文件 |
|------|
| `BitmapFontCache.java` |
| `LuaSkinLoader.java` |
| `SkinLuaAccessor.java` |
| `JSONSkinLoader.java` |
| `JsonSkinObjectLoader.java` |
| `JsonPlaySkinObjectLoader.java` |
| `JsonSelectSkinObjectLoader.java` |
| `LR2FontLoader.java` |
| `LR2SkinHeaderLoader.java` |
| `LR2SkinCSVLoader.java` |
| `SkinTextBitmap.java` |
| `SkinTextFont.java` |
| `JsonSkinSerializer.java` |
| `EventFactory.java` |
| `BarManager.java` |
| `PreviewMusicProcessor.java` |
| `TableEditorView.java` |

---

## 关键问题：SkinHeader.getPath() 返回 Path

```java
// SkinHeader.java:112
public Path getPath() { return path; }
```

影响：`BeatorajaGame.java`, `SkinLoader.java`, `BarManager.java`, `EventFactory.java` 等

**解决方案**：将 `SkinHeader.path` 从 `Path` 改为 `String`

---

## 修复示例

### Path → String（推荐）
```java
// SkinHeader.java
private String path;
public String getPath() { return path; }
```

### Paths.get(parent).resolve(child) → File
```java
// 原来
Path p = Paths.get(str).getParent().resolve("file.json");
// 改为
File f = new File(new File(str).getParent(), "file.json");
```

### Files.exists(path) → file.exists()
```java
// 原来
if (Files.exists(Paths.get(path))) { ... }
// 改为
if (new File(path).exists()) { ... }
```

### Files.readAllBytes(path) → 手动读取
```java
// 改为
byte[] data = readFile(new File(parent, "spectrumconfig.json"));
```

---

## 修复优先级

1. **高**：导致编译错误的（BeatorajaGame, SkinHeader, SkinLoader, MusicSelector）
2. **中**：运行时常用（TableDataAccessor, TableData, CourseDataAccessor, PlayDataAccessor）
3. **低**：工具类或皮肤相关

---

## 验证

编译时查看具体报错行号，逐个修复。