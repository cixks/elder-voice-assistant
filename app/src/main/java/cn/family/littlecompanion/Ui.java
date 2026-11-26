package cn.family.littlecompanion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int GREEN = Color.rgb(23, 107, 91);
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(int color, int radius, Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radius));
        return d;
    }
    static TextView text(Context context, String content, int size) {
        TextView v = new TextView(context);
        v.setText(content); v.setTextSize(size); v.setTextColor(Color.rgb(28, 48, 43));
        v.setPadding(0, dp(context, 6), 0, dp(context, 6));
        return v;
    }
    static Button button(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(17); button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }
    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = dp(context, 20); layout.setPadding(p, p, p, p);
        return layout;
    }
}
