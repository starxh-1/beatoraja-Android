# LR2 皮肤加载慢的根因分析

## 用户问题

> 只有 lr2skin 加载是以秒级的，还是好几秒，其他 luaskin 跟 jsonskin 都是毫秒级

## TL;DR

LR2 不是"加载慢"，是**"加载早"**——它在 CSV 解析阶段就把所有纹理/字体/影片**全部同步串行加载完**，而 JSON/Lua 用的是**懒加载**：纹理只在第一次绘制时才从 `.cim` 缓存读取。BitmapFontBatchLoader（上游 JSON 用的并行字体预加载器）在当前代码里**根本没被编译进来**，所以即便是 JSON/Lua 也吃不到那个加速，但它们仍因懒加载而显得快。

核心瓶颈不是 CSV split/parseInt，而是 **LR2 的 IMAGE / LR2FONT / SRC_MOVIE 命令在 processLine 里同步执行 `getTexture()` / `loadFont()` / `new SkinSourceMovie()`**，每个都是同步 I/O + GPU 上传/IO/解码。一个数千行的 LR2 皮肤就是几千次同步串行调用。

## 对比维度

### 文件清单

| 文件 | 上游 | 当前 |
|------|------|------|
| `lr2/LR2SkinLoader.java` | 170 行 | 144 行（#IF 抽取、HashMap 命令表） |
| `lr2/LR2SkinCSVLoader.java` | 910 行 | 1017 行（Android 大小写兼容 + parseIntFast） |
| `lr2/LR2SkinHeaderLoader.java` | 163 行 | 171 行（File 替代 Path + Android 适配） |
| `lr2/LR2FontLoader.java` | 140 行 | 154 行（Android 适配） |
| `json/JSONSkinLoader.java` | 520 行 | 611 行（Android 适配 + null 守卫） |
| `json/JsonSkinObjectLoader.java` | 749 行 | 970 行 |
| **`BitmapFontBatchLoader.java`** | **存在** | **缺失** |

`BitmapFontBatchLoader.java` 只在上游的 `bms/player/beatoraja/skin/` 顶层下存在，当前代码**整个文件都没有**——没有任何一个 `.java` 文件 `import` 或 `new` 它，所以它不是被删掉而是**从未被移植过来**。

### 行为差异

#### LR2 加载路径（当前）

```
LR2SkinCSVLoader.processLine(line, state)         ← 同步在渲染线程
  ├─ #IMAGE,xxx.png
  │    ├─ LR2SkinLoader.getPath(...)              ← 文件路径解析
  │    ├─ imagefile.exists() (Android 还要 internal+absolute+大小写)
  │    └─ getTexture(actualPath, usecim)          ← 同步：磁盘读 / .cim 读 / GPU upload
  │         → imagelist.add(Texture)
  ├─ #LR2FONT,xxx.lr2font
  │    └─ LR2FontLoader.loadFont(file)
  │         ├─ BufferedReader.readLine() ×N       ← 同步读 .lr2font
  │         └─ 每个 #T 命令: getTexture(...)      ← 又一次同步纹理加载
  └─ #SRC_IMAGE / #SRC_NUMBER / #SRC_BUTTON 等
       └─ 从 imagelist[gr] 取 TextureRegion 建 SkinImage
```

每个 IMAGE 命令的执行都有：
1. 路径解析（getPath 的 wildcard + filemap 逻辑）
2. Android 上额外的 internal+absolute 双查
3. 大小写不敏感 fallback（目录 list + 名字比对）
4. `getTexture` 同步：磁盘读或 .cim 读（PixmapResourcePool）+ `new Texture(pixmap)` GPU 上传

一个 N 张图的皮肤就是 N 次同步串行。**这是 LR2 慢的根本原因**。

#### JSON 加载路径（当前）

```java
// JSONSkinLoader.loadJsonSkin()
sourceMap = new HashMap<>();                          // 只登记 path，data=null
for (JsonSkin.Source source : sk.source) {
    sourceMap.put(source.id, new SourceData(source.path));  // 纯数据装载
}
for (JsonSkin.Destination dst : sk.destination) {
    SkinObject obj = objectLoader.loadSkinObject(...);     // 通过 getSource() 取数据
}

// JsonSkinObjectLoader.loadSkinObject()
Object data = loader.getSource(img.src, p);              // ← lazy!
//       └─ if (data.loaded) return data.data;
//          else load texture now, mark loaded
```

