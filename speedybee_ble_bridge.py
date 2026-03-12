#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["bleak"]
# ///
"""
SpeedyBee BLE-to-UDP bridge.

Connects to a SpeedyBee flight controller over BLE,
enters CLI/passthrough mode, and relays serial data
bidirectionally over UDP for MAVLink (ArduPilot).

Usage:
    # Scan for devices:
    uv run speedybee_ble_bridge.py --scan

    # Connect by name (default UDP 127.0.0.1:14550):
    uv run speedybee_ble_bridge.py --name "SpeedyBee F405 V4"

    # Connect by MAC, custom UDP ports:
    uv run speedybee_ble_bridge.py --addr 10:B4:1D:BD:B6:12 \
        --udp-remote 127.0.0.1:14550 --udp-local 0.0.0.0:14551

ArduPilot GCS (e.g. Mission Planner / MAVProxy) connects to UDP 14550.
"""

import argparse
import asyncio
import logging
import signal
import sys

from bleak import BleakClient, BleakScanner

log = logging.getLogger("speedybee-bridge")

# ── SpeedyBee BLE GATT UUIDs (16-bit shortforms on service 0xABF0) ──────────
# Standard BLE base UUID: 0000xxxx-0000-1000-8000-00805f9b34fb
def uuid16(short: int) -> str:
    return f"0000{short:04x}-0000-1000-8000-00805f9b34fb"

SVC_SPEEDYBEE    = uuid16(0xABF0)
CHR_SERIAL_TX    = uuid16(0xABF1)   # Write Without Response  (host → FC)
CHR_SERIAL_RX    = uuid16(0xABF2)   # Notify                  (FC → host)
CHR_SB_TX        = uuid16(0xABF3)   # Write                   (host → FC, proprietary)
CHR_SB_RX_NOTIFY = uuid16(0xABF4)   # Notify                  (FC → host, proprietary)


def parse_host_port(s: str, default_port: int) -> tuple[str, int]:
    if ":" in s:
        host, port = s.rsplit(":", 1)
        return host, int(port)
    return s, default_port


class SpeedyBeeBridge:
    def __init__(self, client: BleakClient, udp_local: tuple, udp_remote: tuple, mtu: int):
        self.client = client
        self.udp_local = udp_local
        self.udp_remote = udp_remote
        self.mtu = mtu
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

    async def _enter_serial_mode(self):
        """
        SpeedyBee handshake from HCI log:
          1) Write ABF3: 02 00 08 03  — request device info
          2) Notify ABF4: 03 00 02 08 03 — ack
        Then MSP/CLI traffic flows on ABF1/ABF2.
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

        handshake_response = asyncio.Event()
        handshake_data = bytearray()

        def on_handshake(_char, data: bytearray):
            handshake_data.extend(data)
            handshake_response.set()

        await self.client.start_notify(sb_rx, on_handshake)

        log.info("Sending SpeedyBee handshake...")
        await self.client.write_gatt_char(sb_tx, bytes.fromhex("02000803"), response=True)

        try:
            await asyncio.wait_for(handshake_response.wait(), timeout=5.0)
            log.info("Handshake response: %s", handshake_data.hex())
        except asyncio.TimeoutError:
            log.warning("No handshake response (may still work)")

        await self.client.stop_notify(sb_rx)
        log.info("Handshake complete, serial passthrough active")


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
        )

        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, lambda: asyncio.ensure_future(bridge.stop()))

        await bridge.start()


def main():
    parser = argparse.ArgumentParser(description="SpeedyBee BLE-to-UDP MAVLink bridge")
    parser.add_argument("--scan", action="store_true", help="Scan for BLE devices and exit")
    parser.add_argument("--addr", help="BLE device address (MAC)")
    parser.add_argument("--name", help="BLE device name to search for")
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
