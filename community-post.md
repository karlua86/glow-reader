# Glow Reader — community post (2026-07-15)

Hey everyone! 👋

First, a confession: **I'm not from an IT background at all — I'm a property agent and I don't understand a single line of code.** 😅 This entire app was built by **Claude Code (Anthropic's AI coding assistant)**. I just described what I wanted in plain English, tested it on my glasses, and told it what to fix. Honestly, if I can make an app this way, anyone here can.

So here's the story: I love reading at night, but every reader app I sideloaded onto my glasses showed up as a big glowing green block — the app background lights up the whole display, which is really uncomfortable in the dark. So I asked Claude to build me a reader app that fixes exactly that, and I'm sharing the result here in case it helps someone else.

**📖 Glow Reader** — a book reader made specifically for the glasses' see-through display. The background is truly invisible: all you see are floating words in front of you. Nothing else.

**What it does:**
- Reads **EPUB, PDF, TXT and Markdown** files (PDFs are converted to clean flowing text, so no tiny pages)
- **5 reading modes** — flip through pages, one word at a time (speed reading), teleprompter-style auto-scroll, sentence by sentence, or paragraph by paragraph. Press M to switch.
- **Low Light mode** (press L) — dims everything way down for night reading, plus 5 levels of text brightness (press T)
- You can move and resize the text area on the display (like the official teleprompter app)
- "Full lines only" option — no half-cut words at the edge while scrolling
- Remembers your position, mode and settings for every book
- Save your favourite settings with V, restore them anytime with R

**How to get books in:** just download them with the glasses' browser (they show up automatically from the Download folder), or use the phone app below.

**How to control it:**
- **Without the phone app:** any Bluetooth keyboard/mouse works — I use a free "Bluetooth Keyboard & Mouse" app on my phone. Arrow keys or W/A/S/D to navigate, SPACE to turn pages or play/pause, ENTER to open a book, H shows all the keys on screen.
- **With the phone app (Glow Remote):** big friendly buttons for everything — page turns, font size, speed, brightness, moving the text area — all changes apply instantly on the glasses. You can also send books straight from your phone to the glasses with one tap. To use it you'll need **Rokid Manager installed**, and the glasses' **Wi-Fi turned on and connected to the same Wi-Fi network as your phone**. Then just open Glow Remote and tap "Find glasses" — it connects by itself.

**Install:** sideload GlowReader.apk onto the glasses the usual way (I use the install-APK option in the phone companion app). GlowRemote.apk installs on your phone like any normal APK. First launch on the glasses will ask for "all files access" — that's just so it can see the books in your Download folder.

It's a hobby project made for my own night reading, so it's not perfect — but it works great for me and it's free. Since I can't code myself, if you find bugs I'll be passing them straight back to Claude to fix 😄 — so feedback and ideas are very welcome!

Big credit to **Claude Code** for doing all the actual programming — even the fix for the green-glow problem came from it digging through how other community apps solved it.

Happy reading! ✨📚
