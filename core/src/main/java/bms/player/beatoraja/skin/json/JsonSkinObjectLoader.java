package bms.player.beatoraja.skin.json;

import bms.player.beatoraja.play.SkinGauge;
import bms.player.beatoraja.result.SkinGaugeGraphObject;
import bms.player.beatoraja.select.MusicSelectSkin;
import bms.player.beatoraja.select.SkinDistributionGraph;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.SkinObject.SkinOffset;
import bms.player.beatoraja.skin.json.JSONSkinLoader.SourceData;
import bms.player.beatoraja.skin.property.StringProperty;
import bms.player.beatoraja.skin.property.StringPropertyFactory;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

import java.io.File;

/**
 * JSONスキンオブジェクトローダー
 *
 * @author exch
 *
 * @param <S>
 */
public abstract class JsonSkinObjectLoader<S extends Skin> {

	protected final JSONSkinLoader loader;

	public JsonSkinObjectLoader(JSONSkinLoader loader) {
		this.loader = loader;
	}

	public abstract S getSkin(SkinHeader header);

	public SkinObject loadSkinObject(S skin, JsonSkin.Skin sk, JsonSkin.Destination dst, File p) {
		SkinObject obj = null;

		for (JsonSkin.Image img : sk.image) {
			if (dst.id.equals(img.id)) {
				Object data = loader.getSource(img.src, p);

				if(data instanceof SkinSourceMovie) {
					obj = new SkinImage((SkinSourceMovie)data);
				} else if(data instanceof Texture) {
					Texture tex = (Texture) data;
					if (img.len > 1) {
						TextureRegion[] srcimg = getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx,
								img.divy);
						TextureRegion[][] tr = new TextureRegion[img.len][];
						for (int i = 0; i < tr.length; i++) {
							tr[i] = new TextureRegion[srcimg.length / img.len];
							for (int j = 0; j < tr[i].length; j++) {
								tr[i][j] = srcimg[i * tr[i].length + j];
							}
						}
						SkinImage si = new SkinImage(tr, img.timer, img.cycle, img.ref);
						obj = si;
					} else {
						obj = new SkinImage(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
								img.timer, img.cycle);
					}
				}

				if (obj != null && img.act != null) {
					obj.setClickevent(img.act);
					obj.setClickeventType(img.click);
				}
				return obj;
			}
		}
		for (JsonSkin.ImageSet imgs : sk.imageset) {
			if (dst.id.equals(imgs.id)) {
				SkinSourceImage[] sources = new SkinSourceImage[imgs.images.length];
				for (int index = 0; index < imgs.images.length; index++) {
					for (JsonSkin.Image img : sk.image) {
						if (img.id.equals(imgs.images[index])) {
							Object data = loader.getSource(img.src, p);

							if(data instanceof Texture) {
								Texture tex = (Texture) data;
								sources[index] = new SkinSourceImage(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
										img.timer, img.cycle);
							}
							break;
						}
					}
				}

				obj = imgs.value != null ? new SkinImage(sources, imgs.value) : new SkinImage(sources, imgs.ref);
				if (imgs.act != null) {
					obj.setClickevent(imgs.act);
					obj.setClickeventType(imgs.click);
				}
				return obj;
			}
		}
		for (JsonSkin.Value value : sk.value) {
			if (dst.id.equals(value.id)) {
				Texture tex = getTexture(value.src, p);
				TextureRegion[] images = getSourceImage(tex, value.x, value.y, value.w, value.h, value.divx,
						value.divy);
				if (images.length % 24 == 0) {
					TextureRegion[][] pn = new TextureRegion[images.length / 24][];
					TextureRegion[][] mn = new TextureRegion[images.length / 24][];

					for (int j = 0; j < pn.length; j++) {
						pn[j] = new TextureRegion[12];
						mn[j] = new TextureRegion[12];

						for (int i = 0; i < 12; i++) {
							pn[j][i] = images[j * 24 + i];
							mn[j][i] = images[j * 24 + i + 12];
						}
					}

					SkinNumber num = null;
					if(value.value != null) {
						num = new SkinNumber(pn, mn, value.timer, value.cycle, value.digit, value.zeropadding, value.space,
								value.value, value.align);
					} else {
						num = new SkinNumber(pn, mn, value.timer, value.cycle, value.digit, value.zeropadding, value.space,
								value.ref, value.align);
					}

					if(value.offset != null) {
						SkinOffset[] offsets = new SkinOffset[value.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = value.offset[i].x;
							offsets[i].y = value.offset[i].y;
							offsets[i].w = value.offset[i].w;
							offsets[i].h = value.offset[i].h;
						}
						num.setOffsets(offsets);
					}
					obj = num;
				} else {
					int d = images.length % 10 == 0 ? 10 : 11;

					TextureRegion[][] nimages = new TextureRegion[value.divx * value.divy / d][d];
					for (int i = 0; i < d; i++) {
						for (int j = 0; j < value.divx * value.divy / d; j++) {
							nimages[j][i] = images[j * d + i];
						}
					}

					SkinNumber num = null;
					if(value.value != null) {
						num = new SkinNumber(nimages, value.timer, value.cycle, value.digit,
								d > 10 ? 2 : value.padding, value.space, value.value, value.align);
					} else {
						num = new SkinNumber(nimages, value.timer, value.cycle, value.digit,
								d > 10 ? 2 : value.padding, value.space, value.ref, value.align);
					}
					if(value.offset != null) {
						SkinOffset[] offsets = new SkinOffset[value.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = value.offset[i].x;
							offsets[i].y = value.offset[i].y;
							offsets[i].w = value.offset[i].w;
							offsets[i].h = value.offset[i].h;
						}
						num.setOffsets(offsets);
					}
					obj = num;
				}
				return obj;
			}
		}

