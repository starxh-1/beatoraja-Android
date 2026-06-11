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

public class CharacterWheelDialog extends Dialog {

    public interface OnTextConfirmedListener {
        void onTextConfirmed(String text);
    }

    private String currentText;
    private final OnTextConfirmedListener listener;
    private TextView displayTextView;
    private boolean isUppercase = true;
    private CharAdapter adapter;

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

    public CharacterWheelDialog(Context context, String initialText, OnTextConfirmedListener listener) {
        super(context);
        this.currentText = initialText != null ? initialText : "";
        this.listener = listener;
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
        gridView.setNumColumns(9);
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
        @Override public int getCount() { return UPPER_CHARS.length; }
        @Override public Object getItem(int position) { return isUppercase ? UPPER_CHARS[position] : LOWER_CHARS[position]; }
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

            final String val = isUppercase ? UPPER_CHARS[position] : LOWER_CHARS[position];
            btn.setText(val);
            btn.setOnClickListener(v -> {
                if (val.equals("OK")) {
                    if (listener != null) listener.onTextConfirmed(currentText);
                    dismiss();
                } else if (val.equals("DEL")) {
                    if (!currentText.isEmpty()) {
                        currentText = currentText.substring(0, currentText.length() - 1);
                        displayTextView.setText(currentText);
                    }
                } else if (val.equals("SHIFT")) {
                    isUppercase = !isUppercase;
                    notifyDataSetChanged();
                } else {
                    currentText += val;
                    displayTextView.setText(currentText);
                }
            });
            return btn;
        }
    }
}
