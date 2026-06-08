# Implementation Plan - Fix BGA size in portrait mode

This plan fixes the issue where the Background Animation (BGA) does not correctly cover the lane area in portrait mode for the `GenericTheme for Touchscreen` skin. The root cause is that landscape-specific BGA geometry logic was overwriting the portrait settings.

## User Review Required

> [!NOTE]
> This change will make the BGA cover the full screen in portrait mode. It also changes the stretch mode to "Aspect Fill" (stretch=2) specifically for portrait mode to ensure the background is fully covered even if the video aspect ratio differs.

## Proposed Changes

### Skin Configuration

#### [play.lua](file:///E:/beatoraja-Android/android/assets/skin/GenericTheme for Touchscreen/play/play.lua)

- Update `initPortraitGeo` to fully initialize `geo.bgaarea` and `geo.bga` with frame dimensions.
- Wrap landscape BGA area and geometry calculations in a check to prevent overwriting portrait values.
- Change `stretch` mode from `3` (Center) to `2` (Aspect Fill) for portrait BGA.

```lua
-- In initPortraitGeo function (~Line 405)
		-- BGA - cover full screen in portrait (rotated 270°)
		geo.bgaarea = {}
		geo.bgaarea.x = 0
		geo.bgaarea.w = 1920
		geo.bgaarea.y = 0
		geo.bgaarea.h = 1080
		geo.bgaarea.center_x = geo.bgaarea.x + geo.bgaarea.w / 2
		geo.bgaarea.center_y = geo.bgaarea.y + geo.bgaarea.h / 2
		geo.bga = {
			x = geo.bgaarea.x,
			y = geo.bgaarea.y,
			w = geo.bgaarea.w,
			h = geo.bgaarea.h,
			center_x = geo.bgaarea.center_x,
			center_y = geo.bgaarea.center_y,
			frame_w = 0,
			frame_h = 0
		}
```

```lua
-- In main function (~Line 586)
	-- bga and bgaarea geometry
	if not isPortraitLayout() then
		geo.bgaarea = {}
        -- ... [Existing landscape logic] ...
		geo.bgaarea.center_x = geo.bgaarea.x + geo.bgaarea.w / 2

		geo.bga = {}
        -- ... [Existing landscape logic] ...
		geo.bga.center_y = geo.bga.y + geo.bga.h / 2
	end
```

```lua
-- In BGA destination block (~Line 1033)
			if isPortraitLayout() then
				-- portrait: BGA は bgaarea 内 (local 226,0,1694,1080) に描画、cover で埋める
				append_all(skin.destination, bga_dst(real_bga_x, real_bga_y, real_bga_w, real_bga_h, 2))
			else
```

## Verification Plan

### Manual Verification
- Review the code changes to ensure that `geo.bgaarea` and `geo.bga` are correctly preserved in portrait mode.
- Verify that `real_bga_x`, `real_bga_y`, `real_bga_w`, and `real_bga_h` will be calculated correctly (0, 0, 1920, 1080 respectively) in portrait mode given the fixed geometry.
- Confirm that `stretch = 2` is the appropriate value for "Aspect Fill" in the target engine.