		for (JsonSkin.FloatValue fv : sk.floatvalue) {
			if (dst.id.equals(fv.id)) {
				Texture tex = getTexture(fv.src, p);
				TextureRegion[] images = getSourceImage(tex, fv.x, fv.y, fv.w, fv.h, fv.divx, fv.divy);
				// 26の倍数:符号あり、+-別image
				// 24の倍数:符号なし、+-別image
				// 22の倍数:符号なし、+-別image、裏ゼロ共有
				// 12の倍数:符号なし、+-同image
				// 11の倍数:符号なし、+-同image、裏ゼロ共有
				// それ以外:12の倍数として処理
				if (images.length % 26 == 0) {
					TextureRegion[][] pn = new TextureRegion[images.length / 26][];
					TextureRegion[][] mn = new TextureRegion[images.length / 26][];

					for (int j = 0; j < pn.length; j++) {
						pn[j] = new TextureRegion[13];
						mn[j] = new TextureRegion[13];

						for (int i = 0; i < 13; i++) {
							pn[j][i] = images[j * 26 + i];
							mn[j][i] = images[j * 26 + i + 13];
						}
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, fv.isSignvisible, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, fv.isSignvisible, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}

					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				else if (images.length % 24 == 0) {
					TextureRegion[][] pn = new TextureRegion[images.length / 24][];
					TextureRegion[][] mn = new TextureRegion[images.length / 24][];

					for (int j = 0; j < pn.length; j++) {
						pn[j] = new TextureRegion[12];
						mn[j] = new TextureRegion[12];

						for (int i = 0; i < 12; i++) {
							pn[j][i] = images[j * 24 + i];
							mn[j][i] = images[j * 24 + i + 12];
						}
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}

					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				else if (images.length % 22 == 0) {
					TextureRegion[][] pn = new TextureRegion[images.length / 22][];
					TextureRegion[][] mn = new TextureRegion[images.length / 22][];

					for (int j = 0; j < pn.length; j++) {
						pn[j] = new TextureRegion[12];
						mn[j] = new TextureRegion[12];
						for (int i = 0; i < 10; i++) {
							pn[j][i] = images[j * 22 + i];
							mn[j][i] = images[j * 22 + i + 11];
						}
						pn[j][10] = images[j * 22 + 0]; // +裏ゼロ共有
						pn[j][11] = images[j * 22 + 10];// +小数点
						mn[j][10] = images[j * 22 + 11];// -裏
						mn[j][11] = images[j * 22 + 21];// -小
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(pn, mn, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}

					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				else if (images.length % 12 == 0) {
					TextureRegion[][] nimage = new TextureRegion[images.length / 12][];

					for (int j = 0; j < nimage.length; j++) {
						nimage[j] = new TextureRegion[12];
						for (int i = 0; i < 12; i++) {
							nimage[j][i] = images[j * 12 + i];
						}
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(nimage, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(nimage, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}

					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				else if (images.length % 11 == 0) {
					TextureRegion[][] nimage = new TextureRegion[images.length / 11][];

					for (int j = 0; j < nimage.length; j++) {
						nimage[j] = new TextureRegion[12];
						for (int i = 0; i < 10; i++) {
							nimage[j][i] = images[j * 11 + i];
						}
						nimage[j][10] = images[j * 11 + 0]; // 裏ゼロ共有
						nimage[j][11] = images[j * 11 + 10];// 小数点
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(nimage, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(nimage, fv.timer, fv.cycle, fv.iketa, fv.fketa, false, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}

					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				else { // 12で割り切れるものとして処理する
					final int d = 12;

					TextureRegion[][] nimages = new TextureRegion[fv.divx * fv.divy / d][d];
					for (int i = 0; i < d; i++) {
						for (int j = 0; j < fv.divx * fv.divy / d; j++) {
							nimages[j][i] = images[j * d + i];
						}
					}

					SkinFloat fnum = null;
					if(fv.value != null) {
						fnum = new SkinFloat(nimages, fv.timer, fv.cycle, fv.iketa, fv.fketa,
								false, fv.align, fv.zeropadding, fv.space, fv.value, fv.gain);
					} else {
						fnum = new SkinFloat(nimages, fv.timer, fv.cycle, fv.iketa, fv.fketa,
								false, fv.align, fv.zeropadding, fv.space, fv.ref, fv.gain);
					}
					if(fv.offset != null) {
						SkinOffset[] offsets = new SkinOffset[fv.offset.length];
						for(int i = 0;i < offsets.length;i++) {
							offsets[i] = new SkinOffset();
							offsets[i].x = fv.offset[i].x;
							offsets[i].y = fv.offset[i].y;
							offsets[i].w = fv.offset[i].w;
							offsets[i].h = fv.offset[i].h;
						}
						fnum.setOffsets(offsets);
					}
					obj = fnum;
				}
				return obj;
			}
		}

		// text
		for (JsonSkin.Text text : sk.text) {
			if (dst.id.equals(text.id)) {
				if (text.ref == SkinProperty.STRING_SEARCHWORD) {
					JsonSkin.Animation a = dst.dst[0];
					Rectangle r = new Rectangle(a.x * ((float)loader.dstr.width / sk.w),
							a.y * ((float)loader.dstr.height / sk.h), a.w * ((float)loader.dstr.width / sk.w),
							a.h * ((float)loader.dstr.height / sk.h));
					((MusicSelectSkin) skin).setSearchTextRegion(r);
				} else {
					obj = createText(text, p);
				}
				return obj;
			}
		}
		// slider
		for (JsonSkin.Slider img : sk.slider) {
			if (dst.id.equals(img.id)) {
				Texture tex = getTexture(img.src, p);

				if(tex != null) {
					if(img.value != null) {
						obj = new SkinSlider(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
								img.timer, img.cycle, img.angle, (int) ((img.angle == 1 || img.angle == 3
										? ((float)loader.dstr.width / sk.w) : ((float)loader.dstr.height / sk.h)) * img.range),
								img.value, img.event);
					} else if(img.isRefNum) {
						obj = new SkinSlider(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
								img.timer, img.cycle, img.angle, (int) ((img.angle == 1 || img.angle == 3
										? ((float)loader.dstr.width / sk.w) : ((float)loader.dstr.height / sk.h)) * img.range),
								img.type, img.min, img.max);
					} else {
						obj = new SkinSlider(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
								img.timer, img.cycle, img.angle, (int) ((img.angle == 1 || img.angle == 3
										? ((float)loader.dstr.width / sk.w) : ((float)loader.dstr.height / sk.h)) * img.range),
								img.type, img.changeable);
					}
				}
				return obj;
			}
		}
		// graph
		for (JsonSkin.Graph img : sk.graph) {
			if (dst.id.equals(img.id)) {
				if (img.type < 0) {
					Texture tex = getTexture(img.src, p);
					if(tex != null) {
						TextureRegion[] images = getSourceImage(tex, img.x, img.y, img.w, img.h,
								img.divx, img.divy);
						final int len = img.type == -1 ? 11 : 28;
						TextureRegion[][] imgs = new TextureRegion[len][images.length / len];
						for(int j = 0 ;j < len;j++) {
							for(int i = 0 ;i < imgs[j].length;i++) {
								imgs[j][i] = images[i * len + j];
							}
						}

						final int graphtype = img.type == -1 ? 0 : 1;
						obj = new SkinDistributionGraph(graphtype,  imgs, img.timer, img.cycle);
					}
				} else {
					Texture tex = getTexture(img.src, p);
					if(tex != null) {
						if(img.value != null) {
							obj = new SkinGraph(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
									img.timer, img.cycle, img.value, img.angle);
						} else if(img.isRefNum) {
							obj = new SkinGraph(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
									img.timer, img.cycle, img.type, img.min, img.max, img.angle);
						} else {
							obj = new SkinGraph(getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy),
									img.timer, img.cycle, img.type, img.angle);
						}
					}
				}
				return obj;
			}
		}

		for (JsonSkin.GaugeGraph ggraph : sk.gaugegraph) {
			if (dst.id.equals(ggraph.id)) {
				SkinGaugeGraphObject st = null;
				if(ggraph.color != null) {
					Color[][] colors = new Color[6][4];
					for(int i = 0;i < 24 && i < ggraph.color.length;i++) {
						colors[i / 4][i % 4] = Color.valueOf(ggraph.color[i]);
					}
					st = new SkinGaugeGraphObject(colors);
				} else {
					st = new SkinGaugeGraphObject(ggraph.assistClearBGColor, ggraph.assistAndEasyFailBGColor, ggraph.grooveFailBGColor, ggraph.grooveClearAndHardBGColor, ggraph.exHardBGColor, ggraph.hazardBGColor,
							ggraph.assistClearLineColor, ggraph.assistAndEasyFailLineColor, ggraph.grooveFailLineColor, ggraph.grooveClearAndHardLineColor, ggraph.exHardLineColor, ggraph.hazardLineColor,
							ggraph.borderlineColor, ggraph.borderColor);
				}
				obj = st;
				return obj;
			}
		}
		for (JsonSkin.JudgeGraph ggraph : sk.judgegraph) {
			if (dst.id.equals(ggraph.id)) {
				SkinNoteDistributionGraph st = new SkinNoteDistributionGraph(ggraph.type, ggraph.delay, ggraph.backTexOff, ggraph.orderReverse, ggraph.noGap, ggraph.noGapX);
				obj = st;
				break;
			}
		}
		for (JsonSkin.BPMGraph ggraph : sk.bpmgraph) {
			if (dst.id.equals(ggraph.id)) {
				SkinBPMGraph st = new SkinBPMGraph(ggraph.delay, ggraph.lineWidth, ggraph.mainBPMColor, ggraph.minBPMColor, ggraph.maxBPMColor, ggraph.otherBPMColor, ggraph.stopLineColor, ggraph.transitionLineColor);
				return st;
			}
		}
		for (JsonSkin.HitErrorVisualizer hev : sk.hiterrorvisualizer) {
			if (dst.id.equals(hev.id)) {
				SkinHitErrorVisualizer st = new SkinHitErrorVisualizer(hev.width, hev.judgeWidthMillis, hev.lineWidth, hev.colorMode, hev.hiterrorMode, hev.emaMode, hev.lineColor, hev.centerColor, hev.PGColor, hev.GRColor, hev.GDColor, hev.BDColor, hev.PRColor, hev.emaColor, hev.alpha, hev.windowLength, hev.transparent, hev.drawDecay);
				return st;
			}
		}
		for (JsonSkin.TimingVisualizer tv : sk.timingvisualizer) {
			if (dst.id.equals(tv.id)) {
				SkinTimingVisualizer st = new SkinTimingVisualizer(tv.width, tv.judgeWidthMillis, tv.lineWidth, tv.lineColor, tv.centerColor, tv.PGColor, tv.GRColor, tv.GDColor, tv.BDColor, tv.PRColor, tv.transparent, tv.drawDecay);
				return st;
			}
		}

		for (JsonSkin.TimingDistributionGraph td : sk.timingdistributiongraph) {
			if (dst.id.equals(td.id)) {
				SkinTimingDistributionGraph st = new SkinTimingDistributionGraph(td.width, td.lineWidth, td.graphColor, td.averageColor, td.devColor, td.PGColor, td.GRColor, td.GDColor, td.BDColor, td.PRColor, td.drawAverage, td.drawDev);
				return st;
			}
		}

		// gauge (playskin or resultskin only)
		if (sk.gauge != null && dst.id.equals(sk.gauge.id)) {
			int[][] indexmap = null;
			switch(sk.gauge.nodes.length) {
				case 4:
					indexmap = new int[][]{{0,4,6,10,12,16,18,22,24,28,30,34},{1,5,7,11,13,17,19,23,25,29,31,35},{2,8,14,20,26,32},{3,9,15,21,27,33}};
					break;
				case 8:
					indexmap = new int[][]{{12,16,18,22},{13,17,19,23},{14,20},{15,21},
							{0,4,6,10,24,28,30,34},{1,5,7,11,25,29,31,35},{2,8,26,32},{3,9,27,33}};
					break;
				case 12:
					indexmap = new int[][]{{12,18},{13,19},{14,20},{15,21},
							{0,6,24,30},{1,7,25,31},{2,8,26,32},{3,9,27,33},
							{16,22}, {17,23}, {4, 10, 28, 34}, {5,11,29,35}};
					break;
				case 36:
					break;
			}
			TextureRegion[][] pgaugetex = new TextureRegion[36][];

			int gaugelength = 0;
			for (int i = 0; i < sk.gauge.nodes.length; i++) {
				boolean found = false;
				for (JsonSkin.Image img : sk.image) {
					if (sk.gauge.nodes[i].equals(img.id)) {
						Texture tex = getTexture(img.src, p);
						if(tex != null) {
							if(indexmap != null) {
								for(int index : indexmap[i]) {
									pgaugetex[index] = getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy);
									gaugelength = pgaugetex[index].length;
								}
							} else {
								pgaugetex[i] = getSourceImage(tex, img.x, img.y, img.w, img.h, img.divx, img.divy);
								gaugelength = pgaugetex[i].length;
							}
						} else {
							java.util.logging.Logger.getGlobal().warning("Gauge node texture load failed: node=" + sk.gauge.nodes[i] + ", src=" + img.src + ", path=" + p);
						}
						found = true;
						break;
					}
				}
				if (!found) {
					java.util.logging.Logger.getGlobal().severe("fail to open gauge node: " + sk.gauge.nodes[i] + " in " + p);
				}
			}

			TextureRegion[][] gaugetex = new TextureRegion[gaugelength][36];
			for (int i = 0; i < 36; i++) {
				for (int j = 0; j < gaugetex.length; j++) {
					gaugetex[j][i] = pgaugetex[i][j];
				}
			}

			obj = new SkinGauge(gaugetex, 0, 0, sk.gauge.parts, sk.gauge.type, sk.gauge.range, sk.gauge.cycle);

			((SkinGauge)obj).setStarttime(sk.gauge.starttime);
			((SkinGauge)obj).setEndtime(sk.gauge.endtime);
			return obj;
		}

		return obj;
	}

	protected Texture getTexture(String srcid, File p) {
		if(srcid == null) {
			java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: srcid is null");
			return null;
		}

		final SourceData data = loader.sourceMap.get(srcid);
		if(data == null) {
			java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: Source not found in sourceMap, srcid=" + srcid);
			return null;
		}

		if(data.loaded) {
			return (data.data instanceof Texture) ? (Texture)data.data : null;
		}
		// Android 下 assets 路径不能使用 java.io.File.exists()
		// Lua 皮肤中的 source.path 可能已经是从根开始的完整路径 (skin/default/...)
		// 如果已经以 skin/ 开头，直接使用，不要再拼接到 p.getParent() 下面
		String imagePath;
		if (data.path.startsWith("skin/")) {
			imagePath = data.path;
		} else {
			imagePath = p.getParentFile().getPath() + "/" + data.path;
		}

		// 统一使用 normalizePath 确保路径格式一致（移除 .. 和多余的 /），提高 filemap 匹配成功率
		imagePath = imagePath.replace("\\", "/").replaceAll("/+", "/");
		try {
			// 这里调用 SkinLoader 的私有方法不太方便，但由于 JsonSkinObjectLoader 也在同一个包或子包下，
			// 如果 normalizePath 是 public 的就好了。实际上它是 public 的。
			String normalized = SkinLoader.normalizePath(imagePath);
			if (!normalized.isEmpty()) {
				imagePath = normalized;
			}
		} catch (Exception e) {}

		Gdx.app.log("TextureDebug", "Attempting to load note texture srcid=" + srcid + ", fullPath=" + imagePath);

		// Android 大小写敏感问题修复：如果文件找不到，尝试忽略大小写查找 (same logic as JSONSkinLoader.getSource)
		boolean exists = false;
		File imagefile = loader.getPath(imagePath, loader.filemap);
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			String pString = imagefile.getPath().replace("\\", "/");
			exists = com.badlogic.gdx.Gdx.files.internal(pString).exists() || com.badlogic.gdx.Gdx.files.absolute(pString).exists();

			// Android 大小写敏感问题修复：如果文件找不到，尝试忽略大小写查找
			if (!exists) {
				java.io.File file = new java.io.File(pString);
				java.io.File parentDir = file.getParentFile();
				if (parentDir != null && parentDir.exists()) {
					String fileName = file.getName();
					java.io.File[] files = parentDir.listFiles();
					if (files != null) {
						for (java.io.File candidate : files) {
							if (candidate.getName().equalsIgnoreCase(fileName)) {
								String actualPath = candidate.getAbsolutePath().replace("\\", "/");
								if (com.badlogic.gdx.Gdx.files.internal(actualPath).exists()) {
									imagefile = candidate;
									exists = true;
									java.util.logging.Logger.getLogger(getClass().getName()).info("Case-insensitive match found: " + pString + " -> " + actualPath);
									break;
								}
								if (com.badlogic.gdx.Gdx.files.absolute(actualPath).exists()) {
									imagefile = candidate;
									exists = true;
									java.util.logging.Logger.getLogger(getClass().getName()).info("Case-insensitive match found: " + pString + " -> " + actualPath);
									break;
								}
							}
						}
					}
				}
			}
		} else {
			exists = imagefile.exists();
		}

		if (exists) {
			data.data = getTexture(imagefile.getPath());
		} else {
			java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: File does not exist after case-insensitive search, srcid=" + srcid + ", originalPath=" + imagePath);
		}
		data.loaded = true;

		if (data.data == null) {
			java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: Failed to load texture, srcid=" + srcid + ", path=" + imagefile.getPath());
		} else {
			Gdx.app.log("TextureDebug", "SUCCESS: Texture loaded srcid=" + srcid + ", path=" + imagefile.getPath());
		}

		return (Texture) data.data;
	}

	protected SkinSource[] getNoteTexture(String[] images, File p) {
		SkinSource[] noteimages = new SkinSource[images.length];
		for(int i = 0;i < images.length;i++) {
			boolean found = false;
			Gdx.app.log("TextureDebug", "getNoteTexture: Looking for note image id=" + images[i]);
			for (JsonSkin.Image img : loader.sk.image) {
				if (images[i] != null && images[i].equals(img.id)) {
					Gdx.app.log("TextureDebug", "getNoteTexture: Found image id=" + img.id + ", src=" + img.src);
					JsonSkin.Image note = img;
					Texture tex = getTexture(note.src, p);
					noteimages[i] = new SkinSourceImage(getSourceImage(tex,  note.x, note.y, note.w,
							note.h, note.divx, note.divy), note.timer, note.cycle);
					found = true;
					if (tex == null) {
						java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: Note texture load failed, imageId=" + img.id + ", src=" + note.src);
					}
					break;
				}
			}
			// 如果没有找到匹配的图片，创建一个空的 SkinSourceImage 以避免 null
			if (!found) {
				noteimages[i] = new SkinSourceImage(new TextureRegion[0], 0, 0);
				java.util.logging.Logger.getGlobal().severe("JsonSkinObjectLoader: Note image not found, imageId=" + images[i]);
			}
		}
		return noteimages;
	}

	protected TextureRegion[] getSourceImage(Texture image, int x, int y, int w, int h, int divx, int divy) {
		if (image == null) {
			return new TextureRegion[Math.max(1, divx * divy)];
		}
		if (w == -1) {
			w = image.getWidth();
		}
		if (h == -1) {
			h = image.getHeight();
		}
		if (divx <= 0) {
			divx = 1;
		}
		if (divy <= 0) {
			divy = 1;
		}
		TextureRegion[] images = new TextureRegion[divx * divy];
		for (int i = 0; i < divx; i++) {
			for (int j = 0; j < divy; j++) {
				images[divx * j + i] = new TextureRegion(image, x + w / divx * i, y + h / divy * j, w / divx, h / divy);
			}
		}
		return images;
	}

	protected Texture getTexture(String path) {
		return SkinLoader.getTexture(path, loader.usecim);
	}

	protected SkinText createText(JsonSkin.Text text, File skinPath) {
		for (JsonSkin.Font font : loader.sk.font) {
			if (font.id.equals(text.font)) {
				// 智能处理字体路径
				File path;
				String fontPathStr = font.path.replace("\\", "/");

				// Android 上，skin 文件在外部存储而非 assets，所以始终相对于 skinPath.getParent() 来解析
				// 这样 font/fnt/main.fnt 会被解析为 /storage/emulated/0/.../skin/ModernChic/Select/font/fnt/main.fnt
				path = new File(skinPath.getParentFile(), font.path);

				// [DEBUG] 记录 font.path 解析结果以便排查路径问题
				com.badlogic.gdx.Gdx.app.log("FontDebug", "createText: font.id=" + font.id
						+ ", font.path=" + font.path
						+ ", skinPath.parent=" + skinPath.getParent()
						+ ", resolved+normalized=" + path
						+ ", text.size=" + text.size);
				// Android 上检验路径是否实际存在
				com.badlogic.gdx.files.FileHandle fontFh = com.badlogic.gdx.Gdx.files.internal(path.toString().replace("\\", "/"));
				if (!fontFh.exists()) {
					com.badlogic.gdx.files.FileHandle fontFhAbs = com.badlogic.gdx.Gdx.files.absolute(path.toString());
					com.badlogic.gdx.Gdx.app.log("FontDebug", "Font internal not found, trying absolute: " + fontFhAbs.path() + ", exists: " + fontFhAbs.exists());
				} else {
					com.badlogic.gdx.Gdx.app.log("FontDebug", "Font file exists (internal): " + fontFh.path());
				}
				SkinText skinText;
				StringProperty property = text.value;
				if (property == null) {
					property = StringPropertyFactory.getStringProperty(text.ref);
				}
				if (path.toString().toLowerCase().endsWith(".fnt")) {
					if (!loader.bitmapSourceMap.containsKey(font.id)) {
						SkinTextBitmap.SkinTextBitmapSource source = new SkinTextBitmap.SkinTextBitmapSource(path, loader.usecim);
						source.setType(font.type);
						loader.bitmapSourceMap.put(font.id, source);
					}
					skinText = new SkinTextBitmap(loader.bitmapSourceMap.get(font.id), text.size * ((float)loader.dstr.width / loader.sk.w), property);
				} else {
					skinText = new SkinTextFont(path.toString(), 0, text.size, 0, property);
				}
				skinText.setConstantText(text.constantText);
				skinText.setAlign(text.align);
				skinText.setWrapping(text.wrapping);
				skinText.setOverflow(text.overflow);
				skinText.setOutlineColor(parseHexColor(text.outlineColor, Color.WHITE));
				skinText.setOutlineWidth(text.outlineWidth);
				skinText.setShadowColor(parseHexColor(text.shadowColor, Color.WHITE));
				skinText.setShadowOffset(new Vector2(text.shadowOffsetX, text.shadowOffsetY));
				skinText.setShadowSmoothness(text.shadowSmoothness);
				return skinText;
			}
		}
		return null;
	}

	protected Color parseHexColor(String hex, Color fallbackColor) {
		try {
			return Color.valueOf(hex);
		} catch (Exception e) {
			return fallbackColor;
		}
	}

	protected File getSrcIdPath(String srcid, File p) {
		if(srcid == null) {
			return null;
		}

		final SourceData data = loader.sourceMap.get(srcid);
		if(data == null) {
			return null;
		}

		return SkinLoader.getPath(p.getParentFile().getPath() + "/" + data.path, loader.filemap);
	}

	protected void setDestination(Skin skin, SkinObject obj, JsonSkin.Destination dst) {
		JsonSkin.Animation prev = null;
		for (JsonSkin.Animation a : dst.dst) {
			if (prev == null) {
				a.time = (a.time == Integer.MIN_VALUE ? 0 : a.time);
				a.x = (a.x == Integer.MIN_VALUE ? 0 : a.x);
				a.y = (a.y == Integer.MIN_VALUE ? 0 : a.y);
				a.w = (a.w == Integer.MIN_VALUE ? 0 : a.w);
				a.h = (a.h == Integer.MIN_VALUE ? 0 : a.h);
				a.acc = (a.acc == Integer.MIN_VALUE ? 0 : a.acc);
				a.angle = (a.angle == Integer.MIN_VALUE ? 0 : a.angle);
				a.a = (a.a == Integer.MIN_VALUE ? 255 : a.a);
				a.r = (a.r == Integer.MIN_VALUE ? 255 : a.r);
				a.g = (a.g == Integer.MIN_VALUE ? 255 : a.g);
				a.b = (a.b == Integer.MIN_VALUE ? 255 : a.b);
			} else {
				a.time = (a.time == Integer.MIN_VALUE ? prev.time : a.time);
				a.x = (a.x == Integer.MIN_VALUE ? prev.x : a.x);
				a.y = (a.y == Integer.MIN_VALUE ? prev.y : a.y);
				a.w = (a.w == Integer.MIN_VALUE ? prev.w : a.w);
				a.h = (a.h == Integer.MIN_VALUE ? prev.h : a.h);
				a.acc = (a.acc == Integer.MIN_VALUE ? prev.acc : a.acc);
				a.angle = (a.angle == Integer.MIN_VALUE ? prev.angle : a.angle);
				a.a = (a.a == Integer.MIN_VALUE ? prev.a : a.a);
				a.r = (a.r == Integer.MIN_VALUE ? prev.r : a.r);
				a.g = (a.g == Integer.MIN_VALUE ? prev.g : a.g);
				a.b = (a.b == Integer.MIN_VALUE ? prev.b : a.b);
			}
			if(dst.draw != null) {
				skin.setDestination(obj, a.time, a.x, a.y, a.w, a.h, a.acc, a.a, a.r, a.g, a.b, dst.blend, dst.filter,
						a.angle, dst.center, dst.loop, dst.timer, dst.draw);
			} else {
				skin.setDestination(obj, a.time, a.x, a.y, a.w, a.h, a.acc, a.a, a.r, a.g, a.b, dst.blend, dst.filter,
						a.angle, dst.center, dst.loop, dst.timer, dst.op);
			}
			if (dst.mouseRect != null) {
				skin.setMouseRect(obj, dst.mouseRect.x, dst.mouseRect.y, dst.mouseRect.w, dst.mouseRect.h);
			}
			prev = a;
		}

		int[] offsets = new int[dst.offsets.length + 1];
		for(int i = 0; i < dst.offsets.length; i++) {
			offsets[i] = dst.offsets[i];
		}
		offsets[dst.offsets.length] = dst.offset;
		obj.setOffsetID(offsets);
		if (dst.stretch >= 0) {
			obj.setStretch(dst.stretch);
		}
	}

}
