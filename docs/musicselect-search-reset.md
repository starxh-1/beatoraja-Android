# 选曲界面：搜索条目（`Search : 'xxx'`）的归零

> 2026-09-21 反馈并修复。症状：搜歌后根目录多出一条 `Search : 'xxx'` folder，
> 进 play 再返回，它**一直挂在列表里**（用户描述为"没有归零"）。

## 一、搜索条目是怎么来的

两个入口都往 `BarManager.search` 里塞一个 `SearchWordBar`：

- `SearchTextField`（原生输入框回车，Android 上就是屏幕上的 "search song"）：
  `addSearch(swb)` → `updateBar(null)` → `setSelected(swb)`；
- `MusicSelectInputProcessor` 的 NUM0 弹窗：`addSearch(...)` → `updateBar(null)`。

`BarManager.updateBar(null)` 的 root 分支把整个 `search` 追加进根目录列表：

```java
l.addAll(search);   // BarManager.java 约 297 行
```

`addSearch()` 只做两件事：同名去重 + 用 `config.getMaxSearchBarCount()`（默认 10）限条数。
**它只管"最多几条"，不管"什么时候清"** —— `search` 数组的生命周期 = `BarManager`
的生命周期 = 整个 `MusicSelector` 的生命周期，所以会一直留到下次搜索或重启进程。

## 二、为什么"进 play 后没归零"

返回选曲界面时列表**根本没有重建**：

- `MainController.changeState()` 对 `MUSICSELECT` 做了优化：只在首次进入时 `create()`，
  之后只 `loadSkin()`（`MainController.java:366-374`，标志位 `selectorInitialized`）；
- 而列表重建（`manager.updateBar()`）原本挂在 `MusicSelector.create()` 里
  （`create()` 的 `if (manager.getSelected() == null)` 分支，约 211 行）。

于是进 play 前列表是什么样，回来还是什么样 —— 包含那条搜索条目。
（上游每次切回都调 `create()`，所以上游不会积累这个状态；本分支为省一次初始化跳过了它。）

## 三、修法：搜索是"临时视图"，离开选曲界面即失效

1. `BarManager` 新增 `hasSearch()` / `resetSearch()`。`resetSearch()` 清空 `search`，
   并把目录栈里压着的 `SearchWordBar` **连同它之后压入的层级一起弹出**
   （玩家进搜索结果看歌时 `dir` 里就压着它；不弹的话列表重建后会停在一个已经没有
   入口的 folder 里）。
2. `MusicSelector.shutdown()`（离开选曲界面时被调用）只**记标记** `searchResetPending` ——
   此刻不能改列表，因为返回选曲界面要等 `prepare()` 才重建。
3. `MusicSelector.prepare()`（返回选曲界面时必定被调用，`MainController.java:381`）
   **末尾**执行归零：`resetSearch()` 返回 true 就 `updateBar(null)`
   → 回根目录 + 重建列表，那条 `Search : 'xxx'` 不再出现。

### 为什么必须放在 `prepare()` 末尾

`prepare()` 前半段有一段既有的"关键修复"：从 play 返回时按 `playedsong` 遍历
`manager.currentsongs`，找到那首歌的 `SongBar` 刷新 EX Score。**从搜索结果里打完的歌
只存在于旧列表里**，若先 `updateBar(null)` 重建，这首歌不在当前列表中，分数就刷不上
（表现为 EX Score 变 0）。顺序不能反。

## 四、边界行为

- 搜索后**不离开**选曲界面 → 条目照旧保留，可以反复点进去看结果；
- 在普通文件夹里浏览（没搜索过）→ `hasSearch()` 为 false、标记不置位，
  返回时**不做任何重建**，光标位置由 `updateBar` 的同名/同 sha256 匹配逻辑保持；
- 打开设置（NUM6 / 皮肤设置）也会离开 `MUSICSELECT` 并触发 `shutdown()`，
  搜索条目在那条路径上同样被清掉 —— 与"搜索是临时视图"的定位一致。

## 五、改动文件

| 文件 | 改动 |
|---|---|
| `core/.../select/BarManager.java` | 新增 `hasSearch()`、`resetSearch()` |
| `core/.../select/MusicSelector.java` | 新增字段 `searchResetPending`；`shutdown()` 置位；`prepare()` 末尾归零 |

javac 校验注意：`BarManager` 依赖 jackson（`jackson-annotations-core-databind`），
单独编译时要补进 classpath，否则会误报 `程序包 com.fasterxml.jackson.* 不存在`。
