Written by AI

# XBOX手柄支持使用指南

## 概述
Beatoraja-Android现已支持XBOX手柄及其他游戏控制器。你可以在Key Config界面中配置手柄按键映射。

**重要说明**：XBOX手柄与iidx专用控制器共用Controller1/Controller2配置槽位，每个槽位同一时间只能绑定一个控制器（手柄或专用控制器），无需担心按键冲突。

## 已完成的修改

### 1. 新增文件
- `core/src/main/java/bms/player/beatoraja/input/XboxControllerHelper.java`
  - XBOX手柄辅助类，提供控制器检测和日志输出功能

### 2. 修改文件
- `core/src/main/java/bms/player/beatoraja/input/BMControllerInputProcessor.java`
  - 添加了Logger导入
  - 在构造函数中添加初始化日志

- `android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java`
  - 添加了Controllers导入
  - 添加了`detectAndLogControllers()`方法
  - 在onCreate中调用控制器检测

- `core/src/main/java/bms/player/beatoraja/config/KeyConfiguration.java`
  - 增强界面显示，自动识别并显示"XBOX Gamepad"或"Gamepad"类型
  - 添加控制器切换日志提示
  - 添加按键映射成功日志
  - 显示已连接控制器数量

## 如何使用

### 步骤1：连接手柄
1. **蓝牙连接**：
   - 打开XBOX手柄的配对模式（按住配对按钮）
   - 在Android蓝牙设置中配对连接

2. **USB连接**：
   - 使用USB OTG转接线
   - 将手柄连接到Android设备

### 步骤2：启动应用
启动Beatoraja-Android应用，在Logcat中你应该能看到类似以下的日志：
```
=== Connected Controllers ===
Controller: XBOX Wireless Controller
  - Buttons: at least 15
  - Axes: at least 6
===========================
✓ XBOX controller detected and ready!
```

### 步骤3：配置按键映射
1. 进入**Key Configuration**界面
2. 查看界面显示：
   - **Controller1**下方会显示控制器类型（XBOX Gamepad / Gamepad）
   - 右下角显示已连接的控制器数量
3. **按[2]键切换Controller Device 1**，直到显示你的XBOX手柄名称
4. 使用方向键（或键盘）选择要映射的功能（如1 KEY、2 KEY等）
5. **按下手柄上对应的按钮**
6. 系统会自动识别并显示按钮名称（如"BUTTON 0"、"BUTTON 1"等）
7. 查看Logcat日志，会显示映射详情：`Mapped XBOX [XBOX Wireless Controller] BUTTON 0 -> 1 KEY`
8. 重复以上步骤配置所有需要的按键

**提示**：
- 如果要配置双打，按[3]键切换Controller Device 2
- 每个Controller槽位只能绑定一个控制器（手柄或iidx专用控制器）

### 推荐的XBOX手柄按键映射

#### 7KEYS模式
| 游戏功能 | XBOX按键 | 说明 |
|---------|---------|------|
| 1 KEY | A (BUTTON 0) | 主按键1 |
| 2 KEY | B (BUTTON 1) | 主按键2 |
| 3 KEY | X (BUTTON 2) | 主按键3 |
| 4 KEY | Y (BUTTON 3) | 主按键4 |
| 5 KEY | LB (BUTTON 4) | 主按键5 |
| 6 KEY | RB (BUTTON 5) | 主按键6 |
| 7 KEY | BACK (BUTTON 6) | 主按键7 |
| F-SCR | 左摇杆左 (AXIS 1 -) | 左转盘 |
| R-SCR | 左摇杆右 (AXIS 1 +) | 右转盘 |
| START | START (BUTTON 7) | 开始键 |
| SELECT | BACK (BUTTON 6) | 选择键 |

#### 14KEYS双打模式
对于双打模式，你可以配置两个手柄：
- **1P（玩家1）**：使用手柄1，按键映射同上
- **2P（玩家2）**：使用手柄2，按键映射同上

## XBOX手柄按键代码参考

### 按钮代码
```
BUTTON 0  = A键
BUTTON 1  = B键
BUTTON 2  = X键
BUTTON 3  = Y键
BUTTON 4  = LB（左肩键）
BUTTON 5  = RB（右肩键）
BUTTON 6  = BACK/VIEW
BUTTON 7  = START/MENU
BUTTON 8  = L3（左摇杆按下）
BUTTON 9  = R3（右摇杆按下）
BUTTON 10 = XBOX键（可能不可用）
```

### 轴代码
```
AXIS 0 = 左摇杆X轴（左右）
AXIS 1 = 左摇杆Y轴（上下）
AXIS 2 = LT（左扳机）
AXIS 3 = 右摇杆X轴（左右）
AXIS 4 = 右摇杆Y轴（上下）
AXIS 5 = RT（右扳机）
```

## 高级配置

