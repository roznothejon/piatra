#!/usr/bin/env python3
"""Develop over Bluetooth, without the USB cable (docs/esp32-protocol.md section 12).

To pair a laptop, put the board in dev mode: 8 taps on the button, then press and hold 2 s
(long buzz). The laptop then has 2 min to pair with the 6-digit code in firmware/dev.secret.
After that the laptop stays paired: any command opens dev mode again, as long as the board
is awake (it sleeps by itself): tap it once (30 s awake), or `dev.py wait` for its check-in.

  dev.py pair        forget any old bond with the board, then pair with the code
  dev.py wait        wait for the board to advertise (the 30-min check-in), then connect
  dev.py info        which firmware is running, and whether it's on trial
  dev.py flash       build (idf.py build), upload, restart, reconnect, confirm, then prove it
                     comes back after a deep sleep
  dev.py rollback    go back to the firmware in the other slot, then confirm and prove it
  dev.py bootloader  replace the bootloader with this build's (flash this build first). The one
                     risky update: if the power goes during the copy (under a second), the board
                     can't start until it's flashed over USB
  dev.py done        end dev mode now (the laptop stays paired)

  dev.py monitor     the board's log: the last 4 KB kept on the board, then live (Ctrl+C)
  dev.py log         only the last 4 KB (kept through restarts and crashes, not a power cut)
  dev.py ls / df / bat / adv / boot   the console commands, as over USB (tools/pull.py)
  dev.py pm          time in each power mode since the start (SLEEP = light sleep), and what kept it awake
  dev.py get ID [--play] / last       copy a recording to recordings/ID.pcm (+ .wav, + .opus)
  dev.py rm ID / buzz [MS] / crash
  dev.py sleep [S]   deep sleep until the button, or S seconds (at most 30 min); shows the wake-up

Dev mode ends by itself 5 min after the laptop disconnects, and survives the restart after
a flash. A new firmware is "on trial" until dev.py confirms it; if that doesn't happen
within 5 min (or it crashes first), the board goes back to the previous firmware. Then
dev.py proves it: the board sleeps 5 s, and the laptop must be back within 3 min of the
wake-up, or the board goes back to the previous firmware too (a firmware that sleeps but
can't be reached afterwards could never be replaced: there's no USB).
"""
import argparse
import asyncio
import hashlib
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from dbus_fast import BusType
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method

from pull import format_of, parse_get, parse_ls, save, show_df, show_ls
from sync import SERVICE, BoardError, bluez_call

DEV = "cdf70004-b272-4e8a-b578-c59339280171"
INFO, CONFIRM, OTA_BEGIN, OTA_DATA, OTA_END, ROLLBACK, DONE, CONSOLE, LOG = range(1, 10)
# Text the board sends unasked: [0x40][log text], [0x41][console output].
LOG_TEXT, CONSOLE_TEXT = 0x40, 0x41
LOG_HISTORY, LOG_LIVE = 1, 2
STATUS = {1: "UNKNOWN_COMMAND", 2: "BAD_LENGTH", 3: "NOT_FOUND", 4: "BAD_OFFSET (bytes lost)",
          5: "BUSY", 6: "STORAGE_ERROR", 7: "BAD_IMAGE (failed its check, or not Piatra firmware)"}
TARGET_BOOTLOADER = 1  # OTA_BEGIN's optional last byte
FIRMWARE = Path(__file__).resolve().parent.parent
BUILD = FIRMWARE / "build"
# Every this many chunks, one write waits for the board's answer, so the laptop never gets
# far ahead of the board's flash writes.
WRITES_PER_CHECK = 16


def dev_code():
    try:
        return int((FIRMWARE / "dev.secret").read_text().strip())
    except (OSError, ValueError):
        sys.exit("firmware/dev.secret is missing or not 6 digits (it must match the flashed firmware)")


async def known_board():
    """The board as BlueZ already knows it (seen or paired before), or None. A scan only reports
    a device when BlueZ adds it or one of its properties changes, so a known board with a steady
    signal is never "found" again; connecting to the known device works (BlueZ waits for its
    next advert by itself)."""
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    try:
        introspection = await bus.introspect("org.bluez", "/")
        manager = bus.get_proxy_object("org.bluez", "/", introspection).get_interface(
            "org.freedesktop.DBus.ObjectManager")
        for path, interfaces in (await manager.call_get_managed_objects()).items():
            props = {k: v.value for k, v in interfaces.get("org.bluez.Device1", {}).items()}
            if SERVICE in props.get("UUIDs", []):
                return BLEDevice(props["Address"], props.get("Name"), {"path": path, "props": props})
    finally:
        bus.disconnect()
    return None