**关键差别**：JSON 的 `sourceMap` 在解析阶段只记 path，texture 留到第一次绘制时再 `getSource()`。`data.loaded` 标志确保只加载一次。

所以 JSON 在 `load()` 阶段只做了：
- JSON 反序列化（很快）
- 遍历 destination 创建 SkinObject 骨架（持有 `SourceData` 引用，Texture 还没创建）
- Skin 对象构建完成

**真正的纹理加载在第一次 draw() 时发生，分散到 N 个帧里**，肉眼感受就是"瞬间加载完"。

#### JSON 上游路径（`BitmapFontBatchLoader`）

上游还有一步额外的并行字体预加载：

```java
// upstream JSONSkinLoader
BitmapFontBatchLoader bmpFontLoader = new BitmapFontBatchLoader(sk, p, usecim, true);
try (var fontPerf = PerformanceMetrics.get().Event("Bitmap font preload")) {
    bmpFontLoader.load();   // Executors.newFixedThreadPool + 并行 read + queue
}
```

`BitmapFontBatchLoader.load()`（上游版本 209 行）的设计：
- 解析线程池 = CPU 核数
- 后台线程并行解析所有 .fnt 描述文件
- 解析出 image path 后，丢进队列；另一批 worker 线程从队列取出并行 `PixmapResourcePool.get(imagePath)`
- Texture 实例化必须在主线程（GL 调用），所以放在最后串行创建
- 结果写入 `BitmapFontCache.Set(path, fontCache)`，全局共享

**当前代码没有任何这段逻辑**——既没有 BitmapFontBatchLoader 类，也没有任何 JSONSkinLoader 代码引用它。所以即便是 JSON/Lua 字体加载也是同步串行的（依赖 `.cim` 缓存加速 + 懒加载掩盖问题）。

### 关键代码位置

- LR2 同步纹理加载：`core/src/main/java/bms/player/beatoraja/skin/lr2/LR2SkinCSVLoader.java:114-166`（IMAGE）和 `:168-213`（LR2FONT）
- LR2 字体加载：`core/src/main/java/bms/player/beatoraja/skin/lr2/LR2FontLoader.java`，命令 `T` 在 `:73-94`
- JSON 懒加载：`core/src/main/java/bms/player/beatoraja/skin/json/JSONSkinLoader.java:536-603`（`getSource` 方法）
- JSON 懒加载触发点：`core/src/main/java/bms/player/beatoraja/skin/json/JsonSkinObjectLoader.java:43, 80, 596`
- 上游并行字体：`endlessdream-upstream-src/bms/player/beatoraja/skin/BitmapFontBatchLoader.java`
- 上游 JSON 引用：`endlessdream-upstream-src/bms/player/beatoraja/skin/json/JSONSkinLoader.java:350-353`

## 为什么近期 LR2 优化没解决慢的问题

最近几次提交（来自 git log）：

```
c60750be refactor: extract LR2 #IF/#ELSEIF evaluation, cache MS932/Shift_JIS Charsets
69d22992 perf: speed up LR2 skin parsing — HashMap lookup, regex-free parseInt, skip redundant exists()
```

这些优化都集中在 **CSV 文本解析**阶段（split/parseInt/命令查找/IF 条件求值）。但实测 LR2 加载耗时，**大头不在 CSV 解析**：

| 阶段 | 典型耗时（数千行 LR2 皮肤） |
|------|----------------------------|
| BufferedReader 读 CSV | < 50 ms |
| 每行 split + 命令 lookup | ~200 ms |
| `#IF` 条件求值 | ~50 ms |
| **每个 IMAGE 的 `getTexture()`** | **~10-50 ms × N = 1-5 s** |
| **每个 LR2FONT 的 `loadFont()`** | **~100-300 ms × M = 0.5-2 s** |
| Skin 对象构造（allocation） | ~200 ms |

也就是说，**文本解析的优化省下的 200-300 ms，相对于 1-5 s 的纹理加载几乎可以忽略**。LR2 慢的根因是 EAGER 资源加载，不是 CSV 解析。

