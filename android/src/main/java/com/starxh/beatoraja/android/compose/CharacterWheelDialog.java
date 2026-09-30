package com.starxh.beatoraja.android.compose;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.starxh.beatoraja.R;

/**
 * 手柄用的字符输入轮盘（键盘不可用 / 只用手柄时的文本输入替代品）。
 * <p>
 * 键集可以按输入框的内容类型指定：数字框只给数字键，文本框给全键盘。
 */
public class CharacterWheelDialog extends Dialog {

    public interface OnTextConfirmedListener {
        void onTextConfirmed(String text);
    }

    private String currentText;
    private final OnTextConfirmedListener listener;
    private TextView displayTextView;
    private boolean isUppercase = true;
    private CharAdapter adapter;

    /** 大写键集 */
    private final String[] upperChars;
    /** 小写键集；与 {@link #upperChars} 同一个数组 = 没有大小写之分（数字键盘） */
    private final String[] lowerChars;
    private final int numColumns;
    /**
     * 数字键盘：按下的第一个键**顶掉**原有数值（数值一般是整段重打，
     * 不必先按 DELETE 删三次），之后的键正常追加。
     */
    private final boolean replaceOnFirstInput;
    private boolean inputReplaced = false;

    private static final String[] UPPER_CHARS = {
        "A", "B", "C", "D", "E", "F", "G", "H", "I",
        "J", "K", "L", "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X", "Y", "Z", "0",
        "1", "2", "3", "4", "5", "6", "7", "8", "9",
        ".", ":", "/", "?", "=", "&", "_", "-", "@",
        "SHIFT", " ", "DEL", "OK"
    };

    private static final String[] LOWER_CHARS = {
        "a", "b", "c", "d", "e", "f", "g", "h", "i",
        "j", "k", "l", "m", "n", "o", "p", "q", "r",
        "s", "t", "u", "v", "w", "x", "y", "z", "0",
        "1", "2", "3", "4", "5", "6", "7", "8", "9",
        ".", ":", "/", "?", "=", "&", "_", "-", "@",
        "SHIFT", " ", "DEL", "OK"
    };

    /** 数字键盘（{@code inputType=number}）：3 列 → [1 2 3][4 5 6][7 8 9][0 DEL OK] */
    public static final String[] NUMBER_CHARS = {
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "DEL", "OK"
    };

    /** 带符号的数字键盘（{@code number|numberSigned}，如 note timing offset） */
    public static final String[] SIGNED_NUMBER_CHARS = {
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "0", "DEL", "OK"
    };

    /** 数字键集固定 3 列（比全键盘的 9 列更适合十字键：一格一格推，少走很多冤枉路） */
    public static final int NUMBER_COLUMNS = 3;
    /** 全键盘 9 列 */
    public static final int TEXT_COLUMNS = 9;

    /** 全键盘版（文本 / URL 输入框用） */
    public CharacterWheelDialog(Context context, String initialText, OnTextConfirmedListener listener) {
        this(context, initialText, null, TEXT_COLUMNS, listener);
    }

    /**
     * @param fixedChars 固定键集（如 {@link #NUMBER_CHARS}）；传 {@code null} 使用全键盘
     * @param numColumns 每行几列
     */
    public CharacterWheelDialog(Context context, String initialText,
                                String[] fixedChars, int numColumns,
                                OnTextConfirmedListener listener) {
        super(context);
        this.currentText = initialText != null ? initialText : "";
        this.listener = listener;
        this.numColumns = numColumns > 0 ? numColumns : TEXT_COLUMNS;
        if (fixedChars != null) {
            this.upperChars = fixedChars;
            this.lowerChars = fixedChars;
            this.replaceOnFirstInput = true;
        } else {
            this.upperChars = UPPER_CHARS;
            this.lowerChars = LOWER_CHARS;
            this.replaceOnFirstInput = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundColor(0xFF222222);

        displayTextView = new TextView(getContext());
        displayTextView.setText(currentText);
        displayTextView.setTextSize(20);
        displayTextView.setTextColor(0xFFFFFFFF);
        displayTextView.setPadding(16, 16, 16, 16);
        displayTextView.setBackgroundColor(0xFF111111);
        layout.addView(displayTextView);

        GridView gridView = new GridView(getContext());
        gridView.setNumColumns(numColumns);
        gridView.setPadding(0, 32, 0, 0);
        adapter = new CharAdapter();
        gridView.setAdapter(adapter);
        gridView.setFocusable(false);
        gridView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        layout.addView(gridView);

        setContentView(layout);
        if (getWindow() != null) {
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, @androidx.annotation.NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            dismiss();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private class CharAdapter extends BaseAdapter {
        @Override public int getCount() { return upperChars.length; }
        @Override public Object getItem(int position) { return isUppercase ? upperChars[position] : lowerChars[position]; }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Button btn;
            if (convertView instanceof Button) {
                btn = (Button) convertView;
            } else {
                btn = new Button(getContext());
                btn.setFocusable(true);
                btn.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
            }

            final String val = isUppercase ? upperChars[position] : lowerChars[position];
            btn.setText(val);
            btn.setOnClickListener(v -> {
                if (val.equals("OK")) {
                    if (listener != null) listener.onTextConfirmed(currentText);
                    dismiss();
                } else if (val.equals("DEL")) {
                    // 已经动过 DEL = 用户是在改原值，之后再按数字键不该再顶掉整串
                    inputReplaced = true;
                    if (!currentText.isEmpty()) {
                        currentText = currentText.substring(0, currentText.length() - 1);
                        displayTextView.setText(currentText);
                    }
                } else if (val.equals("SHIFT")) {
                    isUppercase = !isUppercase;
                    notifyDataSetChanged();
                } else {
                    if (replaceOnFirstInput && !inputReplaced) {
                        // 数字键盘：第一个键顶掉原值（数值一般整段重打）
                        currentText = val;
                        inputReplaced = true;
                    } else {
                        currentText += val;
                    }
                    displayTextView.setText(currentText);
                }
            });
            return btn;
        }
    }
}
