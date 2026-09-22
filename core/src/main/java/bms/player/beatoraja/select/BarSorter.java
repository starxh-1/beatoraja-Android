package bms.player.beatoraja.select;

import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.FolderBar;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;

import java.util.Comparator;

/**
 * バーのソートアルゴリズム
 *
 * <p>各ソートは自分の主キーだけを比較し、同順位の場合は
 * {@link #compareTitle(Bar, Bar)}(タイトル → 難易度 → レベル)で決着させる。
 *
 * <p>タイトル比較は「曲名の本体」だけを見る。難度違いの同一曲
 * (例: "Inochi [SP NORMAL]" / "Inochi [SP HYPER]" / "Inochi ~ANOTHER~")は
 * 同じ曲とみなされ、難易度の低い順(NORMAL → HYPER → ANOTHER)に並ぶ。
 * 本体の切り出しは 2 段階:
 * <ol>
 * <li>括弧類・記号 ({@link #TITLE_BREAK_CHARS}) と、空白直後のダッシュ
 * ({@link #TITLE_BREAK_DASH_CHARS}) 以降を捨てる。
 * "Finixe [NORMAL]" も "Finixe -Eclipse-" も本体は "Finixe" になる</li>
 * <li>残った文字列から、空白区切りのトークンが難度/譜面種別ラベル
 * ({@link #TITLE_VARIANT_TOKENS}) なら、そこから後ろを捨てる</li>
 * </ol>
 *
 * <p>本体・難易度・レベル・難度名まで同じ場合だけ、記号を無視した文字列比較
 * ({@link #compareIgnorePunctuation}) で決着させる。括弧で囲ったかダッシュで囲ったかで
 * 並びが偏らないようにするため。
 *
 * @author exch
 */
public enum BarSorter {

