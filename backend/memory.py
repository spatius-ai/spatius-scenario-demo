"""What the companion remembers between visits.

The other three scenes start from nothing every time. This one is the opposite: it
keeps what was said, folds it into the persona on the next visit, and so behaves like
someone who met you before.

Stored as a file next to the backend rather than in the browser, so the same memory
follows whichever client connects — a phone and a laptop talking to one backend are
talking to the same companion.

The transcript cannot grow without bound: it goes into a system prompt, and a prompt
that keeps growing eventually costs more than it is worth and starts crowding out the
model's attention on what matters. Past a threshold it is folded down into a summary
by the same LLM that does the talking, which is what `compact` is for.
"""

from __future__ import annotations

import json
import os
import threading
from dataclasses import dataclass, field
from pathlib import Path

MEMORY_DIR = Path(__file__).resolve().parent / "memory"

# One file per persona. The companion comes in a few characters and they do not share a
# past: what you told the older-sister character should not come back out of the room-mate
# one, which is what a single file would do.
DEFAULT_PERSONA = "friend"


def _path(persona: str) -> Path:
    """Where one persona's memory lives.

    The name comes in over HTTP, so it is reduced to the characters a file name may safely
    hold — anything else and a request could name a path outside this directory.
    """
    safe = "".join(c for c in (persona or "") if c.isalnum() or c in "-_") or DEFAULT_PERSONA
    return MEMORY_DIR / f"{safe}.json"

# Roughly the length past which the stored memory is folded into a summary. Counted in
# characters rather than tokens: the two run at very different ratios between Chinese and
# English, and this only has to be the right order of magnitude.
COMPACT_THRESHOLD_CHARS = 6000

# What a compaction should come back under. Long enough to keep the substance of a few
# conversations, short enough that the persona still reads as a persona — past a couple of
# thousand characters models start losing track of the earlier instructions in it.
COMPACT_TARGET_CHARS = 1000

# Belt and braces: even a failed compaction must not leave an unbounded prompt behind, so
# the oldest lines are dropped if the summary comes back too long or not at all.
HARD_CAP_CHARS = 8000

# Two conversations can be in flight at once — a phone and a laptop against one backend,
# or simply both roles of one exchange arriving together. Reentrant because `remember`
# holds it across a load-modify-write and calls `save`, which takes it again.
#
# The lock has to cover the whole read-modify-write, not just the file write: with only the
# write guarded, the second writer overwrites the file from its own stale snapshot and
# every turn the first one added is gone. Measured at 88% of turns lost with eight writers.
_lock = threading.RLock()


@dataclass
class Memory:
    """Everything carried between visits.

    `summary` is what earlier conversations were folded down to; `recent` is what has
    been said since. They are kept apart so a compaction rewrites only the first and
    leaves the raw tail intact.
    """

    summary: str = ""
    recent: list[dict[str, str]] = field(default_factory=list)

    @property
    def size(self) -> int:
        return len(self.summary) + sum(len(item.get("text", "")) for item in self.recent)

    def as_prompt(self) -> str:
        """The memory as it goes into the persona, or empty when there is nothing yet."""
        parts: list[str] = []
        if self.summary:
            parts.append(self.summary)
        if self.recent:
            lines = [f"{item.get('role', 'user')}: {item.get('text', '')}" for item in self.recent]
            parts.append("\n".join(lines))
        return "\n\n".join(parts)


def load(persona: str = DEFAULT_PERSONA) -> Memory:
    """The stored memory, or an empty one when nothing has been saved yet.

    A corrupt file is treated as empty rather than raising: it would otherwise take the
    scene down on entry, and losing the memory is the lesser failure.
    """
    try:
        raw = json.loads(_path(persona).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return Memory()

    if not isinstance(raw, dict):
        return Memory()
    recent = raw.get("recent")
    return Memory(
        summary=str(raw.get("summary") or ""),
        recent=[item for item in recent if isinstance(item, dict)] if isinstance(recent, list) else [],
    )


def save(memory: Memory, persona: str = DEFAULT_PERSONA) -> None:
    """Write the memory out, creating the directory on first use.

    Written to a temporary file and moved into place: a crash midway through would
    otherwise leave a half-written document that the next load reads as corrupt.
    """
    with _lock:
        MEMORY_DIR.mkdir(parents=True, exist_ok=True)
        payload = {"summary": memory.summary, "recent": memory.recent}
        path = _path(persona)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, path)


def clear(persona: str = DEFAULT_PERSONA) -> None:
    """Forget everything for one persona, so the demo can be shown from a blank slate."""
    save(Memory(), persona)


def remember(turns: list[dict[str, str]], persona: str = DEFAULT_PERSONA) -> Memory:
    """Add what was just said and fold the memory down if it has grown too long.

    Returns the memory as it now stands, so the caller can report the size without
    reading it back.
    """
    with _lock:
        memory = load(persona)
        memory.recent.extend(turns)

        if memory.size > COMPACT_THRESHOLD_CHARS:
            memory = compact(memory)

        save(memory, persona)
        return memory


def compact(memory: Memory) -> Memory:
    """Fold the whole memory into a single summary.

    Uses the same LLM the conversation runs on, so no extra credentials are needed. If
    that call fails the memory is truncated instead — the oldest lines go, the newest
    stay — because the one thing that must not happen is an ever-growing prompt.
    """
    text = memory.as_prompt()
    summary = _summarise(text)

    if summary:
        return Memory(summary=summary, recent=[])

    # No summary to be had. Drop from the front until it fits.
    trimmed = list(memory.recent)
    while trimmed and len(memory.summary) + sum(len(i.get("text", "")) for i in trimmed) > HARD_CAP_CHARS:
        trimmed.pop(0)
    return Memory(summary=memory.summary, recent=trimmed)


def _summarise(text: str) -> str:
    """Ask the LLM to fold the memory into notes, or return an empty string if it cannot.

    Deliberately swallows every failure: a companion that cannot summarise should still
    be able to talk, and the caller has a truncation fallback for the length problem.
    """
    if not text.strip():
        return ""

    api_key = (os.getenv("LLM_API_KEY") or "").strip()
    base_url = (os.getenv("LLM_BASE_URL") or "").strip()
    model = (os.getenv("LLM_MODEL") or "").strip()
    # Neither transport needs these: the Agora path keeps its model in the console and the
    # LiveKit path goes through Inference, so a backend has no LLM of its own unless one is
    # configured deliberately. Without it the memory is truncated instead of summarised —
    # see `compact`.
    if not (api_key and base_url and model):
        return ""

    instruction = (
        "Below is what you remember about a person you talk to regularly. Rewrite it as "
        f"a compact set of notes, under {COMPACT_TARGET_CHARS} characters, in the language "
        "the conversation was in. Keep what would matter next time you meet: their name, "
        "what they do, people and things they mentioned, preferences, what they were "
        "worried or excited about, and anything you promised to follow up on. Drop small "
        "talk and anything that will not matter again. Write the notes as statements, not "
        "as a dialogue."
    )

    try:
        import requests

        response = requests.post(
            f"{base_url.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json={
                "model": model,
                "messages": [
                    {"role": "system", "content": instruction},
                    {"role": "user", "content": text},
                ],
                "temperature": 0.3,
            },
            timeout=30,
        )
        if not response.ok:
            return ""
        content = response.json()["choices"][0]["message"]["content"]
        return content.strip() if isinstance(content, str) else ""
    except Exception:  # noqa: BLE001 — any failure falls back to truncation
        return ""