### 模拟转盘（Analog Scratch）
如果你想使用摇杆作为转盘，可以在Key Config界面中：
1. 选择对应的手柄配置
2. 启用"Analog Scratch"选项
3. 设置阈值（推荐100）
4. 选择算法版本（推荐VER 2）

### JKOC Hack
如果遇到摇杆误触发问题，可以启用JKOC Hack：
- 这会在摇杆未达到极端位置（>0.9或<-0.9）时不触发输入
- 适用于需要精确控制的玩家

## 故障排除

### 问题1：手柄未被识别
**解决方案**：
1. 检查手柄是否已成功配对/连接
2. 查看Logcat日志，确认是否显示控制器信息
3. 尝试重新连接手柄并重启应用
4. 确保Android系统版本支持（Android 8.0+）

### 问题2：按键无响应
**解决方案**：
1. 进入Key Config界面重新配置按键
2. 确认手柄按键在系统中正常工作（可在Android设置中测试）
3. 检查是否启用了正确的手柄配置

### 问题3：转盘操作不流畅
**解决方案**：
1. 在Key Config中启用"Analog Scratch"
2. 调整阈值（analogScratchThreshold）
3. 尝试不同的算法版本（VER 1或VER 2）

### 问题4：双打模式只有一个手柄有效
**解决方案**：
1. 在Key Config中按[2]切换Controller Device 1
2. 按[3]切换Controller Device 2
3. 分别为两个手柄配置按键映射

## 技术细节

### 依赖项
项目已包含以下必要依赖：
- `gdx-controllers-core`：libGDX控制器核心
- `gdx-controllers-android`：Android平台控制器支持

### 代码架构
```
BMSPlayerInputProcessor (输入处理器)
  ├── KeyBoardInputProcesseor (键盘输入)
  ├── BMControllerInputProcessor[] (手柄输入数组)
  │    ├── Controller 1
  │    └── Controller 2
  └── MidiInputProcessor (MIDI输入)
```

每个手柄都有独立的`BMControllerInputProcessor`实例，支持最多配置多个手柄。

## 支持的控制器类型

代码会自动识别以下类型的控制器：
- XBOX系列手柄（XBOX、XINPUT）
- 通用游戏手柄（GAMEPAD）
- 其他控制器（CONTROLLER、JOYSTICK）

## 更新日志

### 2026-04-30
- ✅ 添加XboxControllerHelper辅助类
- ✅ 增强BMControllerInputProcessor日志输出
- ✅ 在AndroidLauncher中添加控制器自动检测
- ✅ 完善XBOX手柄按键映射支持

---

## KeyConfiguration界面详解

### 界面布局
```
┌─────────────────────────────────────────────────────────┐
│  <-- 7 KEYS -->                                         │
│  Key Board    Controller1    MIDI                       │
│                 XBOX Gamepad  ← 自动识别控制器类型       │
│                                                         │
│  Music Select: 2dx sp                                   │
│  Controller Device 1: XBOX Wireless Controller          │
│  Controller Device 2: (双打模式)                         │
│                                                         │
│  Connected Controllers: 2      ← 显示已连接手柄数量      │
│  Press any button to configure ← 配置提示               │
└─────────────────────────────────────────────────────────┘
```

### 按键操作
| 按键 | 功能 |
|------|------|
| ← → | 切换游戏模式（5KEYS/7KEYS/9KEYS等） |
| ↑ ↓ | 在按键列表中移动光标 |
| [1] | 切换选曲输入模式（2dx sp / popn / 2dx dp） |
| [2] | **切换Controller Device 1**（循环切换已连接的手柄） |
| [3] | **切换Controller Device 2**（双打模式） |
| Enter | 进入按键配置模式（然后按下手柄按键） |
| Delete | 删除当前按键映射 |
| [7] | 恢复键盘默认配置 |
| [8] | **恢复控制器默认配置** |
| [9] | 恢复MIDI默认配置 |
| ESC | 保存并退出 |

### 日志输出示例
当你配置手柄时，Logcat会输出详细日志：
```
KeyConfig: Switched Controller 1 to: XBOX Wireless Controller [XBOX]
KeyConfig: Mapped XBOX [XBOX Wireless Controller] BUTTON 0 -> 1 KEY
KeyConfig: Mapped XBOX [XBOX Wireless Controller] BUTTON 1 -> 2 KEY
KeyConfig: Mapped XBOX [XBOX Wireless Controller] BUTTON 4 -> 5 KEY
```

### 控制器类型识别
系统会自动识别以下类型：
- **XBOX Gamepad**: 名称包含XBOX、XINPUT、GAMEPAD等关键词
- **Gamepad**: 其他通用游戏控制器
- **Controller1/Controller2**: 未识别或未配置的设备

---

**提示**：如果在使用过程中遇到问题，请查看Logcat日志中的"KeyConfig"、"XboxControllerHelper"和"BMControllerInputProcessor"标签，这些日志会帮助你诊断问题。
