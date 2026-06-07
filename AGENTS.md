# Project Memory

在处理本仓库任务前，先阅读 `ARCHITECTURE.md`。它是当前项目结构、运行链路和修改约束的
主记忆文档；数据库、渲染和性能问题的深入分析位于 `docs/`。

必须记住的事实：

- 实际 Gradle 模块只有 `:core` 和 `:android`。
- `app/` 是未参与构建的遗留目录。
- `SettingsActivity` 是 Java `Activity` + XML View，不是 Jetpack Compose。
- 依赖方向保持 `android -> core`；平台实现通过接口、Factory、静态注入或已有反射钩子接入。
- Android 音频来自 `android/libs/libgdx-oboe.aar`，失败时存在默认 `AndroidAudio` 回退。
- 歌曲库和成绩库使用 Android 原生 SQLite 实现；涉及结果页或扫描并发时先读 `docs/` 专题。
- 配置字段变化要同步检查 `Config`、`PlayerConfig` 和 `SettingsActivity` 的手写 JSON 逻辑。
- 不要修改 `app/` 中的 helper 来修复 APK 行为。

若架构发生变化，在同一任务中同步更新 `ARCHITECTURE.md`。
