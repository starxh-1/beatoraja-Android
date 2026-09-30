package bms.player.beatoraja.select;

import bms.player.beatoraja.BarRenderSource;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

import java.util.Optional;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * 楽曲バー描画用スキンオブジェクト
 * 
 * @author exch
 */
public final class SkinBar extends SkinObject {

    /**
     * 選択時のBarのSkinImage
     */
    private SkinImage[] barimageon = new SkinImage[BAR_COUNT];
    /**
     * 非選択時のBarのSkinImage
     */
    private SkinImage[] barimageoff = new SkinImage[BAR_COUNT];
    
    public static final int BAR_COUNT = 60;

    /**
     * トロフィーのSkinImage。描画位置はBarの相対座標
     */
    private final SkinImage[] trophy = new SkinImage[BARTROPHY_COUNT];
    
    public static final int BARTROPHY_COUNT = 3;

    /**
     * BarのSkinText。描画位置はBarの相対座標。
     * Indexは0:通常 1:新規 2:SongBar(通常) 3:SongBar(新規) 4:FolderBar(通常) 5:FolderBar(新規) 6:TableBar or HashBar
     * 7:GradeBar(曲所持) 8:(SongBar or GradeBar)(曲未所持) 9:CommandBar or ContainerBar 10:SearchWordBar
     * 3以降で定義されてなければ0か1を用いる
     */
    private final SkinText[] text = new SkinText[BARTEXT_COUNT];

    public static final int BARTEXT_NORMAL = 0;
    public static final int BARTEXT_NEW = 1;
    public static final int BARTEXT_SONG_NORMAL = 2;
    public static final int BARTEXT_SONG_NEW = 3;
    public static final int BARTEXT_FOLDER_NORMAL = 4;
    public static final int BARTEXT_FOLDER_NEW = 5;
    public static final int BARTEXT_TABLE = 6;
    public static final int BARTEXT_GRADE = 7;
    public static final int BARTEXT_NO_SONGS = 8;
    public static final int BARTEXT_COMMAND = 9;
    public static final int BARTEXT_SEARCH = 10;
    public static final int BARTEXT_COUNT = 11;
    /**
     * レベルのSkinNumber。描画位置はBarの相対座標
     */
    private final SkinNumber[] barlevel = new SkinNumber[BARLEVEL_COUNT];
    
    public static final int BARLEVEL_COUNT = 7;

    /**
     * 譜面ラベルのSkinImage。描画位置はBarの相対座標
     */
    private final SkinImage[] label = new SkinImage[BARLABEL_COUNT];
    
    public static final int BARLABEL_COUNT = 5;

    private SkinDistributionGraph graph;

    private int position = 0;

    /**
     * ランプ画像
     */
    private final SkinImage[] lamp = new SkinImage[BARLAMP_COUNT];
    /**
     * ライバルランプ表示時のプレイヤーランプ画像
     */
    private final SkinImage[] mylamp = new SkinImage[BARLAMP_COUNT];
    /**
     * ライバルランプ表示時のライバルランプ画像
     */
    private final SkinImage[] rivallamp = new SkinImage[BARLAMP_COUNT];

    public static final int BARLAMP_COUNT = 11;
    
    private BarRenderer render;
    /**
     * 描画不可スキンオブジェクト
     */
    private Array<SkinObject> removes = new Array<SkinObject>();

    public SkinBar(int position) {
        this.position = position;
        this.setDestination(0, 0, 0, 0, 0, 0, 0, 255, 255, 255, 0, 0, 0, 0, 0, 0, new int[0]);
    }

    public void setBarImage(SkinImage[] onimage, SkinImage[] offimage) {
    	barimageon = onimage;
    	barimageoff = offimage;
    }

    public SkinImage getBarImages(boolean on, int index) {
    	return index >= 0 && index < barimageoff.length ? (on ? barimageon[index] : barimageoff[index]) : null;
    }

    public SkinImage getLamp(int id) {
        return id >= 0 && id < this.lamp.length ? this.lamp[id] : null;
    }

    public SkinImage getPlayerLamp(int id) {
        return id >= 0 && id < this.mylamp.length ? this.mylamp[id] : null;
    }

    public SkinImage getRivalLamp(int id) {
        return id >= 0 && id < rivallamp.length ? rivallamp[id] : null;
    }

