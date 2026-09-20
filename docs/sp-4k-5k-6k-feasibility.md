# SP 模式 4K / 5K / 6K 可行性评估 + 轨道运作链路

结论先行：**5K 已经存在**（`BEAT_5K`，零成本）；**4K / 6K 不存在**，且不是"加个枚举"就能了事——
真正的成本在**皮肤几何**与**通道语义**两处，其中 6K 与现有升格规则存在**语义冲突**。

---

## 一、一条轨道从谱面到手指的四层链路

```
谱面通道号 ─①─► lane 索引 ─②─► TimeLine.notes[lane]
                                    │
        ┌───────────────────────────┴───────────────────────────┐
        ▼                                                       ▼
   ③ 皮肤 note.dst[lane] → 屏幕矩形                     ④ LaneProperty: lane ↔ slot
      LaneRenderer 按 lanes.length 画                          JudgeManager 判定
```

### ① 谱面解码层：通道号 → lane 索引（`core/src/main/java/bms/model/Section.java`）

**mode 从哪来**：`BMSDecoder.java:153` 先无条件 `model.setMode(ispms ? POPN_9K : BEAT_5K)`，
之后靠两条**隐式升格**规则改写（`Section.java:145-162`）：

| 触发条件 | 结果 | 行号 |
|---|---|---|
| 某行的组内通道偏移 `ch2 == 7 \|\| 8`（即 1P 的 `18`/`19`、2P 的 `28`/`29`）**且有音符** | `BEAT_5K → BEAT_7K`、`BEAT_10K → BEAT_14K` | `Section.java:145-153` |
| 该行基通道属于 **2P 组**（`P2_KEY_BASE` 等）**且有音符** | `BEAT_5K → BEAT_10K`、`BEAT_7K → BEAT_14K` | `Section.java:154-162` |

`#PLAYER` **完全不参与** mode 判定，只在冲突时打个 warning（`BMSDecoder.java:416-421`）。

**通道 → lane 表**是写死的 static 数组，按 mode 三选一（`Section.java:214-216`、`227-228`）：

```java
CHANNELASSIGN_BEAT5 = { 0, 1, 2, 3, 4, 5, -1, -1, -1, 6, 7, 8, 9, 10, 11, -1, -1, -1 };
CHANNELASSIGN_BEAT7 = { 0, 1, 2, 3, 4, 7, -1, 5, 6, 8, 9, 10, 11, 12, 15, -1, 13, 14 };
CHANNELASSIGN_POPN  = { 0, 1, 2, 3, 4, -1,-1,-1,-1,-1, 5, 6, 7, 8,-1,-1,-1,-1 };
```

数组下标 0–8 = 1P 通道 `11`–`19`，9–17 = 2P 通道 `21`–`29`；值为 `-1` 的行**直接丢弃**（`Section.java:288-290`）。

**由此得到的两张实际映射表**（关键差异：皿的位置）：

| 通道 | 5K 模式的 lane | 7K 模式的 lane |
|---|---|---|
| `11`–`15` | 0–4（K1–K5） | 0–4（K1–K5） |
| `16` | **5（皿）** | **7（皿）** |
| `17` | -1（免费区，丢弃） | -1（丢弃） |
| `18` / `19` | -1，**且触发升格 7K** | 5 / 6（K6 / K7） |
| `21`–`26` | 6–11（触发升格 10K） | 8–15（触发升格 14K） |
| `27` | -1（丢弃） | -1（丢弃） |
| `28` / `29` | -1（触发升格 14K） | 13 / 14 |

注意 **5K 的皿在 lane 5（紧邻 K5），7K 的皿在 lane 7（K6/K7 之后）** —— 两类模式 lane 顺序不同。

### ② 谱面模型层：lane 就是数组下标（`bms/model/TimeLine.java`、`BMSModel.java`）

- **`bms.model.Note` 没有 lane 字段**。"第几轨" = `TimeLine.notes[lane]` 的下标（`TimeLine.java:160-175`）。
- lane 总数 = `Mode.key`，`setMode()` 会 resize 全部 TimeLine 的数组（`BMSModel.java:353-358`）。
- `Lane[]` 只是"按 lane 把整曲该轨音符抽出来"的容器，`new Lane[mode.key]`（`BMSModel.java:484-490`）。

`Mode` 枚举（`Mode.java:10-17`）——`key` 是**含转盘**的通道数：

