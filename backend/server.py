"""Tutoring classroom backend, shared by all three clients (Web / Android / iOS).

Two transports, chosen by `TRANSPORT` in `.env`, behind an API that is identical
for clients either way:

- **livekit** (default): issues join tokens and dispatches the job; the agent
  runs locally in agent.py, and instructions like "read the question" reach it
  over LiveKit RPC. Models go through LiveKit Inference, routed by LiveKit Cloud,
  so credentials come down to LiveKit and Spatius — five in total.
- **agora**: ASR / LLM / TTS and the avatar are all hosted by Agora's
  Conversational AI Engine. Nothing runs locally beyond this server, which only
  signs tokens and calls REST. Models and voice are configured in the Agora
  console rather than here.

Both paths share the same action semantics: say / interrupt / free-talk / stop.
The client owns interaction timing throughout; the server never speaks on its own.

⚠️ This is a demo with no authentication: anyone who can reach this address can
start a session, and sessions are billed. Add authentication and rate limiting
before putting it on a public network.
"""

import asyncio
import atexit
import os
import signal
import socket
import subprocess
import sys
import threading
import uuid
from pathlib import Path

from dotenv import load_dotenv
from flask import Flask, jsonify, request
from flask_cors import CORS

from defaults import DEFAULT_AVATAR_ID

load_dotenv()

app = Flask(__name__)
CORS(app)

AGENT_NAME = "tutoring-classroom"

# Sessions: sessionId → (transport, that path's own handle).
#
# The handle differs per transport: a room name for LiveKit, ConvoAI's agent id
# for Agora. It is stored rather than derived from the sessionId each time so
# that sessions started before a transport switch can still be stopped — a
# leftover session bills continuously.
_sessions: dict[str, tuple[str, str]] = {}
_lock = threading.Lock()

# LiveKit path: one long-lived connection per room, keyed by room name. Building
# a connection takes about four seconds while an instruction takes milliseconds,
# so reconnecting for every one of them is not an option. Closed when the session
# stops (see _livekit_close).
_rooms: dict[str, object] = {}


def _transport() -> str:
    """The transport currently in effect: livekit or agora, defaulting to livekit."""
    return ((os.getenv("TRANSPORT") or "").strip().lower()) or "livekit"


def _err(message: str, status: int = 500):
    return jsonify({"error": message}), status


# ---------------------------------------------------------------- LiveKit


def _livekit_env() -> tuple[str, str, str]:
    url = os.getenv("LIVEKIT_URL", "").strip()
    key = os.getenv("LIVEKIT_API_KEY", "").strip()
    secret = os.getenv("LIVEKIT_API_SECRET", "").strip()
    if not (url and key and secret):
        raise RuntimeError("LiveKit credentials not configured (see .env.example)")
    return url, key, secret


async def _livekit_start(room_name: str, lang: str = "zh") -> None:
    """Create the room and dispatch the job.

    Dispatching is required: the worker registers under an agent_name, and only
    an explicit dispatch pulls it into this room.
    """
    from livekit import api

    url, key, secret = _livekit_env()
    lkapi = api.LiveKitAPI(url, key, secret)
    try:
        try:
            # Fallback cleanup: the client cannot always send a stop request —
            # closing the tab, losing the network, crashing — and letting LiveKit
            # reap the room on a timeout is more reliable than running our own
            # timer here.
            #   empty_timeout     how long to keep a room nobody ever joined
            #   departure_timeout how long to keep it after the last person left
            # Both are short in this demo: sessions bill by duration, so an empty
            # room is money burning.
            await lkapi.room.create_room(
                api.CreateRoomRequest(
                    name=room_name,
                    empty_timeout=120,
                    departure_timeout=20,
                    # The UI language rides to the worker on the room: the worker
                    # is dispatched into existence and has no other way of knowing
                    # which language the student is using, and the persona has to
                    # follow it.
                    #
                    # Not sent as a follow-up RPC — there is a window between the
                    # worker starting and session.start(), and an RPC landing in
                    # it puts the session into a bad state where every later say
                    # fails (it looks like "it read the question but tapping an
                    # option does nothing").
                    metadata=lang,
                )
            )
        except Exception:
            # The room may already exist.
            pass
        await lkapi.agent_dispatch.create_dispatch(
            api.CreateAgentDispatchRequest(room=room_name, agent_name=AGENT_NAME)
        )
    finally:
        await lkapi.aclose()


