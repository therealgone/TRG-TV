package dev.tvhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Which app lives on desktops 1-3, plus the owner's name for greetings. Survives restarts. */
final class Desktops {
    static final int COUNT = 3;

    private Desktops() {}

    static String get(Context c, int desktop) {
        return prefs(c).getString("desktop" + desktop, null);
    }

    static void set(Context c, int desktop, String pkg) {
        prefs(c).edit().putString("desktop" + desktop, pkg).apply();
    }

    /**
     * Name shown on the welcome and signing-out screens. Not in the source: set on the TV by
     * {@code tv-home.ps1 install -OwnerName ...}. Empty means no name line.
     */
    static String ownerName(Context c) {
        return prefs(c).getString("owner_name", "");
    }

    static void setOwnerName(Context c, String name) {
        prefs(c).edit().putString("owner_name", name == null ? "" : name.trim()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("desktops", Context.MODE_PRIVATE);
    }
}
