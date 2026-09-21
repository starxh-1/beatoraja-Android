# 选曲界面：搜索结果 folder 只保留一条（新搜索覆盖旧搜索）

> 2026-09-21 反馈并修复。症状：搜索后根目录出现 `Search : 'xxx'` folder；再搜另一个词，
> 根目录里会**再堆一条**，反复搜索后搜索 folder 越积越多。
> 期望：根目录里**最多只有一个**搜索 folder，新搜索直接覆盖旧的。

> 本文取代了同一天早些时候的 `musicselect-search-reset.md`（那一版走的是"离开选曲界面
> 进 play 就清空搜索"的思路，已按用户反馈整体回退）。

## 一、搜索条目是怎么来的

两个入口都往 `BarManager.search` 里塞一个 `SearchWordBar`：

| 入口 | 代码位置 |
|---|---|
| 屏幕上的 `search song` 输入框（回车） | `SearchTextField.java:119-124`：`addSearch(swb)` → `updateBar(null)` → `setSelected(swb)` |
| `NUM0` 弹出的原生输入框 | `MusicSelectInputProcessor.java:105-106`：`addSearch(...)` → `updateBar(null)` |

`BarManager.updateBar(null)` 的 root 分支把整个 `search` 追加进根目录列表
（`BarManager.java:297` `l.addAll(search);`），所以 **`search` 里有几条，根目录里就有几个
搜索 folder**。`SearchWordBar` 本身是无状态的：只存 `text`，`getChildren()` 每次按需查 DB，
title 固定为 `Search : '<text>'`。

## 二、旧实现为什么堆积

```java
// 旧 addSearch（上游逻辑）
public void addSearch(SearchWordBar bar) {
    for (SearchWordBar s : search) {
        if (s.getTitle().equals(bar.getTitle())) {   // 只做「同名」去重
            search.removeValue(s, true);
            break;
        }
    }
    if (search.size >= select.resource.getConfig().getMaxSearchBarCount()) {
        search.removeIndex(0);                        // 只做「最多 10 条」限长
    }
    search.add(bar);
}
```

它只管**同名去重**和**最多 `maxSearchBarCount`（默认 10）条**，从不清理旧结果。
于是搜 A → 搜 B → 搜 C，根目录里就挂着三条不同标题的搜索 folder；
只有搜到完全相同的标题才会替换。`search` 数组的生命周期 = `BarManager` =
整个 `MusicSelector`，进程不重启就一直留着。

## 三、修法：整体替换，永远只留一条

```java
public void addSearch(SearchWordBar bar) {
    search.clear();
    search.add(bar);
}
```

`search` 恒定 0 或 1 条 ⇒ 根目录里恒定最多一个搜索 folder，新搜索覆盖旧的。
`maxSearchBarCount` 那个配置项在这条路径上不再生效（字段保留，配置兼容性不变；
本分支也没有引用它的设置界面 —— 上游桌面版才有 `MusicSelectConfigurationView` 的入口）。

### 为什么不用额外维护 `dir` 栈

调用方在 `addSearch()` 之后一定紧跟 `updateBar(null)`，而 root 分支开头就是
`dir.clear(); sourcebars.clear();`（`BarManager.java:288-289`）。
所以**即使玩家此刻正停在上一条搜索结果 folder 里**，重建后也自然回到根目录，
不会留下一个"已经没有入口"的悬空层级 —— 不需要像旧方案那样手动弹栈。

### 与 `SearchTextField` 的选中逻辑兼容

`SearchTextField` 在 `addSearch` 后立刻 `setSelected(swb)`。替换后 `swb` 就是唯一元素，
旧 bar 已被 `clear()` 移出列表，选中不会指到一个已经不在列表里的对象。

## 四、边界行为

- 搜索 → 进 play → 返回：搜索 folder **仍然在**（本条反馈明确要求不要清空），
  除非期间又搜了别的词，那时它已被新的覆盖；
- 搜索 → 打开设置（NUM6 / 皮肤设置）→ 返回：同上，条目保留；
- 同名重搜：等价于替换成一条新 bar（结果重新查库），表现与旧实现的同名去重一致；
- `BarRenderer` 只按 `instanceof SearchWordBar` 决定渲染样式（`BarRenderer.java:166`），
  不关心条数，无需改动。

## 五、改动文件

| 文件 | 改动 |
|---|---|
| `core/.../select/BarManager.java` | `addSearch()` 改为「先 `search.clear()` 再 add」，附中文 javadoc 说明 |

`MusicSelector.java` 已完全回到 `c44ad474` 之前的状态（该提交里加的
`searchResetPending` / `shutdown()` 置位 / `prepare()` 末尾归零全部撤销）。

javac 校验注意：`BarManager` 依赖 jackson（`jackson-annotations-core-databind`），
单独编译时要把 jackson 三个 jar 补进 classpath。
