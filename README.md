# TBF Companion

**An open-source, account-free gaming companion for the Lenovo Legion Y700 Gen 3 tablet — and a full configurator for the Legion Gaming Controller G9.**

[Español](README.es.md)

> **Not affiliated with Lenovo.** "Legion" and "Lenovo" are trademarks of Lenovo. This is an independent, unofficial app. It talks to the G9 over USB with a protocol reconstructed by studying the official app and testing the controller; it contains no code or assets from the official app.

> **This app is vibe-coded.** Every line of code, the research and this README were written by Claude (Anthropic's AI) through Claude Code. I described what I wanted, tested every build on my own tablet and controller, and reported what worked and what didn't.

## What it is

A console-like layer for playing on the Y700: a game panel you open from any game, clean screenshots with a Switch-style notice, battery and heat tools, per-game refresh rate, and an app you can drive entirely with a controller. It works with **any controller**; the **G9** gets its own section to configure everything the hardware allows.

No account, no internet permission, no ads. Things that need more than a regular app's permissions are marked and dimmed until you enable them, and the app says why.

The app has three sections (LT / RT switch between them, LB / RB between tabs):

### Tablet (any controller)

| | |
|---|---|
| **Home** | Battery (watts now, last-minute average, time left; while charging, time to the limit or to 100 %), temperature, refresh rate, CPU; quick tiles for bypass, the performance monitor and 16:9; memory with running apps; connected controllers. |
| **Battery** | Own charge limit (70–90 %), manual and automatic bypass charging, low-battery notice (15, 10 and 5 %, like the Switch), power use per app, and **game sessions**: watts, CPU and temperatures per game, performance mode and refresh rate, to compare modes with real data. |
| **Screen** | 16:9 for an external monitor (automatic per monitor in mirror mode), and the tablet's screen dimmed, backlight-off or off while the monitor is on. |
| **Game** | Screenshot notice and sound, the game panel's look (background, transparency, shape and size, mini monitor), the performance monitor, refresh rate for all games, Game Assistant settings. |
| **Controls** | What the guide button does (tap: Back; hold: game panel; + RB: screenshot, hold RB for a burst; + LB: hold Back; hold 5 s: the G9 turns off and the tablet locks), per-emulator tap action (Back, nothing, or pass the guide to the game so the emulator can bind it), L3 + R3 for controllers without a guide button, swapped volume buttons. |
| **Test controller** | Buttons, triggers and sticks of any controller, with the connected controllers and stick values to spot drift. |

### Game panel

Hold the guide button (or L3 + R3, or the accessibility button) in any app: a panel like Android's quick settings over the game, without taking its focus. A mini monitor (free RAM, watts, CPU, battery temperature), brightness and volume, screenshot, Game Assistant, performance mode, refresh rate for this app, gesture lock, recent apps to jump back to (like a suspended game), 16:9, Wi-Fi and Bluetooth, a virtual mouse, Back / Home / Recents and the G9's mode. Move with the stick or D-pad, A selects, B closes. Background: clear, gradient or your own image or GIF, optionally see-through.

### G9

| | |
|---|---|
| **Modes** | Android, X-Input and **DS5** (the best mode on Android: rumble, gyro, touchpad). The official app can't set DS5. Switches automatically per game, with an on-screen View + Menu guide to get back. |
| **Lights, sticks, vibration** | Light strips and the Home button; dead zones and trigger ranges with a live preview; hair trigger; vibration levels. |
| **Paddles and macros** | L4, R4, L5, R5 as any button (including Home and stick directions) or a macro, chosen on a controller-shaped grid; macros recorded with the controller, with a timeline editor. |
| **Profiles** | Complete configurations with file backup. |

Careful with the controller's memory: it only writes what changed, after a short pause.

### Settings

Language, theme, app background (animated gradient, image or GIF, **your live wallpaper** — Wallpaper Engine too — or plain) and permissions in one place.

## Requirements

- Lenovo Legion Y700 Gen 3 (tested on ZUI 17, Android 15). The Tablet section works with any controller; the G9 section needs the Legion Gaming Controller G9 (tested with firmware 0.52).

## Install

1. Download the APK from [Releases](../../releases) and open it on the tablet (allow installing from that source if asked).
2. Open TBF Companion; a quick setup guides you.
   - For the G9: dock the tablet in **Android mode** (dark blue light; from X-Input or DS5 hold **View + Menu** for 2 s) and allow USB access with **Always**.
   - Turn on the **accessibility service** for the game panel, shortcuts and everything automatic.

### About the accessibility service

The game panel, shortcuts, screenshots, per-game switching and the automatic features use an accessibility service. It:

- sees which app is on screen (window changes) and the controller's buttons (and, while the panel is open, the sticks, so they don't reach the game);
- takes screenshots when you ask, of the game window only, saved on the tablet;
- performs gestures only on the tablet's Game Assistant (to open its panel and change its performance mode).

To capture only the game window Android requires the permission to view window content; the app uses it only for the list of windows, never their text. The app has no internet permission: nothing leaves the tablet. If Android says **"Restricted setting"**: Settings → Apps → TBF Companion → ⋮ → **Allow restricted settings**.

### Optional: Shizuku and one adb permission

Most things need nothing extra.

- **16:9, brightness from the panel, gesture lock and auto brightness in games:** grant once from a computer (it stays):
  `adb shell pm grant io.github.sudodiego.g9control android.permission.WRITE_SECURE_SETTINGS`
- **Bypass and charge limit, hiding Game Assistant and its performance mode, FPS in the monitor, running apps (RAM, CPU, stop), Wi-Fi and Bluetooth toggles, dragging with the virtual mouse, the tablet's screen off with a monitor, and keeping the chosen refresh rate in power-saving mode:** [Shizuku](https://shizuku.rikka.app/) running. The app only asks it for a fixed list of commands. Without it, those things are dimmed and the app explains what's missing.

## Limitations

- **No firmware updates** for the G9, on purpose (a failed update can brick it).
- The G9 can only be configured in **Android mode**; switching back from X-Input or DS5 is always View + Menu (the app guides you).
- Android 15 marks buttons sent by software as coming from a "Virtual" device: emulators accept combos like View + Menu, but can't assign them as the G9's own button.
- In Android mode, games vibrate the tablet, not the G9; its 3.5 mm jack doesn't work on Android (firmware).
- Screenshots: at most one per second (Android's limit for accessibility services).
- While streaming from a PC (Artemis, Moonlight…) the guide button goes to the PC.
- Some apps close when switching between 16:9 and 16:10 (Dolphin, for example).

## Build from source

```
./gradlew assembleRelease
```

Without a `keystore.properties` file the release is signed with the debug key. Protocol notes and everything that was tested are in [CONTEXTO.md](CONTEXTO.md) (in Spanish).

## License

[GPL-3.0](LICENSE).
