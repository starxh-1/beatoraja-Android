package bms.player.beatoraja.rating;

import java.util.Map;

public class ClearLampMapper {

    private static final int[] BEATORAJA_CLEAR_TO_LAMP = {
        -1,  // 0: NoPlay (unused)
        1,   // 1: Failed -> failed
        1,   // 2: AssistEasy -> failed
        1,   // 3: LightAssistEasy -> failed
        2,   // 4: Easy -> easy
        3,   // 5: Normal -> normal
        4,   // 6: Hard -> hard
        4,   // 7: ExHard -> hard
        5,   // 8: FullCombo -> fullCombo
        5,   // 9: Perfect -> fullCombo
        5,   // 10: Max -> fullCombo
    };

    // walkure lamp strings (for reference)
    public static final String[] LAMP_NAMES = {"", "failed", "easy", "normal", "hard", "fullCombo"};

    // CLEAR_DIFFICULTY_LAMPS - lamps that have clearDifficulty values
    // (excludes "failed")
    public static final int[] DIFFICULTY_LAMP_ORDINALS = {2, 3, 4, 5};

    public static int beatorajaClearToOrdinal(int beatorajaClearType) {
        if (beatorajaClearType < 0 || beatorajaClearType >= BEATORAJA_CLEAR_TO_LAMP.length) {
            return -1;
        }
        return BEATORAJA_CLEAR_TO_LAMP[beatorajaClearType];
    }

    public static String beatorajaClearToLampName(int beatorajaClearType) {
        int ord = beatorajaClearToOrdinal(beatorajaClearType);
        return ord > 0 && ord < LAMP_NAMES.length ? LAMP_NAMES[ord] : null;
    }
}
