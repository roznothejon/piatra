#!/usr/bin/env python3
"""Encode a raw recording as the board will send it (format 0x02).

Reads raw 16 kHz, 16-bit, mono PCM and writes Opus packets in the protocol's
framing (docs/esp32-protocol.md section 6): [length u16 LE][packet] for each
20 ms frame, no file header. Settings are the ones chosen with opus_test.py: 16 kbps,
voip, 20 ms frames. ffmpeg's libopus encodes into an Ogg file; this script
takes the packets back out of the Ogg pages.

    python3 opus_frame.py recordings/12.pcm 12.opus

It made the test clip for the app's on-phone decoder test
(app/src/androidTest/assets/), and the firmware's output must have
the same layout. decode() goes the other way, for dev.py get.
"""

import argparse
import struct
import subprocess
from pathlib import Path


def encode_ogg(pcm_path, kbps):
    command = [
        "ffmpeg", "-v", "error", "-f", "s16le", "-ar", "16000", "-ac", "1",
        "-i", str(pcm_path), "-c:a", "libopus", "-b:a", f"{kbps}k",
        "-application", "voip", "-frame_duration", "20", "-f", "ogg", "-",
    ]
    return subprocess.run(command, check=True, capture_output=True).stdout


def ogg_packets(data):
    """The packets of an Ogg stream, in order (RFC 3533: pages of segments)."""
    packets, current, pos = [], b"", 0
    while pos < len(data):
        if data[pos:pos + 4] != b"OggS":
            raise ValueError(f"no Ogg page at byte {pos}")
        segments = data[pos + 26]
        lacing = data[pos + 27:pos + 27 + segments]
        pos += 27 + segments
        for size in lacing:
            current += data[pos:pos + size]
            pos += size
            if size < 255:  # a segment under 255 bytes ends the packet
                packets.append(current)
                current = b""
    return packets


def split_framed(data):
    """The packets of a framed file: [length u16 LE][packet] each."""
    packets, pos = [], 0
    while pos + 2 <= len(data):
        (size,) = struct.unpack_from("<H", data, pos)
        packets.append(data[pos + 2:pos + 2 + size])
        pos += 2 + size
    if pos != len(data):
        raise ValueError(f"framed Opus cut off at byte {pos} of {len(data)}")
    return packets


def ogg_crc(data):
    """Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, starting at 0."""
    crc = 0
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) if crc & 0x80000000 else crc << 1
            crc &= 0xFFFFFFFF
    return crc


def ogg_page(packet, granule, sequence, flags):
    """One Ogg page holding one packet (RFC 3533)."""
    lacing = bytes([255] * (len(packet) // 255) + [len(packet) % 255])
    header = struct.pack("<4sBBqIIIB", b"OggS", 0, flags, granule, 1, sequence, 0, len(lacing))
    page = bytearray(header + lacing + packet)
    struct.pack_into("<I", page, 22, ogg_crc(page))
    return bytes(page)


def decode(framed):
    """Framed Opus (format 0x02) back to raw 16 kHz 16-bit mono PCM, with ffmpeg."""
    packets = split_framed(framed)
    # OpusHead: version 1, 1 channel, pre-skip 0 (like the app), 16 kHz input, gain 0, mapping 0.
    head = b"OpusHead" + struct.pack("<BBHIhB", 1, 1, 0, 16000, 0, 0)
    tags = b"OpusTags" + struct.pack("<I", 0) + struct.pack("<I", 0)
    pages = [ogg_page(head, 0, 0, 0x02), ogg_page(tags, 0, 1, 0)]
    for i, packet in enumerate(packets):
        last = i == len(packets) - 1
        # Ogg Opus counts time in 48 kHz samples: 960 per 20 ms packet.
        pages.append(ogg_page(packet, (i + 1) * 960, i + 2, 0x04 if last else 0))
    command = ["ffmpeg", "-v", "error", "-f", "ogg", "-i", "-",
               "-f", "s16le", "-ar", "16000", "-ac", "1", "-"]
    return subprocess.run(command, input=b"".join(pages), check=True, capture_output=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pcm", type=Path, help="raw 16 kHz 16-bit mono .pcm file")
    parser.add_argument("out", type=Path, help="framed Opus file to write")
    parser.add_argument("--kbps", type=int, default=16)
    args = parser.parse_args()

    packets = ogg_packets(encode_ogg(args.pcm, args.kbps))
    # The first two packets are Ogg's headers (OpusHead, OpusTags), not audio.
    if not (packets[0].startswith(b"OpusHead") and packets[1].startswith(b"OpusTags")):
        raise ValueError("unexpected Ogg Opus headers")
    audio = packets[2:]
    with open(args.out, "wb") as f:
        for packet in audio:
            f.write(struct.pack("<H", len(packet)) + packet)
    pcm_bytes = args.pcm.stat().st_size
    framed = args.out.stat().st_size
    print(f"{len(audio)} packets ({len(audio) * 20 / 1000:.1f} s), {framed} bytes, "
          f"{pcm_bytes / framed:.1f}x smaller than the raw {pcm_bytes} bytes")


if __name__ == "__main__":
    main()
