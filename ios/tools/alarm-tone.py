#!/usr/bin/env python3
"""Writes the fallback room alarm tone, ios/Dozecam/Alerts/Sounds/room_alarm.caf.

Generated here rather than taken from anywhere, so it carries no licence but
the repository's own. iOS gives an app no way to play the phone's own alarm
sound through its audio engine, so the fallback path (#68) brings this one:
the four-beep pattern of a digital alarm clock, three times over, about 3 s.
It is the room's tone; monitoring failures have their own
(monitoring_failure.caf, converted from Android's monitoring_failure.wav with
`afconvert -f caff -d LEI16@48000`).

    python3 ios/tools/alarm-tone.py   # needs macOS afconvert
"""

import math
import os
import struct
import subprocess
import tempfile
import wave

RATE = 48_000
FREQ = 2_000.0  # where a phone speaker is loud, like an alarm clock's piezo
BEEP, GAP, GROUP_GAP, GROUPS = 0.09, 0.06, 0.45, 3
FADE = 0.004  # no clicks
PEAK = 0.9


def beep():
    n = int(BEEP * RATE)
    fade = int(FADE * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        # A little third harmonic: squarer, so it carries.
        s = math.sin(2 * math.pi * FREQ * t) + math.sin(2 * math.pi * 3 * FREQ * t) / 6
        envelope = min(1.0, i / fade, (n - 1 - i) / fade)
        out.append(s * envelope / (1 + 1 / 6))
    return out


def silence(seconds):
    return [0.0] * int(seconds * RATE)


samples = []
for _ in range(GROUPS):
    for index in range(4):
        samples += beep()
        if index < 3:
            samples += silence(GAP)
    samples += silence(GROUP_GAP)

here = os.path.dirname(os.path.abspath(__file__))
target = os.path.normpath(os.path.join(here, "..", "Dozecam", "Alerts", "Sounds", "room_alarm.caf"))
with tempfile.TemporaryDirectory() as tmp:
    raw = os.path.join(tmp, "room_alarm.wav")
    with wave.open(raw, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(s * PEAK * 32767)) for s in samples))
    subprocess.run(["afconvert", "-f", "caff", "-d", "LEI16", raw, target], check=True)
print("wrote", target)