async def warn_if_discovering():
    """Another app scanning (e.g. GNOME Settings' Bluetooth panel, open) can stop BlueZ from
    connecting: pairing fails with "Page Timeout" and connects with le-connection-abort-by-local."""
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    try:
        introspection = await bus.introspect("org.bluez", "/")
        manager = bus.get_proxy_object("org.bluez", "/", introspection).get_interface(
            "org.freedesktop.DBus.ObjectManager")
        for interfaces in (await manager.call_get_managed_objects()).values():
            adapter = interfaces.get("org.bluez.Adapter1")
            if adapter and adapter["Discovering"].value:
                print("warning: another app is scanning for Bluetooth devices (GNOME Settings' "
                      "Bluetooth panel?); close it if connecting fails")
    finally:
        bus.disconnect()


async def find_board(timeout=15):
    device = await known_board()
    if device is not None:
        return device
    print("looking for the board...")
    device = await BleakScanner.find_device_by_filter(lambda d, adv: SERVICE in adv.service_uuids, timeout=timeout)
    if device is None:
        raise BoardError("not found: is it awake (tap it once, or `dev.py wait`)? connected to the phone?")
    return device


class Dev:
    """One connection, talking on the dev characteristic."""

    def __init__(self, client):
        self.client = client
        self.replies = asyncio.Queue()
        self.log = asyncio.Queue()  # log text; b"" = the end of the history
        self.console = bytearray()  # the running console command's output

    def on_notify(self, _, data):
        data = bytes(data)
        if data[0] == LOG_TEXT:
            self.log.put_nowait(data[1:])
        elif data[0] == CONSOLE_TEXT:
            self.console += data[1:]
        else:
            self.replies.put_nowait(data)

    async def start(self):
        await self.client.start_notify(DEV, self.on_notify)

    async def command(self, opcode, payload=b"", timeout=5):
        await self.client.write_gatt_char(DEV, bytes([opcode]) + payload, response=True)
        packet = await asyncio.wait_for(self.replies.get(), timeout)
        if packet[1] != 0:
            raise BoardError(f"command {opcode}: {STATUS.get(packet[1], packet[1])}")
        return packet[2:]

    async def run(self, line, progress=False):
        """A console command; returns its output as lines. The reply comes after all of it, so
        wait as long as text keeps coming (a `get` takes a while)."""
        self.console.clear()
        await self.client.write_gatt_char(DEV, bytes([CONSOLE]) + line.encode(), response=True)
        seen = 0
        while True:
            try:
                packet = await asyncio.wait_for(self.replies.get(), 5)
                break
            except asyncio.TimeoutError:
                if len(self.console) == seen:
                    raise BoardError(f"no answer to `{line}`")
                seen = len(self.console)
                if progress:
                    print(f"\r  {seen // 1024} KB...", end="", flush=True)
        if progress:
            print("\r", end="")
        if packet[1] != 0:
            raise BoardError(f"`{line}`: {STATUS.get(packet[1], packet[1])}")
        return self.console.decode(errors="replace").splitlines()

    async def info(self):
        p = await self.command(INFO)
        on_trial, slot, max_write = struct.unpack_from("<BBH", p)
        return {"on_trial": bool(on_trial), "slot": slot, "max_write": max_write,
                "sha": p[4:36], "version": p[36:].decode()}


def show(info):
    trial = "ON TRIAL (not confirmed yet)" if info["on_trial"] else "confirmed"
    print(f"firmware {info['version']} in slot ota_{info['slot']}, {trial}, build {info['sha'][:4].hex()}")


async def connect(device, tries=3):
    for attempt in range(tries):
        client = BleakClient(device)
        try:
            await client.connect()
            break
        except Exception as e:
            if attempt == tries - 1:
                raise BoardError(f"couldn't connect ({e}): is it awake (tap it once, or `dev.py wait`)? "
                                 "connected to the phone?")
            await asyncio.sleep(2)
    dev = Dev(client)
    await dev.start()
    return client, dev