async def _livekit_rpc(room_name: str, method: str, payload: str = "") -> None:
    """Forward an instruction to the agent in the room.

    The connection is reused per room and not closed after sending — building a
    LiveKit connection means signing a token, opening a WebSocket and setting up
    WebRTC, measured at roughly four seconds, while sending one RPC takes
    milliseconds. Connecting and disconnecting each time would make the student
    wait four seconds after tapping an option to hear the feedback, and joining
    and leaving the same room that often also makes later RPCs fail (the previous
    disconnect has not finished when the next connect arrives).

    The connection is closed along with the session in `/api/session/stop` (see
    _livekit_close).
    """
    room = await _livekit_room(room_name)
    agent = next(
        (p for p in room.remote_participants.values() if p.identity.startswith("agent")),
        None,
    )
    if agent is None:
        raise RuntimeError("agent not in room yet")
    await room.local_participant.perform_rpc(
        destination_identity=agent.identity, method=method, payload=payload
    )


async def _livekit_room(room_name: str):
    """The long-lived connection for this room, creating one if there is none."""
    from livekit import api, rtc

    existing = _rooms.get(room_name)
    if existing is not None and existing.isconnected():
        return existing

    url, key, secret = _livekit_env()
    token = (
        api.AccessToken(key, secret)
        .with_identity(f"backend-{uuid.uuid4().hex[:8]}")
        .with_grants(api.VideoGrants(room_join=True, room=room_name))
        .to_jwt()
    )
    room = rtc.Room()
    await room.connect(url, token)
    _rooms[room_name] = room
    return room


async def _livekit_close(room_name: str) -> None:
    """Close this room's long-lived connection. Called when the session stops."""
    room = _rooms.pop(room_name, None)
    if room is not None:
        try:
            await room.disconnect()
        except Exception:  # noqa: BLE001 — already disconnected is not a failure
            pass


def _run(coro):
    """Hand a coroutine to the long-lived event loop and wait for its result.

    Flask is synchronous, but a LiveKit room connection is bound to the event loop
    that created it — with a fresh `asyncio.new_event_loop()` thrown away after
    each call, the connection is unusable next time and has to be rebuilt, which
    costs four seconds. So the backend runs one long-lived loop, all LiveKit work
    happens on it, and connections survive across requests.
    """
    return asyncio.run_coroutine_threadsafe(coro, _loop).result()


def _start_loop() -> asyncio.AbstractEventLoop:
    """The long-lived event loop, running on a background thread."""
    loop = asyncio.new_event_loop()
    threading.Thread(target=loop.run_forever, daemon=True, name="livekit-loop").start()
    return loop


_loop = _start_loop()


# ---------------------------------------------------------------- Routes


@app.post("/api/session")
def create_session():
    """Start a classroom session and return everything the client needs to join.

    The `transport` field tells the client which RTC stack to come in on — the
    rest of the response varies with it, and the client picks LiveKitProvider or
    AgoraProvider accordingly.

    **Billing starts the moment this is called** — the client must call
    /api/session/stop when it leaves.
    """
    body = request.get_json(silent=True) or {}
    avatar_id = (body.get("avatarId") or os.getenv("SPATIUS_AVATAR_ID") or DEFAULT_AVATAR_ID).strip()
    # The UI language. The persona follows it — otherwise, after switching to
    # English, the teacher answers in Chinese as soon as the student speaks.
    lang = (body.get("lang") or "zh").strip()
    transport = _transport()

    # A client that can only speak one transport says so, and gets it regardless of what
    # TRANSPORT is set to. The mobile clients ship the Agora SDK alone: served the LiveKit
    # response they would get a room name and a URL they cannot use, and fail on a decode
    # error that says nothing about the cause. TRANSPORT still decides for anyone who does
    # not ask — the web client speaks both and leaves this out.
    wanted = (body.get("transport") or "").strip().lower()
    if wanted in ("agora", "livekit"):
        transport = wanted

    try:
        if transport == "agora":
            return _create_agora_session(avatar_id, lang)
        return _create_livekit_session(avatar_id, lang)
    except Exception as exc:  # noqa: BLE001 — everything becomes a JSON error for the client
        return _err(str(exc))


