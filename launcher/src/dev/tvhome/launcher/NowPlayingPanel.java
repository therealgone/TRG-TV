package dev.tvhome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Right-hand tile: whatever is playing (Spotify first), with art, progress and transport buttons. */
final class NowPlayingPanel extends LinearLayout {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MediaSessionManager sessions;
    private final ComponentName listenerComponent;

    private final TextView source, title, artist, elapsed, duration;
    private final ImageView art;
    private final ProgressBar progress;
    private final ImageButton prev, playPause, next;
    private final LinearLayout controls;
    private final LinearLayout open;

    /** Layout is designed at 368x288dp; in tiling panes everything scales with the pane. */
    private static final float BASE_W_DP = 368, BASE_H_DP = 288;
    private float scale = 1f;
    private final Runnable rescale = this::applyScale;

    private MediaController controller;
    private boolean started;
    private String artKey;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::pick;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { render(); }
        @Override public void onSessionDestroyed() { refreshSessions(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (started) main.postDelayed(this, 1000);
        }
    };

    NowPlayingPanel(Context c) {
        super(c);
        sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(c, MediaListener.class);

        setOrientation(VERTICAL);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);
        setBackground(Glass.panel(dp(18), dp(1)));
        setClipChildren(false);

        source = Ui.text(c, 13, Ui.ACCENT, true);
        addView(source);

