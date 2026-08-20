# Android scene demos

Scene demos for Android, built on `ai.spatius:avatarkit`.

One app, several scenes — which one opens is picked on the config screen's second
step, and they share the RTC session, the config screen and the backend client.

Agora only. The LiveKit path exists on the web client, where a browser can carry
both without either being much work; here it would mean a second SDK and a second
set of credentials for a path this demo does not need.

## Running

Open `android/` in Android Studio and run, or:

```bash
cd android
./gradlew :app:installDebug
```

It talks to [`backend/`](../backend/), which has to be running first — see its
README. A phone cannot reach the machine's localhost, so the config screen asks
for a LAN address; the backend prints the right one on startup.

## Scenes

| Scene | What it is |
|-------|------------|
| Tutoring classroom | Questions on one side, avatar teacher and student video on the other, adapting to portrait and landscape |
| Live room | A chat-and-hangout stream: a scripted audience, danmaku over the video, gifts, and a mic to request |
| Bank support | Avatar over the bank floor with a translucent sheet below it: topics to browse, a search box, and a mic button that answers anything |
| Companion | Pick a character, then just talk. The conversation is remembered between visits and carried into the persona next time |