def _create_livekit_session(avatar_id: str, lang: str = "zh"):
    from livekit import api

    url, key, secret = _livekit_env()
    room_name = f"tutoring-{uuid.uuid4().hex[:10]}"
    _run(_livekit_start(room_name, lang))

    identity = f"student-{uuid.uuid4().hex[:8]}"
    token = (
        api.AccessToken(key, secret)
        .with_identity(identity)
        .with_grants(api.VideoGrants(room_join=True, room=room_name, can_publish=True))
        .to_jwt()
    )
    session_id = room_name
    with _lock:
        _sessions[session_id] = ("livekit", room_name)

    return jsonify(
        {
            "transport": "livekit",
            "sessionId": session_id,
            "spatiusAppId": os.getenv("SPATIUS_APP_ID", ""),
            "url": url,
            "token": token,
            "roomName": room_name,
            "avatarId": avatar_id,
        }
    )


def _create_agora_session(avatar_id: str, lang: str = "zh"):
    import agora

    session = agora.start_agent(avatar_id, lang)
    # The agent id doubles as the sessionId: later actions look the agent up by it,
    # so there is no second mapping to keep.
    with _lock:
        _sessions[session.agent_id] = ("agora", session.agent_id)
    return jsonify(
        {
            "transport": "agora",
            "sessionId": session.agent_id,
            "spatiusAppId": session.spatius_app_id,
            "spatiusRegion": session.spatius_region,
            "appId": session.app_id,
            "channelName": session.channel_name,
            "token": session.token,
            "uid": session.uid,
            "agentUid": session.agent_uid,
            "avatarId": session.avatar_id,
        }
    )


CUSTOM_PERSONA_PATH = Path(__file__).resolve().parent / "memory" / "custom-persona.txt"


def _write_custom_persona(text: str) -> None:
    """Hand a user-written character to the agent worker.

    Through a file because the worker is a separate process: it re-reads this whenever it
    switches persona, so there is nothing to keep in sync.
    """
    CUSTOM_PERSONA_PATH.parent.mkdir(parents=True, exist_ok=True)
    CUSTOM_PERSONA_PATH.write_text(text, encoding="utf-8")


def _dispatch(action: str, body: dict):
    """Send an action to the agent.

    Dispatched by the transport recorded when the session was created: LiveKit
    goes in over RPC, Agora goes through ConvoAI's REST API.
    """
    session_id = (body.get("sessionId") or "").strip()
    if not session_id:
        return _err("sessionId is required", 400)

    with _lock:
        entry = _sessions.get(session_id)
    if not entry:
        return _err("unknown sessionId", 404)
    transport, handle = entry

    try:
        if transport == "agora":
            import agora

            text = body.get("text", "")
            if action == "say":
                agora.speak(handle, text)
            elif action == "interrupt":
                agora.interrupt(handle)
            else:
                agora.enter_free_talk(
                    handle,
                    body.get("lang", "zh"),
                    body.get("persona", "freetalk"),
                    body.get("custom", ""),
                    body.get("memoryKey", ""),
                )
        else:
            method = {"say": "say", "interrupt": "interrupt", "free-talk": "free_talk"}[action]
            # say carries the text, interrupt carries nothing, and free-talk carries
            # the language and the persona to switch to, joined because an RPC payload is
            # a single string.
            if action == "say":
                payload = body.get("text", "")
            else:
                # An RPC payload is one string, and a character the user wrote does not fit
                # in it alongside the language and the persona name. It is written to a file
                # the worker reads instead — the worker is a separate process, so a module
                # variable would not reach it.
                custom = body.get("custom", "")
                if custom.strip():
                    _write_custom_persona(custom)
                payload = (
                    f"{body.get('lang', 'zh')}:{body.get('persona', 'freetalk')}"
                    f":{body.get('memoryKey', 'friend')}"
                )
            _run(_livekit_rpc(handle, method, payload if action != "interrupt" else ""))
        return jsonify({"ok": True})
    except Exception as exc:  # noqa: BLE001
        # None of these are fatal to the classroom: failing to say one line of
        # feedback should not put an error in front of the student.
        app.logger.warning("%s failed: %s", action, exc)
        return jsonify({"ok": False, "error": str(exc)})


@app.post("/api/session/say")
def session_say():
    return _dispatch("say", request.get_json(silent=True) or {})


@app.post("/api/session/interrupt")
def session_interrupt():
    return _dispatch("interrupt", request.get_json(silent=True) or {})


