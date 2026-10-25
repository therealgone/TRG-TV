package dev.tvhome.hdmitest;

import android.app.Activity;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Feasibility test: can a normal (non-Sony) app show an HDMI input inside a small TvView?
 * Left/Right switches between HDMI inputs. Everything is logged under tag "HdmiTest".
 */
public class MainActivity extends Activity {
    private static final String TAG = "HdmiTest";

    private TvInputManager tvInputManager;
    private TvView tvView;
    private TextView status;
    private TextView tileLabel;
    private final List<TvInputInfo> hdmiInputs = new ArrayList<>();
    private int current = 0;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setPadding(48, 48, 48, 48);
        root.addView(status, new FrameLayout.LayoutParams(820, FrameLayout.LayoutParams.MATCH_PARENT));

        // A 960x540 "tile" on the right, like the planned home-screen preview.
        tvView = new TvView(this);
        FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(960, 540, Gravity.END | Gravity.CENTER_VERTICAL);
        tileParams.setMarginEnd(60);
        root.addView(tvView, tileParams);

        // Big label under the tile so it's obvious which port is being previewed.
        tileLabel = new TextView(this);
        tileLabel.setTextColor(Color.YELLOW);
        tileLabel.setTextSize(28);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(960, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        labelParams.setMarginEnd(60);
        labelParams.bottomMargin = 120;
        root.addView(tileLabel, labelParams);

        setContentView(root);

        tvInputManager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onConnectionFailed(String inputId) { note("onConnectionFailed " + inputId); }
            @Override public void onDisconnected(String inputId) { note("onDisconnected " + inputId); }
            @Override public void onVideoAvailable(String inputId) { note("onVideoAvailable " + inputId + "  <-- PREVIEW WORKS"); }
            @Override public void onVideoUnavailable(String inputId, int reason) { note("onVideoUnavailable " + inputId + " reason=" + reason); }
            @Override public void onVideoSizeChanged(String inputId, int w, int h) { note("onVideoSizeChanged " + w + "x" + h); }
        });

        listInputs();
        tuneCurrent();
    }

    private void listInputs() {
        if (tvInputManager == null) { note("TvInputManager is null"); return; }
        for (TvInputInfo info : tvInputManager.getTvInputList()) {
            String label = String.valueOf(info.loadLabel(this));
            int state = tvInputManager.getInputState(info.getId());
            note("input: " + info.getId() + " type=" + info.getType() + " label=" + label
                    + " passthrough=" + info.isPassthroughInput() + " state=" + state);
            if (info.getType() == TvInputInfo.TYPE_HDMI && info.isPassthroughInput()) hdmiInputs.add(info);
        }
        // Prefer the first HDMI that reports something plugged in (state 0 = connected).
        for (int i = 0; i < hdmiInputs.size(); i++) {
            if (tvInputManager.getInputState(hdmiInputs.get(i).getId()) == TvInputManager.INPUT_STATE_CONNECTED) { current = i; break; }
        }
        note(hdmiInputs.size() + " HDMI inputs found");
    }

    private void tuneCurrent() {
        if (hdmiInputs.isEmpty()) return;
        TvInputInfo info = hdmiInputs.get(current);
        note("tuning " + info.loadLabel(this) + " (" + info.getId() + ")");
        tileLabel.setText("Previewing " + info.loadLabel(this) + "   (Left/Right to change)");
        try {
            tvView.tune(info.getId(), TvContract.buildChannelUriForPassthroughInput(info.getId()));
            tvView.setStreamVolume(0f);
        } catch (Exception e) {
            note("tune threw " + e);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!hdmiInputs.isEmpty() && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            current = (current + step + hdmiInputs.size()) % hdmiInputs.size();
            tvView.reset();
            tuneCurrent();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        tvView.reset();
    }

    private void note(String line) {
        Log.i(TAG, line);
        log.append(line).append('\n');
        status.setText(log);
    }
}