	/**
	 * 楽曲/タイトル名ソート
	 */
	TITLE((o1, o2) -> compareTitle(o1, o2)),
	/**
	 * アーティスト名ソート
	 */
	ARTIST((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (!((SongBar)o1).existsSong() && !((SongBar)o2).existsSong()) {
			return 0;
		}
		if (!((SongBar)o1).existsSong()) {
			return 1;
		}
		if (!((SongBar)o2).existsSong()) {
			return -1;
		}
		return compareIgnoreCase(((SongBar)o1).getSongData().getArtist(), ((SongBar)o2).getSongData().getArtist());
	}),
	/**
	 * 楽曲のBPMソート
	 */
	BPM((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (!((SongBar)o1).existsSong() && !((SongBar)o2).existsSong()) {
			return 0;
		}
		if (!((SongBar)o1).existsSong()) {
			return 1;
		}
		if (!((SongBar)o2).existsSong()) {
			return -1;
		}
		return Integer.compare(((SongBar) o1).getSongData().getMaxbpm(), ((SongBar) o2).getSongData().getMaxbpm());
	}),
	/**
	 * 楽曲の長さソート
	 */
	LENGTH((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (!((SongBar)o1).existsSong() && !((SongBar)o2).existsSong()) {
			return 0;
		}
		if (!((SongBar)o1).existsSong()) {
			return 1;
		}
		if (!((SongBar)o2).existsSong()) {
			return -1;
		}
		return Integer.compare(((SongBar) o1).getSongData().getLength(), ((SongBar) o2).getSongData().getLength());
	}),
	/**
	 * レベルソート
	 */
	LEVEL((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (!((SongBar)o1).existsSong() && !((SongBar)o2).existsSong()) {
			return 0;
		}
		if (!((SongBar)o1).existsSong()) {
			return 1;
		}
		if (!((SongBar)o2).existsSong()) {
			return -1;
		}

		//levelが同じ場合はDifficultyでソート
		final int levelSort = Integer.compare(((SongBar) o1).getSongData().getLevel(), ((SongBar) o2).getSongData().getLevel());
		if(levelSort == 0){
			return Integer.compare(((SongBar)o1).getSongData().getDifficulty(), ((SongBar)o2).getSongData().getDifficulty());
		}else{
			return levelSort;
		}
	}),
	/**
	 * クリアランプソート
	 */
	CLEAR((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (o1.getScore() == null && o2.getScore() == null) {
			return 0;
		}
		if (o1.getScore() == null) {
			return 1;
		}
		if (o2.getScore() == null) {
			return -1;
		}
		return Integer.compare(o1.getScore().getClear(), o2.getScore().getClear());
	}),
	/**
	 * スコアレートソート
	 */
	SCORE((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		final int n1 = o1.getScore() != null ? o1.getScore().getNotes() : 0;
		final int n2 = o2.getScore() != null ? o2.getScore().getNotes() : 0;
		if (n1 == 0 && n2 == 0) {
			return 0;
		}
		if (n1 == 0) {
			return 1;
		}
		if (n2 == 0) {
			return -1;
		}
		return Float.compare(
				(float) o1.getScore().getExscore() / n1,
				(float) o2.getScore().getExscore() / n2
		);
	}),
	/**
	 * ミスカウントソート
	 */
	MISSCOUNT((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (o1.getScore() == null && o2.getScore() == null) {
			return 0;
		}
		if (o1.getScore() == null) {
			return 1;
		}
		if (o2.getScore() == null) {
			return -1;
		}
		return Integer.compare(o1.getScore().getMinbp(), o2.getScore().getMinbp());
	}),
	/**
	 * Durationソート
	 */
	DURATION((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		
		final boolean existsDuration1 = (o1.getScore() != null && o1.getScore().getAvgjudge() != Long.MAX_VALUE);
		final boolean existsDuration2 = (o2.getScore() != null && o2.getScore().getAvgjudge() != Long.MAX_VALUE);
		if (!existsDuration1 && !existsDuration2) {
			return 0;
		}
		if (!existsDuration1) {
			return 1;
		}
		if (!existsDuration2) {
			return -1;
		}
		return Long.compare(o1.getScore().getAvgjudge(), o2.getScore().getAvgjudge());
	}),
	/**
	 * 最終更新日時ソート
	 */
	LASTUPDATE((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if (o1.getScore() == null && o2.getScore() == null) {
			return 0;
		}
		if (o1.getScore() == null) {
			return 1;
		}
		if (o2.getScore() == null) {
			return -1;
		}
		return Long.compare(o1.getScore().getDate(), o2.getScore().getDate());
	}),
	RIVALCOMPARE_CLEAR((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		if ((o1.getScore() == null || o1.getRivalScore() == null) && (o2.getScore() == null || o2.getRivalScore() == null)) {
			return 0;
		}
		if (o1.getScore() == null || o1.getRivalScore() == null) {
			return 1;
		}
		if (o2.getScore() == null || o2.getRivalScore() == null) {
			return -1;
		}
		return (o1.getScore().getClear() - o1.getRivalScore().getClear()) - (o2.getScore().getClear() - o2.getRivalScore().getClear());
	}),
	RIVALCOMPARE_SCORE((o1, o2) -> {
		if (!(o1 instanceof SongBar) || !(o2 instanceof SongBar)) {
			return compareTitle(o1, o2);
		}
		final int n1 = o1.getScore() != null ? o1.getScore().getNotes() : 0;
		final int n2 = o2.getScore() != null ? o2.getScore().getNotes() : 0;
		final int r1 = o1.getRivalScore() != null ? o1.getRivalScore().getNotes() : 0;
		final int r2 = o2.getRivalScore() != null ? o2.getRivalScore().getNotes() : 0;
		if ((n1 == 0 || r1 == 0) && (n2 == 0 || r2 == 0)) {
			return 0;
		}
		if (n1 == 0 || r1 == 0) {
			return 1;
		}
		if (n2 == 0 || r2 == 0) {
			return -1;
		}
		return Float.compare(
				(float) o1.getScore().getExscore() / n1 - (float) o1.getRivalScore().getExscore() / r1,
				(float) o2.getScore().getExscore() / n2 - (float) o2.getRivalScore().getExscore() / r2
		);
	}),
	;
	
	public static final BarSorter[] defaultSorter = {TITLE, ARTIST, BPM, LENGTH, LEVEL, CLEAR, SCORE, MISSCOUNT};

	public static final BarSorter[] allSorter = BarSorter.values();

	/**
	 * 曲名本体より後ろを捨てる文字。ここから先(例: "[SP ANOTHER]"、"~HARD~"、"†Leggendaria")は
	 * 難度や譜面種別のラベルなので、比較対象から外して難度違いの同一曲を同じ曲として扱う。
	 */
	private static final String TITLE_BREAK_CHARS = "[({【（〔〈《「『［｛~†◆";

