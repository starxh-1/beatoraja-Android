package bms.player.beatoraja.play;

import java.util.*;
import java.util.logging.Logger;

import bms.player.beatoraja.*;
import bms.player.beatoraja.play.SkinNote.SkinLane;

import bms.model.*;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.SkinObject.SkinOffset;
import bms.player.beatoraja.skin.SkinImage;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import static bms.player.beatoraja.CourseData.CourseDataConstraint.*;
import static bms.player.beatoraja.skin.SkinProperty.*;

/**
 * レーン描画用クラス
 *
 * @author exch
 */
public class LaneRenderer {

	private float basehispeed;

	private float hispeedmargin = 0.25f;

	private BMSModel model;
	private TimeLine[] timelines;

	private int pos;

	private final BMSPlayer main;

	private BitmapFont font;
	private PlaySkin skin;

	private final PlayerConfig config;
	private PlayConfig playconfig;

	private int currentduration;

	private double basebpm;
	private double nowbpm;
	private double mainbpm;
	private double minbpm;
	private double maxbpm;

	// Portrait mode: notes fall horizontally instead of vertically
	private boolean isPortrait = false;
	// Cached portrait detection - only recompute when skin changes
	private PlaySkin cachedSkinForPortrait = null;
	private boolean cachedPortraitValue = false;
	public boolean isPortrait() { return isPortrait; }
	// Portrait op value from skin (1101 = portrait, 1100 = landscape)
	private static final int OP_PORTRAIT = 1101;

	//PMSのリズムに合わせたノートの拡大用
	//4分から最大拡大までの時間
	private final float noteExpansionTime = 9;
	//最大拡大から通常サイズに戻るまでの時間
	private final float noteContractionTime = 150;

	// Pre-cached strings for BPM guide and timeline to avoid per-frame allocation
	private String[] cachedTimeText;
	private String[] cachedBpmText;
	private String[] cachedStopText;

	private static final Rectangle DEFAULT_VIEWPORT = new Rectangle(0, 0, Float.MAX_VALUE, Float.MAX_VALUE);

	private static final Color COLOR_TIME_TEXT = Color.valueOf("40c0c0");
	private static final Color COLOR_BPM_TEXT = Color.valueOf("00c000");
	private static final Color COLOR_STOP_TEXT = Color.valueOf("c0c000");
	private static final Color[] JUDGE_AREA_COLORS = {
			Color.valueOf("0000ff20"), Color.valueOf("00ff0020"), Color.valueOf("ffff0020"),
			Color.valueOf("ff800020"), Color.valueOf("ff000020")
	};

	public LaneRenderer(BMSPlayer main, BMSModel model) {

		this.main = main;
		Pixmap hp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		hp.drawPixel(0, 0, Color.toIntBits(255, 255, 255, 255));
		hp.dispose();

		// Get pre-loaded 18pt system font from global cache (eliminates runtime I/O)
		font = main.main.getSystemFont18();

		this.skin = (PlaySkin) main.getSkin();
		this.config = main.resource.getPlayerConfig();
		this.playconfig = config.getPlayConfig(model.getMode()).getPlayconfig().clone();

		init(model);

		for (CourseData.CourseDataConstraint i : main.resource.getConstraint()) {
			if (i == NO_SPEED) {
				playconfig.setHispeed(1.0f);
				playconfig.setLanecover(0);
				playconfig.setLift(0);
				playconfig.setHidden(0);
				playconfig.setEnableConstant(false);
			}
		}
	}

	public void init(BMSModel model) {
		pos = 0;
		this.model = model;
		List<TimeLine> tls = new ArrayList<TimeLine>();
		double cbpm = model.getBpm();
		double cscr = 1.0;
		for (TimeLine tl : model.getAllTimeLines()) {
			if (cbpm != tl.getBPM() || tl.getStop() > 0 || cscr != tl.getScroll() || tl.getSectionLine()) {
				tls.add(tl);
			} else if (tl.existNote() || tl.existHiddenNote()) {
				tls.add(tl);
			}
			cbpm = tl.getBPM();
			cscr = tl.getScroll();
		}
		this.timelines = tls.toArray(new TimeLine[tls.size()]);
		// Pre-cache all timeline text strings to avoid per-frame allocation
		cachedTimeText = new String[timelines.length];
		cachedBpmText = new String[timelines.length];
		cachedStopText = new String[timelines.length];
		for(int i = 0; i < timelines.length; i++) {
			TimeLine tl = timelines[i];
			cachedTimeText[i] = String.format("%2d:%02d.%1d", tl.getTime() / 60000,
					(tl.getTime() / 1000) % 60, (tl.getTime() / 100) % 10);
			cachedBpmText[i] = "BPM" + ((int) tl.getBPM());
			if(tl.getStop() > 0) {
				cachedStopText[i] = "STOP " + ((int) tl.getStop()) + "ms";
			}
		}

		minbpm = model.getMinBPM();
		maxbpm = model.getMaxBPM();
		Map<Double, Integer> m = new HashMap<Double, Integer>();
		for (TimeLine tl : model.getAllTimeLines()) {
			Integer count = m.get(tl.getBPM());
			if (count == null) {
				count = 0;
			}
			m.put(tl.getBPM(), count + tl.getTotalNotes());
		}
		int maxcount = 0;
		for (double bpm : m.keySet()) {
			if (m.get(bpm) > maxcount) {
				maxcount = m.get(bpm);
				mainbpm = bpm;
			}
		}
		basebpm = switch (playconfig.getFixhispeed()) {
			case PlayConfig.FIX_HISPEED_OFF -> basebpm;
			case PlayConfig.FIX_HISPEED_STARTBPM -> model.getBpm();
			case PlayConfig.FIX_HISPEED_MINBPM -> minbpm;
			case PlayConfig.FIX_HISPEED_MAXBPM -> maxbpm;
			case PlayConfig.FIX_HISPEED_MAINBPM -> mainbpm;
			default -> basebpm;
		};

		this.setLanecover(playconfig.getLanecover());
		if (playconfig.getFixhispeed() != PlayConfig.FIX_HISPEED_OFF) {
			basehispeed = playconfig.getHispeed();
		}
		this.hispeedmargin = playconfig.getHispeedMargin();
	}

