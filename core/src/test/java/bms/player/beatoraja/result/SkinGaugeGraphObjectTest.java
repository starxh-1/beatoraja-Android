package bms.player.beatoraja.result;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkinGaugeGraphObjectTest {

	@Test
	public void downwardBorderCrossingProducesPositiveHeight() {
		int borderY = 158;
		int nextY = 154;

		int oldHeight = nextY - borderY + 2;
		int fixedHeight = SkinGaugeGraphObject.getGaugeSpan(nextY, borderY, 2);

		assertTrue("The old formula must reproduce the negative-height bug", oldHeight < 0);
		assertEquals(6, fixedHeight);
	}

	@Test
	public void gaugeSpanIsPositiveInBothDirections() {
		assertEquals(6, SkinGaugeGraphObject.getGaugeSpan(154, 158, 2));
		assertEquals(6, SkinGaugeGraphObject.getGaugeSpan(158, 154, 2));
		assertEquals(1, SkinGaugeGraphObject.getGaugeSpan(158, 158, 0));
	}
}
