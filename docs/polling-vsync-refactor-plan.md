# Polling Rate & VSync 重构计划

## 背景

根据 Endless Dream upstream 的修复和 debugforai.txt 的分析，当前项目存在以下问题：

1. **输入轮询率可配置但混乱** - Config 中可配置 250-4000Hz，Settings 中有 UI slider
2. **轮询线程过于复杂** - 每帧读取配置值、频率可变的 LockSupport 等待

## Endless Dream 的解决方案

- **硬编码 1000Hz 轮询率** - 简单的时间检查门控 (`if (time != now)`)
- **简单 polling 线程** - Thread.sleep(0, 500000) 休眠
- **渲染线程和轮询线程分离** - 避免 GLFW 事件阻塞输入

## Android 版本的已有改进

Android 版本的轮询线程**已经优于** upstream（使用 LockSupport.parkNanos、绝对时间对齐），只需简化：

## 具体变更

### 1. Config.java
- `inputPollingRate` 字段保留但硬编码为 1000
- 移除 setter 或保留但不使用
- `getInputPollingRate()` 直接返回 1000

### 2. MainController.java 轮询线程
- 移除 `config.getInputPollingRate()` 读取
- 硬编码 `pollIntervalNs = 1_000_000` (1000Hz = 1ms)
- 保留 LockSupport.parkNanos（比 upstream Thread.sleep 更好）

### 3. SettingsActivity.java + activity_settings.xml
- 注释掉 Polling Rate Spinner UI
- 注释掉 Polling Rate Help 按钮和相关监听器

### 4. BMSPlayerInputProcessor.java
- 轮询率相关逻辑简化

### 5. TimerManager.java
- 保持不变（Android 已改进为实时计算，优于 upstream 的帧同步方案）

### 6. GC
- Android Dalvik/ART 不适用 ZGC/ShenandoahGC，忽略
