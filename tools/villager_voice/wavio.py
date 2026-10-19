"""Minimal mono/PCM WAV I/O using only the standard library.

Samples are plain Python ``list[float]`` in -1..1. That keeps the engine
dependency-free and lets the same code run inside the repo's Python tools and
in a bare interpreter on any machine.
"""

import array
import struct
import wave

__all__ = [
    "read_wav",
    "write_wav",
    "resample_linear",
    "peak",
    "rms",
    "normalize_peak",
    "fade_edges",
    "apply_gain",
    "clamp",
]


def read_wav(path):
    """Read a WAV file and return ``(sample_rate, samples)`` as mono floats.

    8/16/24/32-bit PCM is supported; anything else raises ``ValueError`` so the
    caller can report it instead of silently producing garbage.
    """
    with wave.open(str(path), "rb") as handle:
        channels = handle.getnchannels()
        rate = handle.getframerate()
        width = handle.getsampwidth()
        raw = handle.readframes(handle.getnframes())

    if width == 2:
        data = array.array("h")
        data.frombytes(raw[: len(raw) // 2 * 2])
        scale = 1.0 / 32768.0
        samples = [value * scale for value in data]
    elif width == 1:
        samples = [(byte - 128) / 128.0 for byte in array.array("B", raw)]
    elif width == 3:
        count = len(raw) // 3
        samples = [0.0] * count
        for i in range(count):
            b0, b1, b2 = raw[i * 3], raw[i * 3 + 1], raw[i * 3 + 2]
            value = b0 | (b1 << 8) | (b2 << 16)
            if value & 0x800000:
                value -= 1 << 24
            samples[i] = value / 8388608.0
    elif width == 4:
        count = len(raw) // 4
        values = struct.unpack("<%di" % count, raw[: count * 4])
        samples = [value / 2147483648.0 for value in values]
    else:
        raise ValueError("unsupported WAV sample width: %d bytes" % width)

    if channels > 1:
        mono = []
        for start in range(0, len(samples) - channels + 1, channels):
            mono.append(sum(samples[start:start + channels]) / channels)
        samples = mono

    return rate, samples


def write_wav(path, sample_rate, samples, bit_depth=16):
    """Write mono floats to a PCM WAV file (16-bit by default)."""
    if bit_depth != 16:
        raise ValueError("only 16-bit output is supported, got %r" % bit_depth)
    data = array.array("h")
    for value in samples:
        # Scale by 32768, not 32767: read_wav divides by 32768, and using a
        # different constant here injects a systematic v/32768 error into every
        # round trip (4.3e-05 at 0.9 full scale). The integer is then clamped so
        # a full-scale +1.0 cannot overflow int16. WavAudio.java has the same
        # asymmetry and should be corrected to match.
        quantum = int(round(max(-1.0, min(1.0, value)) * 32768.0))
        data.append(max(-32768, min(32767, quantum)))
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(int(sample_rate))
        handle.writeframes(data.tobytes())
    return path


def resample_linear(samples, destination_rate, source_rate):
    """Linear-interpolating resample (cheap, adequate for voice-band work)."""
    if destination_rate == source_rate or not samples:
        return list(samples)
    ratio = float(destination_rate) / float(source_rate)
    out_len = max(1, int(round(len(samples) * ratio)))
    output = [0.0] * out_len
    last = len(samples) - 1
    for i in range(out_len):
        position = i / ratio
        index = int(position)
        if index >= last:
            output[i] = samples[last]
            continue
        frac = position - index
        output[i] = samples[index] * (1.0 - frac) + samples[index + 1] * frac
    return output


def peak(samples):
    best = 0.0
    for value in samples:
        magnitude = -value if value < 0.0 else value
        if magnitude > best:
            best = magnitude
    return best


def rms(samples):
    if not samples:
        return 0.0
    total = 0.0
    for value in samples:
        total += value * value
    return (total / len(samples)) ** 0.5


def rms_db(samples):
    value = rms(samples)
    if value <= 1e-12:
        return -120.0
    import math

    return 20.0 * math.log10(value)


def normalize_peak(samples, ceiling=0.95):
    """Scale in place so the peak equals ``ceiling`` (never amplifies silence)."""
    current = peak(samples)
    if current <= 1e-9:
        return samples
    gain = ceiling / current
    for i, value in enumerate(samples):
        samples[i] = value * gain
    return samples


def apply_gain(samples, gain):
    if gain == 1.0:
        return samples
    for i, value in enumerate(samples):
        samples[i] = value * gain
    return samples


def clamp(samples, limit=1.0):
    for i, value in enumerate(samples):
        if value > limit:
            samples[i] = limit
        elif value < -limit:
            samples[i] = -limit
    return samples


def fade_edges(samples, fade_samples):
    """Raised-cosine fade in/out, matching ``AudioDsp.fadeEdges`` in Java."""
    if not samples:
        return samples
    fade = max(1, min(fade_samples, len(samples) // 2))
    import math

    for i in range(fade):
        gain = 0.5 - 0.5 * math.cos(math.pi * (i + 1.0) / (fade + 1.0))
        samples[i] *= gain
        samples[len(samples) - 1 - i] *= gain
    return samples


def mix_into(destination, source, offset, gain=1.0):
    """Add ``source`` into ``destination`` at ``offset`` (extends if needed)."""
    end = offset + len(source)
    if end > len(destination):
        destination.extend([0.0] * (end - len(destination)))
    for i, value in enumerate(source):
        destination[offset + i] += value * gain
    return destination