| 枚举 | id | hint | player | key | scratchKey（lane 索引） |
|---|---|---|---|---|---|
| `BEAT_5K` | 5 | beat-5k | 1 | **6** | {5} |
| `BEAT_7K` | 7 | beat-7k | 1 | **8** | {7} |
| `BEAT_10K` | 10 | beat-10k | 2 | 12 | {5, 11} |
| `BEAT_14K` | 14 | beat-14k | 2 | 16 | {7, 15} |
| `POPN_5K` / `POPN_9K` | 9 | popn-5k / popn-9k | 1 | 5 / 9 | {} |
| `KEYBOARD_24K` / `_DOUBLE` | 25 / 50 | keyboard-24k(-double) | 1 / 2 | 26 / 52 | {24,25} / {24,25,50,51} |

**没有 4K / 6K / 8K。bmson 侧也一样**：`BMSONDecoder.java:109-115` 只认上表 8 个 `mode_hint`，
认不出的一律 fallback 到 `BEAT_7K`；`x` 越界的音符被当 BG 丢弃（`BMSONDecoder.java:224`）。

### ③ 皮肤渲染层：轨数由皮肤决定（数据驱动，但皮肤侧写死）

- **轨数 = 皮肤 `note.dst.length`**：`skin/json/JsonPlaySkinObjectLoader.java:83`
  `Rectangle[] region = new Rectangle[sk.note.dst.length];` → 传给 `SkinNote.setLaneRegion`。
- `LaneRenderer.java:701` 按 `lanes.length` 循环，**Java 里没有"7"这个常量**。
- **但"派几条"写死在皮肤里**：`play7.luaskin` → `main(7)`、`play5.luaskin` → `main(5)`、`play9.luaskin` → `main(9)`；
  `touchscreen_play/play.lua:308` 的 `main(keysNumber)` 内是 5 / 7 / 9 三个分支，
  7K 分支的 `each_w` / `each_h` / `note` 贴图数组都是**8 个字面量**（`play.lua:451`、`922-937`），
  `note.dst` 里 7K 走 `for i = 1, #geo.lane.each_w`（=8），5K 走 `for i = 1, 6`，9K 走 `for i = 1, 9`。
- **皮肤与 mode 靠 `SkinType` 唯一配对**：`BMSPlayer.java:499-506` 遍历 `SkinType` 找 `type.getMode() == model.getMode()`；
  `SkinType.java:12-30` 只有 `PLAY_7KEYS / PLAY_5KEYS / PLAY_14KEYS / PLAY_10KEYS / PLAY_9KEYS / PLAY_24KEYS(+DOUBLE)`。
- lane → 皮肤编号由 `LaneProperty.laneToSkinOffset` 决定：7K `{1,2,3,4,5,6,7,0}`、5K `{1,2,3,4,5,0}`（**皿恒为 0**）。
- 谱面 mode 对皮肤只暴露**布尔**属性 `chart_5key=161` / `chart_7key=160` 等（`BooleanPropertyFactory.java:396-400`），
  没有任何"轨数"数值变量；现有两个主题也都没用这些 option。

### ④ 输入与判定层：slot 与 lane 两层索引（`play/LaneProperty.java`）

```
物理键 keycode ─► slot (KeyboardConfig.keys[i])
                 │                                  └─ BMSPlayerInputProcessor.keystate[256] 是 slot 空间
                 └─► lane (LaneProperty.keyToLane) ─► JudgeManager 取音符 / 判定文字
```

- `JudgeManager.java:176` `keyassign = getKeyLaneAssign()`（slot → lane），`:375-387` 循环里
  **`input.getKeyState(key)` 用 slot、`states[lane]` 用 lane**。
- `LaneProperty.java:38-107` 是唯一的 lane ↔ slot 表：7K `keyToLane = {0,1,2,3,4,5,6,7,7}`，
  `laneToKey[7] = {7,8}` —— **转盘占 slot 7（F-SCR）和 8（R-SCR）**；5K 转盘占 slot 5 / 6。
- 触摸 `PlayTouchKeyMapper`：命中 lane 后经 `laneToKey[lane][0]` **转回 slot** 再写（`:362-375`），
  所以触摸转盘只能触发 F-SCR；`isScratchKey()`（`:308-328`）是按 `totalLanes == 6/8/16/9` 的**硬编码启发式**。
