#!/usr/bin/env python3
"""Regenerate ``SCHEMA.md`` from the code.

The schema document is generated rather than hand-written so it cannot drift
from the control surface it describes: every range, enum and default in it comes
from ``voiceprint.schema_document()``, ``prosody.score_schema()`` and
``transform.spec_schema()`` at the moment the file is written.

    cd tools && python3 -m villager_voice.schema_doc > villager_voice/SCHEMA.md
    python3 -m villager_voice.schema_doc --write      # write it in place
"""

import argparse
import os
import sys

from . import prosody, transform
from .voiceprint import ENUMS, RANGES, VoicePrint, _flatten, schema_document

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "SCHEMA.md")


def _block(payload, language="json"):
    import json

    return "```%s\n%s\n```" % (language, json.dumps(payload, indent=2))


def build_document():
    """Assemble the whole SCHEMA.md body as a string."""
    knobs = len(_flatten(VoicePrint.default().data))
    flat = _flatten(VoicePrint.default().data)

    # Group the bounded knobs by section so the range table stays readable.
    sections = {}
    for path in sorted(RANGES):
        section = path.split(".")[0]
        sections.setdefault(section, []).append(path)
    for path in sorted(ENUMS):
        section = path.split(".")[0]
        sections.setdefault(section, []).append(path)

    range_rows = []
    for section in sorted(sections):
        for path in sections[section]:
            if path in ENUMS:
                allowed = ", ".join('`%s`' % value for value in ENUMS[path])
                range_rows.append("| `%s` | enum | %s |" % (path, allowed))
            else:
                low, high = RANGES[path]
                default = flat.get(path, "")
                range_rows.append("| `%s` | %s … %s | `%s` |" % (
                    path, low, high, default))

    parts = [
        "# Villager Voice Engine — JSON schemas",
        "",
        "Generated from the code by `python3 -m villager_voice.schema_doc`, so it",
        "cannot drift from the control surface it describes. Do not edit by hand:",
        "edit `voiceprint.py`, `prosody.py` or `transform.py` and regenerate.",
        "",
        "```bash",
        "cd tools",
        "python3 -m villager_voice.schema_doc --write        # update SCHEMA.md",
        "python3 -m villager_voice.schema_doc > /tmp/out.md  # or print to stdout",
        "```",
        "",
        "Three JSON files drive the engine, and they compose:",
        "",
        "| File | Question it answers | Produced by | Consumed by |",
        "|---|---|---|---|",
        "| **voiceprint** | what does the voice sound like? | `init`, "
        "`analyze --voiceprint-out`, by hand | `render`, `convert` |",
        "| **score** | what is said, when, and how? | `prosody.build_score` "
        "from text, or by hand | `synth.render_score` |",
        "| **transform spec** | how is an existing recording re-voiced? | "
        "by hand | `transform.apply_spec` |",
        "",
        "Every field in every schema is optional. An empty voiceprint is the seed",
        "voice; an empty transform spec returns its input unchanged.",
        "",
        "---",
        "",
        "## 1. Voiceprint",
        "",
        "%d knobs. Every scalar one is bounded by a declared range or enum "
        "(%d ranges, %d enums), and `selftest` fails if a knob is added without "
        "one." % (knobs, len(RANGES), len(ENUMS)),
        "",
        "`character` is a *macro* layer: it resolves into `source`, `tract`,",
        "`prosody` and `articulation` so a caller can say \"female, happy, gruff\"",
        "without knowing what open quotient that implies. An explicitly set",
        "low-level value always wins over a macro — see",
        "`VoicePrint.explicit_paths()`.",
        "",
        _block(schema_document()),
        "",
        "### Declared ranges",
        "",
        "| Path | Range | Default |",
        "|---|---|---|",
    ]
    parts.extend(range_rows)
    parts.extend([
        "",
        "---",
        "",
        "## 2. Score",
        "",
        "The performance contract, and the voice changer's input.",
        "`synth.render_score` consumes a hand-written score exactly as it consumes",
        "one generated from text; the synthesiser cannot tell the difference. See",
        "`examples/score_handwritten_hmmm.json`.",
        "",
        _block(prosody.score_schema()),
        "",
        "---",
        "",
        "## 3. Transform spec",
        "",
        "Re-voicing an existing WAV. Pitch, duration and timbre are separate axes",
        "by design so they can be moved independently: PSOLA re-spaces the",
        "overlap-add marks to change pitch without touching the vocal-tract",
        "envelope, and the formant shift uses resample-then-restore so timbre",
        "moves without the pitch following. See `examples/transform_*.json`.",
        "",
        _block(transform.spec_schema()),
        "",
    ])
    return "\n".join(parts) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(prog="villager_voice.schema_doc")
    parser.add_argument("--write", action="store_true",
                        help="write villager_voice/SCHEMA.md in place")
    parser.add_argument("--out", help="write to this path instead")
    arguments = parser.parse_args(argv)

    document = build_document()
    target = arguments.out or (TARGET if arguments.write else None)
    if target:
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(document)
        print("wrote %s (%d bytes)" % (target, len(document)), file=sys.stderr)
    else:
        sys.stdout.write(document)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