@app.post("/api/session/free-talk")
def session_free_talk():
    return _dispatch("free-talk", request.get_json(silent=True) or {})


@app.get("/api/memory")
def memory_get():
    """What the companion currently remembers, for the scene to show on entry."""
    import memory

    stored = memory.load(request.args.get("persona") or memory.DEFAULT_PERSONA)
    return jsonify(
        {
            "summary": stored.summary,
            "turns": len(stored.recent),
            "size": stored.size,
        }
    )


@app.post("/api/memory/clear")
def memory_clear():
    """Forget everything, so the scene can be demonstrated from a blank slate."""
    import memory

    body = request.get_json(silent=True) or {}
    memory.clear(body.get("persona") or memory.DEFAULT_PERSONA)
    return jsonify({"ok": True})


@app.post("/api/session/stop")
def session_stop():
    """End the session.

    Must be called explicitly: the room is only reclaimed once it empties out, and
    it bills the whole time.
    """
    body = request.get_json(silent=True) or {}
    session_id = (body.get("sessionId") or "").strip()
    if not session_id:
        return _err("sessionId is required", 400)

    with _lock:
        entry = _sessions.pop(session_id, None)
    if not entry:
        return jsonify({"ok": True})
    transport, handle = entry

    try:
        if transport == "agora":
            import agora

            # Collect the conversation before ending it: the transcript lives with the
            # agent, and stopping it takes the history with it. Only the companion scene
            # keeps anything — the other three start fresh every time.
            if (body.get("remember") or "") == "1":
                try:
                    import memory

                    turns = agora.fetch_history(handle)
                    if turns:
                        memory.remember(turns, body.get("persona") or memory.DEFAULT_PERSONA)
                except Exception as exc:  # noqa: BLE001 — a visit that cannot be remembered still ends
                    app.logger.warning("could not record conversation: %s", exc)

            agora.stop_agent(handle)
        else:
            from livekit import api

            url, key, secret = _livekit_env()

            async def _delete():
                # Close our own long-lived connection before tearing the room down.
                await _livekit_close(handle)
                lkapi = api.LiveKitAPI(url, key, secret)
                try:
                    await lkapi.room.delete_room(api.DeleteRoomRequest(room=handle))
                finally:
                    await lkapi.aclose()

            _run(_delete())
    except Exception as exc:  # noqa: BLE001
        app.logger.warning("stop failed: %s", exc)
    return jsonify({"ok": True})


# ------------------------------------------------------------ Config read/write
#
# There is exactly one copy of the configuration, in .env. Clients do not keep
# their own; they ask for it on startup. All three connect to the same backend, so
# changing something once applies everywhere, and nobody has to go hunting through
# files.

ENV_PATH = Path(__file__).parent / ".env"

# Keys clients may read and write. Secrets are among them — the backend runs on
# the user's own machine and is only reachable on their LAN, and being able to
# fill everything in on screen and go matters more than keeping them out of the
# API. Deliberately excludes things like PORT that need a restart to take effect.
#
# Only lists what **has no default and will not run without it**. Model names,
# voices and endpoints already have working defaults in code; putting them on the
# screen would suggest every one of them has to be filled in. Following what the
# official demos do, those stay in .env.example with a note on what they are for,
# and whoever wants to change them edits the file.
# Shared by both transports.
COMMON_KEYS = [
    "SPATIUS_API_KEY",
    "SPATIUS_APP_ID",
    "SPATIUS_AVATAR_ID",
]

LIVEKIT_KEYS = [
    "LIVEKIT_URL",
    "LIVEKIT_API_KEY",
    "LIVEKIT_API_SECRET",
    # Voice. Not a credential, but the accent differs noticeably (some voices read
    # Chinese with a Cantonese accent), and being able to swap it on the spot beats
    # digging through .env.
    "TTS_MODEL",
]

# Agora path. Models and voice are configured in the agent in the Agora console;
# this only references its pipeline id, which is why the LLM / TTS settings do not
# need to appear on screen at all.
AGORA_KEYS = [
    "AGORA_APP_ID",
    "AGORA_APP_CERTIFICATE",
    "AGORA_PIPELINE_ID",
    # The avatar's audio sample rate, which has to match the TTS output configured
    # in the console. Not a credential, but a mismatch leaves the avatar silent
    # with no error anywhere, so it belongs on screen rather than in the docs.
    "AGORA_AVATAR_SAMPLE_RATE",
]

