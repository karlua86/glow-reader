# Glow Reader ðŸ“–âœ¨

**A book reader for Rokid AI Glasses that shows only floating words â€” no glowing background block.**

I'm not from an IT background â€” I'm a property agent and I don't understand a single line of code. This entire app was built by **[Claude Code](https://claude.com/claude-code)** (Anthropic's AI coding assistant). I just described what I wanted in plain English, tested it on my glasses, and told it what to fix.

## The problem it solves

Reader apps sideloaded onto the glasses show up as a big glowing green block at night â€” the app background lights up the whole see-through display. Glow Reader's background is truly invisible: **all you see are floating words.**

> The technical fix, for the curious: on the glasses' waveguide display every non-black pixel glows green. A pure black app window *should* be invisible â€” but Android draws a translucent "focus highlight" over keyboard-navigated apps, which becomes a solid green slab. Glow Reader disables that (`android:defaultFocusHighlightEnabled=false`), along with every other system decoration that would light up.

## Features

- Reads **EPUB, PDF, TXT and Markdown** (PDFs are reflowed into clean, resizable text)
- **5 reading modes** (press `M` to cycle): page flip Â· word-by-word speed reading (RSVP) Â· teleprompter auto-scroll Â· sentence-by-sentence Â· paragraph-by-paragraph
- **Low Light mode** (`L`) + 5 text brightness levels (`T`) for night reading
- Adjustable display area â€” move/resize where the text floats (`U`/`N`/`O`/`P`)
- **Full-lines-only** rendering â€” no half-cut words at the scroll edges (`X`)
- Remembers position, mode and settings per book; save/restore preferred settings (`V`/`R`)
- Books auto-appear from the glasses' **Download folder** (use the built-in browser), or push them from your phone

## The two apps

| APK | Installs on | What it does |
|---|---|---|
| **GlowReader.apk** | the glasses | the reader itself |
| **GlowRemote.apk** | your Android phone | remote control + live settings + send books over WiFi |

Download both from **[Releases](../../releases)**.

## Controls

**Without the phone app:** any Bluetooth keyboard/mouse works (e.g. a free "Bluetooth Keyboard & Mouse" phone app). Arrows or `W/A/S/D` navigate, `SPACE` turns pages / plays / pauses, `ENTER` opens a book, `H` shows the full key list on screen.

**With Glow Remote (phone app):** big buttons for everything â€” pages, font size, speed, brightness, display area â€” applied instantly on the glasses, plus one-tap "send a book to the glasses."
Requirements: Rokid Manager installed, glasses' **WiFi turned on and connected to the same WiFi network as your phone**. Open Glow Remote â†’ tap **Find glasses** â†’ it connects by itself.

## Install

1. Sideload `GlowReader.apk` onto the glasses the usual way (e.g. the install-APK option in the phone companion app).
2. On first launch the glasses will ask for **"all files access"** â€” that's so the app can see books in the Download folder.
3. Install `GlowRemote.apk` on your phone like any normal APK.

## Building from source

Both apps are plain Android/Kotlin projects with the Gradle wrapper:

```
cd GlowReader   # glasses app source
gradlew.bat assembleDebug
cd ../GlowRemote  # phone app source
gradlew.bat assembleDebug
```

Requires JDK 17 and the Android SDK (API 34).

## Credits

- **All programming: [Claude Code](https://claude.com/claude-code)** â€” including researching how other community apps solved the green-glow problem
- Inspired by the excellent open-source Rokid community apps in [awesome-rokid](https://github.com/Anezium/awesome-rokid), especially [Lume](https://github.com/beyondlevi/lume), whose source revealed the focus-highlight fix
- Not affiliated with Rokid â€” "works with Rokid Glasses" is a compatibility statement only

It's a hobby project made for my own night reading, so it's not perfect â€” but it works great for me and it's free. Feedback and ideas welcome! ðŸ™‚
