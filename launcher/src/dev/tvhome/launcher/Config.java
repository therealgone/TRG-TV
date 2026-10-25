package dev.tvhome.launcher;

/** Everything you might want to change about the home screen lives here. */
final class Config {
    private Config() {}

    /** HDMI input shown live in the big tile, by the name the TV gives it. */
    static final String PREVIEW_INPUT_LABEL = "HDMI 1";
    /** Fallback if the label isn't found: hardware id suffix of HDMI 1 on the KD-55X8000H. */
    static final String PREVIEW_INPUT_HW_SUFFIX = "/HW2";
    /** Friendly name shown on the tile. */
    static final String PREVIEW_NAME = "Laptop";

    /** Dock apps, left to right. Missing packages are skipped. */
    static final String[] DOCK = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.crunchyroll.crunchyroid",
            "com.apple.atve.sony.appletv",
    };

    /** Music app the Now playing panel prefers and opens when nothing is playing. */
    static final String MUSIC_PACKAGE = "com.spotify.tv.android";
}
