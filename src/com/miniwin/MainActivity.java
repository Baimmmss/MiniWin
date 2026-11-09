package com.miniwin;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dialog-style app picker. It floats over the current app, so that app stays behind the new window. */
public class MainActivity extends Activity {
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final String[] SIZE_NAMES = {"Tiny", "Small", "Medium", "Large"};
    /** Fractions of the screen. Tiny is a fixed size instead, see TINY_DP. */
    private static final float[][] SIZES = {null, {0.60f, 0.38f}, {0.75f, 0.50f}, {0.90f, 0.65f}};
    /** Below Android's minimum on purpose: the system grows the window to the smallest it allows. */
    private static final int TINY_DP = 100;
    /** Android's usual minimum window size, used to keep grown windows on screen. */
    private static final int MIN_WINDOW_DP = 220;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final ExecutorService iconLoader = Executors.newSingleThreadExecutor();
    private final TextView[] sizeButtons = new TextView[SIZE_NAMES.length];

    private SharedPreferences prefs;
    private TextView status;
    private TextView bubbleButton;
    private AppAdapter adapter;

    private static class AppEntry {
        String label;
        ComponentName component;
        ResolveInfo info;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("miniwin", MODE_PRIVATE);
        setContentView(buildUi());
        DisplayMetrics dm = realMetrics();
        getWindow().setLayout((int) (dm.widthPixels * 0.92f), (int) (dm.heightPixels * 0.82f));
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // On by default, so it appears as soon as the overlay permission is granted.
        if (FloatService.bubbleWanted(this) && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, FloatService.class).setAction(FloatService.ACTION_BUBBLE_ON));
        }
    }

    @Override
    protected void onDestroy() {
        iconLoader.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(8));
        root.setBackground(rounded(0xFF16181D, 18));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MiniWin", 20, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bubbleButton = chip("");
        bubbleButton.setOnClickListener(v -> toggleBubble());
        header.addView(bubbleButton);
        root.addView(header);

        status = text("", 13, 0xFFFFC46B);
        status.setPadding(0, dp(6), 0, dp(4));
        status.setOnClickListener(v -> onStatusTapped());
        root.addView(status);
        addXiaomiHint(root);

        LinearLayout sizes = new LinearLayout(this);
        sizes.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < SIZE_NAMES.length; i++) {
            final int index = i;
            sizeButtons[i] = chip(SIZE_NAMES[i]);
            sizeButtons[i].setOnClickListener(v -> {
                prefs.edit().putInt("size", index).apply();
                refreshSizes();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(6);
            sizes.addView(sizeButtons[i], lp);
        }
        root.addView(sizes);
        refreshSizes();

        final EditText url = field("Website or search, opens a floating browser");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setOnEditorActionListener((v, action, event) -> {
            // A hardware Enter reports both key down and key up.
            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) openWeb(url.getText().toString());
            return true;
        });
        root.addView(url);

        EditText search = field("Search apps");
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.topMargin = dp(6);
        root.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> launch(shownApps.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    /** Xiaomi blocks apps from opening screens in the background unless this switch is on. */
    private void addXiaomiHint(LinearLayout root) {
        if (!"Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || prefs.getBoolean("xiaomi_hint_done", false)) return;
        final TextView hint = text("Xiaomi: tap here, then allow \"Display pop-up windows while running in the background\". The side handle needs it.", 13, 0xFFFFC46B);
        hint.setPadding(0, 0, 0, dp(4));
        hint.setOnClickListener(v -> {
            prefs.edit().putBoolean("xiaomi_hint_done", true).apply();
            hint.setVisibility(View.GONE);
            try {
                startActivity(new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", getPackageName()));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
        root.addView(hint);
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<AppEntry> found = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                AppEntry e = new AppEntry();
                e.label = ri.loadLabel(pm).toString();
                e.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                e.info = ri;
                found.add(e);
            }
            Collections.sort(found, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                allApps.addAll(found);
                applyFilter("");
            });
        }).start();
    }

    private void applyFilter(String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        shownApps.clear();
        for (AppEntry e : allApps) {
            if (e.label.toLowerCase(Locale.ROOT).contains(needle)) shownApps.add(e);
        }
        adapter.notifyDataSetChanged();
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent, freeformOptions());
            finish();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not open " + app.label, Toast.LENGTH_LONG).show();
        }
    }

    private Bundle freeformOptions() {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(nextBounds());
        Bundle bundle = options.toBundle();
        // Same value ActivityOptions.setLaunchWindowingMode() stores; that setter is hidden from apps.
        bundle.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
        return bundle;
    }

    /** Each new window is shifted a little so stacked windows stay grabbable. */
    private Rect nextBounds() {
        DisplayMetrics dm = realMetrics();
        float[] size = SIZES[prefs.getInt("size", 0)];
        int w = size == null ? dp(TINY_DP) : (int) (dm.widthPixels * size[0]);
        int h = size == null ? dp(TINY_DP) : (int) (dm.heightPixels * size[1]);
        int step = prefs.getInt("cascade", 0);
        prefs.edit().putInt("cascade", (step + 1) % 5).apply();
        int roomW = Math.max(w, dp(MIN_WINDOW_DP));
        int roomH = Math.max(h, dp(MIN_WINDOW_DP));
        int left = Math.min((dm.widthPixels - roomW) / 2 + step * dp(14), dm.widthPixels - roomW);
        int top = Math.min(dp(70) + step * dp(28), dm.heightPixels - roomH);
        return new Rect(left, top, left + w, top + h);
    }

    private boolean freeformEnabled() {
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true;
        try {
            return Settings.Global.getInt(getContentResolver(), "enable_freeform_support", 0) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void refreshStatus() {
        bubbleButton.setText(FloatService.bubbleWanted(this) && Settings.canDrawOverlays(this) ? "Side handle: on" : "Side handle: off");
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Tap to allow \"Display over other apps\". The side handle and floating browser need it.");
        } else if (!freeformEnabled()) {
            status.setText("Apps may open full screen until window mode is switched on with install-on-phone.sh. Try one. The floating browser works now.");
        } else {
            status.setText("Ready. Tap an app to float it.");
            status.setTextColor(0xFF7FD6A4);
            return;
        }
        status.setTextColor(0xFFFFC46B);
    }

    private void onStatusTapped() {
        if (!Settings.canDrawOverlays(this)) requestOverlay();
    }

    private void requestOverlay() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void toggleBubble() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay();
            return;
        }
        boolean on = !FloatService.bubbleWanted(this);
        Intent intent = new Intent(this, FloatService.class)
                .setAction(on ? FloatService.ACTION_BUBBLE_ON : FloatService.ACTION_BUBBLE_OFF);
        startForegroundService(intent);
        prefs.edit().putBoolean(FloatService.PREF_BUBBLE, on).apply();
        refreshStatus();
    }

    private void openWeb(String input) {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay();
            return;
        }
        startForegroundService(new Intent(this, FloatService.class)
                .setAction(FloatService.ACTION_WEB)
                .putExtra(FloatService.EXTRA_URL, input));
        finish();
    }

    private void refreshSizes() {
        int selected = prefs.getInt("size", 0);
        for (int i = 0; i < sizeButtons.length; i++) {
            sizeButtons[i].setBackground(rounded(i == selected ? 0xFF4F8CFF : 0xFF262A33, 10));
        }
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 13, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(rounded(0xFF262A33, 10));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF8A8F9C);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(rounded(0xFF262A33, 10));
        return e;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int i) { return shownApps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                ImageView icon = new ImageView(MainActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView label = text("", 15, Color.WHITE);
                label.setPadding(dp(12), 0, 0, 0);
                row.addView(label);
            }
            AppEntry app = shownApps.get(position);
            ImageView icon = (ImageView) row.getChildAt(0);
            ((TextView) row.getChildAt(1)).setText(app.label);
            icon.setTag(app.component);
            Drawable cached = icons.get(app.component.flattenToString());
            icon.setImageDrawable(cached);
            if (cached == null) loadIcon(app, icon);
            return row;
        }

        private void loadIcon(AppEntry app, ImageView target) {
            iconLoader.execute(() -> {
                Drawable d = app.info.loadIcon(getPackageManager());
                runOnUiThread(() -> {
                    icons.put(app.component.flattenToString(), d);
                    if (app.component.equals(target.getTag())) target.setImageDrawable(d);
                });
            });
        }
    }
}