	/**
	 * 直後が数字のときだけ区切りとみなす文字(TITLE_BREAK_CHARS より優先度が低い)。
	 * "Air ☆11" や "Angelic layer ★1" のようなレベル表記は切るが、
	 * "HAPPY☆LUCKY☆BABY" や "FRANTIC☆ARCADE" のように曲名の一部である ☆ は切らない。
	 */
	private static final String TITLE_BREAK_DIGIT_CHARS = "★☆";

	/**
	 * 直前が空白/アンダースコアのときだけ区切りとみなすダッシュ類。
	 * "Finixe -Eclipse-" や "omega iii - beyond the flare" を "Finixe" / "omega iii" にする。
	 *
	 * <p>空白を条件にしているのが要点。"X-DEN" や "A.B.C" のように空白なしで繋がった
	 * ハイフンは曲名の一部なので切らない。難度ラベルを書く人は必ず空白で区切る。
	 */
	private static final String TITLE_BREAK_DASH_CHARS = "-–—―";

	/**
	 * {@link #TITLE_BREAK_DASH_CHARS} を区切りとみなすために、その手前に必要な文字。
	 */
	private static final String TITLE_BREAK_DASH_LEAD_CHARS = " _\u3000";

	/**
	 * 最終決着でだけ無視する文字。曲名本体・難易度・レベルまで同じ場合、
	 * "Finixe [Akasha]" と "Finixe -Luna-" を "Finixe Akasha" / "Finixe Luna" として比べ、
	 * 括弧で囲ったかダッシュで囲ったかで並びが偏らないようにする。
	 */
	private static final String TITLE_IGNORE_CHARS =
			TITLE_BREAK_CHARS + TITLE_BREAK_DIGIT_CHARS + TITLE_BREAK_DASH_CHARS
					+ "_]})】）〕〉》」』］｝・";

	/**
	 * 切り出した曲名本体の末尾から落とす文字。("Air -★10-" を "Air"、"AIR 鳥の詩　[EASY]" を
	 * "AIR 鳥の詩" にするため。U+3000 は全角スペース)
	 */
	private static final String TITLE_TRIM_CHARS = " \t-_・　";

	/**
	 * 切り出した曲名本体の先頭から落とす文字(空白のみ)。記号は落とさない。
	 */
	private static final String TITLE_TRIM_LEAD_CHARS = " \t　";

	/**
	 * 空白/アンダースコア区切りで現れたら「ここから後ろは難度/譜面種別」とみなすトークン。
	 * 先頭トークンは対象外なので "Hard to Say" のような曲名は壊れない。
	 *
	 * <p>"sp" と "sc" は意図的に入れていない。"no sc"(スクラッチ無し)のような
	 * 曲名の一部と衝突してしまうため。
	 */
	private static final String[] TITLE_VARIANT_TOKENS = {
			"beginner", "normal", "hyper", "another", "insane",
			"easy", "hard", "extra", "exhard", "exh", "leggendaria", "training",
			"ln", "mx", "dp", "shd",
	};

	/**
	 * トークン照合時に前後から無視する文字。"-DP" や "MX+"、"ANOTHER-" を拾うため。
	 */
	private static final String TITLE_TOKEN_EDGE_CHARS = "-_+";

	/**
	 * 括弧以降に現れる難度名。DIFFICULTY/LEVEL も同じ場合の決め手にだけ使う。
	 */
	private static final String[] SUFFIX_DIFFICULTY_NAMES = {
			"beginner", "easy", "normal", "hyper", "hard", "another", "insane",
	};

	/**
	 * ソート名称
	 */
	public final Comparator<Bar> sorter;

	private BarSorter(Comparator<Bar> primary) {
		this.sorter = (o1, o2) -> {
			final int result = primary.compare(o1, o2);
			// 主キーが同順位の場合はタイトル → 難易度 → レベルで決着させる
			return result != 0 ? result : compareTitle(o1, o2);
		};
	}