async def connect_within(seconds):
    """Keeps trying to connect for that long (the board is restarting, or asleep for a bit)."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            return await connect(await find_board(timeout=30), tries=1)
        except BoardError:
            if time.monotonic() > deadline:
                raise
            await asyncio.sleep(2)


async def confirm_after_restart(expected_sha, started):
    """Reconnects after the board restarts, checks what it runs, confirms it and proves it."""
    print("waiting for the board to restart...")
    await asyncio.sleep(3)
    client, dev = await connect_within(90)
    confirmed = False
    try:
        info = await dev.info()
        if expected_sha and info["sha"] != expected_sha:
            show(info)
            raise BoardError("it's running a different build: the new firmware failed and the board went back")
        if info["on_trial"]:
            await dev.command(CONFIRM)
            info["on_trial"] = False
            confirmed = True
        show(info)
    finally:
        await client.disconnect()
    if confirmed:
        await prove_after_sleep(info["sha"])
    print(f"done in {time.monotonic() - started:.1f} s")


async def prove_after_sleep(sha, first=True):
    """The board's second trial: a confirmed firmware only stays once the laptop
    has come back after a real wake from deep sleep. Dev mode stays on through it."""
    print("proving it comes back after a deep sleep (5 s)...")
    client, dev = await connect_within(30)
    try:
        replies = await dev.run("sleep 5")
    except Exception:
        replies = ["OK sleeping"]  # asleep before its reply got out
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass
    if not any(t.startswith("OK") for t in replies):
        raise BoardError(f"it wouldn't sleep: {' '.join(replies)}")
    await asyncio.sleep(8)
    # The board gives up after 3 min and goes back to the previous firmware: wait for that too.
    client, dev = await connect_within(5 * 60)
    try:
        info = await dev.info()
        if info["sha"] == sha:
            print("proven: it came back after a deep sleep")
            return
        show(info)
        if info["on_trial"]:
            await dev.command(CONFIRM)
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass
    if first:
        await prove_after_sleep(info["sha"], first=False)  # the previous one, so it stays put
    raise BoardError("the new firmware didn't come back after a deep sleep: the board went back to the "
                     "previous one (confirmed)")


class PasskeyAgent(ServiceInterface):
    """A BlueZ pairing agent that types the dev code when the board asks for it."""

    def __init__(self, code):
        super().__init__("org.bluez.Agent1")
        self.code = code

    @method()
    def Release(self):
        pass

    @method()
    def RequestPasskey(self, device: "o") -> "u":
        return self.code

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"):
        pass

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"):
        pass

    @method()
    def RequestAuthorization(self, device: "o"):
        pass

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"):
        pass

    @method()
    def Cancel(self):
        pass


async def pair():
    code = dev_code()
    device = await find_board()
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    # Forget the laptop's old bond (e.g. a Just Works one from sync.py, or last session's),
    # so BlueZ pairs afresh; then BlueZ has to find the board again.
    path = device.details["path"]
    try:
        await bluez_call(bus, path.rsplit("/", 1)[0], "org.bluez.Adapter1", "RemoveDevice", "o", (path,))
    except BoardError:
        pass
    device = await find_board()

    agent_path = "/dev/piatra/passkey_agent"
    bus.export(agent_path, PasskeyAgent(code))
    await bluez_call(bus, "/org/bluez", "org.bluez.AgentManager1", "RegisterAgent", "os",
                     (agent_path, "KeyboardOnly"))
    try:
        await bluez_call(bus, device.details["path"], "org.bluez.Device1", "Pair")
    except BoardError as e:
        raise BoardError(f"pairing failed ({e}). Is dev mode's 2-min window open? "
                         "Does firmware/dev.secret match the flashed firmware?")
    finally:
        await bluez_call(bus, "/org/bluez", "org.bluez.AgentManager1", "UnregisterAgent", "o", (agent_path,))
        bus.disconnect()
    print("paired with the code: dev mode is on")
    client, dev = await connect(device)
    try:
        show(await dev.info())
    finally:
        await client.disconnect()


def build_firmware(build):
    """Builds (unless --no-build); returns the SHA-256 of the .elf. The board reports the same
    for what it runs: that's how we know which build it restarted into."""
    if build:
        if shutil.which("idf.py") is None:
            sys.exit("idf.py not found: run `. ~/esp/esp-idf-v6.0.3/export.sh` first (or use --no-build)")
        subprocess.run(["idf.py", "build"], cwd=FIRMWARE, check=True, stdout=subprocess.DEVNULL)
    return hashlib.sha256((BUILD / "piatra-board.elf").read_bytes()).digest()


async def upload(client, dev, info, image, begin):
    """OTA_BEGIN with that payload, then the image in OTA_DATA packets."""
    await dev.command(OTA_BEGIN, begin, timeout=30)
    upload_started = time.monotonic()
    # [OTA_DATA][offset u32][bytes], as long as the board's MTU allows (at most 512).
    chunk = min(info["max_write"], 512) - 5
    offsets = range(0, len(image), chunk)
    for i, offset in enumerate(offsets):
        packet = bytes([OTA_DATA]) + struct.pack("<I", offset) + image[offset:offset + chunk]
        await client.write_gatt_char(DEV, packet, response=(i % WRITES_PER_CHECK == WRITES_PER_CHECK - 1))
        if i % 64 == 0:
            print(f"\r  {offset * 100 // len(image)}%", end="", flush=True)
    took = time.monotonic() - upload_started
    print(f"\r  100% in {took:.1f} s ({len(image) / 1024 / took:.0f} KB/s)")


