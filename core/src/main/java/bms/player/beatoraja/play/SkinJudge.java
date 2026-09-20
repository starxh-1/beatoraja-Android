package bms.player.beatoraja.play;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayStateValues;
import bms.player.beatoraja.play.GrooveGauge.Gauge;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * 判定オブジェクト
 * 
 * @author exch
 */
public final class SkinJudge extends SkinObject {

	/**
	 * 文字イメージ
	 */
    private final SkinImage[] judge = new SkinImage[7];
    /**
     * 数字イメージ
     */
    private final SkinNumber[] count = new SkinNumber[7];
    private final int player;
    private final boolean shift;
    
    private SkinImage nowJudge;
    private SkinNumber nowCount;

    /** 失败探针的一次性开关：每个对象只打印第一次失败原因。 */
    private boolean previewDiagLogged;

    /**
     * 失败探针：判定 / combo 在**非游玩状态**（皮肤选择界面的实时预览）里画不出来时，
     * 打印一次原因。只在失败分支调用，成功路径零输出。
     *
     * <p>为什么需要它：{@code Skin.drawAllObjectsSafely} 会把出问题的对象静默置成
     * {@code draw = false} 且不打印任何东西；而判定图还被 dst 上的 {@code timer}
     * 掐着（`time`/`isOff` 判定，见 {@code docs/skinselect-live-preview.md} 第七节第 3 条）。
     * 没有这条日志，"预览里没有判定"这件事从外部完全无从下手。</p>
     *
     * <p>真游玩（{@code BMSPlayer}）直接返回，不会有任何输出。</p>
     */
    private void previewDiag(MainState state, String msg) {
        if (previewDiagLogged || state instanceof BMSPlayer) {
            return;
        }
        previewDiagLogged = true;
        final int side = player == 1 ? 1 : 0;
        Logger.getGlobal().info("SkinJudge(预览) player=" + player + " " + msg
                + " judgeTimerOn=" + (state != null && state.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P + side))
                + " comboTimerOn=" + (state != null && state.timer.isTimerOn(SkinProperty.TIMER_COMBO_1P + side))
                + " judgeTimerValue=" + (state != null ? state.timer.getTimer(SkinProperty.TIMER_JUDGE_1P + side) : -1));
    }

    public SkinJudge(int index, boolean shift) {
        this(null, null, index, shift);
    }

    public SkinJudge(SkinImage[] judge, SkinNumber[] count, int player, boolean shift) {
    	if(judge == null) {
    		Arrays.fill(this.judge, null);
    	} else {
        	for(int i = 0; i < this.judge.length && i < judge.length;i++) {
        		this.judge[i] = judge[i];
        	}
    	}
    	if(count == null) {
    		Arrays.fill(this.count, null);
    	} else {
        	for(int i = 0; i < this.count.length && i < count.length;i++) {
        		this.count[i] = count[i];
        	}
    	}
        this.player = player;
        this.shift = shift;
        
        this.setDestination(0, 0, 0, 0, 0, 0, 0, 255, 255, 255, 0, 0, 0, 0, 0, 0, new int[0]);
    }

    public SkinImage getJudge(int index) {
        return  index >= 0 && index < judge.length ? judge[index] : null;
    }

    public void setJudge(int index, SkinImage judge) {
    	if(index >= 0 && index < this.judge.length) {
    		this.judge[index] = judge;
    	}
    }

    public SkinNumber getJudgeCount(int index) {
        return  index >= 0 && index < count.length ? count[index] : null;
    }
    
    public void setJudgeCount(int index, SkinNumber count) {
    	if(index >= 0 && index < this.count.length) {
    		this.count[index] = count;
    	}
    }

    public boolean isShift() {
    	return shift;
    }

	@Override
	public void prepare(long time, MainState state) {
        // 判定 / 量表取自 state 提供的「游玩态数值」：真游玩是 BMSPlayer，
        // 皮肤预览是合成实现（见 PlayStateValues）。拿不到就不画这个对象。
        final PlayStateValues play = state != null ? state.getPlayStateValues() : null;
        if (play == null) {
        	previewDiag(state, "play==null");
        	draw = false;
        	return;
        }
        final int judgenow = play.getNowJudge(player) - 1;
        if(judgenow < 0) {
        	previewDiag(state, "judgenow=" + judgenow);
        	draw = false;
            return;
        }
		super.prepare(time, state);
		
        final GrooveGauge gaugeSource = play.getGauge();
        final Gauge gauge = gaugeSource != null ? gaugeSource.getGauge() : null;
        if (gauge == null) {
        	previewDiag(state, "gauge==null");
        	draw = false;
        	return;
        }
        
        if(judgenow == 0 && gauge.isMax()) {
        	nowJudge = judge[6] != null ? judge[6] : judge[0];
        	nowCount = count[6] != null ? count[6] : count[0];
        } else {
        	nowJudge = judge[judgenow];
        	nowCount = judgenow < 3 ? count[judgenow] : null;        	
        }
        
        if(nowJudge != null) {
        	nowJudge.prepare(time, state);
        } else {
        	previewDiag(state, "judgenow=" + judgenow + " nowJudge==null");
        	draw = false;
        	return;
        }
        
    	if(nowJudge.draw) {
            if(nowCount != null) {
            	nowCount.prepare(time, state, play.getNowCombo(player), nowJudge.region.x, nowJudge.region.y);
            	if (shift) {
            		if (nowJudge.angle == 270 || nowJudge.angle == 90) {
            			nowJudge.region.y += nowCount.getLength() / 2;
            		} else {
            			nowJudge.region.x += -nowCount.getLength() / 2;
            		}
            	}
            }
    	} else {
        	previewDiag(state, "judgenow=" + judgenow + " nowJudge.draw==false");
        	draw = false;
        	return;    		
    	}
	}

    @Override
    public void draw(SkinObjectRenderer sprite) {
        if (nowCount != null && nowCount.draw) {
        	nowCount.draw(sprite);
        }
        nowJudge.draw(sprite);
    }

    @Override
    public void dispose() {
    	disposeAll(judge);
    	disposeAll(count);
    }
}