- 判定规则/量表按 mode 分流：`BMSPlayerRule.java:13-17`（`Beatoraja_5` 覆盖 `BEAT_5K` + `BEAT_10K`，
  `Beatoraja_7` 覆盖 `BEAT_7K` + `BEAT_14K`，`_9` 覆盖 POPN）。

---

## 二、现状：非标准轨数谱面会被当成什么

**没有任何"规整化 / 校验"步骤**，mode 只会被初始值 + 升格规则决定，**绝不会被压缩到实际用到的轨数**。

| 谱面实际写法 | 被判定为 | 结果 |
|---|---|---|
| 只有 `11`–`14`（4 键） | `BEAT_5K`（6 轨） | K5 与皿两条轨**全空**，但界面上照样画 6 条 |
| `11`–`15` + `16`（5 键 + 皿） | `BEAT_5K` | 标准 5K，正常 |
| `11`–`15` + `18`（或 `19`），不用 `16`（6 键位、无皿） | `BEAT_7K`（8 轨） | 判定成 7K，皿轨空；**无法表达"6 键"** |
| `11`–`16`（想表达 6 个键） | `BEAT_5K` | 通道 `16` 在 BMS 语义里是**皿**，即被理解成"5 键 + 皿" |
| 只用 2P 通道 | `BEAT_10K` / `BEAT_14K` | 1P 轨全空 |

**关键：BMS 的键通道是 `11`–`15` + `18`/`19` 共 7 个键位，加 `16` 一个皿。**
不存在"连续 6 个键通道"这种东西，所以 6K 谱面在**通道层面就无法与 7K 区分**（只要碰 `18`/`19` 就升格），
4K 也只是"5 键位里少用一个"。这是本次评估的核心障碍。

---

## 三、逐项评估

### 5K：已支持，零成本

`Mode.BEAT_5K`（key=6 = 5 键 + 皿）是完整先例：解码表 `CHANNELASSIGN_BEAT5`、判定/量表
`BMSPlayerRule.Beatoraja_5`、键位 `PlayModeConfig` 的 5K 分支（7 槽）、皮肤 `play5.luaskin`
（`touchscreen_play/play.lua:989-1014` 的 5K 分支）**全部齐备**。

> 需要注意的只是"皮肤里有没有 5K 变体"——`GenericTheme` 与 `default` 都有。

### 4K：技术上可行，但缺"识别依据"

- 只用 `11`–`14` 的谱面现在会落进 `BEAT_5K`，多出 K5 + 皿两条空轨。
- 若要做成独立模式，需要：新 `Mode.BEAT_4K`（key = 4 或 5）、新通道表、新 `SkinType`、新皮肤分支。
- **但它没有"判据"**：4 键与 5 键谱在通道上只差"有没有用 `15`"，要不要为此定义"谱面用了几个键位就是几 K"？
  这属于**自定义规则**，不是对接标准。

### 6K：与现有升格规则直接冲突（最难的一项）

- 编码 6 个键位（例如 `11`–`15` + `18`）必然触碰 `18` → `Section.java:145-153` 立刻升格 `BEAT_7K`。
- 想在 7K 之下再区分出"6K"，必须引入**新判据**（例如统计实际用到的键位集合、
  或自定义头字段），而任何新判据都会**改变现有 5K→7K 的行为**，属于兼容性风险。

---

## 四、三条实现路线

### 路线 A：新增 `Mode.BEAT_4K / BEAT_6K`（正统、改动面最广）

改动清单（按依赖顺序，全部是"照 5K 的样子抄一遍"的机械工作）：

