# CLAUDE.md

Guidance for Claude Code (and humans) working on this repo. Read README.md first for what the project is.

## What this is
A debloat kit and a custom Android TV launcher (`dev.tvhome.launcher`) for a Sony BRAVIA KD-55X8000H
(Android TV 10, API 29, MediaTek, 2.2 GB RAM), driven from a Windows PC over network ADB with PowerShell.

## Build and run
```powershell
.\build-apk.ps1 .\launcher                 # compile only -> launcher\build\app.apk
.\tv-home.ps1 install                      # build + install + make Home (+ -OwnerName "...")
.\tv.ps1 status                            # debloat state
```
- Plain Java 11 source against `android-34/android.jar`; no Gradle, no AndroidX, no dependencies.
  Keep it that way — the TV has little RAM and the build stays a single script.
- All UI is built in code (no layout XML). Sizes go through `Ui.dp()`; the UI is 960×540 dp.
- Resources: only `res/values/strings.xml` and `res/xml/key_service.xml`.

## Privacy rules (important)
- **Never write personal data into tracked files**: no IP addresses, hostnames, network names, MAC
  addresses, people's names, emails, or lists of the owner's installed apps.
- The TV address comes from `-TvHost`, `$env:TV_HOST`, or the git-ignored `.tvhost` file
  (`tv-common.ps1`). The greeting name comes from `.owner-name` / `-OwnerName` and is stored on the TV
  in SharedPreferences (`Desktops.ownerName`), never in source.
- `baseline/` (package lists, meminfo dumps), `*.keystore` and `build/` are git-ignored. Don't add them.
- Before committing, grep for `\d+\.\d+\.\d+\.\d+`, `@`, MAC patterns and names.

## Working on the TV safely
- Every script calls `Connect-Tv`, which checks `settings get global device_name` against `-Model`.
  Keep that check in anything new — there may be other Android TVs on the network.
- Disable, never uninstall (`pm disable-user --user 0`); keep `restore` working.
- One `pm` call per package; chained shell commands can stop silently.
- Never clear app data (`pm clear`) — it logs the user out of streaming apps.
- `adb shell input keyevent` bypasses accessibility services, so shortcut changes (KeyService) can only
  be verified with a real remote. Activity-side behaviour can be tested with
  `am start -n dev.tvhome.launcher/.HomeActivity --ez/--ei/--es <extra>` (see HomeActivity EXTRA_*).
- `uiautomator dump` fails while the live HDMI tile is on screen ("could not get idle state") and
  `screencap` returns an empty file while the HDMI layer is up; use `dumpsys activity top` /
  `dumpsys SurfaceFlinger`, or the launcher's own `SNAPSHOT` extra for an image (see README).
- `root` must keep clipping its children: the TvView (SurfaceView) clears its area to punch the video
  hole, and an unclipped parent lets that clear wipe the whole aurora background.
- PowerShell 5.1 strips double quotes from native arguments: for non-trivial `adb shell` commands,
  pipe a script to `/data/local/tmp/x.sh` and run it (and delete it afterwards).
- After changing `key_service.xml` capabilities, re-enable the service
  (`settings delete secure enabled_accessibility_services`, then `tv-home.ps1 install`).

## Sony firmware facts the design depends on
- Stock HOME priority 2 → the stock launcher package must be disabled for ours to be Home.
- No multi-window of any kind; don't retry freeform/split/resize (see README for what was tried).
- Unused digit keys launch Sony's TV app (`com.sony.dtv.tvx`) → KeyService swallows 0–4, times holds
  itself, types taps into focused text fields; HomeActivity swallows all digits.
- `killBackgroundProcesses` is rejected for other apps on this firmware.
- `TvView` passthrough works for HDMI; `onVideoSizeChanged`/tracks are never reported.
- The launcher can start before HDMI inputs register after boot → `retryFindInput` + `TvInputCallback`.
- Keep `com.vewd.core.service` enabled (AirPlay depends on it).

## Code map
- `HomeActivity` — home layout, HDMI tile (expand/collapse, `expandNow` for hold-0), dock, app tray /
  desktop picker (`showAllApps(desktop)`), intents from KeyService.
- `KeyService` — accessibility service: key filtering (hold 0–4, Home), desktop switching, badge,
  welcome / signing-out overlays (`TYPE_ACCESSIBILITY_OVERLAY`), `GLOBAL_ACTION_LOCK_SCREEN` standby.
- `Desktops` — SharedPreferences: desktop → package, owner name.
- `NowPlayingPanel` — MediaSessionManager via the `MediaListener` notification-listener key.
- `Glass` — aurora background, frosted panel, focus ring. `Ui` — dp, text, `focusable()` pop effect.
- `Config` — dock apps, HDMI input label/fallback, music package.

## Style
Match the surrounding code: small classes, short comments that explain *why* (usually a firmware
quirk), no logging left in hot paths, no unused code from experiments.