	/**
	 * タイトル → 難易度 → レベル の比較。
	 * SongBar 同士はタイトル(曲名本体のみ)、難易度、レベルの順に比較する。
	 * どれも同じ場合は括弧内の難度名、完全なタイトル文字列、最後にファイルパスで決着させ、
	 * 並び順が実行ごとに変わらないようにする。
	 */
	private static int compareTitle(Bar o1, Bar o2) {
		final boolean sortable1 = o1 instanceof SongBar || o1 instanceof FolderBar;
		final boolean sortable2 = o2 instanceof SongBar || o2 instanceof FolderBar;
		if (!sortable1 && !sortable2) {
			return 0;
		}
		if (!sortable1) {
			return 1;
		}
		if (!sortable2) {
			return -1;
		}

		final SongData s1 = o1 instanceof SongBar ? ((SongBar) o1).getSongData() : null;
		final SongData s2 = o2 instanceof SongBar ? ((SongBar) o2).getSongData() : null;
		if (s1 != null && s2 != null) {
			final int result = compareTitleValue(s1.getFullTitle(), s1.getDifficulty(), s1.getLevel(),
					s2.getFullTitle(), s2.getDifficulty(), s2.getLevel());
			// ここでまだ 0 なのは「タイトル・難易度・レベルが全て同じ」場合だけ。
			// ファイルパスは人間に見えない情報なので主キーにはしない(改名で並びが飛ぶ)。
			// 最後の決め手としてのみ使い、同値グループ内の並びを実行ごとに固定する。
			return result != 0 ? result : compareIgnoreCase(s1.getPath(), s2.getPath());
		}
		// フォルダ等、難易度/レベルを持たないバーはタイトル文字列のみで比較する
		return compareTitleValue(o1.getTitle(), -1, -1, o2.getTitle(), -1, -1);
	}

	/**
	 * タイトル文字列と難易度/レベルによる比較。
	 */
	private static int compareTitleValue(String title1, int difficulty1, int level1,
			String title2, int difficulty2, int level2) {
		int compare = compareIgnoreCase(baseTitle(title1), baseTitle(title2));
		if (compare != 0) {
			return compare;
		}
		compare = Integer.compare(difficulty1, difficulty2);
		if (compare != 0) {
			return compare;
		}
		compare = Integer.compare(level1, level2);
		if (compare != 0) {
			return compare;
		}
		compare = Integer.compare(suffixDifficultyRank(title1), suffixDifficultyRank(title2));
		if (compare != 0) {
			return compare;
		}
		return compareIgnorePunctuation(title1, title2);
	}

	/**
	 * タイトル比較用の「曲名本体」を返す。難度・譜面種別のラベルを 2 段階で落とす。
	 * <ol>
	 * <li>括弧類・記号 ({@link #TITLE_BREAK_CHARS}) と、空白直後のダッシュ
	 * ({@link #TITLE_BREAK_DASH_CHARS}) 以降を捨てる</li>
	 * <li>残った文字列から、空白/アンダースコア区切りの難度トークン以降を捨てる</li>
	 * </ol>
	 * 2 段階目を必ず走らせるのが要点。"ERIS MX [EX HARD]" のように 1 段階目だけで
	 * "ERIS MX" で止まってしまうと、"ERIS" 本体のグループに入らないため。
	 *
	 * <p>括弧で始まるタイトルは切り落とすと空文字になってしまうので、その場合は元の文字列を返す。
	 */
	private static String baseTitle(String title) {
		if (title == null || title.length() == 0) {
			return "";
		}
		String base = title;
		// 1) 括弧類・記号以降を捨てる
		for (int i = 0; i < title.length(); i++) {
			final char c = title.charAt(i);
			final boolean breakHere = TITLE_BREAK_CHARS.indexOf(c) >= 0
					|| (TITLE_BREAK_DIGIT_CHARS.indexOf(c) >= 0
							&& i + 1 < title.length() && Character.isDigit(title.charAt(i + 1)))
					|| (TITLE_BREAK_DASH_CHARS.indexOf(c) >= 0
							&& i > 0 && TITLE_BREAK_DASH_LEAD_CHARS.indexOf(title.charAt(i - 1)) >= 0);
			if (breakHere) {
				final String cut = trimTail(title.substring(0, i));
				if (cut.length() > 0) {
					base = cut;
				}
				break;
			}
		}
		// 2) 空白/アンダースコア区切りの難度トークン以降を捨てる
		final int variant = indexOfVariantToken(base);
		if (variant > 0) {
			final String cut = trimTail(base.substring(0, variant));
			if (cut.length() > 0) {
				base = cut;
			}
		}
		return base;
	}

	/**
	 * 先頭以外で最初に難度トークンと一致する単語の開始位置を返す。見つからない場合は -1。
	 * ソート中に何度も呼ばれるため、split せず regionMatches で走査する(無割り当て)。
	 */
	private static int indexOfVariantToken(String value) {
		int wordStart = 0;
		while (wordStart < value.length()) {
			int wordEnd = wordStart;
			while (wordEnd < value.length() && !isWordSeparator(value.charAt(wordEnd))) {
				wordEnd++;
			}
			if (wordStart > 0 && isVariantToken(value, wordStart, wordEnd)) {
				return wordStart;
			}
			wordStart = wordEnd + 1;
		}
		return -1;
	}