    public SkinImage getTrophy(int id) {
        return id >= 0 && id < trophy.length ? trophy[id] : null;
    }

    public SkinText getText(int id) {
        return id >= 0 && id < text.length ? text[id] : null;
    }

    public void setTrophy(int id, SkinImage trophy) {
        if(id >= 0 && id < this.trophy.length) {
            this.trophy[id] = trophy;
        }
    }

    public void setLamp(int id, SkinImage lamp) {
        if(id >= 0 && id < this.lamp.length) {
            this.lamp[id] = lamp;
        }
    }

    public void setPlayerLamp(int id, SkinImage mylamp) {
        if(id >= 0 && id < this.mylamp.length) {
            this.mylamp[id] = mylamp;
        }
    }

    public void setText(int id, SkinText text) {
        if(id >= 0 && id < this.text.length) {
            this.text[id] = text;
        }
    }

    public void setRivalLamp(int id, SkinImage rivallamp) {
        if(id >= 0 && id < this.rivallamp.length) {
            this.rivallamp[id] = rivallamp;
        }
    }

    public boolean validate() {
    	for(int i = 0;i < barimageon.length;i++) {
    		if(barimageon[i] != null && !barimageon[i].validate()) {
    			removes.add(barimageon[i]);
    			barimageon[i] = null;
    		}
    	}
    	for(int i = 0;i < barimageoff.length;i++) {
    		if(barimageoff[i] != null && !barimageoff[i].validate()) {
    			removes.add(barimageoff[i]);
    			barimageoff[i] = null;
    		}
    	}
    	for(int i = 0;i < trophy.length;i++) {
    		if(trophy[i] != null && !trophy[i].validate()) {
    			removes.add(trophy[i]);
    			trophy[i] = null;
    		}
    	}
    	for(int i = 0;i < label.length;i++) {
    		if(label[i] != null && !label[i].validate()) {
    			removes.add(label[i]);
    			label[i] = null;
    		}
    	}
    	for(int i = 0;i < lamp.length;i++) {
    		if(lamp[i] != null && !lamp[i].validate()) {
    			removes.add(lamp[i]);
    			lamp[i] = null;
    		}
    	}
    	for(int i = 0;i < mylamp.length;i++) {
    		if(mylamp[i] != null && !mylamp[i].validate()) {
    			removes.add(mylamp[i]);
    			mylamp[i] = null;
    		}
    	}
    	for(int i = 0;i < rivallamp.length;i++) {
    		if(rivallamp[i] != null && !rivallamp[i].validate()) {
    			removes.add(rivallamp[i]);
    			rivallamp[i] = null;
    		}
    	}
    	for(int i = 0;i < text.length;i++) {
    		if(text[i] != null && !text[i].validate()) {
    			removes.add(text[i]);
    			text[i] = null;
    		}
    	}
    	return super.validate();
    }
    
