"""Agent worker for the LiveKit path.

Only one thing differs from the official quickstart: the classroom needs the
teacher to **volunteer** lines — reading the question, giving feedback — rather
than only answering. Those are triggered by the client, arrive here over LiveKit
RPC, and are handed to `session.say()` / `session.interrupt()`.
"""

import logging
import os
from pathlib import Path

from dotenv import load_dotenv
from livekit import rtc
from livekit.agents import (
    Agent,
    AgentSession,
    AutoSubscribe,
    JobContext,
    WorkerOptions,
    cli,
    inference,
    metrics,
)
from livekit.plugins.spatius import AvatarSession

from defaults import DEFAULT_AVATAR_ID

ENV_PATH = Path(__file__).parent / ".env"
load_dotenv(ENV_PATH)

# Personas. Spoken style, no Markdown — every character here is read aloud.
#
# One set per language, kept in sync with the Agora path (PROMPTS in agora.py);
# change one and you have to change the other. When the client switches to
# English the questions and the read-aloud text follow, but if the persona stays
# Chinese the teacher answers in Chinese the moment the student opens the mic.
# The language rides along with the client's RPC.
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


def _custom_persona() -> str:
    """A character written on the config screen, or empty if there is none.

    Read from a file the server writes: the server and this worker are separate processes,
    so there is no shared memory to pass it through. Read on every use rather than cached —
    switching characters mid-session has to take effect.
    """
    try:
        path = Path(__file__).resolve().parent / "memory" / "custom-persona.txt"
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _prompt(kind: str, lang: str, memory_key: str = "") -> str:
    """Persona for a language, falling back to Chinese for anything unknown —
    the demo's default question bank is Chinese.

    An unknown persona falls back to freetalk rather than raising: the name arrives from
    the client, and a typo should leave the avatar talking rather than take the request
    down with a KeyError."""
    table = PROMPTS.get(lang, PROMPTS["zh"])
    custom = _custom_persona() if kind == "companion" else ""

    if custom:
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
        persona = f"{custom}\n\n{rules}"
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


class TutorAgent(Agent):
    def __init__(self, lang: str = "zh") -> None:
        super().__init__(instructions=_prompt("tutor", lang))


