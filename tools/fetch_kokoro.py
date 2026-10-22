#!/usr/bin/env python3
"""Download the Java Kokoro TTS bundle.

The game speaks through Kokoro-82M, an open neural TTS model, entirely in
Java via ONNX Runtime. The model is ~340 MB, so it is downloaded instead of
committed:

    python tools/fetch_kokoro.py                 # fp32 model + all 54 voices
    python tools/fetch_kokoro.py --int8          # 92 MB quantized model
    python tools/fetch_kokoro.py --voices af_heart,bm_george
    python tools/fetch_kokoro.py --check         # verify an existing bundle

The resulting directory (default dev/voice-models/kokoro) contains:

    kokoro-v1.0.onnx     the graph (or kokoro-v1.0.int8.onnx)
    kokoro-config.json   vocab + model config from hexgrad/Kokoro-82M
    gb_gold.json         misaki British-English gold lexicon
    gb_silver.json       misaki British-English silver lexicon
    <voice>.bin          one 510x256 float32 style table per voice

Sources
-------
Graph + voices: https://github.com/thewh1teagle/kokoro-onnx (Apache-2.0
release assets for hexgrad/Kokoro-82M, model licence: Apache-2.0).
Config:        https://huggingface.co/hexgrad/Kokoro-82M
Dictionaries:  https://github.com/hexgrad/misaki (MIT)

No Python is needed at runtime; this script is preparation only.
"""

import argparse
import ast
import glob
import hashlib
import json
import os
import struct
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIR = os.path.join(ROOT, "dev", "voice-models", "kokoro")

MODEL_URL = ("https://github.com/thewh1teagle/kokoro-onnx/releases/download/"
             "model-files-v1.0/{name}")
VOICES_URL = MODEL_URL.format(name="voices-v1.0.bin")
CONFIG_URL = "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/config.json"
DICTIONARY_URL = ("https://raw.githubusercontent.com/hexgrad/misaki/main/"
                  "misaki/data/{name}")

MODEL_FP32 = "kokoro-v1.0.onnx"
MODEL_INT8 = "kokoro-v1.0.int8.onnx"
VOICES_ARCHIVE = "voices-v1.0.bin"

STYLE_ROWS = 510
STYLE_DIM = 256
STYLE_BYTES = STYLE_ROWS * STYLE_DIM * 4

# Verified sizes for the release assets (bytes). A mismatch aborts the
# download instead of leaving a half-written model in place.
EXPECTED_SIZES = {
    MODEL_FP32: 325532387,
    MODEL_INT8: 92361271,
    VOICES_ARCHIVE: 28214398,
}

MANIFEST = "manifest.json"


