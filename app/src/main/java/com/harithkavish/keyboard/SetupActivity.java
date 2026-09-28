package com.harithkavish.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The only screen in the app: two buttons to turn the keyboard on, and a field
 * to try it in.
 *
 * <p>Built in code rather than XML. The layout is a single column of eight
 * views, and an inflater plus layout resources would cost more APK bytes and
 * more startup work than the views themselves.
 */
public final class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        column.setPadding(pad, pad, pad, pad);

        column.addView(title(getString(R.string.app_name)));
        column.addView(body(getString(R.string.setup_tagline), dp(4)));

        column.addView(heading(getString(R.string.setup_step_one), dp(32)));
        column.addView(body(getString(R.string.setup_step_one_detail), dp(2)));
        column.addView(button(getString(R.string.setup_enable), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        }));

        column.addView(heading(getString(R.string.setup_step_two), dp(24)));
        column.addView(body(getString(R.string.setup_step_two_detail), dp(2)));
        column.addView(button(getString(R.string.setup_switch), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.view.inputmethod.InputMethodManager manager =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.showInputMethodPicker();
                }
            }
        }));

        column.addView(heading(getString(R.string.setup_try), dp(32)));
        EditText field = new EditText(this);
        field.setHint(R.string.setup_try_hint);
        field.setLayoutParams(rowParams(dp(8)));
        column.addView(field);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        view.setLayoutParams(rowParams(0));
        return view;
    }

    private TextView heading(String value, int marginTop) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        view.setLayoutParams(rowParams(marginTop));
        return view;
    }

    private TextView body(String value, int marginTop) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setAlpha(0.7f);
        view.setLayoutParams(rowParams(marginTop));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(listener);
        view.setLayoutParams(rowParams(dp(10)));
        return view;
    }

    private LinearLayout.LayoutParams rowParams(int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = marginTop;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