    @Override
    public void prepare(long time, MainState state) {
    	if(render == null) {
    		// 真选曲界面：state 是 MusicSelector；皮肤选择界面的实时预览：state 是
    		// SkinConfiguration，它同样实现 BarRenderSource（借用 MainController 上长驻的
    		// MusicSelector 的渲染器，所以预览里画出来的是真曲目）。
    		// 原来这里写死 ((MusicSelector) state) —— 预览里必然 ClassCastException，
    		// 异常被 Skin.drawAllObjectsSafely 吞掉后本对象被永久置 draw=false，
    		// 整条选曲列表（含曲名 / 等级 / 灯 / 奖杯 / 标签 / 分布图）就此消失。
    		if(!(state instanceof BarRenderSource)) {
    			draw = false;
    			return;
    		}
    		render = ((BarRenderSource) state).getBarRender();
    		if(render == null) {
    			draw = false;
    			return;
    		}
    	}
    	// 每个子对象单独容错：坏掉一张图不该连坐整条列表（尤其是不能让
    	// render.prepare() 被异常跳过 —— 那等于整条列表一个字都不画）。
    	try {
    		super.prepare(time, state);
    	} catch (Throwable e) {
    		// 单张子图失败不向上抛
    	}
    	for(SkinImage bar : barimageon) {
    		prepareChild(bar, time, state);
    	}
    	for(SkinImage bar : barimageoff) {
    		prepareChild(bar, time, state);
    	}
    	for(SkinImage trophy : trophy) {
    		prepareChild(trophy, time, state);
    	}
    	for(SkinText text : this.text) {
    		prepareChild(text, time, state);
    	}
    	for(SkinNumber barlevel : this.barlevel) {
    		prepareChild(barlevel, time, state);
    	}
    	for(SkinImage label : this.label) {
    		prepareChild(label, time, state);
    	}
    	for(SkinImage lamp : this.lamp) {
    		prepareChild(lamp, time, state);
    	}
    	for(SkinImage mylamp : this.mylamp) {
    		prepareChild(mylamp, time, state);
    	}
    	for(SkinImage rivallamp : this.rivallamp) {
    		prepareChild(rivallamp, time, state);
    	}
    	// 🔴 分布图（folder lamp 横条）**不能带进预览**：
    	// SkinDistributionGraph.prepare() 第一行就是 ((MusicSelector) state).getSelectedBar()，
    	// 而预览的 state 是 SkinConfiguration ⇒ 必然 ClassCastException。异常从本方法抛出后
    	// 被 Skin.drawAllObjectsSafely 静默吞掉并置 draw=false —— 于是这一行**后面**的
    	// render.prepare(this, time) 永远不会执行，整条选曲列表（曲名 / 等级 / 灯 / 奖杯 /
    	// 标签）一起消失。2026-09-28 的「SKIN SELECT 里 musicselect 的 songbar 画不出来」就是这条。
    	// 该文件按约定保持与上游一致（不在这里改），所以从调用方跳过：预览里没有
    	// "当前选中的 Bar"，分布图本来也无从计算。
    	if(state instanceof MusicSelector) {
    		prepareChild(graph, time, state);
    	} else if(graph != null) {
    		graph.draw = false;
    	}

    	// BarRenderer.prepare() 必须无条件执行到（上面任何一个子对象失败都不能跳过它，
    	// 否则整条列表一个字都不画），所以单独包一层。
    	try {
    		render.prepare(this, time);
    	} catch (Throwable e) {
    		// 同上：不向上抛，避免连坐整张皮肤
    	}
    }

    /**
     * 准备一个子对象，失败时只把它的 draw 置 false，不向上抛。
     */
    private void prepareChild(SkinObject obj, long time, MainState state) {
    	if(obj == null) {
    		return;
    	}
    	try {
    		obj.prepare(time, state);
    	} catch (Throwable e) {
    		obj.draw = false;
    	}
    }

    public void draw(SkinObjectRenderer sprite) {
    	render.render(sprite, this);
    }

    @Override
    public void dispose() {
    	disposeAll(removes.toArray(SkinObject.class));
    	disposeAll(barimageon);
    	disposeAll(barimageoff);
    	disposeAll(trophy);
    	disposeAll(text);
    	disposeAll(barlevel);
    	disposeAll(label);
        disposeAll(lamp);
        disposeAll(mylamp);
        disposeAll(rivallamp);
        Optional.ofNullable(graph).ifPresent(Disposable::dispose);
        setDisposed();
    }

    public SkinNumber getBarlevel(int id) {
        return id >= 0 && id < barlevel.length ? barlevel[id] : null;
    }

    public void setBarlevel(int id, SkinNumber barlevel) {
        if(id >= 0 && id < this.barlevel.length) {
            this.barlevel[id] = barlevel;
        }
    }

    public int getPosition() {
        return position;
    }
    
    @Override
	protected boolean mousePressed(MainState state, int button, int x, int y) {
        // 只有真选曲界面会被点到（皮肤预览不接收输入，其对象表也不含本对象）；
        // 但仍然判一次类型，避免任何路径下的 ClassCastException。
        if(!(state instanceof MusicSelector)) {
            return false;
        }
        final BarRenderer renderer = ((MusicSelector) state).getBarRender();
        return renderer != null && renderer.mousePressed(this, button, x, y);
	}

    public SkinImage getLabel(int id) {
        return id >= 0 && id < label.length ? label[id] : null;
    }

    public void setLabel(int id, SkinImage label) {
        if(id >= 0 && id < this.label.length) {
            this.label[id] = label;
        }
    }

	public SkinDistributionGraph getGraph() {
		return graph;
	}

	public void setGraph(SkinDistributionGraph graph) {
		this.graph = graph;
	}
}