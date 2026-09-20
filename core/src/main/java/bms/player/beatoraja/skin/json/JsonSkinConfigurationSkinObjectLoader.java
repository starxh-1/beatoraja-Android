package bms.player.beatoraja.skin.json;

import bms.player.beatoraja.config.SkinConfigurationSkin;
import bms.player.beatoraja.config.SkinPreview;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinObject;

import java.io.File;

public class JsonSkinConfigurationSkinObjectLoader extends JsonSkinObjectLoader<SkinConfigurationSkin> {

	public JsonSkinConfigurationSkinObjectLoader(JSONSkinLoader loader) {
		super(loader);
	}

	@Override
	public SkinConfigurationSkin getSkin(SkinHeader header) {
		return new SkinConfigurationSkin(header);
	}

	/**
	 * 皮肤选择界面专属：把皮肤里 skin.skinpreview 声明的 id 解析成实时预览对象。
	 *
	 * <p>放在这里而不是基类，是因为预览对象只对 SKIN SELECT 这一张皮肤有意义
	 * （它要读 SkinConfiguration 的"当前选中项"）。</p>
	 */
	@Override
	public SkinObject loadSkinObject(SkinConfigurationSkin skin, JsonSkin.Skin sk, JsonSkin.Destination dst, File p) {
		SkinObject obj = super.loadSkinObject(skin, sk, dst, p);
		if (obj != null) {
			return obj;
		}
		return sk.skinpreview != null && dst.id.equals(sk.skinpreview.id) ? new SkinPreview() : null;
	}

}