def log(message):
    print("[kokoro] " + message, flush=True)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, path, expected_size=None):
    if os.path.isfile(path):
        size = os.path.getsize(path)
        if expected_size is None or size == expected_size:
            log("cached %s (%d bytes)" % (os.path.basename(path), size))
            return
        log("re-downloading %s (has %d, want %s)"
            % (os.path.basename(path), size, expected_size))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    part = path + ".part"
    log("downloading %s" % url)
    with urllib.request.urlopen(url) as response, open(part, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total and sys.stdout.isatty():
                sys.stdout.write("\r[kokoro]   %5.1f%%  %d / %d MB"
                                 % (100.0 * done / total, done // 1048576,
                                    total // 1048576))
                sys.stdout.flush()
        if sys.stdout.isatty():
            sys.stdout.write("\n")
    size = os.path.getsize(part)
    if expected_size is not None and size != expected_size:
        os.remove(part)
        raise SystemExit("bad size for %s: got %d, want %d"
                         % (url, size, expected_size))
    os.replace(part, path)


def read_npy_header(blob):
    """Return (dtype, shape, data_offset) for a .npy payload."""
    if blob[:6] != b"\x93NUMPY":
        raise ValueError("not a .npy file")
    major = blob[6]
    if major == 1:
        header_len = struct.unpack("<H", blob[8:10])[0]
        start = 10
    else:
        header_len = struct.unpack("<I", blob[8:12])[0]
        start = 12
    header = ast.literal_eval(blob[start:start + header_len].decode("latin1"))
    return header["descr"], tuple(header["shape"]), start + header_len


def extract_voices(archive_path, out_dir, wanted=None):
    os.makedirs(out_dir, exist_ok=True)
    extracted = []
    with zipfile.ZipFile(archive_path) as archive:
        for name in sorted(archive.namelist()):
            if not name.endswith(".npy"):
                continue
            voice = os.path.basename(name)[:-4]
            if wanted and voice not in wanted:
                continue
            blob = archive.read(name)
            descr, shape, offset = read_npy_header(blob)
            if descr != "<f4":
                raise SystemExit("voice %s has dtype %s, expected <f4"
                                 % (voice, descr))
            if int(shape[0]) != STYLE_ROWS or int(shape[-1]) != STYLE_DIM:
                raise SystemExit("voice %s has shape %s, expected (%d, 1, %d)"
                                 % (voice, shape, STYLE_ROWS, STYLE_DIM))
            payload = blob[offset:offset + STYLE_BYTES]
            if len(payload) != STYLE_BYTES:
                raise SystemExit("voice %s is truncated" % voice)
            target = os.path.join(out_dir, voice + ".bin")
            with open(target, "wb") as out:
                out.write(payload)
            extracted.append(voice)
    return extracted


def write_manifest(model_dir, model_name, voices):
    files = [model_name, "kokoro-config.json", "gb_gold.json", "gb_silver.json"]
    files += [voice + ".bin" for voice in sorted(voices)]
    entries = {}
    for name in files:
        path = os.path.join(model_dir, name)
        if not os.path.isfile(path):
            raise SystemExit("missing bundle file: " + name)
        entries[name] = {"bytes": os.path.getsize(path), "sha256": sha256(path)}
    with open(os.path.join(model_dir, MANIFEST), "w", encoding="utf-8") as out:
        json.dump({"model": model_name, "files": entries}, out, indent=2)
        out.write("\n")
    log("wrote %s (%d files)" % (MANIFEST, len(entries)))


def check(model_dir):
    manifest_path = os.path.join(model_dir, MANIFEST)
    if not os.path.isfile(manifest_path):
        raise SystemExit("no manifest in %s; run without --check first"
                         % model_dir)
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    failures = []
    for name, expected in sorted(manifest["files"].items()):
        path = os.path.join(model_dir, name)
        if not os.path.isfile(path):
            failures.append(name + ": missing")
            continue
        size = os.path.getsize(path)
        if size != expected["bytes"]:
            failures.append("%s: %d bytes, want %d"
                            % (name, size, expected["bytes"]))
            continue
        digest = sha256(path)
        if digest != expected["sha256"]:
            failures.append("%s: sha256 mismatch" % name)
    if failures:
        for failure in failures:
            print("FAIL " + failure)
        raise SystemExit(1)
    log("bundle OK (%d files, model=%s)"
        % (len(manifest["files"]), manifest["model"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=DEFAULT_DIR,
                        help="bundle directory (default: %(default)s)")
    parser.add_argument("--int8", action="store_true",
                        help="download the 92 MB quantized graph instead of fp32")
    parser.add_argument("--voices", default="",
                        help="comma-separated voice names (default: all)")
    parser.add_argument("--force", action="store_true",
                        help="re-extract voices even when the files exist")
    parser.add_argument("--check", action="store_true",
                        help="verify an existing bundle and exit")
    args = parser.parse_args()

    model_dir = os.path.abspath(args.dir)
    if args.check:
        check(model_dir)
        return

    model_name = MODEL_INT8 if args.int8 else MODEL_FP32
    wanted = [v.strip() for v in args.voices.split(",") if v.strip()]
    if wanted and "af_heart" not in wanted:
        wanted.append("af_heart")  # runtime fallback style

    os.makedirs(model_dir, exist_ok=True)
    download(MODEL_URL.format(name=model_name),
             os.path.join(model_dir, model_name), EXPECTED_SIZES[model_name])
    archive = os.path.join(model_dir, VOICES_ARCHIVE)
    download(VOICES_URL, archive, EXPECTED_SIZES[VOICES_ARCHIVE])
    download(CONFIG_URL, os.path.join(model_dir, "kokoro-config.json"))
    for name in ("gb_gold.json", "gb_silver.json"):
        download(DICTIONARY_URL.format(name=name),
                 os.path.join(model_dir, name))

    if args.force:
        # Drop old style tables so a narrow --voices selection leaves no stale
        # voices behind (the archive itself is kept as a download cache).
        for stale in glob.glob(os.path.join(model_dir, "*.bin")):
            if os.path.basename(stale) != VOICES_ARCHIVE:
                os.remove(stale)
    voices = extract_voices(archive, model_dir, wanted or None)
    log("extracted %d voice(s): %s" % (len(voices), ", ".join(sorted(voices))))
    write_manifest(model_dir, model_name, voices)
    log("bundle ready in " + model_dir)
    log("run the game normally; -Dvoxel.voice.kokoro.dir=<dir> overrides the path")


if __name__ == "__main__":
    main()
