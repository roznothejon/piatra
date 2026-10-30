#!/usr/bin/env python3
"""Range test: how strong the board's adverts are at the laptop, over time.

Connects once (that opens dev mode, so the board stays awake and advertises for 5 min after
the laptop lets go), disconnects, then logs the signal of its adverts (a reading every 2 s or so). Walk the
board away from the laptop and stop at each spot for ~20 s; a line every 2 s shows what the
laptop hears. Saved to recordings/range-<time>.csv (seconds, dBm), with a 10 s summary at the end.

  range.py [SECONDS]   default 280 (dev mode ends 5 min after the laptop disconnects)

The laptop's antenna is weaker than the phone's, so compare runs with each other (bare board vs.
in the case), not with the phone's numbers.
"""
import argparse
import asyncio
import statistics
import time

from bleak import BleakScanner
from bleak.args.bluez import BlueZScannerArgs

from dev import BoardError, connect_within, warn_if_discovering
from pull import OUT_DIR

STEP = 2      # seconds per live line
SUMMARY = 10  # seconds per summary line


def describe(values):
    if not values:
        return "-- nothing heard --"
    med = statistics.median(values)
    bar = "#" * max(0, int((med + 100) / 2))  # -100 dBm: empty, -40 dBm: 30 marks
    return f"{len(values):3d} readings {med:6.1f} dBm  ({min(values)} … {max(values)})  {bar}"


async def run(seconds):
    await warn_if_discovering()
    print("connecting to open dev mode (tap the board if it's asleep)...")
    client, _ = await connect_within(120)
    address = client.address
    await client.disconnect()
    print(f"board {address}: advertising for 5 min. Walk it around; stop ~20 s at each spot.\n")

    readings = []  # (seconds since start, dBm)
    start = time.monotonic()

    def heard(device, adv):
        if device.address == address:
            readings.append((time.monotonic() - start, adv.rssi))

    # BlueZ reports a device only when something about it changes (asking for every advert,
    # "DuplicateData", made no difference). It forgets the signal when a scan stops, so a fresh
    # scan every STEP seconds reports the board again if it's heard at all.
    shown = 0
    while time.monotonic() - start < seconds:
        scanner = BleakScanner(heard, bluez=BlueZScannerArgs(filters={"DuplicateData": True}))
        await scanner.start()
        try:
            await asyncio.sleep(STEP)
        finally:
            await scanner.stop()
        now = time.monotonic() - start
        new = [r for t, r in readings[shown:]]
        shown = len(readings)
        print(f"{now:5.0f} s  {describe(new)}", flush=True)

    path = OUT_DIR / time.strftime("range-%Y%m%d-%H%M%S.csv")
    path.write_text("seconds,dbm\n" + "".join(f"{t:.2f},{r}\n" for t, r in readings))
    print(f"\nsaved {path}\n\nevery {SUMMARY} s:")
    for begin in range(0, int(seconds), SUMMARY):
        print(f"{begin:4d}–{begin + SUMMARY:<4d} s  {describe([r for t, r in readings if begin <= t < begin + SUMMARY])}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("seconds", nargs="?", type=int, default=280)
    try:
        asyncio.run(run(p.parse_args().seconds))
    except BoardError as e:
        raise SystemExit(f"board: {e}")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