| 层 | 文件 | 要做什么 |
|---|---|---|
| 模式 | `bms/model/Mode.java:10-17` | 新增枚举（id / hint / player / key / scratchKey） |
| 解码 | `bms/model/Section.java:145-162`（升格）、`:214-216`（通道表）、`:227`（选表） | 新规则 + 新表 + 新分支 |
| 解码 | `bms/model/BMSONDecoder.java:109-132` | 新 `mode_hint` + `keyassign` |
| 渲染 | `play/LaneProperty.java:38-107` | 新 case（`keyToLane`/`laneToKey`/`laneToScratch`/`laneToSkinOffset`） |
| 渲染 | `skin/SkinType.java:12-30`、`SkinConfig.java:168-182` | 新皮肤类型 + 默认皮肤路径 |
| 渲染 | `skin/property/BooleanPropertyFactory.java:396-400` | `chart_4key` / `chart_6key` 布尔 |
| 输入 | `PlayModeConfig.java`（Keyboard `:217-242`、MouseScratch `:311-325`、Controller `:480-508`、Midi `:639-732`） | 每套模式的键位数组长度与默认值 |
| 输入 | `PlayerConfig.java:197-209, 372-412, 751-779` | `modeX` 字段与分发 |
| 输入 | `KeyConfiguration.java:37-75` | `MODE` / `MODE_HINT` / `KEYS` / `KEYSA` |
| 输入 | `ControlInputProcessor.java:64-73` | `keybinds` 长度必须等于槽位数 |
| 判定 | `play/BMSPlayerRule.java:13-17`、`calculateDefaultTotal` | gauge / judge / total 归属 |
| 其他 | `MusicSelector.java:53`、`ScoreData` / `SongData.mode`（存 `Mode.id`） | 选曲筛选 + 存档兼容 |
| 触摸 | `play/PlayTouchKeyMapper.java:308-328` | `isScratchKey` 启发式加分支 |
| **皮肤** | `play4.luaskin` / `play6.luaskin` + `play.lua` | **新增两套几何分支（竖屏 + 横屏各一套）** |

**最大工作量与最大风险都在最后一行**：`touchscreen_play/play.lua` 的 7K 布局涉及
`base_widths`（`:368-394`）、`order`（`:409`）、宽度摊分（`:422-448`）、`each_h`（`:451`）、
`note` 贴图序列（`:922-937`）、`dst` 生成（`:938-949`）——每新增一种轨数都要重推这一整套，
且**每套皮肤（GenericTheme 横竖屏、default、以后的 walkure）都要各来一遍**。

### 路线 B：不新增 Mode，做"实际轨数自适应"（推荐先评估）

思路：加载完谱面后统计哪些 lane 真的出现过音符，得到一个"活跃 lane 掩码"，
渲染时跳过空 lane（可选：把剩余 lane 重新均分宽度）。

- **优点**：不新增 `Mode` / `SkinType` / 存档格式；4K 谱（在 5K 模式下）自动隐藏 K5 + 皿，
  "无皿 7K 谱"自动隐藏皿轨；纯 Java 改动。
- **难点**：
  1. 轨道的**背景光柱 / 判定线**是皮肤里**独立的 image 对象**（lua 里 8 个），不是 `SkinNote` 自动生成，
     所以"隐藏空轨"在视觉上仍需皮肤配合——Java 侧只能控制 `SkinNote` 的音符层与触摸层。
  2. 宽度重排要改 `SkinNote.setLaneRegion` 的赋值逻辑，并同步 `laneToSkinOffset`（判定文字/光柱位置）。
  3. `PlayTouchKeyMapper` 需忽略 0 宽轨（否则触摸区错乱）。
- **结论**：能解决"空轨吃掉触摸区、音符层轨道不对齐"的问题，但**屏幕上的轨道底图仍要皮肤支持**。

### 路线 C：降轨映射（如果目的是"用 6 键玩 7K 谱"）

把 7K 的键 lane 按自定义规则合并到 6 个槽位。这属于**自定义玩法**，
判定语义与原生谱面操作会冲突，且**仍然需要 6 轨皮肤**——瓶颈与路线 A 相同。

---

## 五、待决问题（动手前必须先定）

1. **目标是哪种？**
   - (a) 让"非标准谱面"（只用部分通道的谱）不显示空轨 → 路线 B 更合适；
   - (b) 让玩家能主动选 4K / 6K 玩法（把标准谱投到更少按键上）→ 路线 C；
   - (c) 真的存在 4K / 6K 谱面要读 → 路线 A，但要先确认谱面来源与通道分配约定。
2. **"4K / 6K"含不含转盘？** 含皿则 lane = 5 / 7，不含则 = 4 / 6，通道表与 `scratchKey` 完全不同。
3. **皮肤怎么办？** 现有皮肤没有 4K / 6K 几何。是否能接受"新增皮肤文件"这一成本，
   还是只做 Java 侧的自适应（路线 B，视觉上轨道底图会留白）？

> 补充事实：**BMS / BMSON 标准单人模式只有 5key 与 7key（都含皿）**，本仓库另有 PMS 9key 与键盘 24key。
> 不存在标准的 4K / 6K 模式，所以任何实现都是"自定义映射规则"。
