# 搜索 folder 生命周期（2026-09-21）

## 最终方案（已落地，commit 904a8f3b）

搜索结果 `Search : 'xxx'` folder **可堆叠**，由玩家自己删除：

- **`BarManager.addSearch(SearchWordBar)`**：回到上游的可堆叠逻辑 —— 同名标题替换、超过
  `maxSearchBarCount`（默认 10）时丢最旧的一条。**不再 `search.clear()`**。
  根目录列表（`updateBar(null)` 的 root 分支）把整个 `search` 追加进去，所以 `search` 里有几条、
  根目录里就有几个 `Search : 'xxx'` folder。
- **`BarManager.removeSearch(Bar)`**：只从 `search` 里移除**传入的那一条**（非 `SearchWordBar`
  直接返回 `false`），**不重建列表**。调用方删完要自己 `updateBar(null)`；root 分支开头就
  `dir.clear()`，所以即使玩家正停在刚删的搜索结果里，也会自然回到根目录，不会留悬空层级。
- **`SearchTextField`（enter 分支的 `else`）**：搜索框里**什么都不输入直接回车** = 删掉光标当前
  停着的那个搜索 folder。搜索成功后代码本来就会 `setText("")`，所以"再按一次回车"天然是空输入，
  正好当删除手势。删完（`removeSearch` 返回 `true`）调 `updateBar(null)` 重建；光标不在搜索 folder 上则 no-op 并提示 `no search folder here`。
  **无论删没删成，都必须 `isControlKeyPressed(ControlKeys.ENTER)` 吞掉这次回车** —— 理由见下。

### 为什么必须吞掉 ENTER

`ControlKeys.ENTER` 的 `text=false`：libGDX 文本输入态下，这次按键照样会被写进
`keystate`。若不吞，`MusicSelectInputProcessor` 在
`:339`（光标在 `DirectoryBar` + ENTER = 打开 folder）和 `:325`（光标在 `SongBar` + ENTER =
开始游戏）会立刻把"删完重建后光标新落到的那个 bar"当成打开/开始。既有的 `no song found`
分支出于同样原因也吞了这次回车，新分支照做。

### 为什么空输入回车能被收到

libGDX `TextField.keyTyped`（`gdx-1.14.0` `TextField.java:1096`）对监听器的回调在
`add || remove` 判断**之外**、无条件执行；`writeEnters=false`、文本为空时照样收到 `'\n'`，
所以空输入回车一定走到监听器。

### 删除后选中安全

`updateBar` 末尾 `selectedindex` 先归 0，再按 `class + title` 匹配；删掉的那条匹配不到 →
停在 0，不越界。`SearchTextField` 在 `addSearch` 之后紧跟 `setSelected(swb)`，替换后 `swb`
是唯一元素，不会指向列表外对象。

## 被否决的三版（存档，避免再走弯路）

1. **`c44ad474` 进 play 时清掉搜索结果** —— 已彻底回退（`MusicSelector` 回到逐字相同）。
   搜索结果是选曲界面内的正常操作结果，不该因为进 play 就消失。
2. **`e3bedb93` 恒定只留一条、新搜索覆盖旧** —— 用户："不用恒定"。"缺的不是限制条数，
   而是删除手段"：旧版搜一次多一条、只能重启进程才清。
3. **中间设想：空输入回车删"最近一条"** —— 用户纠正：删的对象是**光标当前停着的那一个**
   （滑到 `Search : 'love'` 就删它），不是时间上最近的那条。

## 边界

- 光标不在搜索 folder 上（含"人正停在搜索 folder 里看歌"）→ no-op + `no search folder here`。
- `maxSearchBarCount` 字段保留（配置兼容），本分支无设置界面引用它（上游桌面版的
  `MusicSelectConfigurationView` 才有）。