async def flash(build):
    started = time.monotonic()
    sha = build_firmware(build)
    image = (BUILD / "piatra-board.bin").read_bytes()

    client, dev = await connect(await find_board())
    try:
        info = await dev.info()
        show(info)
        print(f"uploading {len(image) // 1024} KB, build {sha[:4].hex()}...")
        await upload(client, dev, info, image, struct.pack("<I", len(image)))
        await dev.command(OTA_END, timeout=30)  # the board checks the image, then restarts
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass  # the board may already have gone
    await confirm_after_restart(sha, started)


def bootloader_built(image):
    """The compile time in a bootloader image's description (esp_bootloader_desc_t, after the
    24-byte image header and the first segment's 8-byte header)."""
    desc = image[32:32 + 80]
    if desc[0] != 0x50:
        sys.exit("bootloader.bin has no description: not an ESP-IDF v6 bootloader?")
    return desc[8:40].rstrip(b"\0").decode(), desc[40:64].rstrip(b"\0").decode()


async def update_bootloader(build):
    """The bootloader is what starts the firmware, and the chip can't fall back to
    another one, so this is the one update without a safety net. The board receives it into
    spare flash, checks it, copies it, reads it back, then restarts through it."""
    sha = build_firmware(build)
    image = (BUILD / "bootloader" / "bootloader.bin").read_bytes()
    # 0xE9: an ESP image; chip id 9 (bytes 12-13): ESP32-S3; it must fit before the partition table.
    if image[0] != 0xE9 or image[12:14] != b"\x09\x00" or len(image) > 0x8000:
        sys.exit("build/bootloader/bootloader.bin doesn't look like an ESP32-S3 bootloader")
    idf, built = bootloader_built(image)

    client, dev = await connect(await find_board())
    try:
        info = await dev.info()
        show(info)
        # The firmware must be this very build: it's built with the same settings (it leaves the
        # RTC memory the new bootloader writes alone), and it's the one that will start next.
        if info["sha"] != sha:
            raise BoardError("it runs a different build: `dev.py flash` this build first")
        if info["on_trial"]:
            raise BoardError("its firmware is still on trial: let `dev.py flash` finish first")
        now = next(l for l in await dev.run("boot") if l.startswith(("BOOT", "ERR")))
        print(f"on the board: {now}")
        print(f"new:          BOOT {idf}, built {built} ({len(image) // 1024} KB)")
        if now.endswith(f"built {built}"):
            print("it already has this bootloader")
            return
        print("If the power goes or the board restarts during the copy (under a second), it can't")
        print("start again until it's flashed over USB. Charged battery, board left alone?")
        if input("type yes to go on: ").strip() != "yes":
            print("nothing changed")
            return
        print("uploading...")
        await upload(client, dev, info, image, struct.pack("<IB", len(image), TARGET_BOOTLOADER))
        try:
            # The board checks it, copies it over the old one and reads it back, then restarts.
            await dev.command(OTA_END, timeout=30)
        except BoardError as e:
            if "STORAGE_ERROR" in str(e):
                raise BoardError("THE COPY FAILED: the board is still running but can't start again. "
                                 "It stays awake: run `dev.py bootloader` again now (`dev.py log` shows why)")
            raise  # refused (BAD_IMAGE etc.): the old bootloader is untouched
        print("written and checked on the board; it restarts through the new bootloader...")
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass
    await asyncio.sleep(3)
    client, dev = await connect_within(90)
    try:
        now = next(l for l in await dev.run("boot") if l.startswith(("BOOT", "ERR")))
        print(f"back: {now}")
        if not now.endswith(f"built {built}"):
            raise BoardError("it came back, but not with the new bootloader?")
        await sleep_and_wake(client, dev, 5)  # a wake-up now skips the firmware check: shows the time
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


async def simple(action):
    client, dev = await connect(await find_board())
    try:
        if action == "info":
            show(await dev.info())
        elif action == "done":
            await dev.command(DONE)
            print("dev mode off (this laptop stays paired: any command opens it again while the board is awake)")
            return
        elif action == "rollback":
            await dev.command(ROLLBACK)
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass
    if action == "rollback":
        await confirm_after_restart(None, time.monotonic())