# The transport switch itself is editable too; without it there is no way to
# change transports from the UI.
EDITABLE_KEYS = ["TRANSPORT"] + COMMON_KEYS + LIVEKIT_KEYS + AGORA_KEYS


@app.get("/api/config")
def read_config():
    """The configuration currently in effect, used to populate the config page so
    the user can confirm or change it before submitting.

    `_fields` tells the client which settings each transport needs, so all three
    do not each maintain their own copy of the same list — adding a setting means
    changing only this. The leading underscore keeps it apart from actual values.
    """
    config = {key: os.getenv(key, "") for key in EDITABLE_KEYS}
    config["_fields"] = {
        "common": COMMON_KEYS,
        "livekit": LIVEKIT_KEYS,
        "agora": AGORA_KEYS,
    }
    return jsonify(config)


def _read_env_file() -> dict[str, str]:
    """Read the keys already in .env. Only understands the simplest form: one
    `KEY=value` per line."""
    if not ENV_PATH.exists():
        return {}
    existing: dict[str, str] = {}
    for raw in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        existing[key.strip()] = value.strip()
    return existing


@app.post("/api/config")
def write_config():
    """Write back to .env, taking effect immediately.

    Rewrites the whole file rather than appending: with a key repeated, dotenv's
    resolution is not obvious, and leaving duplicates around eventually produces
    the "I changed it and nothing happened" problem. Comments and formatting are
    lost in the process; what that buys is that the file always matches what is on
    screen.

    The rewrite has to carry over the other keys already in the file — anything not
    on screen (model names, voices, endpoints) was put there by the user, and
    keeping only EDITABLE_KEYS would wipe it out the first time they hit save on
    the config page.
    """
    body = request.get_json(silent=True) or {}
    updates = {k: str(v) for k, v in body.items() if k in EDITABLE_KEYS}

    merged = _read_env_file()
    merged.update({key: os.getenv(key, merged.get(key, "")) for key in EDITABLE_KEYS})
    merged.update(updates)

    lines = ["# Written by the config page. You can also edit this file directly.", ""]
    lines += [f"{key}={value}" for key, value in merged.items()]
    ENV_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Mirror into the running process so no restart is needed.
    for key, value in merged.items():
        os.environ[key] = value

    # The agent worker is a separate process and read .env at its own startup, so the
    # values above never reach it. Restarting is what makes a save take effect without
    # the user having to restart the server themselves.
    _restart_agent_worker()

    return jsonify({"ok": True})


def _lan_ip() -> str:
    """This machine's address on the LAN, or an empty string if it cannot be found.

    Resolves every IPv4 from the hostname and picks one by private-range priority.

    Deliberately not the common "connect to an external address and see which
    interface the kernel picks" trick: with a VPN up, the default route points at
    the tunnel interface and that returns something like 172.19.0.1, which a phone
    cannot reach at all (measured here).
    """
    try:
        candidates = [
            info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        ]
    except OSError:
        return ""

    def is_private(ip: str) -> bool:
        if ip.startswith("192.168.") or ip.startswith("10."):
            return True
        if ip.startswith("172."):
            second = int(ip.split(".")[1]) if ip.count(".") >= 1 else 0
            return 16 <= second <= 31
        return False

    # Home and office ranges first: VPNs and container bridges tend to sit in
    # 172.16-31, so that one goes last.
    for ip in candidates:
        if ip.startswith("192.168."):
            return ip
    for ip in candidates:
        if ip.startswith("10."):
            return ip
    for ip in candidates:
        if is_private(ip):
            return ip
    return ""


