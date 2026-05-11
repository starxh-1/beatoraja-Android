# beatoraja-Android

English version is translated by AI, but with some changes of myself to express emotions :)
maybe changed 5% texts

Android version of beatoraja, built with [libGDX](https://libgdx.com/).

[中文](README.md)

---

## 1. Why do this beatoraja-Android?

It's been nearly a decade since mobile had a playable BMS Player. Malody? Lacking support base62, bmson, and isn't really part of the BMS — not the flavor.

beatoraja itself is almost entirely written in Java, making its code structure fairly friendly for porting. 
So I just used AI to help bring it to Android.

(well I changed this sentence cuz the Original Chinese version said lines on the top translated by AI :)

Main reason though: x86 handhelds are too heavy omg I just can't imagine playing BMS with a 600grams deck

## 2. What's Different from the Original beatoraja

What's new: Audio Spectrum — it's in the Play Option in the launcher.

What's missing:
1. All JavaFX-related implementations are gone — so here's a new launcher with Jetpack Compose, it just needs to be able to change options.
2. Audio engine swapped to libgdx-oboe instead of PortAudio or OpenAL — honestly without Oboe this project wouldn't have been possible at all.

Everything else is essentially the same as beatoraja.

## 3. System Requirements

- CPU: arm64-v8a, reference device is Snapdragon 810.
- Display: 16:9 1080P for the best experience. Since modern phones rarely have 16:9 aspect ratios, the audio spectrum is placed outside the playfield on left and right by default.
- RAM: 2GB or above
- Note: The app technically supports 32-bit devices, but performance is terrible — can barely run no-K BMS. Can't handle BGA. (Tested on Xiaomi 2s)

## 4. Not Yet Implemented

- BGA playback for `.mpg`, `.wmv` and other formats (only mp4 is supported).
- Internet Ranking (still evaluating if allowed).
- For Touchscreen only skin (Still need to learn lua or javascript)

## 5. Known Bugs

- select, decide, and result sound effects can't play — still investigating.
- High refresh rate screens (>60Hz) may experience unstable framerates — under research.
- Result could be lag by at least 10 secs to get response — still investigating.

## 6. Features from the Original That I Couldn't Get Working

- **cim reading and parsing**: There might be a way, but I have no idea how this works...

---

# Special Thanks

- beatoraja by exch-bms2 (https://github.com/exch-bms2/beatoraja)
- libgdx-oboe by barsoosayque (https://github.com/barsoosayque/libgdx-oboe)
- GenericTheme Skin by Shimi9999 (https://github.com/Shimi9999/GenericTheme)
- KissFFT by Mark Borgerding (https://github.com/mborgerding/kissfft)

- MiniMax-M2.7, Volcano Coding Plan Lite, Free Google Gemini, Not Free OpenAI Codex, Expensive Qoder Plan,
  and some AIs whatever free or paid which I can't remember anymore

- Anonymous test human guys

- I love keysounded VSRG, yeah.