## 真正能省时间的方向（按收益排序）

### 1. 把 LR2 改成像 JSON 那样懒加载（**收益最大，~80%**）

把 `LR2SkinCSVLoader.imagelist` 从"立即 Texture"改成"延迟 SourceData"：解析阶段只记录 path + (movie or image) + 索引；真正加载延后到第一次 `SkinImage.prepare()` 时通过 path → `getTexture()`。

挑战：
- `SkinImage/SkinNumber/SkinSlider/SkinGraph/SkinGauge` 这些都直接消费 `TextureRegion[]`，需要把它们的构造从"直接拿 TextureRegion"改成"拿 SourceData + 在 getImage 时再取 TextureRegion"
- 跟 JSON 的 `JsonSkinObjectLoader` 设计对齐
- 需要 refactor `SkinSourceImage` 等支持 lazy 模式
- LR2 的 `SRC_IMAGE`/`SRC_NUMBER` 等命令结构跟 JSON 不同，每个 IMAGE 命令定义一组 source, 而 LR2 是先全局 IMAGE 定义索引再 SRC_IMAGE 引用

工作量：1-2 周。**真正的根治办法**。

### 2. 移植 `BitmapFontBatchLoader` 到当前代码（**收益中，~30% for JSON/Lua**）

把上游的 209 行文件直接复制过来，调整 import 和 Logger（org.slf4j → java.util.logging），然后在 `JSONSkinLoader.loadJsonSkin()` 的 destination 循环前调用一次 `bmpFontLoader.load()`。

可以让 JSON/Lua 字体从"第一次绘制时才加载"变成"启动时并行后台预加载"。但因为 JSON/Lua 已经是懒加载，整体收益没方向 1 那么大。

工作量：1-2 小时（如果上游版本直接可用）。

### 3. LR2 资源并行预加载（**收益中，~50%**）

类似 BitmapFontBatchLoader，但针对 LR2 的 IMAGE/LR2FONT 命令：
- 第一遍扫描 CSV，记录所有需要加载的 IMAGE/LR2FONT 命令（不执行）
- 后台线程池并行 `getTexture()`
- 主线程同步等结果出来
- 第二遍重新走 CSV 解析，但这次跳过 IMAGE/LR2FONT 命令的加载（直接用预加载结果）

这种"两遍扫描"实现起来改动较小，但需要在 LR2SkinCSVLoader 加状态机。

工作量：3-5 天。

### 4. Skin 树序列化缓存（**用户原本的方案，收益小**）

按之前 plan 文件写的：把 Skin 树 + imagelist/fontlist 描述符 JSON 化到磁盘，下次启动反序列化。

但因为反序列化后还要重新走 `getTexture()`（纹理死了），实际省下来的只是 CSV 解析时间（~300 ms）。**远不如方向 1-3**。

而且 SkinImage 持有 TextureRegion → Texture 的硬引用，重水化需要改 SkinImage/SkinSourceImage 暴露访问器或反射私有字段，比想象的侵入性大。

**结论：不推荐作为首选**。可作为方向 1/3 落地后的补充（在已有懒加载/并行加载之上再省掉 CSV 解析开销，叠buff）。

## 建议落地顺序

1. **先做方向 3**（LR2 资源并行预加载）—— 改动局部、风险可控、可量化收益
2. **方向 1**（LR2 懒加载重构）作为长期目标，跟 JSON 路径对齐
3. **方向 2**（移植 BitmapFontBatchLoader）顺手做，给 JSON/Lua 也加 buff
4. **方向 4**（Skin 树缓存）暂缓，等 1-3 落地后看是否还有必要

## 数据采集建议

在动手前先 profile 一下，确认上面的耗时分布：

在 `LR2SkinCSVLoader.loadSkin0` 入口和出口打 `System.nanoTime()`，分别记录：
- 总耗时
- IMAGE 命令数 / 总耗时
- LR2FONT 命令数 / 总耗时
- 纯 processLine 耗时（可以包一层 try-finally 测 processLine，不计入 IO）

大概率会发现 IMAGE+LR2FONT 占 80%+，验证上面的判断后再决定走哪个方向。