PUBLIC_AVATARS = [
    {"id": "8b86dda1-98ed-4acd-8a4e-b1a00ba69268", "name": "Leyla", "coverUrl": "https://cdn.spatius.ai/avatar-assets/5aa9ab58-09c1-4f69-a4ce-e6757ddbb638/a81500a3-a57c-4028-8c93-c7beea86015b.jpg"},
    {"id": "566981dd-1d95-4844-953e-d67e18b2fde8", "name": "Adrian", "coverUrl": "https://cdn.spatius.ai/avatar-assets/16e8f443-aaab-444e-9183-c448fa527451/8e205849-0e1c-4912-88bc-5edff55de551.jpg"},
    {"id": "981ed26d-fbfe-42eb-a5d5-56ebd104847b", "name": "Haru", "coverUrl": "https://cdn.spatius.ai/avatar-assets/3b4e39ea-381a-4c27-9ff4-d49a74f8eb6c/4e77f48e-5ab3-43a0-8bb9-3a82efd95fdb.jpg"},
    {"id": "56f31c71-58ff-410f-85d2-11b9658c7b49", "name": "Ethan", "coverUrl": "https://cdn.spatius.ai/avatar-assets/451226c7-df18-4d2f-bc5b-8352ca506737/f60fcecb-66ba-48a0-98ad-746f7faf8939.jpg"},
    {"id": "e06640cb-e011-4806-bd3e-6b07575eff2e", "name": "Samir", "coverUrl": "https://cdn.spatius.ai/avatar-assets/1f5520f9-7558-4a12-b0a8-65c0f325ad3b/2b446079-c01b-4cfe-b98c-c6553d71cbed.jpg"},
    {"id": "4aef57fb-80d7-42d7-bce0-b7b6bee211fb", "name": "Andy", "coverUrl": "https://cdn.spatius.ai/avatar-assets/avatars/4aef57fb-80d7-42d7-bce0-b7b6bee211fb/cover/d63fc31a-8083-4904-b2e7-5c3d1fceb9db.png"},
    {"id": "41c62a7c-993c-4b6b-b6d3-549ce3c8be00", "name": "Kian", "coverUrl": "https://cdn.spatius.ai/avatar-assets/ad70324c-3788-4f90-bb60-627db52f5f5f/36f15985-fd01-419b-b1ca-57d7063b8975.jpg"},
    {"id": "dbb01388-7c57-47bf-ab59-c492caeb9d90", "name": "Julian", "coverUrl": "https://cdn.spatius.ai/avatar-assets/ba527f07-7c22-47fb-9257-5d241d920249/9c8e54ef-8d93-4135-803c-a17afa469df2.jpg"},
    {"id": "d51ab422-3db7-47cc-afa8-7273b02bc70b", "name": "Clara", "coverUrl": "https://cdn.spatius.ai/avatar-assets/f0698ab0-3490-4505-aae9-52d9d91e9129/360bbe88-08ba-4a39-9ea3-89f943369170.jpg"},
    {"id": "c7069121-8245-4015-9940-82d0dc0c6bda", "name": "Halima", "coverUrl": "https://cdn.spatius.ai/avatar-assets/00bee5e1-b0b4-46c9-9ba4-83cc2143c7ac/e4cd014f-12a2-4272-b9eb-a892079ccade.jpg"},
    {"id": "3854e8f8-d4d9-42e2-af7e-9f6971ad18e2", "name": "Amara", "coverUrl": "https://cdn.spatius.ai/avatar-assets/avatars/3854e8f8-d4d9-42e2-af7e-9f6971ad18e2/cover/2c287172-f23e-40d1-a291-e453564ee8c3.png"},
    {"id": "94a457d0-fa28-4aa8-a37a-e41e9f448f89", "name": "Elena", "coverUrl": "https://cdn.spatius.ai/avatar-assets/3e71b400-69bf-428f-bb7e-1f890b294cec/1a4a090b-f283-4f5d-855d-4844d7d71805.png"},
    {"id": "d8d8401c-8bbf-42f3-a582-9aa514c36728", "name": "Shu Man", "coverUrl": "https://cdn.spatius.ai/avatar-assets/eec9fce2-d501-4f9c-ab09-4a5c71c5ac44/2ddef61b-dfba-468a-a9d2-62d9e42c6398.png"},
]


@app.get("/api/catalogue")
def catalogue():
    """The characters a client can pick from, with their cover images.

    Served from here rather than baked into each client so the three of them cannot
    drift apart, and so adding a character is a change in one place.
    """
    return jsonify({"avatars": PUBLIC_AVATARS})


@app.get("/health")
def health():
    """`lanUrl` is there for clients to display: a phone has to use the LAN
    address, and the page cannot work it out on its own.

    `transport` comes along to make troubleshooting easier — "which path is this
    actually running" is the first thing to establish with two transports, and the
    Agora path in particular starts no worker of its own, so the process list does
    not tell you.
    """
    ip = _lan_ip()
    port = int(os.getenv("PORT", "8787"))
    return jsonify(
        {
            "ok": True,
            "lanUrl": f"http://{ip}:{port}" if ip else "",
            "transport": _transport(),
        }
    )


