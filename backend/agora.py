"""Agora path: orchestrating ConvoAI.

The division of labour differs from the LiveKit path — that one runs an agent
process of its own (agent.py), this one does not: ASR / LLM / TTS and the avatar
are all hosted by Agora's Conversational AI Engine, and the backend only signs
tokens and calls REST.

Models and voice come from the agent that `AGORA_PIPELINE_ID` points at — built,
configured and published in the Agora console; this only references it. That is
why the user does not have to sign up with each LLM and TTS provider.

The avatar goes in as a ConvoAI avatar vendor (`avatar.vendor = "spatius"`): the
engine feeds TTS audio to Spatius, Spatius generates motion data and joins the
same Agora channel as its own publisher, and the client's AvatarKit subscribes
and renders locally. What travels through the channel is audio plus motion data,
not rendered video.

A session has three RTC participants: the student (client), the conversational
agent, and the avatar publisher. Agora does not allow UID collisions, so the
three draw from non-overlapping ranges.
"""

from __future__ import annotations

import os
import secrets
import sys
from dataclasses import dataclass

import requests

from agora_token import Role_Publisher, RtcTokenBuilder
from defaults import DEFAULT_AVATAR_ID

# UID ranges, non-overlapping across the three participants.
UID_RANGES = {
    "user": (100_000, 599_999),
    "agent": (600_000, 799_999),
    "avatar": (800_000, 999_999),
}

