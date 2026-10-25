package dev.tvhome.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: clock bar on top, live HDMI tile (left) + Now playing (right), app dock below.
 * OK on the HDMI tile grows the live picture to full screen with sound; Back shrinks it again.
 * Also the target of the remote shortcuts handled by {@link KeyService}: HDMI full screen (hold 0),
 * the desktop app picker (hold 1-3 on an empty desktop), and Home.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "TvHome";
    private static final int ALL_APPS_COLUMNS = 5;

    /** Show HDMI 1 full screen right away. */
    static final String EXTRA_HDMI = "dev.tvhome.launcher.HDMI";
    /** Show the app picker for desktop N (1-3). */
    static final String EXTRA_PICK_DESKTOP = "dev.tvhome.launcher.PICK_DESKTOP";
    /** Close desktop N's app and leave it empty (no UI). */
    static final String EXTRA_CLOSE_DESKTOP = "dev.tvhome.launcher.CLOSE_DESKTOP";
    static final String EXTRA_SET_DESKTOP = "dev.tvhome.launcher.SET_DESKTOP";
    static final String EXTRA_SET_OWNER_NAME = "dev.tvhome.launcher.SET_OWNER_NAME";
    /**
     * Save the home screen as a PNG in the app's external files dir (for the README). This TV's screencap
     * is empty while the HDMI layer is on screen and drops the background, so the launcher draws itself.
     * Now playing shows its idle state in the image, so no personal listening ends up in the screenshot.
     */
    static final String EXTRA_SNAPSHOT = "dev.tvhome.launcher.SNAPSHOT";

    /** True while HDMI is full screen and this activity is in front; read by {@link KeyService}. */
    static volatile boolean hdmiFullScreen;

    private FrameLayout root;
    private FrameLayout content;
    private TvView tvView;
    private View tileFocus;
    private TextView tileStatus;
    private NowPlayingPanel nowPlaying;
    private ScrollView allApps;
    private TextView allAppsTitle;
    private TextView closeButton;
    private View firstAppTile;
    private int pickingDesktop; // 0 = plain "All apps"; 1-3 = choosing the app for that desktop

    private final Rect tileRect = new Rect();
    private final Rect fullRect = new Rect();
    private int screenW, screenH;
    private int videoW, videoH;
    private String previewInputId;
    private boolean expanded;
    private boolean resumed;
    private ValueAnimator tvAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        // Big centered clock on top, live HDMI tile + Now playing in the middle, glass dock at the bottom.
        int pad = dp(32), gap = dp(18), clockH = dp(150), dockH = dp(104), bottom = dp(22);
        int midTop = clockH;
        int midH = h - midTop - gap - dockH - bottom;
        int tileW = midH * 16 / 9; // keep the live picture 16:9 so it isn't stretched
        tileRect.set(pad, midTop, pad + tileW, midTop + midH);
        screenW = w;
        screenH = h;
        updateFullRect();

        root = new FrameLayout(this);
        root.setBackground(new Glass.Aurora(0));
        // root must clip its children: the TvView clears its area to punch a hole for the video, and
        // unclipped that clear wipes the whole background. Popping tiles only need `content` unclipped.

        // The live picture sits under all UI; the UI layer draws the tile frame and labels over it.
        tvView = new TvView(this);
        root.addView(tvView, Ui.at(tileRect.left, tileRect.top, tileRect.width(), tileRect.height()));
        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        buildClock(clockH);
        buildCornerButtons(pad);
        buildTile();
        int panelLeft = tileRect.right + gap;
        nowPlaying = new NowPlayingPanel(this);
        content.addView(nowPlaying, Ui.at(panelLeft, midTop, w - pad - panelLeft, midH));
        buildDock(h - bottom - dockH, dockH);

        setContentView(root);

        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String inputId) {
                tileStatus.setVisibility(View.GONE);
                updateVolume();
            }
            @Override public void onVideoSizeChanged(String inputId, int width, int height) {
                // Sony's HDMI service doesn't send this today; if it ever does, full screen zooms to fill.
                Log.w(TAG, "onVideoSizeChanged " + width + "x" + height);
                setVideoSize(width, height, 1f);
            }
            @Override public void onVideoUnavailable(String inputId, int reason) {
                showTileStatus("No signal on " + Config.PREVIEW_INPUT_LABEL);
            }
            @Override public void onConnectionFailed(String inputId) {
                showTileStatus(Config.PREVIEW_INPUT_LABEL + " unavailable");
            }
            @Override public void onDisconnected(String inputId) {
                showTileStatus(Config.PREVIEW_INPUT_LABEL + " unavailable");
            }
        });
        previewInputId = findPreviewInput();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputWatcher, new android.os.Handler(getMainLooper()));
        tileFocus.requestFocus();
        handleIntent(getIntent());
    }

    // ---------- layout ----------

    /** iOS lock-screen style: date above, large 12-hour time, centered. */
    private void buildClock(int height) {
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(21);
        date.setTextColor(0xE6FFFFFF);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        // Full width: a wrap-content TextClock keeps its first measured width and clips longer dates.
        clock.addView(date, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm"); // always 12-hour, like iOS, whatever the TV's setting
        time.setFormat24Hour("h:mm");
        time.setTextSize(92);
        time.setTextColor(0xF2FFFFFF);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setLetterSpacing(-0.02f);
        time.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(2);
        clock.addView(time, tp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(560), height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(16);
        content.addView(clock, lp);
    }

    /** Settings and Power, as small glass pills in the top-right corner. */
    private void buildCornerButtons(int pad) {
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        right.addView(pill("Settings", v -> start(new Intent(Settings.ACTION_SETTINGS))));
        View power = pill("Power", v -> signOut());
        ((LinearLayout.LayoutParams) power.getLayoutParams()).leftMargin = dp(14);
        right.addView(power);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP | Gravity.END);
        sp.topMargin = dp(28);
        sp.rightMargin = pad;
        content.addView(right, sp);
    }

    /** "Signing out" + owner name, then standby. Done by the shortcut service, which may put the TV to sleep. */
    private void signOut() {
        KeyService service = KeyService.instance;
        if (service != null) service.signOut();
    }

    private TextView pill(String label, View.OnClickListener click) {
        TextView p = Ui.text(this, 15, Ui.TEXT, false);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(22), 0, dp(22), 0);
        p.setBackground(Glass.panel(dp(20), dp(1)));
        Ui.focusable(p, dp(20), true);
        p.setOnClickListener(click);
        p.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        return p;
    }

    /** Text tile the same size as an app banner, for dock actions. */
    private TextView textTile(String label, View.OnClickListener click) {
        TextView t = Ui.text(this, 15, Ui.TEXT, false);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Glass.panel(dp(14), dp(1)));
        Ui.focusable(t, dp(14), true);
        t.setOnClickListener(click);
        return t;
    }

    private void buildTile() {
        tileFocus = new View(this);
        tileFocus.setBackground(Ui.ring(0x1AFFFFFF, Math.max(1, dp(0.75f)), dp(14)));
        Ui.focusable(tileFocus, dp(14), false);
        tileFocus.setOnClickListener(v -> expand());
        content.addView(tileFocus, Ui.at(tileRect.left, tileRect.top, tileRect.width(), tileRect.height()));

        tileStatus = Ui.text(this, 16, Ui.TEXT_DIM, false);
        tileStatus.setGravity(Gravity.CENTER);
        content.addView(tileStatus, Ui.at(tileRect.left, tileRect.top, tileRect.width(), tileRect.height()));

        TextView label = Ui.text(this, 13, Ui.TEXT, false);
        label.setText(Config.PREVIEW_INPUT_LABEL + "  ·  " + Config.PREVIEW_NAME);
        label.setPadding(dp(14), dp(6), dp(14), dp(6));
        label.setBackground(Glass.tinted(dp(14), dp(1), 0x000000));
        FrameLayout.LayoutParams lp = Ui.at(tileRect.left + dp(14), tileRect.bottom - dp(42), FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        content.addView(label, lp);
    }

    /** Glass dock centered at the bottom, like the macOS / iPadOS dock. */
    private void buildDock(int top, int height) {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        int inner = dp(16);
        dock.setPadding(inner, 0, inner, 0);
        dock.setBackground(Glass.panel(dp(26), dp(1)));

        int tileH = dp(70), tileW = dp(124), gap = dp(9);
        PackageManager pm = getPackageManager();
        for (String pkg : Config.DOCK) {
            if (!isInstalled(pm, pkg)) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, tileH);
            p.leftMargin = p.rightMargin = gap;
            dock.addView(appTile(pkg, tileW, tileH), p);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, tileH);
        p.leftMargin = p.rightMargin = gap;
        dock.addView(textTile("All apps", v -> showAllApps(0)), p);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = top;
        content.addView(dock, lp);
    }

    /** App banner tile (Android TV apps ship a 16:9 banner); falls back to icon + name on glass. */
    private View appTile(String pkg, int w, int h) {
        PackageManager pm = getPackageManager();
        FrameLayout tile = new FrameLayout(this);
        float radius = dp(14);
        tile.setBackground(Glass.panel(radius, dp(1)));
        tile.setClipToOutline(true);

        Drawable banner = null;
        try {
            banner = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(banner);
            tile.addView(img, new FrameLayout.LayoutParams(w, h));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            int iconSize = Math.min(dp(40), h / 2);
            col.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView name = Ui.text(this, 12, Ui.TEXT, false);
            name.setText(label(pm, pkg));
            name.setSingleLine(true);
            name.setPadding(dp(8), dp(5), dp(8), 0);
            col.addView(name);
            tile.addView(col, new FrameLayout.LayoutParams(w, h));
        }
        Ui.focusable(tile, radius, true);
        tile.setOnClickListener(v -> {
            if (pickingDesktop > 0) {
                // Picking for a desktop: that desktop now holds this app.
                Desktops.set(this, pickingDesktop, pkg);
                hideAllApps();
            }
            launch(pkg);
        });
        return tile;
    }

    /**
     * Empty a desktop. The app itself stays in Android's background cache until memory is needed:
     * this firmware rejects other apps' requests to end apps (killBackgroundProcesses -> "Invalid packageName").
     */
    private void closeDesktop(int desktop) {
        Desktops.set(this, desktop, null);
    }

    /**
     * All apps over the aurora. With desktop 1-3 it's that desktop's tray. Tile size is computed from
     * the screen width so columns always fit, with room around each tile for the focus pop.
     */
    private void showAllApps(int desktop) {
        if (allApps == null) {
            PackageManager pm = getPackageManager();
            List<String> pkgs = new ArrayList<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkgs.contains(pkg) && !pkg.equals(getPackageName())) pkgs.add(pkg);
            }
            pkgs.sort((a, b) -> label(pm, a).compareToIgnoreCase(label(pm, b)));

            int side = dp(72), gapX = dp(30), gapY = dp(30);
            int tileW = (screenW - 2 * side - (ALL_APPS_COLUMNS - 1) * gapX) / ALL_APPS_COLUMNS;
            int tileH = tileW * 9 / 16;

            LinearLayout grid = new LinearLayout(this);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(side, dp(44), side, dp(64));
            grid.setClipChildren(false);
            grid.setClipToPadding(false);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setClipChildren(false);
            allAppsTitle = Ui.text(this, 30, Ui.TEXT, false);
            allAppsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.addView(allAppsTitle);
            // Desktop tray only: close the desktop's app and leave it empty.
            closeButton = pill("Close", v -> {
                closeDesktop(pickingDesktop);
                showAllApps(pickingDesktop);
            });
            closeButton.setBackground(Glass.tinted(dp(20), dp(1), 0xFF4D5E));
            ((LinearLayout.LayoutParams) closeButton.getLayoutParams()).leftMargin = dp(28);
            header.addView(closeButton);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.bottomMargin = dp(30);
            grid.addView(header, hp);

            LinearLayout row = null;
            for (int i = 0; i < pkgs.size(); i++) {
                if (i % ALL_APPS_COLUMNS == 0) {
                    row = new LinearLayout(this);
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rp.bottomMargin = gapY;
                    grid.addView(row, rp);
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, tileH);
                if (i % ALL_APPS_COLUMNS != 0) p.leftMargin = gapX;
                View t = appTile(pkgs.get(i), tileW, tileH);
                if (i == 0) firstAppTile = t;
                row.addView(t, p);
            }
            allApps = new ScrollView(this);
            allApps.setClipChildren(false);
            allApps.setClipToPadding(false);
            allApps.setVerticalScrollBarEnabled(false);
            allApps.setBackground(new Glass.Aurora(0x8C03040C));
            allApps.addView(grid);
            root.addView(allApps, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        pickingDesktop = desktop;
        String current = desktop > 0 ? Desktops.get(this, desktop) : null;
        PackageManager pm = getPackageManager();
        if (desktop == 0) allAppsTitle.setText("All apps");
        else if (current == null) allAppsTitle.setText("Desktop " + desktop + "  ·  pick an app");
        else allAppsTitle.setText("Desktop " + desktop + "  ·  " + label(pm, current));
        closeButton.setText(current == null ? "Close" : "Close " + label(pm, current));
        closeButton.setVisibility(current != null ? View.VISIBLE : View.GONE);
        allApps.setVisibility(View.VISIBLE);
        allApps.scrollTo(0, 0);
        if (firstAppTile != null) firstAppTile.requestFocus();
        else allApps.requestFocus(View.FOCUS_DOWN);
    }

    private boolean hideAllApps() {
        pickingDesktop = 0;
        if (allApps == null || allApps.getVisibility() != View.VISIBLE) return false;
        allApps.setVisibility(View.GONE);
        tileFocus.requestFocus();
        return true;
    }

    // ---------- live HDMI tile ----------

    private String findPreviewInput() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return null;
        String byHw = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || !info.isPassthroughInput()) continue;
            if (Config.PREVIEW_INPUT_LABEL.contentEquals(info.loadLabel(this))) return info.getId();
            if (info.getId().endsWith(Config.PREVIEW_INPUT_HW_SUFFIX)) byHw = info.getId();
        }
        return byHw;
    }

    /**
     * Right after a restart the launcher starts before Sony's HDMI service has registered its inputs,
     * so HDMI 1 isn't in the list yet: look again until it is, and also when the TV announces it.
     */
    private final Runnable retryFindInput = new Runnable() {
        @Override public void run() {
            if (previewInputId != null || !resumed) return;
            previewInputId = findPreviewInput();
            if (previewInputId != null) tunePreview();
            else tvView.postDelayed(this, 1000);
        }
    };

    private final TvInputManager.TvInputCallback inputWatcher = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String inputId) {
            if (previewInputId == null) retryFindInput.run();
        }
        @Override public void onInputStateChanged(String inputId, int state) {
            if (previewInputId == null) retryFindInput.run();
        }
    };

    private void tunePreview() {
        if (previewInputId == null) previewInputId = findPreviewInput();
        if (previewInputId == null) {
            showTileStatus("Waiting for " + Config.PREVIEW_INPUT_LABEL + "…");
            tvView.removeCallbacks(retryFindInput);
            tvView.postDelayed(retryFindInput, 1000);
            return;
        }
        showTileStatus("Connecting to " + Config.PREVIEW_INPUT_LABEL + "…");
        tvView.tune(previewInputId, TvContract.buildChannelUriForPassthroughInput(previewInputId));
        updateVolume();
    }

    private void showTileStatus(String s) {
        tileStatus.setText(s);
        tileStatus.setVisibility(View.VISIBLE);
    }

    private void expand() {
        if (previewInputId == null || expanded) return;
        expanded = true;
        updateFullScreenFlag();
        updateVolume();
        content.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (expanded) content.setVisibility(View.INVISIBLE);
        }).start();
        animateTv(tileRect, fullRect);
    }

    /** Hold-0 shortcut: straight to full screen, no animation (HDMI 1 tunes in as soon as it's found). */
    private void expandNow() {
        expanded = true;
        updateFullScreenFlag();
        if (tvAnimator != null) tvAnimator.cancel();
        content.animate().cancel();
        content.setVisibility(View.INVISIBLE);
        setTvRect(fullRect);
        updateVolume();
    }

    private void collapse(boolean animate) {
        if (!expanded) return;
        expanded = false;
        updateFullScreenFlag();
        updateVolume();
        content.setVisibility(View.VISIBLE);
        if (animate) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setStartDelay(120).setDuration(180).start();
            animateTv(fullRect, tileRect);
        } else {
            if (tvAnimator != null) tvAnimator.cancel();
            content.animate().cancel();
            content.setAlpha(1f);
            setTvRect(tileRect);
        }
        tileFocus.requestFocus();
    }

    private void updateVolume() {
        tvView.setStreamVolume(expanded ? 1f : 0f);
    }

    private void updateFullScreenFlag() {
        hdmiFullScreen = expanded && resumed;
    }

    private void animateTv(Rect from, Rect to) {
        if (tvAnimator != null) tvAnimator.cancel();
        Rect r = new Rect();
        tvAnimator = ValueAnimator.ofFloat(0f, 1f);
        tvAnimator.setDuration(260);
        tvAnimator.setInterpolator(new DecelerateInterpolator());
        tvAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            r.set(lerp(from.left, to.left, f), lerp(from.top, to.top, f), lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f));
            setTvRect(r);
        });
        tvAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { setTvRect(to); }
        });
        tvAnimator.start();
    }

    private void setVideoSize(int width, int height, float pixelAspect) {
        if (width <= 0 || height <= 0) return;
        videoW = Math.round(width * (pixelAspect > 0 ? pixelAspect : 1f));
        videoH = height;
        updateFullRect();
        if (expanded && (tvAnimator == null || !tvAnimator.isRunning())) setTvRect(fullRect);
    }

    /**
     * Full screen fills the panel edge to edge. The input keeps the signal's shape inside whatever
     * box it gets, so a non-16:9 signal (e.g. a 16:10 laptop) would get black bars; instead the box
     * takes the signal's shape and is sized to cover the screen, cropping the small overflow.
     */
    private void updateFullRect() {
        if (videoW <= 0 || videoH <= 0) {
            fullRect.set(0, 0, screenW, screenH);
            return;
        }
        float scale = Math.max((float) screenW / videoW, (float) screenH / videoH);
        int w = Math.round(videoW * scale), h = Math.round(videoH * scale);
        int left = (screenW - w) / 2, top = (screenH - h) / 2;
        fullRect.set(left, top, left + w, top + h);
    }

    private void setTvRect(Rect r) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) tvView.getLayoutParams();
        p.leftMargin = r.left;
        p.topMargin = r.top;
        p.width = r.width();
        p.height = r.height();
        tvView.setLayoutParams(p);
    }

    private static int lerp(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }

    // ---------- lifecycle, intents and keys ----------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateFullScreenFlag();
        nowPlaying.start();
        tunePreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        nowPlaying.stop();
        tvView.removeCallbacks(retryFindInput);
        collapse(false);
        hideAllApps();
        updateFullScreenFlag();
        // Release the video pipeline so the app being opened can play.
        tvView.reset();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nowPlaying.shutdown();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputWatcher);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Hold-0 (HDMI), desktop picker, or Home: Home always lands on the plain home layout. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_SNAPSHOT, false)) {
            collapse(false);
            hideAllApps();
            // After onResume (which restarts Now playing): switch the panel to idle so no track or album
            // art is in the image, let it lay out, draw, then bring Now playing back.
            root.postDelayed(() -> {
                nowPlaying.stop();
                root.postDelayed(() -> {
                    saveSnapshot();
                    if (resumed) nowPlaying.start();
                }, 400);
            }, 800);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_HDMI, false)) {
            hideAllApps();
            expandNow();
            return;
        }
        // Owner name for the welcome / signing-out screens (set by tv-home.ps1, kept out of the source).
        if (intent.hasExtra(EXTRA_SET_OWNER_NAME)) {
            Desktops.setOwnerName(this, intent.getStringExtra(EXTRA_SET_OWNER_NAME));
            return;
        }
        // "n:package" sets a desktop's app without opening it (setup over adb).
        String assign = intent.getStringExtra(EXTRA_SET_DESKTOP);
        if (assign != null && assign.matches("[1-3]:.+")) {
            Desktops.set(this, assign.charAt(0) - '0', assign.substring(2));
            return;
        }
        int close = intent.getIntExtra(EXTRA_CLOSE_DESKTOP, 0);
        if (close > 0) {
            closeDesktop(close);
            return;
        }
        int desktop = intent.getIntExtra(EXTRA_PICK_DESKTOP, 0);
        if (desktop > 0) {
            collapse(false);
            showAllApps(desktop);
            return;
        }
        hideAllApps();
        if (expanded) collapse(true);
        else tileFocus.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Nothing here takes digits. Swallow them, or Sony's TV app catches the unused key and jumps
        // to the TV input with a channel number on screen.
        int k = e.getKeyCode();
        if ((k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) || (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9)) {
            return true;
        }
        if (expanded) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) collapse(true);
                return true;
            }
            // Full screen is just the picture: swallow navigation so nothing hidden gets focus.
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                    || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // This is the home screen: Back only closes overlays, never leaves.
        hideAllApps();
    }

    // ---------- helpers ----------

    /** Draws the current screen into files/home.png (adb: /sdcard/Android/data/<pkg>/files/home.png). */
    private void saveSnapshot() {
        // The live HDMI picture isn't part of the view tree (the TvView only cuts a hole), so the tile is
        // shown as a black screen with a caption.
        int statusWas = tileStatus.getVisibility();
        CharSequence statusText = tileStatus.getText();
        showTileStatus("Live " + Config.PREVIEW_INPUT_LABEL + " preview");
        Bitmap ui = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(ui));
        tileStatus.setText(statusText);
        tileStatus.setVisibility(statusWas);

        Bitmap out = Bitmap.createBitmap(ui.getWidth(), ui.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF000000);
        canvas.drawBitmap(ui, 0, 0, null);
        ui.recycle();
        File file = new File(getExternalFilesDir(null), "home.png");
        try (FileOutputStream os = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, os);
            Log.i(TAG, "snapshot saved: " + file);
        } catch (IOException e) {
            Log.w(TAG, "snapshot failed", e);
        } finally {
            out.recycle();
        }
    }

    private void launch(String pkg) {
        Intent i = launchIntent(this, pkg);
        if (i != null) start(i);
    }

    /** The app's TV launch intent; starting it brings an already-running app back as it was. */
    static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
