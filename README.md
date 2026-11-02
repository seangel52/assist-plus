# Assist Plus

**A better voice client for Home Assistant's Assist on Android.** It keeps Home Assistant's own assistant and makes talking to it work in a real, noisy home: it listens on the phone, isolates *your* voice from the TV and the kids, shows your words as you speak, and lets you hand it photos, files and barcodes.

> **Why this exists:** to show, with real working examples, what a phone voice assistant for Home Assistant *could* be — and to inspire others to build on it. Take any idea here, use the code, fork it, improve it, or bring it to the [official Companion app](https://github.com/home-assistant/android). You don't need to ask; that's the point.

<p>
  <img src="docs/screenshots/assist-sheet.png" width="360" alt="The Assist sheet listening">
</p>
<p>
  <img src="docs/screenshots/settings-connection.png" width="200" alt="Connection settings">
  <img src="docs/screenshots/settings-voice-id.png" width="200" alt="Voice ID settings">
  <img src="docs/screenshots/settings-wake-word.png" width="200" alt="Wake word settings">
  <img src="docs/screenshots/settings-noise.png" width="200" alt="Noise reduction settings">
</p>

I built this with Claude because I wanted the Assist experience to work for my family. I'm not a professional Android developer, so it isn't on the Play Store — it's a working proof of concept you can install yourself. Everything is open source (Apache-2.0): read the code, have Claude or any AI review it, and build something better.

## What it does

- **Isolates your voice.** On-device noise removal plus voice ID that tracks who is talking every 0.4 s: words from the TV, a YouTube video or the kids are dropped, even when they run straight into or over your sentence, and the command ends when *you* stop talking, not when the room goes quiet.
- **Works from across the room.** Voice setup and wake word setup include a few takes from about 10 feet away, and the voice check allows for a distant voice or a loud background (never below what a TV scores).
- **Speech to text on the phone.** Your words appear on screen *as you speak*, not at the end. Home Assistant only ever receives text.
- **Better conversations.** Hands-free follow-ups, room to pause and think before it decides you're done, "cancel" / "never mind" to close it, and the agent can end the conversation itself. Say the wake word or tap the mic to cut a reply short. Names it mishears can be taught: the agent tags a correction and the app listens for that name from then on.
- **Custom wake words.** Pick any phrase, record it a few times so it learns how you say it. Optional spoken PIN when the phone is locked (experimental).
- **Phone actions.** "Take me to Target on Main Street" and "set a timer for 10 minutes" are done right on the phone in Google Maps (or Waze) and your clock app. Addresses are checked first, so a misheard house number becomes a "Did you mean…?" instead of a wrong turn. Everything else goes to Home Assistant.
- **Music and podcasts through Home Assistant.** With [SpotifyPlus](#music-spotify) on the server, "play Dare You by Papadosio", "play the newest Good Hang", "next song", "what's playing" and "add this song to Road Trip" work on your phone or any speaker in the house — even on mobile data.
- **Home Assistant can act on the phone.** While Assist is open, scripts can set the phone's volume or open an app on it ([`docs/home-assistant/phone-scripts.yaml`](docs/home-assistant/phone-scripts.yaml)).
- **Photos, files and barcodes.** Snap a barcode and say "I had one serving of this"; send a photo and say "remember this wine". The phone reads barcodes itself and looks them up in Open Food Facts; photos are described by your Home Assistant AI Task.
- **Looks like Home Assistant.** The Assist sheet copies the Companion app's design, and it uses your existing pipelines, agents and voices.

## How it works

```
Phone:  mic → noise removal → speech detection → live speech-to-text (Parakeet)
                                      ↓
                    voice ID (TitaNet): who is talking, every 0.4 s; keep your words
                                      ↓
Home Assistant:            text → your Assist pipeline → conversation agent → reply (+ voice)
```

Everything that hears audio runs on the phone. Home Assistant gets the final text, plus anything you attach.

## Real numbers

Measured on a Samsung Galaxy S25 with a loud cooking video playing on a nearby computer.

**Voice ID: you vs. the video** (similarity to the owner's voiceprint, 5-second samples)

| Model | Owner | Video | Separates them? |
|---|---|---|---|
| WeSpeaker CAM++ (first try) | 0.38–0.60 | 0.57–0.63 | No — the video scored *higher* |
| **NVIDIA TitaNet small (used)** | **0.70–0.87** | **0.12–0.16** | **Yes, wide gap** |

**What got sent with the video playing** (measured with the earlier version, which judged whole phrases)

| What the mic heard | What Assist Plus sent |
|---|---|
| "…or lentils. Hey, what do we want for dinner tonight" | "Hey, what do we want for dinner tonight?" |
| "…vegetables containing fats. Hey, I really want to have Taco Bell for dinner. What do I need to do to do that?" | "Hey, I really want to have Taco Bell for dinner. What do I need to do to do that" |
| "That's the bad news today. Hey, can we go to the store today?" | "Hey, can we go to the store today" |
| "Squeeze lemon juice over lentils. Add tomatoes to chickpeas…" (video only) | *(nothing — ignored)* |

Before this, with speech-to-text in the cloud and no voice filtering, the same setup sent: *"And basically McNugget ever fresh from McDonald's, but actually this is just a test, so don't save this as a memory I'm gonna add one pound of softened butter here, and I'm gonna grate in one clove of…"* — the video and the speaker mixed together.

**Speed**

| Step | Time |
|---|---|
| Noise removal (Strong) | 10 ms delay, ~10% of one CPU core |
| Speech-to-text of a whole command (Parakeet, on phone) | 30–80 ms |
| Live words on screen | refreshed every 0.4 s while you talk |
| Deciding you're done | 1.2 s after a finished sentence (adjustable), +1 s if you trail off |
| Checking who is talking | a 1.5 s window every 0.4 s while you talk |
| Models loading on first open | 1.5–3.5 s (the mic records meanwhile, nothing is lost) |
| The agent's reply | depends on your agent; typically 1.5–5 s for a cloud LLM |

## Install

1. Download the latest **`assist-plus-*.apk`** from [Releases](../../releases/latest) on your Android phone (Android 8+, 64-bit).
2. Open it and allow installing from your browser or file manager. Android may warn about an unknown developer; that's normal for apps outside the Play Store. On Samsung phones, turn **Auto Blocker** off while installing (Settings → Security and privacy → Auto Blocker), then back on.
3. Updates: install the new APK over the old one, or use [Obtainium](https://github.com/ImranR98/Obtainium) to get them automatically from this repository's releases.

## Setup

### 1. Home Assistant (2025.8 or newer recommended; developed on 2026.9)

1. **Reach Home Assistant from your phone.** At home, the local address works (`http://homeassistant.local:8123`). To use it away from home, use [Home Assistant Cloud (Nabu Casa)](https://www.nabucasa.com/) and its remote address (`https://….ui.nabu.casa`), or your own HTTPS address.
2. **Add an AI conversation agent** (strongly recommended). Settings → Devices & services → **Add integration** → **Anthropic** (Claude), **OpenAI**, **Google Gemini**, or **Ollama** for local. Home Assistant's built-in agent works too, but only for fixed device commands. Tip: in the agent's settings, turn on **web search** (Anthropic and OpenAI have it) so it can look up places, addresses and hours.
3. **Create or edit an assistant.** Settings → **Voice assistants** → your assistant (or **Add assistant**):
   - **Conversation agent:** the AI agent from step 2. Turn on "Prefer handling commands locally" if you like.
   - **Text-to-speech:** Home Assistant Cloud, **ElevenLabs** (Add integration → ElevenLabs), Google, or local **Piper** (Settings → Apps → Piper). Pick a **voice** — Assist Plus speaks replies with it.
   - **Speech-to-text:** not used by Assist Plus (the phone does it), but keep one if you also use the Companion app.
4. **Expose your devices.** Settings → Voice assistants → **Expose** tab → turn on the entities the assistant may control.
5. **For photos (optional):** an **AI Task** that can look at images. The Anthropic, OpenAI and Google integrations add one (Settings → Devices & services → your AI integration → AI Task). Without it, photo options are greyed out; barcodes and text files still work.
6. **Create an access token.** Click your profile (bottom left) → **Security** → **Long-lived access tokens** → **Create token**. Use an **admin** user (needed for photo uploads and the app's log). Copy it — it's shown once.

### 2. The phone

1. Open **Assist Plus**, enter your Home Assistant address and token, tap **Test connection & save**, and pick your assistant (pipeline).
2. **Make it the default assistant:** tap **Open default apps settings** → **Digital assistant app** → **Assist Plus**. On Samsung phones, also set **Settings → Advanced features → Side button → Press and hold → Digital assistant**.
3. Long-press the side/power button (or home) and talk. You can switch assistants from the sheet's title.
4. **Voice ID (recommended in a busy house):** Voice ID → **Set up my voice** (six short commands at normal distance, then three from across the room after each beep), then **Only respond to my voice**.
5. **Wake word (optional):** Wake word → add a phrase (3+ syllables of everyday words work best), tap **Record** (five takes close, three from across the room), allow **open over other apps**. Setup shows how many close and far takes it caught. Android shows a notification while it listens.

The Home Assistant Companion app keeps working as before for dashboards, notifications and location — Assist Plus only replaces the assistant panel.

### Music (Spotify)

Spotify doesn't accept play requests from other apps on the phone, so music is handled by Home Assistant, which reaches your phone and every speaker through Spotify's own service. Recommended setup (Spotify Premium required):

1. **Install [SpotifyPlus](https://github.com/thlucas1/homeassistantcomponent_spotifyplus) on the server:** HACS → ⋮ → **Custom repositories** → `thlucas1/homeassistantcomponent_spotifyplus` (Integration) → **Download** → restart. Then Settings → Devices & services → **Add integration** → **SpotifyPlus** and sign in to Spotify. It needs a Spotify developer app ([developer.spotify.com](https://developer.spotify.com/dashboard)) with `https://my.home-assistant.io/redirect/oauth` as a Redirect URI. SpotifyPlus replaces Home Assistant's built-in Spotify integration.
2. **Add the four music tools** from [`docs/home-assistant/spotify-scripts.yaml`](docs/home-assistant/spotify-scripts.yaml) (play, control, what's playing, add to playlist). Replace the three `YOUR_…` placeholders first.
3. **Expose them to the assistant:** Settings → **Voice assistants** → **Expose** → add the four scripts.

Your agent then picks the right tool on its own: "play some Khruangbin in the kitchen", "skip ahead 30 seconds", "who sings this?", "add this song to Road Trip". Spotify only lists a phone while its app is open; the play tool has Assist Plus open Spotify on the phone first (the Companion app is the backup, and needs its **Appear on top** permission for that).

## Recommended agent instructions

Paste this into your conversation agent's instructions (Settings → Devices & services → your AI integration → the conversation agent → configure), then add your own personality and details:

```
You are the voice assistant for this home. Replies are spoken aloud: keep them to one or two short sentences, with no lists or markdown.

Requests come from a phone app that filters out background voices, but words can still be misheard. If a word doesn't fit, go with the most likely meaning of the whole request; never start a new topic from one odd word. If the request is unclear, ask one short question.

A message may end with notes in square brackets, like [Attached photo …] or [Scanned barcode …]. They describe what the user sent along with the request.

Only ask a follow-up question when it continues what the user asked. Don't offer unrelated tasks.

When the user is wrapping up (thanks, that's all, never mind, cancel, goodbye), give a brief sign-off and put [end] at the very end of your reply.

When you realize a name was misheard (the user corrected it, or it's clear from context), add a tag like [heard: Marlin -> Marlon] at the very end of your reply.
```

The `[end]` tag lets the app close the assistant when the conversation is over, and `[heard: … -> …]` teaches it a name to listen for (see the Dictionary in Settings); both are removed before anything is shown or spoken.

## Privacy and security

- **Audio never leaves the phone.** Home Assistant receives text, the photos and files you choose to attach, and nothing else. Barcode numbers are looked up on Open Food Facts.
- **Your token** is kept in the app's private storage (Android backups are disabled for the app) and only sent to your Home Assistant.
- **The app's log** (requests, timings, voice ID scores) is copied into your own Home Assistant log under the `assist_plus` logger, so problems can be diagnosed. A spoken PIN is never logged, saved or shown.
- **Troubleshooting recordings** (Settings → Advanced) are off by default. Turned on, they keep Assist sessions and wake word clips on the phone only, to work out why it missed or misheard you.
- **Spoken PIN** (experimental) is separate from your phone's PIN and stored only as a salted hash; three wrong tries pause it for 5 minutes. The phone itself stays locked — no app can unlock Android.
- **Use HTTPS away from home.** Plain `http://` is allowed so local addresses work.
- **Permissions:** microphone (listening), notifications and foreground service (wake word), display over other apps (wake word opening Assist), camera is not requested (photos use the camera app and the photo picker).

This app had a quick security review while it was built (for example, other apps can't fake a wake-word launch to open it over the lock screen), but **no professional audit**. The code is small and readable — ask Claude or another AI to review it before you install if you want a second opinion, and open an issue if you find something.

## Build from source

Requires JDK 17 and the Android SDK (command-line tools are enough).

```bash
./scripts/download-models.sh        # speech engine + models from their publishers (~230 MB)
./gradlew assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
```

## Contributing and ideas

Fork it, improve it, send pull requests, or take the ideas to the [Home Assistant Companion app](https://github.com/home-assistant/android). Things I'd like next:

- More phone actions: alarms, calls and texts.
- A custom-trained wake word model, for better range than the general keyword spotter.
- Models downloaded on first launch instead of bundled (smaller APK).
- More languages (the models are English-only today).

## License

[Apache-2.0](LICENSE). See [THIRD_PARTY.md](THIRD_PARTY.md) for the libraries and models this builds on. Not affiliated with or endorsed by Home Assistant or Nabu Casa.