async def log(live):
    client, dev = await connect(await find_board())
    try:
        await dev.command(LOG, bytes([LOG_HISTORY | (LOG_LIVE if live else 0)]))
        while True:
            text = await dev.log.get()
            if not text and not live:
                return  # the whole history is out
            print(text.decode(errors="replace"), end="", flush=True)
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


async def console(action, rid, play):
    client, dev = await connect(await find_board())
    try:
        if action == "ls":
            show_ls(parse_ls(await dev.run("ls")))
        elif action == "df":
            show_df(next(l for l in await dev.run("df") if l.startswith("DF")))
        elif action in ("get", "last"):
            recordings = parse_ls(await dev.run("ls"))
            if action == "last":
                if not recordings:
                    raise BoardError("no recordings on the board")
                rid = recordings[-1][0]
            if rid is None:
                sys.exit("get needs an id")
            started = time.monotonic()
            data = parse_get(await dev.run(f"get {rid}", progress=True))
            print(f"copied in {time.monotonic() - started:.1f} s")
            save(rid, data, play, format_of(recordings, rid))
        elif action == "crash":
            try:
                for text in await dev.run("crash"):
                    print(text)
            except Exception:
                print("OK crashing")  # the board restarted before its reply got out
            print("the board restarts (dev mode stays on); `dev.py log` shows the lines before it and the start-up")
        elif action == "sleep":
            await sleep_and_wake(client, dev, rid)
            return
        else:  # rm, bat, adv, pm, buzz: show what the board says
            line = action if rid is None else f"{action} {rid}"
            for text in await dev.run(line):
                print(text)
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


async def sleep_and_wake(client, dev, seconds):
    """Deep sleep. Dev mode stays on through it, so after a timer wake the
    laptop reconnects and shows the wake-up lines of the log."""
    try:
        replies = await dev.run("sleep" if seconds is None else f"sleep {seconds}")
    except Exception:
        replies = ["OK sleeping"]  # asleep before its reply got out
    for text in replies:
        print(text)
    if not any(t.startswith("OK") for t in replies):
        return
    try:
        await client.disconnect()
    except Exception:
        pass
    if seconds is None:
        print("asleep until the button (or 30 min): press it, then `dev.py log` shows the wake-up")
        return
    print(f"asleep for {seconds} s; waiting to reconnect...")
    await asyncio.sleep(seconds + 3)
    client, dev = await connect(await find_board())
    try:
        await dev.command(LOG, bytes([LOG_HISTORY]))
        lines = []
        while text := await dev.log.get():
            lines += text.decode(errors="replace").splitlines()
        # Only this wake-up: from the last "deep sleep" line on.
        start = max((i for i, l in enumerate(lines) if "deep sleep until" in l), default=0)
        print("\n".join(lines[start:]))
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


async def wait():
    """Waits for the board to advertise (a tap, recordings waiting, or the 30-min check-in, which
    lasts ~5 s), then connects, which opens dev mode, and shows the firmware."""
    print("waiting for the board to advertise (up to 35 min; a tap on it is quicker)...")
    device = await BleakScanner.find_device_by_filter(lambda d, adv: SERVICE in adv.service_uuids,
                                                      timeout=35 * 60)
    if device is None:
        raise BoardError("not seen in 35 min: is the battery flat?")
    client, dev = await connect(device)
    try:
        show(await dev.info())
        print("connected: dev mode is on for 5 min after this (any dev.py command now)")
    finally:
        await client.disconnect()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", choices=["pair", "wait", "info", "flash", "rollback", "done", "monitor", "log",
                                      "ls", "get", "last", "rm", "df", "bat", "buzz", "adv", "pm", "boot", "crash", "sleep",
                                      "bootloader"])
    p.add_argument("id", nargs="?", type=int, help="get/rm: a recording's id; buzz: milliseconds; sleep: seconds")
    p.add_argument("--no-build", action="store_true", help="flash/bootloader: upload the last build as it is")
    p.add_argument("--play", action="store_true", help="get/last: play it afterwards")
    args = p.parse_args()
    try:
        asyncio.run(warn_if_discovering())
        if args.action == "pair":
            asyncio.run(pair())
        elif args.action == "wait":
            asyncio.run(wait())
        elif args.action == "flash":
            asyncio.run(flash(not args.no_build))
        elif args.action == "bootloader":
            asyncio.run(update_bootloader(not args.no_build))
        elif args.action in ("monitor", "log"):
            asyncio.run(log(live=args.action == "monitor"))
        elif args.action not in ("info", "rollback", "done"):
            asyncio.run(console(args.action, args.id, args.play))
        else:
            asyncio.run(simple(args.action))
    except BoardError as e:
        sys.exit(f"board: {e}")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