	public float getHispeed() {
		return playconfig.getHispeed();
	}

	public int getDuration() {
		return playconfig.getDuration();
	}

	public void setDuration(int gvalue) {
		playconfig.setDuration(gvalue < 1 ? 1 : gvalue);
		setLanecover(playconfig.getLanecover());
	}

	public int getCurrentDuration() {
		return currentduration;
	}

	public float getHispeedmargin() {
		return hispeedmargin;
	}

	public void setHispeedmargin(float hispeedmargin) {
		this.hispeedmargin = hispeedmargin;
	}

	public boolean isEnableLift() {
		return playconfig.isEnablelift();
	}

	public float getLiftRegion() {
		return playconfig.getLift();
	}

	public void setLiftRegion(float liftRegion) {
		playconfig.setLift(liftRegion < 0 ? 0 : (liftRegion > 1 ? 1 : liftRegion));
	}

	public float getLanecover() {
		return playconfig.getLanecover();
	}

	public void resetHispeed(double targetbpm) {
		if (playconfig.getFixhispeed() != PlayConfig.FIX_HISPEED_OFF) {
			playconfig.setHispeed((float) ((2400f / (targetbpm / 100) / playconfig.getDuration()) * (1 - (playconfig.isEnablelanecover() ? playconfig.getLanecover() : 0))));
		}
	}

	public void setLanecover(float lanecover) {
		playconfig.setLanecover(lanecover < 0 ? 0 : (lanecover > 1 ? 1 : lanecover));
		resetHispeed(basebpm);
	}

	/**
	 * Computes alpha for constant fade-in effect.
	 * @param tl The current timeline
	 * @param targetTime The target time (microtime + baseduration * 1000)
	 * @param alphaLimit The fade-in time limit in ms
	 * @return Alpha value (0.0 to 1.0), or -1.0f to signal skip (note should be hidden)
	 */
	private float computeConstantFadeAlpha(TimeLine tl, long targetTime, float alphaLimit) {
		final long timeDifference = tl.getMicroTime() - targetTime;
		if (alphaLimit >= 0) {
			if (tl.getMicroTime() >= targetTime) {
				if (timeDifference < alphaLimit) {
					return (alphaLimit - timeDifference) / alphaLimit;
				} else {
					return -1.0f;
				}
			}
			return 1.0f;
		} else {
			if (tl.getMicroTime() >= targetTime) {
				return -1.0f;
			} else {
				if (timeDifference > alphaLimit) {
					return 1.0f - (alphaLimit - timeDifference) / alphaLimit;
				}
				return 1.0f;
			}
		}
	}

	/**
	 * Computes the delta to add to notePos for a single timeline.
	 * @param tl The current timeline
	 * @param prevtl The previous timeline (null if i == 0)
	 * @param microtime Current playback time in microseconds
	 * @param rxhs The pixel-per-section factor
	 * @return The delta value to add to notePos
	 */
	private double computeNotePosDelta(TimeLine tl, TimeLine prevtl, long microtime, double rxhs) {
		if (prevtl == null) {
			return tl.getSection() * (tl.getMicroTime() - microtime) / tl.getMicroTime() * rxhs;
		}
		if (prevtl.getMicroTime() + prevtl.getMicroStop() > microtime) {
			return (tl.getSection() - prevtl.getSection()) * prevtl.getScroll() * rxhs;
		}
		return (tl.getSection() - prevtl.getSection()) * prevtl.getScroll()
				* (tl.getMicroTime() - microtime)
				/ (tl.getMicroTime() - prevtl.getMicroTime() - prevtl.getMicroStop()) * rxhs;
	}

	public void setEnableLanecover(boolean b) {
		playconfig.setEnablelanecover(b);
	}

	public boolean isEnableLanecover() {
		return playconfig.isEnablelanecover();
	}

	public float getHiddenCover() {
		return playconfig.getHidden();
	}

	public void setHiddenCover(float hiddenCover) {
		playconfig.setHidden(hiddenCover < 0 ? 0 : (hiddenCover > 1 ? 1 : hiddenCover));
	}

	public boolean isEnableHidden() {
		return playconfig.isEnablehidden();
	}

	public void changeHispeed(boolean b) {
		float f = 0;
		if (playconfig.getFixhispeed() != PlayConfig.FIX_HISPEED_OFF) {
			f = basehispeed * hispeedmargin * (b ? 1 : -1);
		} else {
			f = hispeedmargin * (b ? 1 : -1);
		}
		if (playconfig.getHispeed() + f > 0 && playconfig.getHispeed() + f < 20) {
			playconfig.setHispeed(playconfig.getHispeed() + f);
		}
	}

	public PlayConfig getPlayConfig() {
		return playconfig;
	}

	public void drawLane(SkinObjectRenderer sprite, long time, SkinLane[] lanes, SkinOffset[] offsets) {
		drawLane(sprite, time, lanes, offsets, null);
	}

