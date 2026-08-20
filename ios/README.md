# iOS scene demos

Scene demos for iOS, built on AvatarKit.

One app, several scenes — which one opens is picked on the config screen's second
step, and they share the RTC session, the config screen and the backend client.

Agora only, matching the Android demo. The LiveKit path exists on the web client,
where a browser can carry both; here it would mean a second SDK and a second set
of credentials for a path this demo does not need.

## Running

The project is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
cd ios
xcodegen generate
open SpatiusScenes.xcodeproj
```

Device only — neither Agora nor AvatarKit ships a simulator slice.

It talks to [`backend/`](../backend/), which has to be running first — see its
README. A phone cannot reach the machine's localhost, so the config screen asks
for a LAN address; the backend prints the right one on startup.

## Scenes

| Scene | What it is |
|-------|------------|
| Tutoring classroom | Questions on one side, avatar and student video on the other, adapting to portrait and landscape |
| Live room | A chat-and-hangout stream: a scripted audience, danmaku over the video, gifts, and a mic to request |
| Bank support | Avatar over the bank floor with a translucent sheet below it: topics to browse, a search box, and a mic button that answers anything |
| Companion | Pick a character, then just talk. The conversation is remembered between visits and carried into the persona next time |
