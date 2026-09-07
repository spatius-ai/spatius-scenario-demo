# AvatarKit Scenes Demo

Scene-based demos for AvatarKit. Every scene ships in three implementations — Web,
Android and iOS.

## Layout

```
.
├── web/        # Web demos (@spatius/avatarkit)
├── android/    # Android demos (ai.spatius:avatarkit)
├── ios/        # iOS demos (AvatarKit.xcframework)
└── backend/    # Shared by all three; runs on your own machine
```

One project per platform rather than one per scene: the scenes share almost
everything that matters — the session layer, the config screen, the backend
client — and which one opens is picked on the config screen's second step.

## Scenes

| Scene | What it is | Web | Android | iOS |
|-------|------------|:---:|:-------:|:---:|
| One-on-one tutoring | Question panel plus avatar teacher and student video, adapting to portrait and landscape; spoken feedback on answers and a free-talk mode | ✅ | ✅ | ✅ |
| Live streaming | A chat-and-hangout stream: a scripted audience in the chat, danmaku over the video, gifts to send, and a mic to request | ✅ | ✅ | ✅ |
| Bank customer service | A branch assistant: topics to browse, a search box, spoken answers with cards, and a mic that hands the whole thing to the LLM | ✅ | ✅ | ✅ |
| Companion | No task at all — pick a character and talk. What is said is kept between visits and folded into the persona next time | ✅ | ✅ | ✅ |

Status: ⬜ not started · 🚧 in progress · ✅ done

## Backend

The avatar is driven over an RTC channel, which needs a backend to issue
connection credentials and start the conversational agent. All three platforms
share [`backend/`](backend/). It runs **on your own machine
with your own credentials** — see its README for the details.

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env      # fill in your credentials, or do it later from the app's config page
python server.py          # listens on 0.0.0.0:8787
```

Pick one of two transports with `TRANSPORT` in `.env` (the config page in each
app can switch it too):

- `livekit` — the conversation runs on your machine; `server.py` starts the agent
  worker for you
- `agora` — the conversation is hosted by Agora Conversational AI Engine. Two
  things in its console have to match the backend: the TTS sample rate, and the
  ASR setup (leave it at the defaults of a new agent) — both are explained in
  the backend README, and a mismatch fails silently

Point each client at the backend address. On startup the backend prints the LAN
address to use from a phone — a device cannot reach your computer's `localhost`.

## Adding a scene

A scene is one screen per platform plus its own data file, not a directory of its
own — see the three that are already there.

- Pick an id and use the same one everywhere (`tutoring`, `live`, `service`)
- Add the screen and register the id where the platform dispatches on it:
  `web/src/App.vue`, `android/.../MainActivity.kt`, `ios/.../SpatiusScenesApp.swift`
- Add it to the scene list on the config screen so it can be picked
- Add a row to the table above, marking any platform you have not implemented yet

## Per-platform notes

- [web/README.md](web/README.md)
- [android/README.md](android/README.md)
- [ios/README.md](ios/README.md)

## Related

The SDKs these demos are built on live in
[avatarkit-sdks](https://github.com/spatius-ai/avatarkit-sdks).
