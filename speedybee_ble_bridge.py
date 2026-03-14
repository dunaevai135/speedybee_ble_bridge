#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["bleak"]
# ///
"""
SpeedyBee BLE-to-UDP bridge v2.

Supports full handshake protocol including password authentication
required by newer boards (F405 Wing, eFLY-BLE, etc.).

Protocol (from HCI snoop analysis):
  1) Init:     cmd=0x02 {field1: 3}     → FC responds with ack
  2) Password: cmd=0x08 {field1: 4, field2: password}  (if FC requires it)
  3) Serial:   cmd=0x0e {field1: 13, field2: serial}   → FC responds with device info
  4) Key:      cmd=0x02 {field1: 0x2d}  → FC responds with session key
  5) MSP/MAVLink flows on ABF1/ABF2

Usage:
    uv run speedybee_ble_bridge_v2.py --scan
    uv run speedybee_ble_bridge_v2.py --name "SpeedyBee F405 V4"
    uv run speedybee_ble_bridge_v2.py --addr 6C:C8:40:E0:86:64 --password XXXX
"""

import argparse
import asyncio
import logging
import signal
import sys

from bleak import BleakClient, BleakScanner

log = logging.getLogger("speedybee-bridge")


# ── SpeedyBee BLE GATT UUIDs (16-bit shortforms on service 0xABF0) ──────────
def uuid16(short: int) -> str:
    return f"0000{short:04x}-0000-1000-8000-00805f9b34fb"

SVC_SPEEDYBEE    = uuid16(0xABF0)
CHR_SERIAL_TX    = uuid16(0xABF1)   # Write Without Response  (host → FC)
CHR_SERIAL_RX    = uuid16(0xABF2)   # Notify                  (FC → host)
CHR_SB_TX        = uuid16(0xABF3)   # Write                   (host → FC, proprietary)
CHR_SB_RX_NOTIFY = uuid16(0xABF4)   # Notify                  (FC → host, proprietary)


# ── SpeedyBee proprietary protocol helpers ───────────────────────────────────
# Packet format: [cmd_id] [len_hi] [len_lo] [protobuf_payload...]
# Protobuf fields: field1=varint (tag 0x08), field2=string (tag 0x12), field3=bytes (tag 0x1a)

def _encode_varint(value: int) -> bytes:
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value & 0x7F)
    return bytes(result)


def _encode_proto(field1: int | None = None, field2: bytes | None = None) -> bytes:
    payload = bytearray()
    if field1 is not None:
        payload.append(0x08)
        payload.extend(_encode_varint(field1))
    if field2 is not None:
        payload.append(0x12)
        payload.extend(_encode_varint(len(field2)))
        payload.extend(field2)
    return bytes(payload)


def _sb_packet(cmd: int, field1: int | None = None, field2: bytes | None = None) -> bytes:
    """Write format: [cmd] [0x00] [protobuf_payload...]"""
    proto = _encode_proto(field1, field2)
    return bytes([cmd, 0x00]) + proto


