#!/usr/bin/env python3
"""Talk to the board over Bluetooth LE, the way the phone will (docs/esp32-protocol.md).

  sync.py scan                watch the board's advertisement: signal strength (RSSI) and
                              status, once a second (Ctrl+C stops)
  sync.py pair                pair with the board (once; it must be in pairing mode:
                              no bonds yet, or 5 taps on the button)
  sync.py info                status report (INFO)
  sync.py ls                  list recordings
  sync.py get ID [--play]     download one to recordings/ID.pcm (+ .wav), check its CRC
  sync.py rm ID               delete one from the board
  sync.py sync [--keep]       a full sync (section 9): INFO, SET_TIME, LIST, then GET and
                              DELETE each recording (--keep: don't delete)

Needs bleak: tools/.venv (see firmware/README.md). The board is found by its service UUID.
The board only answers a paired device. To pair again from scratch: `bluetoothctl remove ADDR`.
"""
import argparse
import asyncio
import struct
import sys
import time
import zlib

from bleak import BleakClient, BleakScanner
from dbus_fast import BusType, Message, MessageType
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method

from pull import save  # same WAV saving and level report as the USB tool

SERVICE = "cdf70001-b272-4e8a-b578-c59339280171"
CONTROL = "cdf70002-b272-4e8a-b578-c59339280171"
DATA = "cdf70003-b272-4e8a-b578-c59339280171"

GET_INFO, SET_TIME, LIST, GET, DELETE = 1, 2, 3, 4, 5
ENTRY, LIST_END, GET_START, GET_END = 0x83, 0x93, 0x84, 0x94
STATUS = {1: "UNKNOWN_COMMAND", 2: "BAD_LENGTH", 3: "NOT_FOUND", 4: "BAD_OFFSET", 5: "BUSY", 6: "STORAGE_ERROR"}
RESET = ["unknown", "power-on", "brownout", "watchdog", "crash", "software restart", "deep-sleep wake"]
REPLY_TIMEOUT = 5  # seconds (section 9)
COMPANY_ID = 0xFFFF  # manufacturer data in the scan response (section 8)


class BoardError(Exception):
    pass


