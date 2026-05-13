# beatoraja-Android

beatoraja 的 Android 移植版，使用 [libGDX](https://libgdx.com/) 构建。

[English](README-en.md)

## 1. 为啥做这个beatoraja-Android移植

移动端已经快十年没出现过能看的 BMS Player 了。Malody 吗？那个不支持 base62, bmson, 本身也不算 BMS 生态的，味不够。

beatoraja 本身几乎是纯 Java 写的，代码结构对移植比较友好，于是干脆借 AI 的力量把它搬到了 Android 上。
（其实这句话也是AI写的，实际上我觉得这句话看着很傻不拉几，:)

主要还是我嫌 x86 掌机太重了，把这个移植到 Android 上，我就可以去买轻便的 Android 掌机了。

## 2. 跟原来的beatoraja比多了什么功能，差在哪里

多出来的：音频频谱 (Audio Spectrum)，launcher 的 Play Option 选项就有

区别：
1. JavaFX 相关的实现都没有了，让 AI 拿 Compose 随便写了个新的 launcher，能改选项就行
2. 音频引擎换成了 libgdx-oboe，可以说如果没有 Oboe 这个移植项目根本搞不出来。

其余功能基本沿袭自 beatoraja。

## 3. 配置要求

- CPU：arm64-v8a，以 Snapdragon 810 为参考基准。
- 屏幕：16:9 的 1080P 屏幕可获得最佳体验，考虑到现代手机很少 16:9 比例，音频频谱默认做在了游戏外的左右两边。
- 内存：2GB RAM 或以上
- 说明：app 本身支持 32bit 设备，但性能太差，勉强跑跑无 K 音的 BMS 吧。BGA 都带不起来。(Tested on Xiaomi 2s)

## 4. 尚未实现的功能

- `.mpg`、`.wmv` 等格式的 BGA 播放（目前仅支持 mp4）。
- Internet Ranking 功能（不清楚是否允许接入）。
- 为触控屏专门设计的皮肤（我太懒了，这个之后再弄吧。。。）

## 5. 已知 bug

- select, decide 和 result 音效无法播放，原因仍在排查中。
- 高刷屏幕（>60Hz）有一定几率出现帧数不稳定的问题，待进一步研究。
- result 界面有概率遇到卡死，需等到大概十秒以上才能响应，排查中
- 部分皮肤会遇到字体 bug（如 Modernchic）

## 6. 原版beatoraja有，但我无法实现的功能

- **CimFS 读取和解析**：或许有办法，但我不懂这个。。

# Special Thanks

- beatoraja by exch-bms2 (https://github.com/exch-bms2/beatoraja)
- libgdx-oboe by barsoosayque (https://github.com/barsoosayque/libgdx-oboe)
- GenericTheme Skin by Shimi9999 (https://github.com/Shimi9999/GenericTheme)
- KissFFT by Mark Borgerding (https://github.com/mborgerding/kissfft)

- MiniMax-M2.7, Volcano Coding Plan Lite, Free Google Gemini, Not Free OpenAI Codex, Expensive Qoder Plan,
  and some AIs whatever free or paid which I can't remember anymore

- Anonymous test human guys

- I love keysounded VSRG, yeah. ~~所以管他呢就算AI写屎山我也要弄出来我就要玩反正修啥bug不是修~~
