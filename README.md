# beatoraja-Android

Android port of beatoraja, built with [libGDX](https://libgdx.com/).

[中文](README_zh.md)

## 1. Why port beatoraja to Android

It's been nearly a decade since a usable BMS Player showed up on mobile. Malody? Doesn't support base62 or bmson, and it's not really part of the BMS ecosystem — not the right flavor.

beatoraja is almost entirely written in Java, so the code structure is fairly port-friendly. I just leaned on AI to bring it over to Android.

The real reason: x86 handhelds are too damn heavy. Port it to Android and I can buy a lightweight Android handheld.

## 2. What's added vs the original, and what's missing

What's added: Audio Spectrum — it's in the Play Option in the launcher.

What's different:
1. All JavaFX-related implementations are gone — I had AI throw together a new launcher, it just needs to be able to change options.
2. Audio engine swapped to libgdx-oboe — honestly, without Oboe this port couldn't have happened at all.
3. Built-in Walkure recommendation

Everything else is essentially inherited from beatoraja.

## 3. System Requirements

- CPU: arm64-v8a, reference baseline is Snapdragon 810.
- Display: 16:9 1080P for the best experience. Since modern phones rarely have 16:9, the audio spectrum is placed outside the playfield on the left and right by default.
- RAM: 2GB or above
- Note: The app technically supports 32-bit devices, but performance is terrible — only barely runnable on no Keysounded BMS. BGA won't even work. (Tested on Xiaomi 2s)

## 4. TBD Features

None

## 5. Features That Can't Be Implemented (too difficult)

- Software decoding of non-mp4 (H.264, HEVC) video formats (too hard, hoping someone comes up with a new approach)
- Internet Ranking

## 6. Known Bugs

- The joystick area may experience drift or misalignment issues

# Special Thanks

- beatoraja by exch-bms2 (https://github.com/exch-bms2/beatoraja)
- LR2oraja-EndlessDream by seraxis (https://github.com/seraxis/lr2oraja-endlessdream)
- libgdx-oboe by barsoosayque (https://github.com/barsoosayque/libgdx-oboe)
- GenericTheme Skin by Shimi9999 (https://github.com/Shimi9999/GenericTheme)
- KissFFT by Mark Borgerding (https://github.com/mborgerding/kissfft)

- Walkure by naktazdim (https://github.com/naktazdim/walkure-offline)

- MiniMax-M2.7, Volcano Coding Plan Lite, Free Google Gemini, Not Free OpenAI Codex, Expensive Qoder Plan,
  and some AIs whatever free or paid which I can't remember anymore

- Anonymous test human guys

- I love keysounded VSRG, yeah.

- **Don't use this application for playing copyrighted contents.**