class Board:
    """One connection. Replies on `control` go into a queue; chunks on `data` into a file buffer."""

    def __init__(self, client):
        self.client = client
        self.replies = asyncio.Queue()
        self.download = None  # bytearray being filled by GET
        self.expected = 0     # the offset the next chunk should have
        self.gap = False
        self.last_chunk = 0.0

    async def start(self):
        await self.client.start_notify(CONTROL, lambda _, data: self.replies.put_nowait(bytes(data)))
        await self.client.start_notify(DATA, lambda _, data: self.on_chunk(bytes(data)))

    def on_chunk(self, chunk):
        # [offset u32][bytes]: write it at its place. A gap means a chunk got lost.
        (offset,) = struct.unpack_from("<I", chunk)
        self.last_chunk = time.monotonic()
        if self.download is None or self.gap:
            return
        if offset != self.expected:
            self.gap = True
            return
        self.download[offset:offset + len(chunk) - 4] = chunk[4:]
        self.expected = offset + len(chunk) - 4

    async def send(self, opcode, payload=b""):
        await self.client.write_gatt_char(CONTROL, bytes([opcode]) + payload, response=True)

    async def reply(self, timeout=REPLY_TIMEOUT):
        """The next reply: (opcode, payload). A non-OK status raises BoardError."""
        packet = await asyncio.wait_for(self.replies.get(), timeout)
        opcode, status = packet[0], packet[1]
        if status != 0:
            raise BoardError(f"reply 0x{opcode:02x}: {STATUS.get(status, status)}")
        return opcode, packet[2:]

    async def info(self):
        await self.send(GET_INFO)
        _, p = await self.reply()
        version, flags, pending, mv, total, free, missed, uptime, reset = struct.unpack_from("<BIHHIIHIB", p)
        return {
            "protocol": version, "flags": flags, "pending": pending, "battery_mv": mv,
            "storage_total_kib": total, "storage_free_kib": free, "missed": missed,
            "uptime_s": uptime, "reset": RESET[reset] if reset < len(RESET) else reset,
            "firmware": p[struct.calcsize("<BIHHIIHIB"):].decode(),
        }

    async def set_time(self, now):
        await self.send(SET_TIME, struct.pack("<I", now))
        await self.reply()

    async def list(self):
        await self.send(LIST)
        entries = []
        while True:
            opcode, p = await self.reply()
            if opcode == ENTRY:
                entries.append(struct.unpack_from("<IIIB", p))  # id, size, recordedAt, format
            elif opcode == LIST_END:
                (count,) = struct.unpack_from("<H", p)
                assert count == len(entries), f"LIST_END says {count}, got {len(entries)}"
                return entries

    async def get(self, rid):
        """Download one recording, as the phone does (section 5): resume after a gap, start
        over on a bad CRC, give up after 3 failed tries."""
        data, tries = bytearray(), 0
        while tries < 3:
            self.download, self.expected, self.gap = data, len(data), False
            started = time.monotonic()
            await self.send(GET, struct.pack("<II", rid, len(data)))
            _, p = await self.reply()
            _, offset, size = struct.unpack_from("<III", p)
            # GET_END follows the last chunk. A long download keeps going as long as chunks arrive.
            self.last_chunk = time.monotonic()
            while True:
                try:
                    opcode, p = await self.reply(timeout=1)
                    break
                except asyncio.TimeoutError:
                    if time.monotonic() - self.last_chunk > REPLY_TIMEOUT:
                        raise BoardError("no chunk for 5 s")
            _, crc = struct.unpack_from("<II", p)
            got = self.expected - offset
            took = time.monotonic() - started
            print(f"  {got} bytes in {took:.1f} s ({got / 1024 / max(took, 0.001):.0f} KB/s)")
            self.download = None
            if self.gap or len(data) < size:
                print(f"  gap at byte {len(data)} of {size}: resuming")
                tries += 0 if got > 0 else 1  # a round that made progress doesn't count as a try
                continue
            if zlib.crc32(data) == crc:
                return bytes(data)
            print(f"  CRC mismatch: board {crc:08x}, received {zlib.crc32(data):08x}; starting over")
            data, tries = bytearray(), tries + 1
        raise BoardError(f"gave up on recording {rid} after 3 tries")

    async def delete(self, rid):
        await self.send(DELETE, struct.pack("<I", rid))
        await self.reply()


def show_info(i):
    clock = "set" if i["flags"] & 1 else "not set"
    battery = f"{i['battery_mv']} mV" if i["battery_mv"] else "unknown"
    used = i["storage_total_kib"] - i["storage_free_kib"]
    print(f"firmware {i['firmware']} (protocol {i['protocol']}), up {i['uptime_s']} s, last reset: {i['reset']}")
    print(f"{i['pending']} pending, storage {used} of {i['storage_total_kib']} KiB used, "
          f"battery {battery}, clock {clock}, flags 0x{i['flags']:x}, missed {i['missed']}")


def show_entries(entries):
    for rid, size, recorded_at, fmt in entries:
        when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(recorded_at)) if recorded_at else "time unknown"
        print(f"{rid:5}  {size / 32000:6.1f} s  {when}  format {fmt}")


def show_status(status):
    # [protocolVersion u8][flags u32][pendingCount u16][batteryMillivolts u16][storageFreePercent u8]
    version, flags, pending, battery, free = struct.unpack("<BIHHB", status[:10])
    battery = f"{battery} mV" if battery else "battery unknown"
    clock = "clock set" if flags & 1 else "clock not set"
    return f"{pending} pending, {battery}, {free}% free, {clock} (protocol {version})"