def _decode_varint(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while offset < len(data):
        b = data[offset]
        result |= (b & 0x7F) << shift
        offset += 1
        if not (b & 0x80):
            break
        shift += 7
    return result, offset


def _decode_proto(data: bytes) -> dict:
    fields = {}
    offset = 0
    while offset < len(data):
        if offset >= len(data):
            break
        tag = data[offset]
        offset += 1
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:  # varint
            val, offset = _decode_varint(data, offset)
            fields[field_num] = val
        elif wire_type == 2:  # length-delimited
            length, offset = _decode_varint(data, offset)
            fields[field_num] = data[offset:offset + length]
            offset += length
        else:
            break
    return fields


def parse_host_port(s: str, default_port: int) -> tuple[str, int]:
    if ":" in s:
        host, port = s.rsplit(":", 1)
        return host, int(port)
    return s, default_port


class SpeedyBeeBridge:
    def __init__(self, client: BleakClient, udp_local: tuple, udp_remote: tuple,
                 mtu: int, password: str | None = None):
        self.client = client
        self.udp_local = udp_local
        self.udp_remote = udp_remote
        self.mtu = mtu
        self.password = password
        self.transport = None
        self.tx_char = None
        self.rx_char_uuid = None
        self._running = True

    async def start(self):
        await self._find_characteristics()
        await self.client.start_notify(self.rx_char_uuid, self._on_ble_data)
        log.info("Subscribed to notifications on %s", self.rx_char_uuid)

        loop = asyncio.get_running_loop()
        self.transport, _ = await loop.create_datagram_endpoint(
            lambda: _UdpProtocol(self),
            local_addr=self.udp_local,
        )
        log.info("UDP listening on %s:%d, forwarding to %s:%d",
                 *self.udp_local, *self.udp_remote)

        await self._enter_serial_mode()

        log.info("Bridge is running.  Ctrl+C to stop.")
        while self._running:
            await asyncio.sleep(1)

    async def stop(self):
        self._running = False
        if self.transport:
            self.transport.close()
        try:
            await self.client.stop_notify(self.rx_char_uuid)
        except Exception:
            pass
        try:
            await self.client.disconnect()
        except Exception:
            pass
        log.info("Bridge stopped.")

    def _on_ble_data(self, _char, data: bytearray):
        if self.transport and self.udp_remote:
            self.transport.sendto(bytes(data), self.udp_remote)
            log.debug("BLE→UDP %d bytes: %s", len(data), data.hex())

    async def send_to_ble(self, data: bytes):
        chunk_size = self.mtu - 3  # ATT overhead
        for i in range(0, len(data), chunk_size):
            chunk = data[i : i + chunk_size]
            await self.client.write_gatt_char(self.tx_char, chunk, response=False)
            log.debug("UDP→BLE %d bytes: %s", len(chunk), chunk.hex())

    async def _find_characteristics(self):
        services = self.client.services

        svc = None
        for s in services:
            if "abf0" in s.uuid.lower():
                svc = s
                break

        if not svc:
            log.warning("Service ABF0 not found. Available services:")
            for s in services:
                log.warning("  %s", s)
            raise RuntimeError("SpeedyBee GATT service not found")

        log.info("Found service: %s", svc.uuid)
        for char in svc.characteristics:
            log.info("  Characteristic %s  props=%s", char.uuid, char.properties)

        tx_candidates = []
        rx_candidates = []
        for char in svc.characteristics:
            if "write-without-response" in char.properties or "write" in char.properties:
                tx_candidates.append(char)
            if "notify" in char.properties or "indicate" in char.properties:
                rx_candidates.append(char)

        self.tx_char = None
        for c in tx_candidates:
            if "abf1" in c.uuid.lower():
                self.tx_char = c
                break
        if not self.tx_char and tx_candidates:
            self.tx_char = tx_candidates[0]

        self.rx_char_uuid = None
        for preferred in ["abf2", "abf4"]:
            for c in rx_candidates:
                if preferred in c.uuid.lower():
                    self.rx_char_uuid = c.uuid
                    break
            if self.rx_char_uuid:
                break
        if not self.rx_char_uuid and rx_candidates:
            self.rx_char_uuid = rx_candidates[0].uuid

        if not self.tx_char or not self.rx_char_uuid:
            raise RuntimeError(
                f"Could not find TX/RX characteristics. TX={self.tx_char}, RX={self.rx_char_uuid}"
            )

        log.info("Using TX: %s  RX: %s", self.tx_char.uuid, self.rx_char_uuid)

    async def _sb_write_and_wait(self, sb_tx, resp_buf: bytearray,
                                  resp_event: asyncio.Event,
                                  packet: bytes, timeout: float = 5.0) -> bytes:
        resp_buf.clear()
        resp_event.clear()
        log.debug("SB TX: %s", packet.hex())
        await self.client.write_gatt_char(sb_tx, packet, response=True)
        try:
            await asyncio.wait_for(resp_event.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            log.warning("No response (timeout %.1fs)", timeout)
            return bytes(resp_buf)
        # Wait briefly for multi-packet responses
        await asyncio.sleep(0.15)
        log.debug("SB RX: %s", resp_buf.hex())
        return bytes(resp_buf)

    async def _enter_serial_mode(self):
        """
        Full SpeedyBee handshake (from HCI snoop analysis):

        F405 V4 (no password):
          1) Write ABF3: 02 00 08 03
          2) Notify ABF4: 03 00 02 08 03
          3) Write ABF3: 0e 00 08 0d 12 0a <serial>
          4) Notify ABF4: f6 00 ... <device info>

        F405 Wing / eFLY-BLE (password required):
          1) Write ABF3: 02 00 08 03
          2) Notify ABF4: 07 00 06 08 03 10 02 1a 00  (10 02 = password required)
          3) Write ABF3: 08 00 08 04 12 <len> <password>
          4) Notify ABF4: 05 00 04 08 04 1a 00  (password accepted)
          5) Write ABF3: 0e 00 08 0d 12 0a <serial>
          6) Notify ABF4: f6 00 ... <device info>
          7) Write ABF3: 02 00 08 2d
          8) Notify ABF4: 26 00 ... <session key>
        """
        sb_tx = None
        sb_rx = None
        for s in self.client.services:
            if "abf0" in s.uuid.lower():
                for c in s.characteristics:
                    if "abf3" in c.uuid.lower():
                        sb_tx = c
                    if "abf4" in c.uuid.lower():
                        sb_rx = c.uuid

        if not sb_tx or not sb_rx:
            log.warning("SpeedyBee proprietary chars not found, skipping handshake")
            return

        resp_buf = bytearray()
        resp_event = asyncio.Event()

        def on_notify(_char, data: bytearray):
            resp_buf.extend(data)
            resp_event.set()

        await self.client.start_notify(sb_rx, on_notify)

        # Step 1: Init
        log.info("Handshake step 1: init")
        init_pkt = _sb_packet(0x02, field1=3)
        resp = await self._sb_write_and_wait(sb_tx, resp_buf, resp_event, init_pkt)

        if not resp:
            log.warning("No init response, continuing anyway")
            await self.client.stop_notify(sb_rx)
            return

        resp_cmd = resp[0]
        resp_payload = resp[3:] if len(resp) > 3 else b""
        resp_fields = _decode_proto(resp_payload) if resp_payload else {}
        log.info("Init response: cmd=0x%02x fields=%s", resp_cmd, resp_fields)

        # Check if password is required (field 2 present in response = password flag)
        needs_password = resp_cmd == 0x07 and 2 in resp_fields

        # Step 2: Password (if required)
        if needs_password:
            if not self.password:
                await self.client.stop_notify(sb_rx)
                raise RuntimeError(
                    "FC requires password authentication. "
                    "Use --password to provide the BLE password."
                )
            log.info("Handshake step 2: password authentication")
            pw_pkt = _sb_packet(0x08, field1=4, field2=self.password.encode("ascii"))
            resp = await self._sb_write_and_wait(sb_tx, resp_buf, resp_event, pw_pkt)

            if resp:
                resp_cmd = resp[0]
                resp_payload = resp[3:] if len(resp) > 3 else b""
                resp_fields = _decode_proto(resp_payload) if resp_payload else {}
                log.info("Password response: cmd=0x%02x fields=%s", resp_cmd, resp_fields)
                if resp_cmd != 0x05:
                    log.error("Password rejected! (cmd=0x%02x)", resp_cmd)
                    await self.client.stop_notify(sb_rx)
                    raise RuntimeError("Password authentication failed")
            else:
                log.warning("No password response")

        # Step 3: Serial number
        import secrets
        serial = secrets.token_hex(5)  # random 10-char serial like the app does
        log.info("Handshake step 3: serial (%s)", serial)
        serial_pkt = _sb_packet(0x0e, field1=13, field2=serial.encode("ascii"))
        resp = await self._sb_write_and_wait(sb_tx, resp_buf, resp_event, serial_pkt)

        if resp and len(resp) > 6:
            resp_cmd = resp[0]
            log.info("Device info response: cmd=0x%02x, %d bytes", resp_cmd, len(resp))
            self._parse_device_info(resp)
        else:
            log.warning("No device info response")

        # Step 4: Session key request
        log.info("Handshake step 4: session key")
        key_pkt = _sb_packet(0x02, field1=0x2D)
        resp = await self._sb_write_and_wait(sb_tx, resp_buf, resp_event, key_pkt)
        if resp:
            log.info("Session key response: cmd=0x%02x, %d bytes", resp[0], len(resp))

        await self.client.stop_notify(sb_rx)
        log.info("Handshake complete, serial passthrough active")

        # Send MSP API_VERSION request to kick FC into sending data.
        # Some boards (eFLY-BLE / F405 Wing) don't start the MAVLink stream
        # until they receive something on ABF1.
        # $M< (MSP v1 request), payload_len=0, cmd=2 (API_VERSION), checksum=2
        msp_api_version = bytes([0x24, 0x4D, 0x3C, 0x00, 0x02, 0x02])
        log.info("Sending MSP API_VERSION kick")
        await self.client.write_gatt_char(self.tx_char, msp_api_version, response=False)

    def _parse_device_info(self, data: bytes):
        """Extract and log device info from the 0xf6 response."""
        if len(data) < 10:
            return
        # Skip header (cmd + 2 bytes len + protobuf prefix)
        # The payload contains null-terminated strings
        raw = data[6:]  # skip cmd(1) + len(2) + proto_header(3)
        try:
            # Find product code (first string-like field)
            parts = raw.split(b"\x00")
            strings = [p.decode("ascii", errors="replace") for p in parts if p and len(p) > 2]
            for s in strings[:5]:
                log.info("  Device info: %s", s)
        except Exception:
            pass


class _UdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, bridge: SpeedyBeeBridge):
        self.bridge = bridge

    def connection_made(self, _transport):
        pass

    def datagram_received(self, data, addr):
        if self.bridge.udp_remote[1] == 0:
            self.bridge.udp_remote = addr
            log.info("Auto-detected GCS at %s:%d", *addr)
        asyncio.ensure_future(self.bridge.send_to_ble(data))


async def scan_devices(timeout: float = 10.0):
    log.info("Scanning for BLE devices (%ds)...", timeout)
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    print(f"\n{'Address':<20} {'RSSI':>5}  Name")
    print("-" * 60)
    for addr, (dev, adv) in sorted(devices.items(), key=lambda x: x[1][1].rssi or -999, reverse=True):
        name = dev.name or adv.local_name or "(unknown)"
        print(f"{dev.address:<20} {adv.rssi or 0:>5}  {name}")
    return devices


async def run_bridge(args):
    if args.scan:
        await scan_devices()
        return

    address = args.addr
    if not address and args.name:
        log.info("Scanning for '%s'...", args.name)
        device = await BleakScanner.find_device_by_name(args.name, timeout=15.0)
        if not device:
            log.error("Device '%s' not found", args.name)
            sys.exit(1)
        address = device.address
        log.info("Found %s at %s", device.name, address)

    if not address:
        log.error("Specify --addr or --name (or use --scan to list devices)")
        sys.exit(1)

    udp_local = parse_host_port(args.udp_local, 14551)
    udp_remote = parse_host_port(args.udp_remote, 14550)

    log.info("Connecting to %s...", address)
    async with BleakClient(address, timeout=20.0) as client:
        log.info("Connected! MTU=%d", client.mtu_size)

        bridge = SpeedyBeeBridge(
            client=client,
            udp_local=udp_local,
            udp_remote=udp_remote,
            mtu=client.mtu_size,
            password=args.password,
        )

        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, lambda: asyncio.ensure_future(bridge.stop()))

        await bridge.start()


def main():
    parser = argparse.ArgumentParser(description="SpeedyBee BLE-to-UDP MAVLink bridge v2")
    parser.add_argument("--scan", action="store_true", help="Scan for BLE devices and exit")
    parser.add_argument("--addr", help="BLE device address (MAC)")
    parser.add_argument("--name", help="BLE device name to search for")
    parser.add_argument("--password", default=None,
                        help="BLE password (required if FC has password protection)")
    parser.add_argument("--udp-local", default="0.0.0.0:14551",
                        help="Local UDP bind address (default: 0.0.0.0:14551)")
    parser.add_argument("--udp-remote", default="127.0.0.1:14550",
                        help="Remote UDP address for GCS (default: 127.0.0.1:14550)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Debug logging")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    asyncio.run(run_bridge(args))


if __name__ == "__main__":
    main()