SESSION_TTL_SECONDS = 30 * 60
# How long ConvoAI waits with no remote user in the channel before stopping the
# agent. A backstop — the client still has to stop explicitly.
IDLE_TIMEOUT_SECONDS = 60
# Sample rates Motion Server accepts (see docs.spatius.ai/concepts/audio).
SUPPORTED_SAMPLE_RATES = (8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
DEFAULT_AVATAR_SAMPLE_RATE = 24_000


def _avatar_sample_rate() -> int:
    """The avatar's audio sample rate, which **must equal the TTS output rate**.

    Motion Server supports 8000 / 16000 / 22050 / 24000 / 32000 / 44100 / 48000
    but does not resample: a mismatch is simply silent — the avatar joins,
    publishes and reports its track as playing, the volume stays at zero, and
    neither side reports an error.

    See SUPPORTED_SAMPLE_RATES for the range. 24000 is the default and also what
    TTS providers that do not expose the setting (OpenAI, for one) actually emit,
    so it rarely needs changing.

    Read on every call rather than as a module-level constant: a constant is
    evaluated at import, before the .env the config page writes back has been
    loaded, so changes would not take effect.
    """
    return int((os.getenv("AGORA_AVATAR_SAMPLE_RATE") or "").strip() or DEFAULT_AVATAR_SAMPLE_RATE)


REQUEST_TIMEOUT_SECONDS = 20
# Lifetime of the REST auth token. It is used immediately after signing, so five
# minutes is plenty.
REST_AUTH_TOKEN_TTL_SECONDS = 5 * 60

DEFAULT_CONVOAI_BASE_URL = "https://api.agora.io"

# Speech recognition, sent with every session. These are what a newly created agent comes
# with in the console, so a fresh agent in any account matches them as is; they only need
# changing when the ASR vendor or model was changed in the console — see the README.
#
# No credential goes with them: the console's ASR panel shows a `resource_id` next to the
# model, but the join API ignores it (a garbage id recognised Chinese and English just the
# same, verified against a second account), and the credential comes from the agent itself.
ASR_VENDOR = "deepgram"
ASR_MODEL = "nova-3"

# Personas, kept in sync with the LiveKit path (agent.py): the teacher should
# sound the same on both paths, so changing one means changing the other.
#
# One set per language. When the client switches to English the questions and the
# read-aloud text follow, but if the persona stays Chinese the teacher answers in
# Chinese the moment the student opens the mic and asks something. The language
# rides along with the request — it lives on each client, and the backend has no
# way of knowing it otherwise.
PROMPTS = {
    "zh": {
        "tutor": """你是一位耐心的小学数学老师，正在一对一辅导学生做题。
用口语化的中文回答，每次不超过 50 字。不要使用 Markdown 或任何符号排版，
你说的每一个字都会被朗读出来。学生答错时给提示而不是直接给答案。""",
        "freetalk": """你是一位耐心的小学数学老师，学生已经做完了练习题，
现在是自由问答时间。用口语化的中文回答，每次不超过 100 字。不要使用
Markdown 或任何符号排版，你说的每一个字都会被朗读出来。""",
        # The live room's own persona. Nothing about tutoring survives here: the same
        # backend serves both scenes, and a viewer who gets on the mic is talking to a
        # streamer, not to a maths teacher.
        "host": """你是一位正在直播的主播，观众刚刚连麦上来和你说话。
像朋友聊天那样回应，语气轻松自然，每次不超过 50 字。不要使用 Markdown
或任何符号排版，你说的每一个字都会被朗读出来。""",
        # The companion scene. No task, no menu — the whole scene is the conversation,
        # and what makes it different from freetalk is that it remembers: everything said
        # is kept (see memory.py) and folded back in here on the next visit.
        "companion": """你是用户的朋友，正在随意聊天。
用口语化的中文回答，每次不超过 60 字。不要使用 Markdown 或任何符号排版，
你说的每一个字都会被朗读出来。

像真正的朋友那样说话：不用每句都有帮助，可以只是接话、追问、开个玩笑。
不要像客服或助理那样反复问「还有什么可以帮您」。记住对方说过的事，
自然地提起来，但不要生硬地复述记忆内容。

对方一开口，你的第一句要先接住上次聊到的事——挑一件具体的问问后来怎么样了，
就像惦记着这件事一样。如果你还什么都不记得，就像初次见面那样打个招呼。""",
        # The bank scene's live Q&A. The menu answers are canned text; this is what takes
        # over when the customer opens the mic, so the business knowledge that backs those
        # answers is carried here as background rather than a script.
        "banker": """你是一家银行的数字客户经理，正在网点为客户解答业务问题。
用口语化的中文回答，每次不超过 100 字。不要使用 Markdown 或任何符号排版，
你说的每一个字都会被朗读出来。

你熟悉以下业务，回答时以此为准：
账户——对公账户分基本、一般、临时、专用四类；基本户一家单位只能开一个、
可支取现金；一般户可存不可取、需先有基本户；临时户最长两年；专用户需提供
用途证明。账户连续两年无收付转为久悬户，需持开户资料到开户网点恢复。
银行卡——挂失可在手机银行即时冻结，正式挂失补卡需本人持身份证到网点，当场
拿卡。密码错三次锁定、次日零点解锁，重置须本人到网点。每人可申请一个账户
免年费和小额账户管理费。
转账——手机银行默认单笔五万、单日二十万，可在安全中心自助调整；线上境内
转账免手续费；本行实时到账，跨行可选实时、普通或次日；次日到账在到账前
可撤销，已到账的银行无权划回。
贷款——利率按 LPR 加点执行，实际以审批为准；房贷需身份证、收入证明、购房
合同、首付凭证，审批约五到十个工作日；放款满一年提前还款免违约金，不满
一年收百分之一。
理财——风险评估在手机银行线上完成、有效期一年；产品按风险分五级；开放式
产品开放日可赎回、一到三个工作日到账，封闭式需持有到期。
电子银行——手机银行用本人卡和预留手机号自助注册；变更预留手机号须本人到
网点；登录密码错五次锁定、次日零点解锁。

不清楚或超出以上范围的，如实说需要为客户转接人工或建议到网点咨询，
不要编造利率、费用或办理条件。银行工作人员绝不会索要密码和验证码。""",
    },
    "en": {
        "tutor": """You are a patient primary school maths teacher tutoring one student.
Answer in spoken English, at most 40 words each time. Do not use Markdown or any
symbol formatting — every character you write will be read aloud. When the student
gets it wrong, give a hint rather than the answer.""",
        "freetalk": """You are a patient primary school maths teacher. The student has
finished the exercises and this is open question time. Answer in spoken English, at
most 80 words each time. Do not use Markdown or any symbol formatting — every
character you write will be read aloud.""",
        "host": """You are a livestreamer and a viewer has just come on the mic to
talk to you. Reply the way you would to a friend — relaxed and unforced, at most 40
words each time. Do not use Markdown or any symbol formatting, since every character
you write will be read aloud.""",
        "companion": """You are the user's friend, chatting with them.

Answer in spoken English, at most 50 words each time. Do not use Markdown or any
symbol formatting — every character you write will be read aloud.

Talk the way a friend does: not every line has to be useful, and picking up on what
they said, asking about it, or making a joke is enough. Do not keep asking whether
there is anything else you can help with, the way an assistant would. Remember what
they have told you and bring it up naturally, but do not recite your notes back at
them.

When they first speak, open by picking up something specific from last time — ask how
it turned out, the way someone does who has been wondering about it. If you remember
nothing yet, just greet them as you would someone you are meeting for the first
time.""",
        "banker": """You are a bank's digital account manager, helping a customer in
branch. Answer in spoken English, at most 80 words each time. Do not use Markdown or
any symbol formatting — every character you write will be read aloud.

Work from the following, which is what the bank actually offers:
Accounts — business accounts come in four kinds: basic, general, temporary and
special. One basic account per company, cash withdrawals allowed. General accounts
take deposits but no withdrawals and need a basic account first. Temporary accounts
run at most two years. Special accounts need evidence of the earmarked purpose. Two
years without activity moves an account to dormant, reactivated at the opening branch.
Cards — freeze instantly in the app; a formal report and replacement needs the
customer in branch with ID, issued the same day. Three wrong PINs locks it until
midnight, and resetting needs them in branch. One account per customer can have the
annual and small balance fees waived.
Transfers — app defaults are fifty thousand per transfer and two hundred thousand a
day, adjustable in the security centre. Domestic online transfers are free. Own-bank
is instant; interbank can be instant, standard or next day. Next day can be cancelled
before it settles; once it has landed the bank cannot pull it back.
Lending — priced off LPR plus a margin, confirmed at approval. Mortgages need ID,
proof of income, the purchase contract and evidence of deposit, assessed in five to
ten working days. No early repayment charge after the first year, one percent inside it.
Investments — risk assessment is done in the app and lasts a year. Products are graded
across five risk levels. Open-ended products redeem on dealing days, money back in one
to three working days; closed-ended run to maturity.
Digital banking — register with the card and the mobile number on file. Changing that
number has to be done in branch. Five wrong passwords locks the login until midnight.

If something falls outside this, say honestly that you would pass them to a colleague
or suggest visiting a branch. Never invent a rate, a fee or an eligibility rule. Bank
staff never ask for passwords or verification codes.""",
    },
}


def _prompt(kind: str, lang: str, custom: str = "", memory_key: str = "") -> str:
    """Persona for a language, falling back to Chinese for anything unknown —
    the demo's default question bank is Chinese.

    An unknown persona falls back to freetalk rather than raising: the name arrives from
    the client, and a typo should leave the avatar talking rather than take the request
    down with a KeyError."""
    table = PROMPTS.get(lang, PROMPTS["zh"])

    if kind == "companion" and custom.strip():
        # A character the user wrote. Wrapped in the same rules the built-in companions
        # carry — length and no markup — because those are about the medium rather than the
        # character: everything here is read aloud, however it was written.
        rules = (
            "用口语化的中文回答，每次不超过 60 字。不要使用 Markdown 或任何符号排版，"
            "你说的每一个字都会被朗读出来。"
            if lang != "en"
            else "Answer in spoken English, at most 50 words each time. Do not use Markdown "
            "or any symbol formatting — every character you write will be read aloud."
        )
        persona = f"{custom.strip()}\n\n{rules}"
    else:
        persona = table.get(kind, table["freetalk"])

    # The companion is the one persona that carries state between visits. Appended rather
    # than stored in the table, since it changes with every conversation.
    if kind == "companion":
        import memory

        notes = memory.load(memory_key or memory.DEFAULT_PERSONA).as_prompt()
        if notes:
            heading = (
                "以下是你记得的关于对方的事：" if lang != "en" else "Here is what you remember about them:"
            )
            persona = f"{persona}\n\n{heading}\n{notes}"
    return persona


class AgoraConfigError(RuntimeError):
    """Missing credentials or invalid configuration."""


@dataclass
class AgoraSession:
    """Connection credentials for one session, returned to the client."""

    agent_id: str
    app_id: str
    channel_name: str
    token: str
    uid: int
    avatar_id: str
    spatius_app_id: str
    spatius_region: str
    # The conversational agent's uid. The client uses it to tell whether the
    # agent has joined — ConvoAI starts it asynchronously after /join returns and
    # it takes a second or two to arrive; a speak sent in that window is dropped.
    agent_uid: int


def _require(key: str) -> str:
    value = (os.getenv(key) or "").strip()
    if not value:
        raise AgoraConfigError(f"{key} is not configured (see .env.example)")
    return value


def _random_uid(kind: str) -> int:
    low, high = UID_RANGES[kind]
    return low + secrets.randbelow(high - low + 1)


def _base_url() -> str:
    configured = (os.getenv("AGORA_CONVOAI_BASE_URL") or "").strip()
    return (configured or DEFAULT_CONVOAI_BASE_URL).rstrip("/")


def _auth_header() -> str:
    """REST authentication for ConvoAI.

    Signs a short-lived AccessToken2 on the spot from the App ID and certificate
    and puts it in the Authorization header. It is the same signing path as the
    join token, which is why the static Customer ID / Secret pair is not needed —
    two fewer things for the user to fill in. The channel is an empty string (a
    wildcard): the REST layer only verifies the signature, not channel ownership.
    The uid carries no meaning here, so it is fixed.
    """
    token = RtcTokenBuilder.build_token_with_uid(
        _require("AGORA_APP_ID"),
        _require("AGORA_APP_CERTIFICATE"),
        "",
        1,
        Role_Publisher,
        REST_AUTH_TOKEN_TTL_SECONDS,
        REST_AUTH_TOKEN_TTL_SECONDS,
    )
    return f'agora token="{token}"'


def _safe_upstream_message(payload: object, status: int) -> str:
    """Upstream errors can echo back configuration, secrets included, so they are
    never passed through verbatim."""
    # `detail` first: `reason` is a bare code ("InternalError") while `detail` carries the
    # actual cause ("properties: tts.addon not found"), and losing that turned a wrong
    # pipeline id into an opaque 500 on the config page.
    detail = ""
    if isinstance(payload, dict):
        for key in ("detail", "reason", "message"):
            value = payload.get(key)
            if isinstance(value, str) and value:
                detail = value
                break
    lowered = detail.lower()
    if not detail or any(k in lowered for k in ("key", "secret", "token", "authorization")):
        return f"ConvoAI request failed (HTTP {status})"
    return detail


def _mint_identities() -> dict:
    """Mint the channel name and tokens for the three identities.

    The student token carries both RTC and RTM privileges: ConvoAI's data_channel
    runs over RTM, and without it the status messages the engine publishes never
    arrive. AccessToken2 binds the RTM login to the same account, so when the
    client logs into RTM it has to use exactly the same string as its RTC uid.
    """
    app_id = _require("AGORA_APP_ID")
    certificate = _require("AGORA_APP_CERTIFICATE")
    channel = f"tutoring-{secrets.token_hex(10)}"
    ttl = SESSION_TTL_SECONDS
    user_uid = _random_uid("user")
    agent_uid = _random_uid("agent")
    avatar_uid = _random_uid("avatar")

    return {
        "channel": channel,
        "user_uid": user_uid,
        "user_token": RtcTokenBuilder.build_token_with_rtm(
            app_id, certificate, channel, str(user_uid), Role_Publisher, ttl, ttl
        ),
        "agent_uid": agent_uid,
        "agent_token": RtcTokenBuilder.build_token_with_uid(
            app_id, certificate, channel, agent_uid, Role_Publisher, ttl, ttl
        ),
        "avatar_uid": avatar_uid,
        "avatar_token": RtcTokenBuilder.build_token_with_uid(
            app_id, certificate, channel, avatar_uid, Role_Publisher, ttl, ttl
        ),
    }


def start_agent(avatar_id: str = "", lang: str = "zh") -> AgoraSession:
    """Start a ConvoAI agent and return everything the client needs to join.

    **Billing starts the moment this is called** — the client must call
    stop_agent when it leaves.
    """
    app_id = _require("AGORA_APP_ID")
    spatius_app_id = _require("SPATIUS_APP_ID")
    region = (os.getenv("SPATIUS_REGION") or "").strip() or "cn-beijing"
    avatar = (avatar_id or os.getenv("SPATIUS_AVATAR_ID") or DEFAULT_AVATAR_ID).strip()
    pipeline_id = _require("AGORA_PIPELINE_ID")
    ids = _mint_identities()

    properties = {
        "channel": ids["channel"],
        "token": ids["agent_token"],
        "agent_rtc_uid": str(ids["agent_uid"]),
        "remote_rtc_uids": [str(ids["user_uid"])],
        "idle_timeout": IDLE_TIMEOUT_SECONDS,
        "advanced_features": {"enable_rtm": True},
        # The avatar is not configured in the console, so it goes here in both
        # modes.
        "avatar": {
            "enable": True,
            "vendor": "spatius",
            "params": {
                "spatius_api_key": _require("SPATIUS_API_KEY"),
                "spatius_app_id": spatius_app_id,
                "spatius_avatar_id": avatar,
                "agora_uid": str(ids["avatar_uid"]),
                "agora_token": ids["avatar_token"],
                "region": region,
                "sample_rate": _avatar_sample_rate(),
                "session_expire_minutes": 30,
            },
        },
        # Let the student interrupt the avatar at any point.
        "turn_detection": {"interrupt_mode": "interrupt"},
        "parameters": {"data_channel": "rtm", "enable_error_message": True},
    }

    body: dict = {"name": ids["channel"], "properties": properties}

    # ASR / LLM / TTS all come from the configuration published in the console —
    # not one of them is sent from here.
    #
    # The persona is the exception and is still sent by us: the console holds the
    # agent's base prompt, but the classroom switches between answering questions
    # and free talk, which only the client can drive. In pipeline mode `llm` is an
    # optional override, so we send system_messages alone and leave the rest.
    body["pipeline_id"] = pipeline_id
    properties["llm"] = {"system_messages": [{"role": "system", "content": _prompt("tutor", lang)}]}
    # Recognition language follows the UI, so switching to English makes the avatar
    # understand English rather than transcribing it against a Chinese model.
    #
    # The whole block has to be sent: in pipeline mode a field given here replaces the
    # agent's, so a partial block would drop the vendor or model. The language goes in
    # both places because the console's own ASR params JSON carries it under params too.
    #
    # Changing the ASR vendor or model in the console means changing it here too — see the
    # README — and a mismatch is silent: Chinese speech comes back as "Yeah." and "Hello?",
    # or as empty text with the timings intact, and nothing reports an error.
    asr_language = "en" if lang == "en" else "zh"
    properties["asr"] = {
        "vendor": ASR_VENDOR,
        "language": asr_language,
        "model": ASR_MODEL,
        "params": {
            "language": asr_language,
            "model": ASR_MODEL,
            "keyterm": "",
        },
    }

    response = requests.post(
        f"{_base_url()}/api/conversational-ai-agent/v2/projects/{app_id}/join",
        headers={"Authorization": _auth_header(), "Content-Type": "application/json"},
        json=body,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    payload = _json_or_none(response)
    if not response.ok:
        # The terminal gets the whole thing: the client only sees the filtered message.
        print(f"ConvoAI join failed: HTTP {response.status_code} {response.text[:500]}", file=sys.stderr)
        raise RuntimeError(_safe_upstream_message(payload, response.status_code))

    agent_id = payload.get("agent_id") if isinstance(payload, dict) else None
    if not isinstance(agent_id, str) or not agent_id:
        raise RuntimeError("ConvoAI returned no agent id")

    return AgoraSession(
        agent_id=agent_id,
        app_id=app_id,
        channel_name=ids["channel"],
        token=ids["user_token"],
        uid=ids["user_uid"],
        avatar_id=avatar,
        spatius_app_id=spatius_app_id,
        spatius_region=region,
        agent_uid=ids["agent_uid"],
    )


def _json_or_none(response: requests.Response) -> object:
    try:
        return response.json()
    except ValueError:
        return None


def _agent_url(agent_id: str, action: str) -> str:
    app_id = _require("AGORA_APP_ID")
    return (
        f"{_base_url()}/api/conversational-ai-agent/v2"
        f"/projects/{app_id}/agents/{agent_id}/{action}"
    )


def _call(agent_id: str, action: str, body: dict | None = None) -> None:
    response = requests.post(
        _agent_url(agent_id, action),
        headers={"Authorization": _auth_header()},
        json=body,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    if response.ok:
        return
    raise RuntimeError(_safe_upstream_message(_json_or_none(response), response.status_code))


def speak(agent_id: str, text: str) -> None:
    """Have the avatar read a line. Upstream caps it at 512 bytes."""
    _call(
        agent_id,
        "speak",
        {
            "text": text,
            # INTERRUPT: cut off whatever is playing. Feedback should be heard the
            # moment the student answers, not after the question finishes.
            "priority": "INTERRUPT",
            # This line itself cannot be interrupted — with True, the interruption
            # produced by the line it displaces washes it away too: the question
            # stops mid-sentence and the new line is never said either.
            "interruptable": False,
        },
    )


def interrupt(agent_id: str) -> None:
    _call(agent_id, "interrupt", {})


def enter_free_talk(
    agent_id: str,
    lang: str = "zh",
    persona: str = "freetalk",
    custom: str = "",
    memory_key: str = "",
) -> None:
    """Switch to the free-talk persona.

    Sends system_messages only and leaves llm.params alone — submitting params
    replaces the configuration from creation wholesale, losing the model name and
    max_tokens.

    **Switches the persona without speaking**: what to say and when is the
    client's call. If the server spoke too, the two messages would arrive at the
    same agent back to back and interrupt each other.
    """
    _call(
        agent_id,
        "update",
        {
            "properties": {
                "llm": {
                    "system_messages": [
                        {"role": "system", "content": _prompt(persona, lang, custom, memory_key)}
                    ]
                }
            }
        },
    )


def stop_agent(agent_id: str) -> None:
    """Stop the agent. **Must be called when the session ends** — an agent bills
    continuously from the moment it starts."""
    response = requests.post(
        _agent_url(agent_id, "leave"),
        headers={"Authorization": _auth_header()},
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    # Stopping often runs on the page-unload path where there is no retry, and an
    # agent that has already exited is not a failure.
    if response.ok or response.status_code == 404:
        return
    raise RuntimeError(_safe_upstream_message(_json_or_none(response), response.status_code))

def fetch_history(agent_id: str) -> list[dict[str, str]]:
    """What has been said in this conversation so far, oldest first.

    The conversation itself runs inside ConvoAI — the backend only signs the token — so
    the transcript has to be asked for rather than observed. Verified against a live
    agent: `contents` carries one entry per turn with `role` ("user" or "assistant") and
    `content`, and entries the client sent through `speak` are marked
    `metadata.source == "command"`.

    Returns an empty list on any failure. This is only ever used to feed the companion's
    memory, and a visit that cannot be remembered is better than one that cannot end.
    """
    try:
        response = requests.get(
            _agent_url(agent_id, "history"),
            headers={"Authorization": _auth_header()},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        if not response.ok:
            return []
        payload = _json_or_none(response)
    except Exception:  # noqa: BLE001 — memory is best-effort, see above
        return []

    if not isinstance(payload, dict):
        return []
    contents = payload.get("contents")
    if not isinstance(contents, list):
        return []

    turns: list[dict[str, str]] = []
    for item in contents:
        if not isinstance(item, dict):
            continue
        text = str(item.get("content") or "").strip()
        if not text:
            continue

        # Skip anything the client sent through `speak`. Those are fixed lines — the
        # companion's greeting, the classroom's questions — not things either side said,
        # and keeping them fills the memory with the same greeting repeated once per
        # visit. ConvoAI marks them for us (verified against a live agent).
        metadata = item.get("metadata")
        if isinstance(metadata, dict) and metadata.get("source") == "command":
            continue

        role = "assistant" if item.get("role") == "assistant" else "user"
        turns.append({"role": role, "text": text})
    return turns
