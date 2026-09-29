#!/usr/bin/env python3
"""Writes raw 8-bit gray frames showing this Mac's wall clock (HH:MM:SS.mmm)
as seven-segment digits to stdout, paced in real time, for glass-to-glass
latency measurements. No dependencies: the Homebrew ffmpeg has no drawtext.

Usage: clock.py WIDTH HEIGHT FPS | ffmpeg -f rawvideo -pixel_format gray ...
"""
import sys
import time
from datetime import datetime

W, H, FPS = (int(a) for a in sys.argv[1:4])

# Segments a..g as (x, y, w, h) in a 6x10 cell grid.
SEGMENTS = {
    "a": (1, 0, 4, 1), "b": (5, 1, 1, 4), "c": (5, 5, 1, 4), "d": (1, 9, 4, 1),
    "e": (0, 5, 1, 4), "f": (0, 1, 1, 4), "g": (1, 4.5, 4, 1),
}
DIGITS = {
    "0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc",
    "5": "afgcd", "6": "afgedc", "7": "abc", "8": "abcdefg", "9": "abcdfg",
}

text_len = len("00:00:00.000")
unit = max(2, min(W // (text_len * 8), H // 14))
text_w = text_len * 8 * unit
x0 = (W - text_w) // 2
y0 = (H - 10 * unit) // 2
background = bytes([32]) * (W * H)


def rect(frame, x, y, w, h):
    x, y, w, h = int(x), int(y), int(w), int(h)
    row = bytes([255]) * w
    for yy in range(y, y + h):
        start = yy * W + x
        frame[start:start + w] = row


def render(now):
    frame = bytearray(background)
    text = now.strftime("%H:%M:%S.") + f"{now.microsecond // 1000:03d}"
    for i, ch in enumerate(text):
        cx = x0 + i * 8 * unit
        if ch in DIGITS:
            for seg in DIGITS[ch]:
                sx, sy, sw, sh = SEGMENTS[seg]
                rect(frame, cx + sx * unit, y0 + sy * unit, sw * unit, sh * unit)
        elif ch == ":":
            rect(frame, cx + 2 * unit, y0 + 2 * unit, unit, unit)
            rect(frame, cx + 2 * unit, y0 + 7 * unit, unit, unit)
        elif ch == ".":
            rect(frame, cx + 2 * unit, y0 + 9 * unit, unit, unit)
    return frame


out = sys.stdout.buffer
period = 1.0 / FPS
next_at = time.monotonic()
while True:
    out.write(render(datetime.now()))
    out.flush()
    next_at += period
    delay = next_at - time.monotonic()
    if delay > 0:
        time.sleep(delay)
    else:
        next_at = time.monotonic()
