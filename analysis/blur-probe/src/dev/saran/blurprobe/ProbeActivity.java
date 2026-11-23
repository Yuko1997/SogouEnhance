package dev.saran.blurprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private static final String EXTRA_MODE = "mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        boolean webMode = intent != null
                && "web".equals(intent.getStringExtra(EXTRA_MODE));
        if (webMode) {
            setContentView(createWebLayout());
        } else {
            setContentView(createNativeLayout());
        }
    }

    private View createNativeLayout() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new PatternView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        EditText editor = createEditor("Native EditText");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        params.gravity = android.view.Gravity.BOTTOM;
        params.leftMargin = dp(24);
        params.rightMargin = dp(24);
        params.bottomMargin = dp(48);
        root.addView(editor, params);
        focusEditor(editor);
        return root;
    }

    private View createWebLayout() {
        FrameLayout root = new FrameLayout(this);
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadDataWithBaseURL(null, ""
                + "<html><body style='margin:0;background:"
                + "linear-gradient(135deg,#ff1744,#2979ff 48%,#00e676);"
                + "font:700 48px sans-serif;color:white'>"
                + "<div style='padding:32px'>WebView input surface"
                + "<div style='height:45vh'></div>"
                + "<input id='q' autofocus value='Blur Probe Web' "
                + "style='width:88%;height:88px;font-size:38px'>"
                + "</div></body></html>",
                "text/html", "UTF-8", null);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.postDelayed(() -> webView.evaluateJavascript(
                "document.getElementById('q').focus()", null), 800);
        return root;
    }

    private EditText createEditor(String hint) {
        EditText editor = new EditText(this);
        editor.setHint(hint);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(Color.WHITE);
        return editor;
    }

    private void focusEditor(EditText editor) {
        editor.requestFocus();
        editor.postDelayed(() -> {
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            inputMethodManager.showSoftInput(
                    editor, InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PatternView(Activity context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setShader(new LinearGradient(
                    0, 0, getWidth(), getHeight(),
                    new int[]{
                            Color.rgb(255, 23, 68),
                            Color.rgb(255, 235, 59),
                            Color.rgb(0, 230, 118),
                            Color.rgb(41, 121, 255)
                    },
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setColor(Color.WHITE);
            paint.setTextSize(getResources().getDisplayMetrics().density * 42);
            canvas.drawText("Native View surface", 48,
                    getResources().getDisplayMetrics().density * 150, paint);
        }
    }
}