async def scan():
    """Prints the board's signal strength and status once a second, for the antenna check."""
    # BlueZ only reports the board again when something changes (e.g. the RSSI), so keep
    # the latest reading and show how old it is.
    latest = {}

    def on_advert(device, adv):
        if SERVICE in adv.service_uuids or COMPANY_ID in adv.manufacturer_data:
            old = latest.get(device.address, (0, None, 0))
            status = adv.manufacturer_data.get(COMPANY_ID, old[1])  # name-only updates lack it
            latest[device.address] = (adv.rssi, status, time.monotonic())

    async with BleakScanner(on_advert):
        print("scanning (Ctrl+C stops); about -50 dBm is close by, -90 is the edge")
        while True:
            await asyncio.sleep(1)
            now = time.strftime("%H:%M:%S")
            if not latest:
                print(f"{now}  not seen yet (connected to something, or out of range)")
            for address, (rssi, status, when) in latest.items():
                text = show_status(status) if status else "no status (older firmware?)"
                print(f"{now}  {address}  {rssi:4} dBm  {text}  ({time.monotonic() - when:.0f} s ago)")


class JustWorksAgent(ServiceInterface):
    """A BlueZ pairing agent that says yes. Pairing needs an agent to confirm "Just Works",
    and a desktop's own agent may refuse (or there is none), so `pair` brings its own."""

    def __init__(self):
        super().__init__("org.bluez.Agent1")

    @method()
    def Release(self):
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


async def bluez_call(bus, path, interface, member, signature="", body=()):
    reply = await bus.call(Message(destination="org.bluez", path=path, interface=interface,
                                   member=member, signature=signature, body=list(body)))
    if reply.message_type == MessageType.ERROR:
        raise BoardError(f"{member}: {reply.error_name} {reply.body}")


async def pair(device):
    """Pairs through BlueZ with our own agent, on the same D-Bus connection as the request."""
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    agent_path = "/dev/piatra/agent"
    bus.export(agent_path, JustWorksAgent())
    await bluez_call(bus, "/org/bluez", "org.bluez.AgentManager1", "RegisterAgent", "os",
                     (agent_path, "NoInputNoOutput"))
    try:
        await bluez_call(bus, device.details["path"], "org.bluez.Device1", "Pair")
        print("paired")
    finally:
        await bluez_call(bus, "/org/bluez", "org.bluez.AgentManager1", "UnregisterAgent", "o", (agent_path,))
        bus.disconnect()


async def run(args):
    if args.action == "scan":
        return await scan()
    print("scanning for the board...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, adv: SERVICE in adv.service_uuids, timeout=10)
    if device is None:
        sys.exit("board not found (is the firmware running? is it already connected to something?)")
    if args.action == "pair":
        # The board refuses unless it's in pairing mode (no bonds yet, or 5 taps).
        return await pair(device)
    async with BleakClient(device) as client:
        print(f"connected to {device.name} {device.address}")
        board = Board(client)
        await board.start()

        if args.action == "info":
            show_info(await board.info())
        elif args.action == "ls":
            show_entries(await board.list())
        elif args.action == "get":
            save(args.id, await board.get(args.id), args.play)
        elif args.action == "rm":
            await board.delete(args.id)
            print(f"deleted {args.id}")
        elif args.action == "sync":
            show_info(await board.info())
            await board.set_time(int(time.time()))
            entries = await board.list()
            show_entries(entries)
            for rid, size, recorded_at, fmt in entries:
                if fmt != 1:
                    print(f"{rid}: unknown format {fmt}, left on the board")
                    continue
                print(f"recording {rid}:")
                save(rid, await board.get(rid), args.play)
                if not args.keep:
                    await board.delete(rid)
                    print(f"  deleted from the board")
            print("sync done")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", choices=["scan", "pair", "info", "ls", "get", "rm", "sync"])
    p.add_argument("id", nargs="?", type=int)
    p.add_argument("--play", action="store_true")
    p.add_argument("--keep", action="store_true", help="sync: leave recordings on the board")
    args = p.parse_args()
    if args.action in ("get", "rm") and args.id is None:
        sys.exit(f"{args.action} needs an id")
    try:
        asyncio.run(run(args))
    except BoardError as e:
        sys.exit(f"board: {e}")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