def _spawn_agent_worker() -> subprocess.Popen | None:
    """Bring up the LiveKit agent worker alongside this server.

    On the LiveKit path the conversation runs locally: the worker is a separate
    process that registers with LiveKit Cloud on startup and is pulled into a room
    by `create_dispatch`. Without it the room is created and the client connects
    fine, but nobody reads the question — it sits on "your teacher is on the way"
    forever, with no error on either side.

    So it is started for the user here rather than left as something to remember to
    run in another terminal. The Agora path needs no worker (the conversation runs
    in Agora's cloud), so none is started.

    The process exits with this one (`atexit`). A worker that crashes on its own is
    not restarted — there is a stack trace in the log for that, and restarting
    would only scroll it away.
    """
    if _transport() != "livekit":
        return None

    # Nothing to register with yet. The worker exits immediately on a missing URL, and
    # the failure is a stack trace in this terminal that scrolls away — after which every
    # request answers "agent not in room yet" and nothing says why. Skipping is the
    # honest outcome: it starts for real once the credentials are saved.
    if not (os.getenv("LIVEKIT_URL") or "").strip():
        app.logger.warning(
            "LIVEKIT_URL is not set — the agent worker will start once credentials are "
            "saved from the config page"
        )
        return None

    worker = subprocess.Popen(
        [sys.executable, str(Path(__file__).parent / "agent.py"), "dev"],
        cwd=str(Path(__file__).parent),
        # Its own process group. Sharing this one means Ctrl+C reaches the worker as well
        # as the server, and the two then wait on each other — the worker begins its own
        # shutdown while the server waits for it, and the terminal hangs until both are
        # killed by hand.
        start_new_session=True,
    )
    return worker


_worker: subprocess.Popen | None = None
_worker_lock = threading.Lock()


def _stop_agent_worker() -> None:
    """Stop the worker and wait for it to actually be gone.

    The worker forks children of its own, so signalling the process group rather than the
    one process is what stops the whole tree; without it the children outlive the server
    and keep holding the port. Escalates to SIGKILL because a worker mid-shutdown does
    not always honour SIGTERM.
    """
    global _worker
    if _worker is None:
        return
    try:
        os.killpg(os.getpgid(_worker.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass
    try:
        _worker.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(_worker.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        _worker.wait(timeout=2)
    _worker = None


def _restart_agent_worker() -> None:
    """Bring the worker back up against the credentials that are current now.

    The worker is a separate process that reads .env once, at startup. Writing new
    values into this process's environment does nothing for it, so without this the
    first save on the config page appears to do nothing at all: the page says saved,
    and the classroom still cannot reach an agent that is running on whatever was in
    .env when the server booted — or that was never started, because the transport
    said Agora at the time.

    Called on every save rather than only when something changed: comparing would mean
    tracking which keys the worker actually reads, and a restart costs a second.
    """
    global _worker
    with _worker_lock:
        _stop_agent_worker()
        _worker = _spawn_agent_worker()


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8787"))

    # Print the address a phone should use.
    #
    # A phone cannot reach the dev machine's localhost, so the config page needs
    # the LAN address, and there is nowhere else for the user to see it — a web
    # page derives it from location.hostname, a phone cannot. This terminal is the
    # first thing they look at after starting the server, so it belongs here.
    ip = _lan_ip()
    print()
    if ip:
        print(f"  Enter this address on the phone's config page: http://{ip}:{port}")
    else:
        print("  Could not determine the LAN address — look up this machine's IP for the phone")
    print()

    # Start the worker only in the main process: Flask's reloader re-executes the
    # module, and without this guard every hot reload leaves another worker behind.
    if os.getenv("WERKZEUG_RUN_MAIN") != "true":
        # Through the lock-guarded path so the handle is stored where a later save on
        # the config page can find and replace it.
        _restart_agent_worker()
        # Registered once here rather than per spawn: restarts replace the handle the
        # stop reads, so one registration covers whichever worker is current, and
        # repeated registration would leave a trail of callbacks for dead processes.
        atexit.register(_stop_agent_worker)

    app.run(host="0.0.0.0", port=port)
