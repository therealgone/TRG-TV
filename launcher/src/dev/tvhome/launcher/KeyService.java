package dev.tvhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Remote shortcuts that work from any app. Sees keys before the app on screen does.
 * <ul>
 *   <li>Hold 0: HDMI 1 full screen (does nothing if it already is).</li>
 *   <li>Hold 1/2/3: switch to that desktop's app, as it was left. Empty desktop, or holding the number of
 *       the desktop you're on: pick its app.</li>
 *   <li>Hold 4 (only while on a desktop): that desktop's app tray, to swap or close its app.</li>
 *   <li>Home: always straight to the home screen.</li>
 * </ul>
 * 0-3 are kept from apps (Sony's TV app would otherwise take unused digits); a quick tap types the digit
 * into the focused text field instead, so PIN boxes still work.
 */
public class KeyService extends AccessibilityService {
    private static final long BADGE_MS = 1200;
    private static final long HOLD_MS = 450;
    private static final int TRAY_KEY = 4;

    /** The running service, for the home screen's Power button. */
    static volatile KeyService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String foregroundPkg;
    private int heldKey = -1;
    private boolean heldHandled;
    private TextView badge;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (k == KeyEvent.KEYCODE_HOME) {
            if (!down) main.post(this::goHome);
            return true;
        }

        int slot = slotFor(k);
        if (slot < 0) return false;
        // 4 is only a shortcut while you're on a desktop; anywhere else it's left alone.
        if (slot == TRAY_KEY && !(heldKey == k || (down && e.getRepeatCount() == 0 && currentDesktop() > 0))) return false;
        // 0-3 never reach the app: if the app ignores a digit, Sony's TV app grabs it and jumps to the
        // TV input with a channel number. Holding is a shortcut; a tap types the digit.
        // The hold is timed here: a key swallowed by this service never gets Android's auto-repeat.
        if (down) {
            if (e.getRepeatCount() == 0 && k != heldKey) {
                heldKey = k;
                heldHandled = false;
                main.removeCallbacks(holdTimer);
                holdSlot = slot;
                main.postDelayed(holdTimer, HOLD_MS);
            }
            return true;
        }
        main.removeCallbacks(holdTimer);
        boolean tap = k == heldKey && !heldHandled;
        heldKey = -1;
        heldHandled = false;
        if (tap) main.post(() -> typeDigit(slot));
        return true;
    }

    private int holdSlot;

    private final Runnable holdTimer = () -> {
        if (heldKey < 0 || heldHandled) return;
        heldHandled = true;
        shortcut(holdSlot);
    };

    /** A tapped 0-3 goes into the focused text field (PIN boxes, search), if there is one. */
    private void typeDigit(int digit) {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) return;
        CharSequence current = field.isShowingHintText() ? null : field.getText();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                (current == null ? "" : current.toString()) + digit);
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** 0 = HDMI, 1-3 = desktops, 4 = the current desktop's app tray. */
    private static int slotFor(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_0 + TRAY_KEY) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_0 + TRAY_KEY) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    /** The desktop whose app is in front, or 0 if you're not on a desktop. */
    private int currentDesktop() {
        if (foregroundPkg == null) return 0;
        for (int n = 1; n <= Desktops.COUNT; n++) {
            if (foregroundPkg.equals(Desktops.get(this, n))) return n;
        }
        return 0;
    }

    private void shortcut(int slot) {
        boolean onHome = getPackageName().equals(foregroundPkg);
        if (slot == 0) {
            if (onHome && HomeActivity.hdmiFullScreen) return; // already there
            open(home().putExtra(HomeActivity.EXTRA_HDMI, true));
            showBadge("0  ·  " + Config.PREVIEW_INPUT_LABEL);
            return;
        }
        if (slot == TRAY_KEY) {
            int desktop = currentDesktop();
            if (desktop == 0) return;
            open(home().putExtra(HomeActivity.EXTRA_PICK_DESKTOP, desktop));
            showBadge(desktop + "  ·  change or close app");
            return;
        }
        String pkg = Desktops.get(this, slot);
        boolean installed = pkg != null && HomeActivity.isInstalled(getPackageManager(), pkg);
        if (!installed || pkg.equals(foregroundPkg)) {
            // Empty desktop, or already on it: choose its app.
            open(home().putExtra(HomeActivity.EXTRA_PICK_DESKTOP, slot));
            showBadge(slot + "  ·  pick an app");
            return;
        }
        Intent launch = HomeActivity.launchIntent(this, pkg);
        if (launch != null) open(launch);
        showBadge(slot + "  ·  " + HomeActivity.label(getPackageManager(), pkg));
    }

    private void goHome() {
        open(home());
    }

    private Intent home() {
        return new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Small "2 · YouTube" label at the top of the screen for a moment. */
    private void showBadge(String text) {
        WindowManager wm = getSystemService(WindowManager.class);
        if (badge == null) {
            badge = Ui.text(this, 18, Ui.TEXT, true);
            int px = Ui.dp(this, 18), py = Ui.dp(this, 9);
            badge.setPadding(px, py, px, py);
            badge.setBackground(Glass.tinted(Ui.dp(this, 22), Ui.dp(this, 1), 0x101428));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Ui.dp(this, 28);
            badge.setVisibility(android.view.View.GONE);
            wm.addView(badge, lp);
        }
        badge.setText(text);
        badge.setVisibility(android.view.View.VISIBLE);
        main.removeCallbacks(hideBadge);
        main.postDelayed(hideBadge, BADGE_MS);
    }

    private final Runnable hideBadge = () -> {
        if (badge != null) badge.setVisibility(android.view.View.GONE);
    };

    // ---------- welcome / signing out ----------

    private static final long GREETING_MS = 2400;
    private View greeting;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            showGreeting("Welcome", null);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        // The service comes up at the end of a full restart: greet then too.
        if (SystemClock.elapsedRealtime() < 5 * 60_000) showGreeting("Welcome", null);
    }

    /** "Signing out" plus the owner's name, then standby (the same as the power button). */
    void signOut() {
        showGreeting("Signing out", () -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN));
    }

    /** Full-screen liquid-glass greeting with the owner's name (if set); fades in, holds, then runs {@code then}. */
    private void showGreeting(String line, Runnable then) {
        WindowManager wm = getSystemService(WindowManager.class);
        removeGreeting(wm);

        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(new Glass.Aurora(0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView top = Ui.text(this, 34, 0xD9FFFFFF, false);
        top.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        top.setText(line);
        col.addView(top);
        String owner = Desktops.ownerName(this);
        if (owner.isEmpty()) {
            // No name set: the one line becomes the headline.
            top.setTextSize(72);
            top.setTextColor(0xFFFFFFFF);
            top.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        } else {
            TextView name = Ui.text(this, 120, 0xFFFFFFFF, false);
            name.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            name.setLetterSpacing(0.12f);
            name.setText(owner);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = Ui.dp(this, 10);
            col.addView(name, np);
        }
        screen.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        screen.setAlpha(0f);
        col.setScaleX(0.94f);
        col.setScaleY(0.94f);
        wm.addView(screen, lp);
        greeting = screen;

        screen.animate().alpha(1f).setDuration(450).start();
        col.animate().scaleX(1f).scaleY(1f).setDuration(GREETING_MS).setInterpolator(new DecelerateInterpolator()).start();
        main.postDelayed(() -> {
            if (greeting != screen) return;
            if (then != null) {
                // Sleep first, then take the overlay away, so home never flashes before standby.
                then.run();
                main.postDelayed(() -> removeGreeting(wm), 800);
            } else {
                screen.animate().alpha(0f).setDuration(500).withEndAction(() -> removeGreeting(wm)).start();
            }
        }, GREETING_MS);
    }

    private void removeGreeting(WindowManager wm) {
        if (greeting == null) return;
        try {
            wm.removeView(greeting);
        } catch (Exception ignored) {
        }
        greeting = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Track which app is in front, to know which desktop you're on.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!pkg.equals("com.android.systemui")) foregroundPkg = pkg;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        removeGreeting(getSystemService(WindowManager.class));
        if (badge != null) getSystemService(WindowManager.class).removeView(badge);
        super.onDestroy();
    }
}