async def entrypoint(ctx: JobContext) -> None:
    # Re-read on every job: the config page writes changes back to .env, but the
    # worker read its copy at process start, so without this you would have to
    # restart the process just to change the voice. override=True is what makes
    # it replace values already in the process.
    load_dotenv(ENV_PATH, override=True)

    await ctx.connect(auto_subscribe=AutoSubscribe.AUDIO_ONLY)

    # Through LiveKit Inference: models are routed by LiveKit Cloud, so the user
    # does not have to sign up with OpenAI, Deepgram and the rest — credentials
    # come down to LiveKit and Spatius.
    #
    # Three stages rather than a speech-to-speech model: the latter (Gemini Live,
    # for one) needs yet another provider's key, and that account cannot be
    # obtained in some regions — the bar for a demo should be low enough that
    # signing up is all it takes.
    # The UI language, which the backend puts in the room metadata (see _livekit_start
    # in server.py). Read before the session is built: recognition and synthesis both
    # need it, and a session constructed with the wrong one cannot be corrected later.
    lang = ctx.room.metadata or "zh"
    speech_lang = "en" if lang == "en" else "zh"

    session = AgentSession(
        # Recognition follows the UI language. Left on Chinese it transcribes English
        # speech into nonsense Chinese, and the LLM then answers the nonsense — which
        # presents as the avatar replying to something nobody said.
        stt=inference.STT(
            model=os.getenv("STT_MODEL", "deepgram/nova-3"), language=speech_lang
        ),
        llm=inference.LLM(model=os.getenv("LLM_MODEL", "openai/gpt-4.1-mini")),
        # The accent comes from the voice, not from `language`: ElevenLabs'
        # default voice reads Chinese with a Cantonese accent and passing
        # language="zh" does not fix it. The model is picked on the config page;
        # to pin a specific voice set TTS_VOICE in .env (values come from each
        # provider's voice library — LiveKit has no endpoint to list them).
        tts=inference.TTS(
            model=os.getenv("TTS_MODEL", "fishaudio/s2.1-pro"),
            language=os.getenv("TTS_LANGUAGE") or speech_lang,
            **({"voice": v} if (v := os.getenv("TTS_VOICE")) else {}),
        ),
        # How long to wait before deciding the user has finished talking.
        #
        # The default ceiling is 2.5s, and the turn detector reaches it on most
        # conversational lines — measured at exactly 2.5s on turn after turn, which is
        # the single largest piece of the delay before a reply: the LLM's first token
        # and the TTS first byte are around 1.2s each, so the wait is roughly half of
        # what someone sits through.
        #
        # 0.5s is the trade, matching what the Agora path manages in practice. Pause
        # longer than that mid-sentence and the avatar starts answering before the
        # sentence is done — but a chat stream is short back and forth, where being cut
        # off occasionally costs less than a gap after every single line.
        turn_handling={
            "endpointing": {
                "min_delay": 0.3,
                "max_delay": float(os.getenv("ENDPOINTING_MAX_DELAY", "0.5")),
            }
        },
    )

    # The avatar joins this path: audio still travels over LiveKit, and the
    # motion data Spatius generates rides along on the video track.
    avatar = AvatarSession(
        api_key=os.getenv("SPATIUS_API_KEY"),
        app_id=os.getenv("SPATIUS_APP_ID"),
        avatar_id=os.getenv("SPATIUS_AVATAR_ID") or DEFAULT_AVATAR_ID,
    )
    await avatar.start(session, room=ctx.room)

    # Log the per-stage timings of each turn. Between the student finishing a
    # sentence and hearing a reply sit three cloud services in series — ASR, LLM,
    # TTS — and which one is slow is not something you can tell by feel.
    #   EOU  how long it took to decide the student had finished speaking
    #   LLM  time to first token (ttft)
    #   TTS  time to first audio byte (ttfb)
    # Which persona is in effect. A one-element list rather than a plain name: the RPC
    # below rebinds it, and a closure over a bare string would keep reading the value it
    # was created with.
    active_persona = ["tutor"]
    # Lines the client sent through `say` — the companion's greeting, the classroom's
    # questions — are fixed text rather than something either side came up with, and
    # recording them fills the memory with the same greeting once per visit. The Agora path
    # filters them by the marker ConvoAI attaches; here we know which they are because we
    # sent them, so they are simply listed.
    scripted_lines: set[str] = set()
    # Which stored memory this conversation belongs to. Sent by the client alongside the
    # persona: the characters are defined there, so only it knows which one was picked.
    active_memory_key = ["friend"]

    # Everything either side says, kept for the companion scene to remember. The event
    # fires for both roles, so the transcript comes out of it complete — there is no need
    # to reconstruct the user's half from ASR separately.
    #
    # Written on every turn rather than at the end of the session: a tab closed or a
    # process killed would otherwise lose the whole conversation, which is precisely the
    # visit the companion was supposed to remember.
    @session.on("conversation_item_added")
    def _on_conversation_item(event) -> None:
        if active_persona[0] != "companion":
            return
        item = getattr(event, "item", None)
        role = getattr(item, "role", None)
        text = getattr(item, "text_content", None) or ""
        if role not in ("user", "assistant") or not text.strip():
            return
        if text.strip() in scripted_lines:
            return
        try:
            import memory

            memory.remember([{"role": role, "text": text.strip()}], active_memory_key[0])
        except Exception as exc:  # noqa: BLE001 — a visit that cannot be remembered still runs
            logging.warning("could not record turn: %s", exc)

    @session.on("metrics_collected")
    def _on_metrics(ev) -> None:
        metrics.log_metrics(ev.metrics)

    # Kept rather than passed inline: switching personas later goes through the agent,
    # not the session, so there has to be something to call it on.
    agent = TutorAgent(lang)
    await session.start(agent=agent, room=ctx.room)

    # ---- Actions the client initiates ----
    #
    # Registered after session.start(): an RPC can arrive the moment it is
    # registered, and before start `session.interrupt()` raises "AgentSession
    # isn't running", which fails that say entirely — nothing is interrupted and
    # nothing new is said. The client does wait for the ready attribute, but
    # attribute propagation has a delay and correctness should not rest on it.
    #
    # RPC rather than a data message: an RPC has a reply, so the client knows
    # whether the line actually landed. The UI decides when to read a question
    # (switching questions, answering, entering free talk) and the server must
    # not chime in on its own — if both sides spoke they would interrupt each
    # other, and the client's throttling has no say over the server's line.

    @ctx.room.local_participant.register_rpc_method("say")
    async def _say(data: rtc.RpcInvocationData) -> str:
        # allow_interruptions=False: this line cannot be interrupted by **the
        # student speaking**. With True, the interruption produced by the line it
        # displaces washes it away too — the question stops mid-sentence and the
        # new line is never said either.
        #
        # But the classroom's own next line has to be able to displace it —
        # feedback on an answer should immediately override the question being
        # read. A plain interrupt() hitting a line sent with
        # allow_interruptions=False raises "does not allow interruptions" and
        # takes the whole say down with it: nothing interrupted, nothing new
        # said. force=True exists for exactly this.
        session.interrupt(force=True)
        scripted_lines.add(data.payload.strip())
        handle = session.say(data.payload, allow_interruptions=False)
        # Return only once the line has finished playing, so the caller knows when the
        # avatar has stopped talking. There is no way to work that out from the client
        # side on this transport: the avatar's audio is consumed by the SDK rather than
        # subscribed as a room track, so LiveKit reports activeSpeakers empty and every
        # participant's audioLevel as a flat zero however long it talks.
        await handle.wait_for_playout()
        return "ok"

    @ctx.room.local_participant.register_rpc_method("interrupt")
    async def _interrupt(data: rtc.RpcInvocationData) -> str:
        # Same as say: the line being read was sent with
        # allow_interruptions=False, so a plain interrupt() raises "does not
        # allow interruptions". The classroom is deliberately stopping it, so
        # force.
        session.interrupt(force=True)
        return "ok"

    @ctx.room.local_participant.register_rpc_method("free_talk")
    async def _free_talk(data: rtc.RpcInvocationData) -> str:
        # Switch the persona only, do not speak: what to say and when is the
        # client's call. The payload is the client's current UI language, and the
        # persona follows it.
        # On the agent, not the session: AgentSession has no update_instructions, and
        # calling it there raises an AttributeError that the RPC turns into an
        # application error — the persona silently stays on the scripted one, so the
        # avatar keeps answering in the wrong voice with nothing to show why.
        # Payload is "<lang>:<persona>" — one string is all an RPC carries.
        # Payload is "<lang>:<persona>:<memory key>" — an RPC carries one string, and the
        # key cannot be derived here: which character was picked is known only to the client.
        lang_code, _, rest = (data.payload or "zh:freetalk:friend").partition(":")
        persona, _, memory_key = rest.partition(":")
        active_persona[0] = persona or "freetalk"
        active_memory_key[0] = memory_key or "friend"
        await agent.update_instructions(
            _prompt(active_persona[0], lang_code or "zh", active_memory_key[0])
        )
        return "ok"


    # Announce readiness: this is what the client waits for before sending the
    # first line (reading the question).
    #
    # "The agent joined" is not enough — at join time AgentSession is still
    # initialising and a say arriving then is dropped. Waiting for it to publish
    # an audio track does not work either: the agent is driven by TTS, so there
    # is no track until it speaks, and the first thing to say *is* the question —
    # that would add another ten-odd seconds.
    await ctx.room.local_participant.set_attributes({"ready": "1"})


if __name__ == "__main__":
    cli.run_app(
        WorkerOptions(
            entrypoint_fnc=entrypoint,
            agent_name="tutoring-classroom",
            # Keep one process warm: the default is to spawn on demand, and the
            # 1.4s cold start lands directly in the wait between entering the
            # classroom and hearing the first question.
            num_idle_processes=1,
        )
    )