	public void drawLane(SkinObjectRenderer sprite, long time, SkinLane[] lanes, SkinOffset[] offsets, Rectangle viewport) {
		Rectangle visibleViewport = viewport != null ? viewport : DEFAULT_VIEWPORT;
		float offsetX = 0;
		float offsetY = 0;
		float offsetW = 0;
		float offsetH= 0;
		for(SkinOffset offset : offsets) {
			offsetX += offset.x;
			offsetY += offset.y;
			offsetW += offset.w;
			offsetH += offset.h;
		}

		long jtime = (main.timer.isTimerOn(TIMER_PLAY) ? time - main.timer.getTimer(TIMER_PLAY) :
			(main.timer.isTimerOn(141) ? time - main.timer.getTimer(141) : 0)) + config.getJudgetiming();
		if (main.getState() == BMSPlayer.STATE_PRACTICE) {
			jtime = main.getPracticeConfiguration().getPracticeProperty().starttime;
			pos = 0;
		}
		final long microtime = jtime * 1000;
		final boolean showTimeline = (main.getState() == BMSPlayer.STATE_PRACTICE);

		final float hispeed = main.getState() != BMSPlayer.STATE_PRACTICE ? playconfig.getHispeed() : 1.0f;
		if (skin == null) {
			if (main.getSkin() instanceof PlaySkin) {
				skin = (PlaySkin) main.getSkin();
			}
		}
		if (skin == null) return;
		// Detect portrait mode from skin configuration (op 1101 = portrait)
		// Cache result - only recompute when skin instance changes
		if (skin != cachedSkinForPortrait) {
			cachedSkinForPortrait = skin;
			cachedPortraitValue = false;
			if (main.getSkin() != null && main.getSkin().header != null) {
				for (bms.player.beatoraja.skin.SkinHeader.CustomOption co : main.getSkin().header.getCustomOptions()) {
					if (co.name.equals("Layout") && co.getSelectedOption() == 1101) {
						cachedPortraitValue = true;
						break;
					}
				}
			}
			if (!cachedPortraitValue && skin.getOption() != null) {
				for (com.badlogic.gdx.utils.IntIntMap.Entry e : skin.getOption()) {
					if (e.value == OP_PORTRAIT) {
						cachedPortraitValue = true;
						break;
					}
				}
			}
		}
		isPortrait = cachedPortraitValue;
		final Rectangle[] playerr = skin.getLaneGroupRegion();
		double nbpm = model.getBpm();
		double nscroll = 1.0;
		for (int i = (pos > 5 ? pos - 5 : 0); i < timelines.length && timelines[i].getMicroTime() <= microtime; i++) {
			nbpm = timelines[i].getBPM();
			nscroll = timelines[i].getScroll();
		}
		nowbpm = nbpm;
		final double region = nscroll > 0 ? (240000 / nbpm / hispeed) / nscroll : 0;
		// double sect = (bpm / 60) * 4 * 1000;
		// TODO hu,hlをレーン毎に変更

		// Portrait mode: notes fall horizontally (right to left)
		// Landscape mode: notes fall vertically (bottom to top)
		final double hu;
		final double hl;
		final double rxhs;
		final double notePosStart;
		final float trackWidth = isPortrait ? lanes[0].region.width - 40 : lanes[0].region.width; // trackWidth across lanes
		if (isPortrait) {
			// Portrait: horizontal falling - x decreases from right to left
			// Judgment line at left (hl), spawn at right (hu)
			// Add 40px offset to hl to allow drawing note heads behind the judgment line
			hl = (lanes[0].region.x + 40) + trackWidth * playconfig.getLift();
			hu = lanes[0].region.x + lanes[0].region.width;
			// Matches landscape logic: rxhs is based on the actual visual track length
			rxhs = (hu - hl) * hispeed;
			notePosStart = hl;  // Distance logic: hl + distance
		} else {
			// Landscape: vertical falling - y increases from bottom to top
			hu = lanes[0].region.y + lanes[0].region.height;
			hl = playconfig.isEnablelift() ? lanes[0].region.y + lanes[0].region.height * playconfig.getLift() : lanes[0].region.y;
			rxhs = (hu - hl) * hispeed;
			notePosStart = hl;  // Start from bottom
		}
		double notePos = notePosStart;

		final float lanecover = playconfig.isEnablelanecover() ? playconfig.getLanecover() : 0;
		currentduration = (int) Math.round(region * (1 - lanecover));

		if (isPortrait) {
			// Portrait: LIFT/LANECOVER affect X direction
			main.main.getOffset(OFFSET_LIFT).x = (float) ((hu - lanes[0].region.x) * playconfig.getLift());
			main.main.getOffset(OFFSET_LIFT).y = 0;
			// Lanecover moves from spawn (hu) towards judgment (hl). hl-hu is negative.
			main.main.getOffset(OFFSET_LANECOVER).x = (float) ((hl - hu) * lanecover);
			main.main.getOffset(OFFSET_LANECOVER).y = 0;
		} else {
			// Landscape: LIFT/LANECOVER affect Y direction
			main.main.getOffset(OFFSET_LIFT).y = (float) (hl - lanes[0].region.y);
			main.main.getOffset(OFFSET_LIFT).x = 0;
			main.main.getOffset(OFFSET_LANECOVER).y = (float) ((hl - hu) * lanecover);
			main.main.getOffset(OFFSET_LANECOVER).x = 0;
		}
		// TODO HIDDENとLIFT混在の必要性とHIDDENの必要性
		final SkinOffset hidden = main.main.getOffset(OFFSET_HIDDEN_COVER);
		if (playconfig.isEnablehidden()) {
			hidden.a = 0;
			if (isPortrait) {
				hidden.x = (int) ((1 - playconfig.getLift()) * playconfig.getHidden()
						* skin.getLaneRegion()[0].width);
				hidden.y = 0;
			} else {
				hidden.y = (int) ((1 - playconfig.getLift()) * playconfig.getHidden()
						* skin.getLaneRegion()[0].height);
				hidden.x = 0;
			}
		} else {
			hidden.a = -255;
		}

		// 判定エリア表示
		if (config.isShowjudgearea()) {
			for (int lane = 0; lane < lanes.length; lane++) {
				final long[][] judgetime = main.getJudgeManager().getJudgeTimeRegion(lane);
				for (int i = pos; i < timelines.length; i++) {
					final TimeLine tl = timelines[i];
					if (tl.getMicroTime() >= microtime) {
						double rate = (tl.getSection() - (i > 0 ? timelines[i - 1].getSection() : 0)) * (i > 0 ? timelines[i - 1].getScroll() : 1.0) * rxhs
								/ (tl.getMicroTime() - (i > 0
										? timelines[i - 1].getMicroTime() + timelines[i - 1].getMicroStop() : 0));
						for (int j = JUDGE_AREA_COLORS.length - 1; j >= 0; j--) {
							sprite.setColor(JUDGE_AREA_COLORS[j]);
							long nj = j > 0 ? judgetime[j - 1][1] : 0;
							if (isPortrait) {
								sprite.draw(main.getImage(IMAGE_WHITE), (float) (hl + nj * rate), lanes[lane].region.y,
										(float) ((judgetime[j][1] - nj) * rate), lanes[lane].region.height);
							} else {
								sprite.draw(main.getImage(IMAGE_WHITE), lanes[lane].region.x, (float) (hl + nj * rate), lanes[lane].region.width,
										(float) ((judgetime[j][1] - nj) * rate));
							}
						}
						break;
					}
				}
			}
		}

		// Draw BGA background for transparent lane effect (GenericTheme Touchscreen only)
		// This allows the BGA to show through the lane area when lane_darkness_a is set
		boolean isTouchscreenSkin = main.getSkin() != null && main.getSkin().header != null &&
			main.getSkin().header.getPath() != null &&
			main.getSkin().header.getPath().toString().contains("Touchscreen");
		boolean actuallyPortrait = main.getSkin() != null && main.getSkin().getHeight() > main.getSkin().getWidth();
		if (isTouchscreenSkin && lanes != null && lanes.length > 0 && skin != null) {
			int skinW = (int)main.getSkin().getWidth();
			int skinH = (int)main.getSkin().getHeight();
			Texture bgaFrame = main.resource.getBGAManager().getCurrentBGAFrame(skinW, skinH);
			if (bgaFrame != null) {
				float minX = Float.MAX_VALUE;
				float minY = Float.MAX_VALUE;
				float maxX = -Float.MAX_VALUE;
				float maxY = -Float.MAX_VALUE;
				for (SkinLane lane : lanes) {
					if (lane != null && lane.region != null) {
						minX = Math.min(minX, lane.region.x);
						minY = Math.min(minY, lane.region.y);
						maxX = Math.max(maxX, lane.region.x + lane.region.width);
						maxY = Math.max(maxY, lane.region.y + lane.region.height);
					}
				}

				float laneX, laneY, laneW, laneH;
				if (minX == Float.MAX_VALUE) {
					// Fallback if no lane regions found
					laneX = actuallyPortrait ? 0 : lanes[0].region.x;
					laneY = 0;
					laneW = actuallyPortrait ? skinW : lanes[0].region.width * lanes.length;
					laneH = actuallyPortrait ? lanes[0].region.width * lanes.length : skinH;
				} else {
					laneX = minX;
					laneY = minY;
					laneW = maxX - minX;
					laneH = maxY - minY;

					// In landscape mode, ensure it covers full height
					if (!actuallyPortrait && laneH < skinH * 0.5f) {
						laneY = 0;
						laneH = skinH;
					}
					// In portrait mode, ensure it covers full width
					if (actuallyPortrait && laneW < skinW * 0.5f) {
						laneX = 0;
						laneW = skinW;
					}
				}

				sprite.setColor(1f, 1f, 1f, 0.15f);
				sprite.setBlend(0); // Use alpha blending for a more natural darkened BGA look

				// Perfect alignment: Sample the sub-region of the combined BGA that matches lane position.
				// Since bgaFrame was rendered to match skin dimensions and positions, we sample using skin coordinates.
				int srcX = (int)laneX;
				int srcY = (int)laneY;
				int srcW = (int)laneW;
				int srcH = (int)laneH;

				// Convert Y-down skin coordinates to Y-up FBO texture coordinates
				int fboSrcY = bgaFrame.getHeight() - srcY - srcH;
				// Clip to texture bounds to avoid GL errors
				srcX = Math.max(0, Math.min(srcX, bgaFrame.getWidth() - 1));
				fboSrcY = Math.max(0, Math.min(fboSrcY, bgaFrame.getHeight() - 1));
				srcW = Math.max(1, Math.min(srcW, bgaFrame.getWidth() - srcX));
				srcH = Math.max(1, Math.min(srcH, bgaFrame.getHeight() - fboSrcY));

				TextureRegion bgaRegion = new TextureRegion(bgaFrame, srcX, fboSrcY, srcW, srcH);
				bgaRegion.flip(false, true); // FBO textures are Y-up, must flip for Y-down display
				sprite.draw(bgaRegion, laneX, laneY, laneW, laneH);
			}
		}

		// draw section line
		final double orgNotePos = notePos;
		final boolean enableConstant = playconfig.isEnableConstant() && (main.getState() != BMSPlayer.STATE_PRACTICE);
		final int baseduration = playconfig.getDuration();
		final float alphaLimit =  playconfig.getConstantFadeinTime() * 1000;
		for (int i = pos; i < timelines.length && notePos <= hu; i++) {
			final TimeLine tl = timelines[i];
			// Reset to full opacity at the beginning of each timeline iteration
			// This fixes the bug where alpha from a previous continue'd timeline affects the next timeline
			sprite.setColor(1f, 1f, 1f, 1f);
			if (tl.getMicroTime() >= microtime) {
				if (enableConstant) {
					final long targetTime = microtime + (baseduration * 1000);
					float alpha = computeConstantFadeAlpha(tl, targetTime, alphaLimit);
					if (alpha < 0) {
						continue;
					}
					sprite.setColor(1f, 1f, 1f, alpha);
				}

				notePos += computeNotePosDelta(tl, i > 0 ? timelines[i - 1] : null, microtime, rxhs);
				if (showTimeline && (i > 0 && (tl.getTime() / 1000) > (timelines[i - 1].getTime() / 1000))) {
					for (SkinImage line : skin.getTimeLine()) {
						if (isPortrait) {
							line.draw(sprite, jtime, main, (float) (notePos - (lanes[0].region.x + 40)) + 0.01f, 0);
						} else {
							line.draw(sprite, jtime, main, 0, (float) (notePos - lanes[0].region.y) + 0.01f);
						}
					}
					for (Rectangle r : playerr) {
						// TODO 数值もスキンベースへ移行
						if(font != null) {
							if (isPortrait) {
								sprite.draw(font, cachedTimeText[i], (float) (hu - notePos), r.y + r.height - 4, COLOR_TIME_TEXT);
							} else {
								sprite.draw(font, cachedTimeText[i], r.x + 4, (float) (notePos + 20), COLOR_TIME_TEXT);
							}
						}
					}
				}

				if (config.isBpmguide() || showTimeline) {
					if (tl.getBPM() != nbpm) {
						for (SkinImage line : skin.getBPMLine()) {
							if (isPortrait) {
								line.draw(sprite, jtime, main, (float) (notePos - (lanes[0].region.x + 40)) + 0.01f, 0);
							} else {
								line.draw(sprite, jtime, main, 0, (float) (notePos - lanes[0].region.y) + 0.01f);
							}
						}
						for (Rectangle r : playerr) {
							// TODO 数値もスキンベースへ移行
							if(font != null) {
								if (isPortrait) {
									sprite.draw(font, cachedBpmText[i], (float) (hu - notePos), r.y + r.height / 2, COLOR_BPM_TEXT);
								} else {
									sprite.draw(font, cachedBpmText[i], r.x + r.width / 2, (float) (notePos + 20), COLOR_BPM_TEXT);
								}
							}
						}

					}
					if (tl.getStop() > 0) {
						for (SkinImage line : skin.getStopLine()) {
							if (isPortrait) {
								line.draw(sprite, jtime, main, (float) (notePos - (lanes[0].region.x + 40)) + 0.01f, 0);
							} else {
								line.draw(sprite, jtime, main, 0, (float) (notePos - lanes[0].region.y) + 0.01f);
							}
						}
						for (Rectangle r : playerr) {
							// TODO 数値もスキンベースへ移行
							if(font != null) {
								if (isPortrait) {
									sprite.draw(font, cachedStopText[i], (float) (hu - notePos), r.y + r.height / 2, COLOR_STOP_TEXT);
								} else {
									sprite.draw(font, cachedStopText[i], r.x + r.width / 2, (float) (notePos + 20), COLOR_STOP_TEXT);
								}
							}
						}
					}

				}
				if (tl.getSectionLine()) {
					for (SkinImage line : skin.getLine()) {
						if (isPortrait) {
							line.draw(sprite, jtime, main, (float) (notePos - (lanes[0].region.x + 40)) + 0.01f, 0);
						} else {
							line.draw(sprite, jtime, main, 0, (float) (notePos - lanes[0].region.y) + 0.01f);
						}
					}
				}
				nbpm = tl.getBPM();
			} else if (pos == i - 1) {
				boolean b = true;
				for (int lane = 0; lane < lanes.length; lane++) {
					final Note note = tl.getNote(lane);
					if (note != null && ((note instanceof LongNote ln && (ln.isEnd() ? ln : ln.getPair()).getMicroTime() >= microtime)
							|| (config.isShowpastnote() && note instanceof NormalNote && note.getState() == 0))) {
						b = false;
						break;
					}
				}
				if (b) {
					pos = i;
				}
			}
		}

		sprite.setColor(Color.WHITE);
		sprite.setBlend(0);
		sprite.setType(SkinObjectRenderer.TYPE_NORMAL);
		notePos = orgNotePos;
		final long now = main.timer.getNowTime();

		for (int i = pos; i < timelines.length && notePos <= hu; i++) {
			final TimeLine tl = timelines[i];
			// Reset to full opacity at the beginning of each timeline iteration
			// This fixes the bug where alpha from a previous continue'd timeline affects the next timeline
			sprite.setColor(1f, 1f, 1f, 1f);
			if (enableConstant) {
				final long targetTime = microtime + (baseduration * 1000);
				float alpha = computeConstantFadeAlpha(tl, targetTime, alphaLimit);
				if (alpha < 0) {
					continue;
				}
				sprite.setColor(1f, 1f, 1f, alpha);
			}

			if (tl.getMicroTime() >= microtime) {
				notePos += computeNotePosDelta(tl, i > 0 ? timelines[i - 1] : null, microtime, rxhs);
			}
			// ノート描画
			for (int lane = 0; lane < lanes.length; lane++) {
				final float scale = lanes[lane].scale;
				final Note note = tl.getNote(lane);
				if (note != null) {
					// 4分音符タイミングでノートを拡大する
					float dstx, dsty, dstw, dsth;
					if (isPortrait) {
						// Portrait: notes fall horizontally (x decreases from right to left)
						// After 270 CCW rotation around center:
						// Visual width along Buffer X = Asset Height (thickness)
						// Visual height along Buffer Y = Asset Width (lane width)
						dstw = lanes[lane].region.height + offsetH;
						dsth = scale + offsetW;

						// Compensation for 270 CCW rotation around center:
						// Center sprite exactly on notePos.
						dstx = (float) notePos - dstw / 2f;
						dsty = lanes[lane].region.y + offsetY + (dstw - dsth) / 2f;
					} else {
						// Landscape: notes fall vertically (y increases from bottom to top)
						dstx = lanes[lane].region.x + offsetX;
						dsty = (float) notePos + offsetY - offsetH / 2;
						dstw = lanes[lane].region.width + offsetW;
						dsth = scale + offsetH;
					}
					if(skin.getNoteExpansionRate()[0] != 100 || skin.getNoteExpansionRate()[1] != 100) {
						if((now - main.getNowQuarterNoteTime()) < noteExpansionTime) {
							dstw *= 1 + (skin.getNoteExpansionRate()[0]/100.0f - 1) * (now - main.getNowQuarterNoteTime()) / noteExpansionTime;
							dsth *= 1 + (skin.getNoteExpansionRate()[1]/100.0f - 1) * (now - main.getNowQuarterNoteTime()) / noteExpansionTime;
							if (isPortrait) {
								dstx -= (dstw - scale) / 2;
								dsty -= (dsth - lanes[lane].region.width) / 2;
							} else {
								dstx -= (dstw - lanes[lane].region.width) / 2;
								dsty -= (dsth - scale) / 2;
							}
						} else if((now - main.getNowQuarterNoteTime()) >= noteExpansionTime && (now - main.getNowQuarterNoteTime()) <= (noteExpansionTime + noteContractionTime)) {
							dstw *= 1 + (skin.getNoteExpansionRate()[0]/100.0f - 1) * (noteContractionTime - (now - main.getNowQuarterNoteTime() - noteExpansionTime)) / noteContractionTime;
							dsth *= 1 + (skin.getNoteExpansionRate()[1]/100.0f - 1) * (noteContractionTime - (now - main.getNowQuarterNoteTime() - noteExpansionTime)) / noteContractionTime;
							if (isPortrait) {
								dstx -= (dstw - scale) / 2;
								dsty -= (dsth - lanes[lane].region.width) / 2;
							} else {
								dstx -= (dstw - lanes[lane].region.width) / 2;
								dsty -= (dsth - scale) / 2;
							}
						}
					}
					// 可见性裁剪：跳过视口外的音符
					if (dsty + dsth < visibleViewport.y || dsty > visibleViewport.y + visibleViewport.height ||
						dstx + dstw < visibleViewport.x || dstx > visibleViewport.x + visibleViewport.width) {
						continue;
					}
					if (note instanceof NormalNote) {
						// draw normal note
						TextureRegion s;
						if (config.isMarkprocessednote() && note.getState() != 0 && lanes[lane].processedImage != null) {
							s = lanes[lane].processedImage;
						} else {
							s = lanes[lane].noteImage;
						}
						// Fallback: if preferred is null, use the other one
						if (s == null) {
							s = lanes[lane].noteImage != null ? lanes[lane].noteImage : lanes[lane].processedImage;
						}
						if (lanes[lane].dstnote2 != Integer.MIN_VALUE) {
							if (tl.getMicroTime() >= microtime && (note.getState() == 0 || note.getState() >= 4)) {
		// 								Gdx.app.log("NoteDebug", "NormalNote 1: noteImage=" + (lanes[lane].noteImage != null) + ", processedImage=" + (lanes[lane].processedImage != null) + ", selected s=" + (s != null));
		// 								Gdx.app.log("NoteDebug", "Camera Viewport - laneRegion: y=" + lanes[lane].region.y + ", h=" + lanes[lane].region.height + " | hu=" + hu + " hl=" + hl + " | Note: x=" + dstx + " y=" + dsty + " w=" + dstw + " h=" + dsth);
								// Force full opacity to fix alpha transparency issue
								sprite.setColor(1, 1, 1, 1f);
								if (s != null) {
									if (isPortrait) {
									sprite.draw(s, dstx + 0.01f, dsty, dstw, dsth, 0.5f, 0.5f, 270.0f);
								} else {
										sprite.draw(s, dstx + 0.01f, dsty + 0.01f, dstw, dsth);
									}
								}
							}
						} else if (tl.getMicroTime() >= microtime || (config.isShowpastnote() && note.getState() == 0)) {
		// 							Gdx.app.log("NoteDebug", "NormalNote 2: noteImage=" + (lanes[lane].noteImage != null) + ", processedImage=" + (lanes[lane].processedImage != null) + ", selected s=" + (s != null));
		// 							Gdx.app.log("NoteDebug", "Camera Viewport - laneRegion: y=" + lanes[lane].region.y + ", h=" + lanes[lane].region.height + " | hu=" + hu + " hl=" + hl + " | Note: x=" + dstx + " y=" + dsty + " w=" + dstw + " h=" + dsth);
							// Force full opacity to fix alpha transparency issue
							sprite.setColor(1, 1, 1, 1f);
							if (s != null) {
								if (isPortrait) {
								sprite.draw(s, dstx + 0.01f, dsty, dstw, dsth, 0.5f, 0.5f, 270.0f);
							} else {
									sprite.draw(s, dstx + 0.01f, dsty + 0.01f, dstw, dsth);
								}
							}
						}
					} else if (note instanceof LongNote ln) {
						if (!ln.isEnd() && ln.getPair().getMicroTime() >= microtime) {
							// if (((LongNote) note).getEnd() == null) {
							// Logger.getGlobal().warning(
							// "LN終端がなく、モデルが正常に表示されません。LN開始時間:"
							// + ((LongNote) note)
							// .getStart()
							// .getTime());
							// } else {
							double dy = 0;
							TimeLine prevtl = tl;
							for (int j = i + 1; j < timelines.length
									&& prevtl.getSection() != ln.getPair().getSection(); j++) {
								final TimeLine nowtl = timelines[j];
								if (nowtl.getMicroTime() >= microtime) {
									if (prevtl.getMicroTime() + prevtl.getMicroStop() > microtime) {
										dy += (nowtl.getSection() - prevtl.getSection()) * prevtl.getScroll() * rxhs;
									} else {
										dy += (nowtl.getSection() - prevtl.getSection()) * prevtl.getScroll()
												* (nowtl.getMicroTime() - microtime)
												/ (nowtl.getMicroTime() - prevtl.getMicroTime() - prevtl.getMicroStop())
												* rxhs;
									}
								}
								prevtl = nowtl;
							}
					if (dy > 0) {
						if (isPortrait) {
							// Draw centered on notePos for perfect alignment with visual judge line
							this.drawLongNote(sprite, lanes[lane].longImage, (float) notePos + 0.01f, lanes[lane].region.y + offsetY, dstw, (float) dy, scale, lane, ln, isPortrait);
						} else {
							final float dscale = (dsth > scale ? (dsth - scale) / 2 : 0);
							this.drawLongNote(sprite, lanes[lane].longImage, dstx, (float) (dsty + dy), dstw,
									(float) (dsty < (lanes[lane].region.y - dscale) ? dsty - (lanes[lane].region.y - dscale) : dy), dsth, lane,
									ln, isPortrait);
						}
					}

							// System.out.println(dy);
						}
					} else if (note instanceof MineNote) {
						// draw mine note
						if (tl.getMicroTime() >= microtime) {
							if (lanes[lane].mineImage != null) {
								sprite.setColor(1, 1, 1, 1f);
								if (isPortrait) {
									sprite.draw(lanes[lane].mineImage, dstx, dsty, dstw, dsth, 0.5f, 0.5f, 270.0f);
								} else {
									sprite.draw(lanes[lane].mineImage, dstx + 0.01f, dsty + 0.01f, dstw, dsth);
								}
							}
						}
					}
				}
				// hidden note
				if (config.isShowhiddennote() && tl.getMicroTime() >= microtime) {
					final Note hnote = tl.getHiddenNote(lane);
					if (hnote != null) {
						if (lanes[lane].hiddenImage != null) {
							sprite.setColor(1, 1, 1, 1f);
							if (isPortrait) {
								// In portrait: rotate 270 for correct orientation.
								// width (across) = region.height, height (along) = scale
								sprite.draw(lanes[lane].hiddenImage, (float) notePos, lanes[lane].region.y, scale, lanes[lane].region.height, 0.5f, 0.5f, 270.0f);
							} else {
								sprite.draw(lanes[lane].hiddenImage, lanes[lane].region.x, (float) notePos, lanes[lane].region.width, scale);
							}
						}
					}
				}
			}
		}
		// System.out.println("time :" + ltime + " y :" + yy + " real time : "
		// + (ltime * (hu - hl) / yy));

		//PMS見逃しPOOR描画
		// TODO dstnote2をレーン毎に変更
		if (lanes[0].dstnote2 != Integer.MIN_VALUE) {
			//遅BADからノースピの速度で落下
			final long badTime = Math.abs( main.getJudgeManager().getJudgeTable(false)[2][0] );
			double stopTime;
			double orgNotePos2 = lanes[0].dstnote2;
			if(orgNotePos2 < -lanes[0].region.width) orgNotePos2 = -lanes[0].region.width;
			if(isPortrait) {
				if(orgNotePos2 > hu) orgNotePos2 = hu;
			} else {
				if(orgNotePos2 > orgNotePos) orgNotePos2 = orgNotePos;
			}
			final double rxhs2 = (hu - hl);
			int nowPos = timelines.length - 1;
			for (int i = pos; i < timelines.length; i++) {
				final TimeLine tl = timelines[i];
				if (tl.getMicroTime() >= microtime) {
					nowPos = i;
					break;
				}
			}
			for (int i = nowPos; i >= 0 && (isPortrait ? notePos <= orgNotePos2 : notePos >= orgNotePos2); i--) {
				final TimeLine tl = timelines[i];
				notePos = orgNotePos;
				if (i + 1 < timelines.length) {
					int j;
					for (j = i; j + 1 < timelines.length && timelines[j + 1].getMicroTime() < microtime; j++) {
						if(timelines[j + 1].getMicroTime() > tl.getMicroTime() + tl.getMicroStop() + badTime) {
							stopTime = Math.max(tl.getMicroTime() + tl.getMicroStop() + badTime - timelines[j].getMicroTime() - timelines[j].getMicroStop(), 0);
							if (isPortrait) {
								notePos += (timelines[j + 1].getMicroTime() - timelines[j].getMicroTime() - timelines[j].getMicroStop() - stopTime) * rxhs2 * timelines[j].getBPM() / 240000000;
							} else {
								notePos -= (timelines[j + 1].getMicroTime() - timelines[j].getMicroTime() - timelines[j].getMicroStop() - stopTime) * rxhs2 * timelines[j].getBPM() / 240000000;
							}
							//4分の画面上での長さ rxhs2 / 4 [pixel] 4分の時間 60 / BPM [second] 落下速度 rxhs2 * BPM / 240 [pixel/second]
						}
					}
					if(timelines[j].getMicroTime() + timelines[j].getMicroStop() < microtime) {
						if(microtime > tl.getMicroTime() + tl.getMicroStop() + badTime) {
							stopTime = Math.max(tl.getMicroTime() + tl.getMicroStop() + badTime - timelines[j].getMicroTime() - timelines[j].getMicroStop(), 0);
							if (isPortrait) {
								notePos += (microtime - timelines[j].getMicroTime() - timelines[j].getMicroStop() - stopTime) * rxhs2 * timelines[j].getBPM() / 240000000;
							} else {
								notePos -= (microtime - timelines[j].getMicroTime() - timelines[j].getMicroStop() - stopTime) * rxhs2 * timelines[j].getBPM() / 240000000;
							}
						}
					}
				} else {
					if(tl.getMicroTime() + tl.getMicroStop() < microtime) {
						if(microtime > tl.getMicroTime() + tl.getMicroStop() + badTime) {
							stopTime = Math.max(tl.getMicroTime() + tl.getMicroStop() + badTime - tl.getMicroTime() - tl.getMicroStop(), 0);
							if (isPortrait) {
								notePos -= (microtime - tl.getMicroTime() - tl.getMicroStop() - stopTime) * rxhs2 * tl.getBPM() / 240000000;
							} else {
								notePos -= (microtime - tl.getMicroTime() - tl.getMicroStop() - stopTime) * rxhs2 * tl.getBPM() / 240000000;
							}
						}
					}
				}
				// ノート描画
				for (int lane = 0; lane < lanes.length; lane++) {
					final float scale = lanes[lane].scale;
					final Note note = tl.getNote(lane);
					if (note != null) {
						if (note instanceof NormalNote) {
							// draw normal note
							//4分のタイミングでノートを拡大する
							float dstx, dsty, dstw, dsth;
							if (isPortrait) {
								dstx = (float) notePos;
								dsty = lanes[lane].region.y;
								dstw = lanes[lane].region.height;
								dsth = scale;
							} else {
								dstx = lanes[lane].region.x;
								dsty = (float) notePos;
								dstw = lanes[lane].region.width;
								dsth = scale;
							}
							if(skin.getNoteExpansionRate()[0] != 100 || skin.getNoteExpansionRate()[1] != 100) {
								if((now - main.getNowQuarterNoteTime()) < noteExpansionTime) {
									dstw *= 1 + (skin.getNoteExpansionRate()[0]/100.0f - 1) * (now - main.getNowQuarterNoteTime()) / noteExpansionTime;
									dsth *= 1 + (skin.getNoteExpansionRate()[1]/100.0f - 1) * (now - main.getNowQuarterNoteTime()) / noteExpansionTime;
									if (isPortrait) {
										// In portrait: dstw is lane width (buffer Y), dsth is thickness (buffer X)
										// Shift across lane (dstw) and along lane (dsth)
										dstx -= (dsth - scale) / 2;
										dsty -= (dstw - lanes[lane].region.height) / 2;
									} else {
										dstx -= (dstw - lanes[lane].region.width) / 2;
										dsty -= (dsth - scale) / 2;
									}
								} else if((now - main.getNowQuarterNoteTime()) >= noteExpansionTime && (now - main.getNowQuarterNoteTime()) <= (noteExpansionTime + noteContractionTime)) {
									dstw *= 1 + (skin.getNoteExpansionRate()[0]/100.0f - 1) * (noteContractionTime - (now - main.getNowQuarterNoteTime() - noteExpansionTime)) / noteContractionTime;
									dsth *= 1 + (skin.getNoteExpansionRate()[1]/100.0f - 1) * (noteContractionTime - (now - main.getNowQuarterNoteTime() - noteExpansionTime)) / noteContractionTime;
									if (isPortrait) {
										dstx -= (dsth - scale) / 2;
										dsty -= (dstw - lanes[lane].region.height) / 2;
									} else {
										dstx -= (dstw - lanes[lane].region.width) / 2;
										dsty -= (dsth - scale) / 2;
									}
								}
							}
							TextureRegion s;
							if (config.isMarkprocessednote() && note.getState() != 0 && lanes[lane].processedImage != null) {
								s = lanes[lane].processedImage;
							} else {
								s = lanes[lane].noteImage;
							}
							// Fallback: if preferred is null, use the other one
							if (s == null) {
								s = lanes[lane].noteImage != null ? lanes[lane].noteImage : lanes[lane].processedImage;
							}
							// Final fallback: if everything is null (texture loading failed), don't leave s as null
							if (s == null) {
		// 								Gdx.app.log("NoteDebug", "WARNING: both noteImage and processedImage are null! lane=" + lane);
							}
							boolean drawCondition = ((note.getState() == 0 || note.getState() >= 4) && tl.getMicroTime() <= microtime) && (notePos >= orgNotePos2);
							if (drawCondition) {
								sprite.setColor(1, 1, 1, 1f);
									if (isPortrait) {
										// Align leading edge to notePos and rotate around center
										// Pre-rotation x_pre = notePos - dstw/2 + dsth/2
										float poorX = (float) notePos - dstw / 2f + dsth / 2f;
										float poorY = dsty;
										if (s != null) sprite.draw(s, poorX, poorY, dstw, dsth, 0.5f, 0.5f, 270.0f);
									} else {
									if (notePos > orgNotePos) {
										if (s != null) sprite.draw(s, dstx, (float) (orgNotePos - (dsth - scale) / 2), dstw, dsth);
									} else if (s != null) {
										sprite.draw(s, dstx, dsty, dstw, dsth);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	public double getNowBPM() {
		return nowbpm;
	}

	public double getMinBPM() {
		return minbpm;
	}

	public double getMaxBPM() {
		return maxbpm;
	}

	public double getMainBPM() {
		return mainbpm;
	}

	final private void drawLongNote(SkinObjectRenderer sprite, TextureRegion[] longImage, float x, float y, float width, float height, float scale,
			int lane, LongNote ln, boolean isPortrait) {
		if (longImage == null || longImage.length == 0) return;

		// Force full opacity to fix alpha transparency issue
		sprite.setColor(1, 1, 1, 1f);

		// Determine LN type indices
		int bodyIdx, headIdx, tailIdx;
		if ((model.getLntype() == BMSModel.LNTYPE_HELLCHARGENOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
				|| ln.getType() == LongNote.TYPE_HELLCHARGENOTE) {
			// HCN
			final JudgeManager judge = main.getJudgeManager();
			bodyIdx = judge.getProcessingLongNote(lane) == ln.getPair() ? 6
					: (judge.getPassingLongNote(lane) == ln && ln.getState() != 0
							? (judge.getHellChargeJudge(lane) ? 8 : 9) : 7);
			headIdx = 4;
			tailIdx = 5;
		} else {
			// CN or LN
			bodyIdx = main.getJudgeManager().getProcessingLongNote(lane) == ln.getPair() ? 2 : 3;
			headIdx = 0;
			tailIdx = 1;
		}

		if (isPortrait) {
			// Portrait mode: notes fall horizontally (x decreases from right to left)
			// Leading edge of head is at x. Tail follows at x + height.
			float W = width;  // Lane width (vertical on screen)
			float L = height; // LN length (horizontal on screen)
			float T = scale;  // Note thickness
			float laneCenterY = y + (W - T) / 2f;

			// Visual Head (Hit side) - Draw at x.
			// Matches landscape logic where tailIdx is drawn at head position (y - height).
			if (tailIdx < longImage.length && longImage[tailIdx] != null) {
				sprite.draw(longImage[tailIdx], x + 0.5f * T - 0.5f * W, laneCenterY, W, T, 0.5f, 0.5f, 270.0f);
			}

			// Visual Body - Draw from x + T to x + L.
			if (bodyIdx < longImage.length && longImage[bodyIdx] != null) {
				float bodyL = L - T;
				if (bodyL > 0.01f) {
					sprite.draw(longImage[bodyIdx], x + T + 0.5f * bodyL - 0.5f * W, y + (W - bodyL) / 2f, W, bodyL, 0.5f, 0.5f, 270.0f);
				}
			}

			// Visual Tail (End side) - Draw at x + L.
			// Matches landscape logic where headIdx is drawn at tail position (y).
			if (headIdx < longImage.length && longImage[headIdx] != null) {
				sprite.draw(longImage[headIdx], x + L + 0.5f * T - 0.5f * W, laneCenterY, W, T, 0.5f, 0.5f, 270.0f);
			}
		} else {
			// Landscape mode: notes fall vertically (y increases from bottom to top)
			// Unified drawing: if head/tail materials exist, always draw them at endpoints

			// Draw Body (bodyIdx)
			if (bodyIdx < longImage.length && longImage[bodyIdx] != null) {
				sprite.draw(longImage[bodyIdx], x, y - height + scale, width, height - scale);
			}

			// Draw Head at start position (headIdx)
			if (headIdx >= 0 && headIdx < longImage.length && longImage[headIdx] != null) {
				sprite.draw(longImage[headIdx], x, y, width, scale);
			}

			// Draw Tail at end position (tailIdx)
			if (tailIdx < longImage.length && longImage[tailIdx] != null) {
				sprite.draw(longImage[tailIdx], x, y - height, width, scale);
			}
		}
	}


	public void dispose() {
		// Font is now globally cached in MainController - don't dispose it here
		// if (font != null) {
		// 	font.dispose();
		// 	font = null;
		// }
	}
}
