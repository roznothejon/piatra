#!/usr/bin/env python3
"""Copy recordings off the board over the USB cable (the firmware's console commands).

  pull.py ls                  list recordings on the board
  pull.py get ID [--play]     copy one to recordings/ID.pcm (+ .wav, and .opus if it's Opus), check its CRC
  pull.py last [--play]       the newest one
  pull.py rm ID               delete one from the board
  pull.py df                  free space, and where (sd or flash)

Needs pyserial: tools/.venv (see firmware/README.md). Close `idf.py monitor` first: only one
program can have the port open.
"""
import argparse
import array
import base64
import math
import shutil
import subprocess
import sys
import time
import wave
import zlib
from pathlib import Path

import serial

from opus_frame import decode as decode_opus

SAMPLE_RATE = 16000
FORMAT_PCM, FORMAT_OPUS = 1, 2  # protocol section 6
OUT_DIR = Path(__file__).parent / "recordings"


def open_port(port):
    s = serial.Serial()
    s.port = port
    s.baudrate = 115200  # ignored by USB serial, but pyserial wants one
    s.timeout = 5
    # The ESP32-S3 resets when RTS is on while DTR is off. Linux turns both on when the port
    # opens, so only turn RTS off; turning DTR off first would reset the board.
    s.rts = False
    s.open()
    time.sleep(0.2)
    s.reset_input_buffer()
    return s


def command(s, text):
    s.write((text + "\n").encode())
    s.flush()


def lines(s):
    """Lines from the board. Log lines (which start with I/W/E and a time) are passed over."""
    while True:
        raw = s.readline()
        if not raw:
            sys.exit("no answer from the board (is the firmware running? is idf.py monitor closed?)")
        yield raw.decode(errors="replace").rstrip("\r\n")


def parse_ls(lines):
    """REC lines up to LS_END (the console's `ls`), as (id, size, recordedAt, crc, format)."""
    recordings = []
    for line in lines:
        parts = line.split()
        if parts[:1] == ["REC"]:
            rid, size, recorded_at, crc, fmt = parts[1:6]
            recordings.append((int(rid), int(size), int(recorded_at), int(crc, 16), int(fmt)))
        elif parts[:1] == ["LS_END"]:
            break
    return recordings


def parse_get(lines):
    """The recording in BEGIN, D <base64>... END lines (the console's `get`), CRC checked."""
    data = bytearray()
    size = crc = None
    for line in lines:
        if line.startswith("ERR"):
            sys.exit(f"board says: {line}")
        if line.startswith("BEGIN "):
            _, _, size, crc = line.split()
            size, crc = int(size), int(crc, 16)
        elif line.startswith("D ") and size is not None:
            data += base64.b64decode(line[2:])
        elif line == "END":
            break
    if len(data) != size:
        sys.exit(f"got {len(data)} bytes, expected {size}")
    # The same CRC-32 as the board, the protocol and java.util.zip.CRC32.
    if zlib.crc32(data) != crc:
        sys.exit(f"CRC mismatch: board {crc:08x}, received {zlib.crc32(data):08x}")
    return bytes(data)


def list_recordings(s):
    command(s, "ls")
    return parse_ls(lines(s))


def get(s, rid):
    command(s, f"get {rid}")
    return parse_get(lines(s))


def show_ls(recordings):
    for rid, size, recorded_at, crc, fmt in recordings:
        when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(recorded_at)) if recorded_at else "time unknown"
        # Raw PCM's length follows from its size; Opus is ~2 KB a second (16 kbps), roughly.
        length = f"{size / (2 * SAMPLE_RATE):6.1f} s" if fmt == FORMAT_PCM else f"~{size / 2000:5.0f} s"
        print(f"{rid:5}  {length}  {size // 1024:5} KB  {when}  crc {crc:08x}  format {fmt}")


def format_of(recordings, rid):
    return next((fmt for r, *_, fmt in recordings if r == rid), FORMAT_PCM)


def show_df(line):
    _, total, free, where = line.split()
    print(f"{where}: {int(free) // 1024} of {int(total) // 1024} MB free")


def levels(pcm):
    samples = array.array("h", pcm)  # 16-bit signed, little-endian on this laptop
    peak = max((abs(x) for x in samples), default=0)
    rms = math.sqrt(sum(x * x for x in samples) / len(samples)) if samples else 0
    db = lambda v: 20 * math.log10(v / 32767) if v else -99
    return f"peak {db(peak):.1f} dBFS, RMS {db(rms):.1f} dBFS"


def save(rid, data, play, fmt=FORMAT_PCM):
    OUT_DIR.mkdir(exist_ok=True)
    pcm = data
    if fmt == FORMAT_OPUS:
        (OUT_DIR / f"{rid}.opus").write_bytes(data)
        pcm = decode_opus(data)
        print(f"Opus: {len(data)} bytes, {len(pcm) / len(data):.1f}x smaller than raw")
    (OUT_DIR / f"{rid}.pcm").write_bytes(pcm)
    wav_path = OUT_DIR / f"{rid}.wav"
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)
    print(f"saved {wav_path} ({len(pcm) / (2 * SAMPLE_RATE):.1f} s, CRC ok, {levels(pcm)})")
    if play:
        player = shutil.which("pw-play") or shutil.which("aplay")
        if player:
            subprocess.run([player, str(wav_path)])
        else:
            print("no pw-play or aplay found to play it")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", choices=["ls", "get", "last", "rm", "df"])
    p.add_argument("id", nargs="?", type=int)
    p.add_argument("--play", action="store_true")
    p.add_argument("--port", default="/dev/ttyACM0")
    args = p.parse_args()

    s = open_port(args.port)
    if args.action == "ls":
        show_ls(list_recordings(s))
    elif args.action in ("get", "last"):
        rid = args.id
        recordings = list_recordings(s)
        if args.action == "last":
            if not recordings:
                sys.exit("no recordings on the board")
            rid = recordings[-1][0]
        if rid is None:
            sys.exit("get needs an id")
        save(rid, get(s, rid), args.play, format_of(recordings, rid))
    elif args.action == "rm":
        command(s, f"rm {args.id}")
        print(next(l for l in lines(s) if l.startswith(("OK", "ERR"))))
    elif args.action == "df":
        command(s, "df")
        show_df(next(l for l in lines(s) if l.startswith("DF")))


if __name__ == "__main__":
    main()