        // Art + title block; OK on it opens the music app.
        open = new LinearLayout(c);
        open.setOrientation(HORIZONTAL);
        open.setGravity(Gravity.CENTER_VERTICAL);
        open.setPadding(dp(6), dp(6), dp(6), dp(6));
        Ui.focusable(open, dp(12), false);
        open.setOnClickListener(v -> openMusicApp());
        LayoutParams op = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(8);
        addView(open, op);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Glass.panel(dp(12), dp(1)));
        art.setClipToOutline(true);
        open.addView(art, new LayoutParams(dp(108), dp(108)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        LayoutParams tp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(16);
        open.addView(text, tp);
        title = Ui.text(c, 20, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        artist = Ui.text(c, 15, Ui.TEXT_DIM, false);
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(6), 0, 0);
        text.addView(artist);

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        LayoutParams pp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(4));
        pp.topMargin = dp(14);
        addView(progress, pp);

        LinearLayout times = new LinearLayout(c);
        elapsed = Ui.text(c, 12, Ui.TEXT_DIM, false);
        duration = Ui.text(c, 12, Ui.TEXT_DIM, false);
        duration.setGravity(Gravity.END);
        times.addView(elapsed, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        times.addView(duration, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams tsp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tsp.topMargin = dp(6);
        addView(times, tsp);

        controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.setClipChildren(false);
        prev = control(android.R.drawable.ic_media_previous, () -> controller.getTransportControls().skipToPrevious());
        playPause = control(android.R.drawable.ic_media_play, this::togglePlay);
        next = control(android.R.drawable.ic_media_next, () -> controller.getTransportControls().skipToNext());
        controls.addView(prev);
        controls.addView(playPause);
        controls.addView(next);
        LayoutParams cp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        addView(controls, cp);

        Ui.focusable(this, dp(16), false);
        setSelectable(false);
        render();
    }

    private ImageButton control(int icon, Runnable action) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        b.setBackground(Glass.panel(dp(24), dp(1)));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        Ui.focusable(b, dp(24), true);
        b.setOnClickListener(v -> {
            if (controller != null) action.run();
        });
        LayoutParams p = new LayoutParams(dp(48), dp(48));
        p.leftMargin = p.rightMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    // ---------- pane behaviour (tiling mode) ----------

    /**
     * Selectable: the whole panel is one focus target (a tiling pane) and its buttons are unreachable.
     * Otherwise the buttons inside take focus as usual.
     */
    void setSelectable(boolean selectable) {
        setFocusable(selectable);
        setDescendantFocusability(selectable ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
        if (!selectable && hasFocus() && findFocus() == this) clearFocus();
    }

    /** Focus the play/pause button, or the open-app block when nothing is playing. */
    void focusControls() {
        if (controls.getVisibility() == VISIBLE) playPause.requestFocus();
        else open.requestFocus();
    }

    void setPaneBackground(boolean opaque, float radiusPx) {
        setBackground(Ui.rounded(opaque ? (Ui.PANEL | 0xFF000000) : Ui.PANEL, radiusPx));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Debounced: pane animations resize every frame; re-layout once it settles.
        removeCallbacks(rescale);
        postDelayed(rescale, 60);
    }

    private void applyScale() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float s = Math.min(getWidth() / (float) dp(BASE_W_DP), getHeight() / (float) dp(BASE_H_DP));
        s = Math.max(0.75f, Math.min(3f, s));
        if (Math.abs(s - scale) < 0.02f) return;
        scale = s;

        int pad = dp(20 * s);
        setPadding(pad, pad, pad, pad);
        source.setTextSize(13 * s);
        title.setTextSize(20 * s);
        artist.setTextSize(15 * s);
        elapsed.setTextSize(12 * s);
        duration.setTextSize(12 * s);
        artist.setPadding(0, dp(6 * s), 0, 0);

        LayoutParams ap = (LayoutParams) art.getLayoutParams();
        ap.width = ap.height = dp(108 * s);
        art.setLayoutParams(ap);
        ((LayoutParams) ((View) title.getParent()).getLayoutParams()).leftMargin = dp(16 * s);
        ((LayoutParams) open.getLayoutParams()).topMargin = dp(8 * s);

        LayoutParams pp = (LayoutParams) progress.getLayoutParams();
        pp.height = dp(4 * s);
        pp.topMargin = dp(14 * s);
        progress.setLayoutParams(pp);

        for (ImageButton b : new ImageButton[]{prev, playPause, next}) {
            LayoutParams bp = (LayoutParams) b.getLayoutParams();
            bp.width = bp.height = dp(48 * s);
            bp.leftMargin = bp.rightMargin = dp(10 * s);
            b.setLayoutParams(bp);
            int bpad = dp(10 * s);
            b.setPadding(bpad, bpad, bpad, bpad);
            b.setBackground(Glass.panel(dp(24 * s), dp(1)));
        }
        requestLayout();
    }

    // ---------- sessions ----------

    void start() {
        started = true;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main);
            pick(sessions.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            // Media access not granted yet (tv-home.ps1 grants it).
            setController(null);
            title.setText("Media access is off");
            artist.setText("Run: tv-home.ps1 grant");
        }
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        started = false;
        main.removeCallbacks(tick);
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
        }
        setController(null);
    }

    void shutdown() {
        io.shutdownNow();
    }

    private void refreshSessions() {
        try {
            pick(sessions.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            setController(null);
        }
    }

    /** Prefer whatever is actually playing, then the music app, then anything with a session. */
    private void pick(List<MediaController> list) {
        if (!started) return;
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best == null) {
                for (MediaController c : list) {
                    if (Config.MUSIC_PACKAGE.equals(c.getPackageName())) { best = c; break; }
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        setController(best);
    }

    private void setController(MediaController c) {
        if (controller != null && c != null && controller.getSessionToken().equals(c.getSessionToken())) return;
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = c;
        if (controller != null) controller.registerCallback(controllerCallback, main);
        render();
    }

    // ---------- rendering ----------

    private void render() {
        PackageManager pm = getContext().getPackageManager();
        if (controller == null) {
            source.setText("Music");
            title.setText("Nothing playing");
            artist.setText("Press OK to open " + HomeActivity.label(pm, Config.MUSIC_PACKAGE));
            setArtToAppIcon(Config.MUSIC_PACKAGE);
            controls.setVisibility(INVISIBLE);
            progress.setProgress(0);
            elapsed.setText("");
            duration.setText("");
            return;
        }
        MediaMetadata m = controller.getMetadata();
        PlaybackState s = controller.getPlaybackState();
        source.setText("Now playing  ·  " + HomeActivity.label(pm, controller.getPackageName()));
        title.setText(firstText(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE));
        artist.setText(firstText(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        playPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        controls.setVisibility(VISIBLE);
        loadArt(m, controller.getPackageName());
        updateProgress();
    }

    private void updateProgress() {
        if (controller == null) return;
        MediaMetadata m = controller.getMetadata();
        PlaybackState s = controller.getPlaybackState();
        long dur = m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        long pos = 0;
        if (s != null) {
            pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
        }
        if (dur > 0) pos = Math.min(Math.max(pos, 0), dur);
        progress.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
        elapsed.setText(dur > 0 ? time(pos) : "");
        duration.setText(dur > 0 ? time(dur) : "");
    }

    private void loadArt(MediaMetadata m, String pkg) {
        Bitmap b = null;
        String uri = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            uri = firstText(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        }
        if (b != null) {
            artKey = null;
            art.setImageBitmap(b);
            return;
        }
        if (TextUtils.isEmpty(uri)) {
            artKey = null;
            setArtToAppIcon(pkg);
            return;
        }
        if (uri.equals(artKey)) return;
        artKey = uri;
        final String key = uri;
        io.execute(() -> {
            Bitmap fetched = fetch(key);
            main.post(() -> {
                if (!key.equals(artKey)) return;
                if (fetched != null) art.setImageBitmap(fetched);
                else setArtToAppIcon(pkg);
            });
        });
    }

    private Bitmap fetch(String uri) {
        try {
            if (uri.startsWith("http")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (InputStream in = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                } finally {
                    conn.disconnect();
                }
            }
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setArtToAppIcon(String pkg) {
        try {
            art.setImageDrawable(getContext().getPackageManager().getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            art.setImageDrawable(null);
        }
    }

    private void togglePlay() {
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private void openMusicApp() {
        String pkg = controller != null ? controller.getPackageName() : Config.MUSIC_PACKAGE;
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private static String firstText(MediaMetadata m, String... keys) {
        if (m == null) return "";
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString();
        }
        return "";
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