	private static boolean isWordSeparator(char c) {
		return c == ' ' || c == '_';
	}

	/** token が難度/譜面種別ラベルと一致するか(大文字小文字は無視、前後の "-_+" も無視)。 */
	private static boolean isVariantToken(String value, int start, int end) {
		while (start < end && TITLE_TOKEN_EDGE_CHARS.indexOf(value.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && TITLE_TOKEN_EDGE_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
			end--;
		}
		final int length = end - start;
		for (String variant : TITLE_VARIANT_TOKENS) {
			if (variant.length() == length && value.regionMatches(true, start, variant, 0, length)) {
				return true;
			}
		}
		return false;
	}

	private static String trimTail(String value) {
		int end = value.length();
		while (end > 0 && TITLE_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
			end--;
		}
		int start = 0;
		while (start < end && TITLE_TRIM_LEAD_CHARS.indexOf(value.charAt(start)) >= 0) {
			start++;
		}
		// String.trim() は U+3000(全角スペース)を落とさないため自前で処理する
		return start == 0 && end == value.length() ? value : value.substring(start, end);
	}

	/**
	 * タイトル中の難度名(BEGINNER/EASY/NORMAL/HYPER/HARD/ANOTHER/INSANE)の並び順を返す。
	 * 見つからない場合は -1。単語として現れたものだけを見る("abnormal" は NORMAL とみなさない)。
	 */
	private static int suffixDifficultyRank(String title) {
		if (title == null || title.length() == 0) {
			return -1;
		}
		int start = -1;
		for (int i = 0; i <= title.length(); i++) {
			final boolean word = i < title.length() && isWordChar(title.charAt(i));
			if (word) {
				if (start < 0) {
					start = i;
				}
			} else if (start >= 0) {
				final int rank = rankOf(title, start, i - start);
				if (rank >= 0) {
					return rank;
				}
				start = -1;
			}
		}
		return -1;
	}

	private static boolean isWordChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	private static int rankOf(String title, int start, int length) {
		for (int i = 0; i < SUFFIX_DIFFICULTY_NAMES.length; i++) {
			if (SUFFIX_DIFFICULTY_NAMES[i].length() == length
					&& title.regionMatches(true, start, SUFFIX_DIFFICULTY_NAMES[i], 0, length)) {
				return i;
			}
		}
		return -1;
	}

	private static int compareIgnoreCase(String s1, String s2) {
		if (s1 == null) {
			return s2 == null ? 0 : -1;
		}
		return s2 == null ? 1 : s1.compareToIgnoreCase(s2);
	}

	/**
	 * {@link #TITLE_IGNORE_CHARS} を無視した文字列比較。
	 * 曲名本体・難易度・レベル・難度名まで同じ場合の最終決着にだけ使う。
	 *
	 * <p>括弧で囲うか、ダッシュで囲うか、という「書き方の違い」で並びが決まらないようにする。
	 * ソート中に何度も呼ばれるため、整形した文字列を作らず添字を進めながら比較する(無割り当て)。
	 */
	private static int compareIgnorePunctuation(String s1, String s2) {
		if (s1 == null) {
			return s2 == null ? 0 : -1;
		}
		if (s2 == null) {
			return 1;
		}
		int i1 = 0;
		int i2 = 0;
		while (true) {
			i1 = skipIgnoreChars(s1, i1);
			i2 = skipIgnoreChars(s2, i2);
			final boolean end1 = i1 >= s1.length();
			final boolean end2 = i2 >= s2.length();
			if (end1 || end2) {
				return end1 == end2 ? 0 : (end1 ? -1 : 1);
			}
			final char c1 = Character.toLowerCase(s1.charAt(i1));
			final char c2 = Character.toLowerCase(s2.charAt(i2));
			if (c1 != c2) {
				return c1 < c2 ? -1 : 1;
			}
			i1++;
			i2++;
		}
	}

	private static int skipIgnoreChars(String value, int index) {
		while (index < value.length() && TITLE_IGNORE_CHARS.indexOf(value.charAt(index)) >= 0) {
			index++;
		}
		return index;
	}
}
