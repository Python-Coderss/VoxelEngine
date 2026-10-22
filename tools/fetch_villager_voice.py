#!/usr/bin/env python3
"""Install or verify the dynamic villager voice model bundle.

Villagers speak unmatched lines through a fixed-voice (non-zero-shot) Coqui
VITS model trained on VCTK. Like the Kokoro bundle it is ~137 MB of model
data, so it is not committed: this script checks that the files the engine
needs are present and structurally the model `CoquiVitsTts` expects, and it
can download the graph from a URL you trust when it is missing.

    python tools/fetch_villager_voice.py            # verify + write manifest
    python tools/fetch_villager_voice.py --check    # verify only
    python tools/fetch_villager_voice.py --url <graph.onnx> --sha256 <hex>

Expected bundle (default dev/voice-models, override with --dir, or point the
game at it with -Dvoxel.voice.coqui.dir=<dir>):

    coqui-vctk-vits.onnx            Coqui VITS export, 4 inputs:
                                    input (int64 [1, phonemes]),
                                    input_lengths (int64 [1]),
                                    scales (float [3]), sid (int64 [1])
    coqui-vctk-vocab.json           phoneme -> id list; must contain <BLNK>
    coqui-vctk-speaker_ids.json     {"p225": 1, ...} closed speaker inventory
    coqui-vctk-config.json          training config; audio.sample_rate must be
                                    22050 (CoquiVitsTts.OUTPUT_RATE)

The vocab and speaker files come from the same export as the graph: the
vocabulary decides the phoneme ids and the speaker map decides which trained
voices exist, so they cannot be mixed between exports. A missing or mismatched
bundle is not fatal at runtime -- the game keeps its recorded-clip villager
voice -- which is exactly why this check exists.

The English G2P dictionaries are shared with the Kokoro bundle
(tools/fetch_kokoro.py); this script warns when they are missing.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_DIR = os.path.join(ROOT, "dev", "voice-models")
KOKORO_DIR = os.path.join(DEFAULT_DIR, "kokoro")

MODEL = "coqui-vctk-vits.onnx"
VOCAB = "coqui-vctk-vocab.json"
SPEAKERS = "coqui-vctk-speaker_ids.json"
CONFIG = "coqui-vctk-config.json"
MANIFEST = "villager-voice.manifest.json"

REQUIRED_INPUTS = {"input", "input_lengths", "scales", "sid"}
SAMPLE_RATE = 22050


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def read_io_names(path):
    """Input/output names and element types, or None when `onnx` is absent."""
    try:
        import onnx  # noqa: F401  (optional: only used for the signature)
    except Exception:
        return None
    import onnx

    graph = onnx.load(path, load_external_data=False).graph
    names = {}
    for value in graph.input:
        names.setdefault("inputs", []).append(value.name)
    for value in graph.output:
        names.setdefault("outputs", []).append(value.name)
    return names


def download(url, target, expected_sha):
    if expected_sha is None:
        raise SystemExit(
            "refusing to download without --sha256: an unverified model graph "
            "would change what every villager says")
    print("downloading %s" % url)
    tmp = target + ".part"
    with urllib.request.urlopen(url, timeout=120) as response, open(tmp, "wb") as out:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
    actual = sha256(tmp)
    if actual.lower() != expected_sha.lower():
        os.remove(tmp)
        raise SystemExit("SHA-256 mismatch: expected %s, got %s"
                         % (expected_sha, actual))
    os.replace(tmp, target)
    print("downloaded %s (%d bytes, SHA-256 verified)" % (MODEL, os.path.getsize(target)))


def check(directory, write_manifest, quiet=False):
    problems = []
    notes = []
    paths = {
        MODEL: os.path.join(directory, MODEL),
        VOCAB: os.path.join(directory, VOCAB),
        SPEAKERS: os.path.join(directory, SPEAKERS),
        CONFIG: os.path.join(directory, CONFIG),
    }

    for name, path in paths.items():
        if not os.path.isfile(path):
            problems.append("missing %s (%s)" % (name, path))
    if problems:
        return problems, notes

    vocab = load_json(paths[VOCAB])
    if not isinstance(vocab, list) or "<BLNK>" not in vocab:
        problems.append("%s is not a Coqui phoneme vocab with <BLNK>" % VOCAB)
    elif len(vocab) < 100:
        problems.append("%s has only %d symbols; the export looks truncated"
                        % (VOCAB, len(vocab)))

    speakers = load_json(paths[SPEAKERS])
    ids = [value for key, value in speakers.items() if key.strip().startswith("p")]
    if not isinstance(speakers, dict) or not ids:
        problems.append("%s has no pNNN speakers" % SPEAKERS)
    elif len(ids) < 50:
        problems.append("%s lists only %d trained voices" % (SPEAKERS, len(ids)))
    elif sorted(ids) != list(range(min(ids), max(ids) + 1)):
        problems.append("%s speaker ids are not contiguous" % SPEAKERS)

    config = load_json(paths[CONFIG])
    rate = (config.get("audio") or {}).get("sample_rate")
    if rate != SAMPLE_RATE:
        problems.append("%s sample_rate is %r, CoquiVitsTts expects %d"
                        % (CONFIG, rate, SAMPLE_RATE))

    names = read_io_names(paths[MODEL])
    if names is None:
        notes.append("python `onnx` not installed; input signature not checked")
    else:
        missing = REQUIRED_INPUTS - set(names.get("inputs", []))
        if missing:
            problems.append("%s is not a Coqui VITS export (missing inputs: %s)"
                            % (MODEL, ", ".join(sorted(missing))))

    if not missing_text_dictionaries():
        notes.append("Kokoro G2P dictionaries missing: run tools/fetch_kokoro.py "
                     "(the villager voice phonemizes with them)")

    if not problems and write_manifest:
        manifest = {
            "model": MODEL,
            "inputs": sorted(REQUIRED_INPUTS),
            "sample_rate": SAMPLE_RATE,
            "speakers": len(ids),
            "vocab": len(vocab) if isinstance(vocab, list) else 0,
            "sha256": {name: sha256(path) for name, path in paths.items()},
        }
        with open(os.path.join(directory, MANIFEST), "w", encoding="utf-8") as out:
            json.dump(manifest, out, indent=2, sort_keys=True)
            out.write("\n")
        if not quiet:
            print("wrote %s" % os.path.join(directory, MANIFEST))
    return problems, notes


def missing_text_dictionaries():
    gold = os.path.join(KOKORO_DIR, "gb_gold.json")
    return os.path.isfile(gold)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=DEFAULT_DIR,
                        help="bundle directory (default: %(default)s)")
    parser.add_argument("--check", action="store_true",
                        help="verify only; do not write the manifest")
    parser.add_argument("--url", help="download the model graph from this URL")
    parser.add_argument("--sha256", help="expected SHA-256 of the downloaded graph")
    args = parser.parse_args()

    directory = os.path.abspath(args.dir)
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, MODEL)

    if args.url:
        download(args.url, target, args.sha256)

    problems, notes = check(directory, write_manifest=not args.check)
    for note in notes:
        print("note: %s" % note)
    if problems:
        print("villager voice bundle FAILED:")
        for problem in problems:
            print("  - %s" % problem)
        print("\nInstall %s, %s and %s from one Coqui VITS-VCTK export into %s"
              % (MODEL, VOCAB, SPEAKERS, directory))
        return 1

    speakers = load_json(os.path.join(directory, SPEAKERS))
    # Only pNNN rows are trained voices the engine casts; the export also
    # carries a non-speaker "ED" row.
    voices = [key for key in speakers if key.strip().startswith("p")]
    print("villager voice bundle OK in %s (%d voices, %d phonemes, %d Hz)"
          % (directory, len(voices), len(load_json(os.path.join(directory, VOCAB))),
             SAMPLE_RATE))
    return 0


if __name__ == "__main__":
    sys.exit(main())
