package com.miniwin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A movable, resizable browser window with tabs, drawn as an overlay above whatever app is open. */
class WebWindow {
    private static class Tab {
        WebView web;
        TextView chip;
    }

    private final FloatService service;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final List<Tab> tabs = new ArrayList<>();
    private final FrameLayout root;
    private final LinearLayout tabStrip;
    private final LinearLayout urlBar;
    private final FrameLayout content;
    private final View resizeHandle;
    private final EditText urlField;
    private Tab current;
    private boolean collapsed;
    private int expandedHeight;

    WebWindow(FloatService service, String input, int cascade) {
        this.service = service;
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        boolean tiny = service.getSharedPreferences("miniwin", Context.MODE_PRIVATE).getInt("size", 0) == 0;
        expandedHeight = tiny ? dp(180) : (int) (dm.heightPixels * 0.5f);
        lp = new WindowManager.LayoutParams(tiny ? dp(180) : (int) (dm.widthPixels * 0.85f), expandedHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16) + cascade * dp(14);
        lp.y = dp(90) + cascade * dp(28);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        // The window only takes keyboard focus while it is being used, so the app behind keeps working.
        root = new FrameLayout(service) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    takeFocus(false);
                } else if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    takeFocus(true);
                }
                return super.dispatchTouchEvent(e);
            }

            private void takeFocus(boolean focusable) {
                int flags = focusable
                        ? lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        : lp.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                if (flags != lp.flags) {
                    lp.flags = flags;
                    wm.updateViewLayout(this, lp);
                }
            }
        };
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF16181D);
        frame.setCornerRadius(dp(12));
        frame.setStroke(dp(1), 0xFF3A3F4B);
        root.setBackground(frame);
        root.setClipToOutline(true);

        LinearLayout column = new LinearLayout(service);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleBar = new LinearLayout(service);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(4), 0, 0, 0);
        attachDrag(titleBar);

        HorizontalScrollView scroller = new HorizontalScrollView(service);
        scroller.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(service);
        scroller.addView(tabStrip);
        titleBar.addView(scroller, new LinearLayout.LayoutParams(0, dp(40), 1));

        titleBar.addView(barButton("+", v -> addTab("https://www.google.com")));
        titleBar.addView(barButton("–", v -> toggleCollapsed()));
        titleBar.addView(barButton("✕", v -> close()));
        column.addView(titleBar);

        urlBar = new LinearLayout(service);
        urlBar.setGravity(Gravity.CENTER_VERTICAL);
        urlBar.setPadding(dp(4), 0, dp(8), dp(4));
        urlBar.addView(barButton("‹", v -> {
            if (current != null && current.web.canGoBack()) current.web.goBack();
        }));
        urlField = new EditText(service);
        urlField.setSingleLine(true);
        urlField.setTextSize(13);
        urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(0xFF8A8F9C);
        urlField.setHint("Website or search");
        urlField.setPadding(dp(10), dp(6), dp(10), dp(6));
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFF262A33);
        fieldBg.setCornerRadius(dp(8));
        urlField.setBackground(fieldBg);
        urlField.setOnEditorActionListener((v, action, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (current != null) current.web.loadUrl(normalize(urlField.getText().toString()));
            service.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(urlField.getWindowToken(), 0);
            urlField.clearFocus();
            return true;
        });
        urlBar.addView(urlField, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(urlBar);

        content = new FrameLayout(service);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        resizeHandle = buildResizeHandle();
        root.addView(resizeHandle, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM | Gravity.END));

        wm.addView(root, lp);
        root.post(() -> addTab(normalize(input)));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void addTab(String url) {
        final Tab tab = new Tab();
        tab.web = new WebView(service);
        WebSettings s = tab.web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        tab.web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String loaded) {
                String title = view.getTitle();
                tab.chip.setText(shorten(title == null || title.isEmpty() ? loaded : title));
                if (tab == current && !urlField.hasFocus()) urlField.setText(loaded);
            }
        });
        tab.chip = new TextView(service);
        tab.chip.setTextSize(12);
        tab.chip.setTextColor(Color.WHITE);
        tab.chip.setSingleLine(true);
        tab.chip.setText("New tab");
        tab.chip.setGravity(Gravity.CENTER);
        tab.chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        tab.chip.setOnClickListener(v -> selectTab(tab));
        tab.chip.setOnLongClickListener(v -> {
            closeTab(tab);
            return true;
        });
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.gravity = Gravity.CENTER_VERTICAL;
        chipLp.rightMargin = dp(4);
        tabStrip.addView(tab.chip, chipLp);
        content.addView(tab.web, new FrameLayout.LayoutParams(-1, -1));
        tabs.add(tab);
        selectTab(tab);
        tab.web.loadUrl(url);
    }

    private void selectTab(Tab tab) {
        current = tab;
        for (Tab t : tabs) {
            boolean active = t == tab;
            t.web.setVisibility(active ? View.VISIBLE : View.GONE);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? 0xFF4F8CFF : 0xFF262A33);
            bg.setCornerRadius(dp(8));
            t.chip.setBackground(bg);
        }
        urlField.setText(tab.web.getUrl());
    }

    private void closeTab(Tab tab) {
        if (tabs.size() == 1) {
            close();
            return;
        }
        int index = tabs.indexOf(tab);
        tabs.remove(tab);
        tabStrip.removeView(tab.chip);
        content.removeView(tab.web);
        tab.web.destroy();
        if (tab == current) selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        urlBar.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        resizeHandle.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        lp.height = collapsed ? dp(40) : expandedHeight;
        wm.updateViewLayout(root, lp);
    }

    private void close() {
        destroy();
        service.onWebWindowClosed(this);
    }

    void destroy() {
        for (Tab t : tabs) t.web.destroy();
        tabs.clear();
        if (root.isAttachedToWindow()) wm.removeView(root);
    }

    private TextView barButton(String label, View.OnClickListener onClick) {
        TextView b = new TextView(service);
        b.setText(label);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(38));
        b.setMinHeight(dp(40));
        b.setOnClickListener(onClick);
        return b;
    }

    private View buildResizeHandle() {
        TextView handle = new TextView(service);
        handle.setText("◢");
        handle.setTextSize(16);
        handle.setTextColor(0xFF8A8F9C);
        handle.setGravity(Gravity.BOTTOM | Gravity.END);
        handle.setPadding(0, 0, dp(4), 0);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float rawX, rawY;
            int w, h;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        rawX = e.getRawX();
                        rawY = e.getRawY();
                        w = root.getWidth();
                        h = root.getHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.width = Math.max(dp(180), w + (int) (e.getRawX() - rawX));
                        lp.height = Math.max(dp(160), h + (int) (e.getRawY() - rawY));
                        expandedHeight = lp.height;
                        wm.updateViewLayout(root, lp);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return handle;
    }

    private void attachDrag(View bar) {
        bar.setOnTouchListener(new View.OnTouchListener() {
            float rawX, rawY;
            int x, y;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        rawX = e.getRawX();
                        rawY = e.getRawY();
                        x = lp.x;
                        y = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = x + (int) (e.getRawX() - rawX);
                        lp.y = y + (int) (e.getRawY() - rawY);
                        wm.updateViewLayout(root, lp);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private static String shorten(String s) {
        return s.length() > 18 ? s.substring(0, 17) + "…" : s;
    }

    static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return "https://www.google.com";
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (s.contains(".") && !s.contains(" ")) return "https://" + s;
        return "https://www.google.com/search?q=" + Uri.encode(s);
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
