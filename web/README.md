# Web scene demos

Scene demos for the browser, built on `@spatius/avatarkit`.

One project, several scenes — which one opens is picked on the config page's
second step, and they share the session layer, the config screen and the backend
client.

## Running

```bash
cd web
pnpm install
pnpm dev
```

Then open http://localhost:5180/spatius-scenario-demo/ — the path is deliberate, see `vite.config.ts`. It talks to [`backend/`](../backend/), which has
to be running first — see its README for the credentials it needs.

## Scenes

| Scene | What it is |
|-------|------------|
| Tutoring classroom | Questions on the left, avatar teacher and student video on the right; spoken feedback on answers and a free-talk mode |
| Live room | A chat-and-hangout stream: a scripted audience, danmaku over the video, gifts, and a mic to request |
| Bank support | Avatar on the left, topics and answers in a column on the right: a search box, spoken answers with cards, and a mic button that answers anything |
| Companion | Pick a character, then just talk. The conversation is remembered between visits and carried into the persona next time |
