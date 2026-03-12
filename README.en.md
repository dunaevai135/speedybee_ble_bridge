# SpeedyBee BLE-to-UDP Bridge

[![Русская версия](https://img.shields.io/badge/lang-ru-blue)](README.md)

A bridge between SpeedyBee flight controllers (BLE UART) and ground control stations (GCS) over UDP.

## Why

SpeedyBee flight controllers (F405 V4, etc.) have a built-in BLE module (ESP32) that the SpeedyBee mobile app uses to communicate with the FC via MSP protocol. However, full-featured GCS applications — **Mission Planner** and **QGroundControl** — cannot connect to the FC over BLE directly.

This script bridges the gap: it connects to the controller over BLE from a computer and creates a transparent serial channel over UDP. The GCS sees it as a regular MAVLink connection.

This allows you to:
- Configure ArduPilot/Betaflight via Mission Planner or QGroundControl without a USB cable
- Receive real-time telemetry over BLE
- Use the flight controller CLI remotely

## Requirements

- Linux with BlueZ
- Python 3.11+
- [uv](https://github.com/astral-sh/uv)

## Usage

```bash
# Scan for BLE devices
uv run speedybee_ble_bridge.py --scan

# Connect by MAC address
uv run speedybee_ble_bridge.py --addr 10:B4:1D:BD:B6:12

# Connect by device name
uv run speedybee_ble_bridge.py --name "SpeedyBee F405 V4"

# With debug output
uv run speedybee_ble_bridge.py --addr 10:B4:1D:BD:B6:12 -v
```

By default the bridge listens on UDP `0.0.0.0:14551` and sends data to `127.0.0.1:14550`.

## Mission Planner setup

1. Start the bridge
2. In Mission Planner: **CONNECT** → select **UDP** → click **Connect**
3. Enter port: **14550**
4. Listening host: **127.0.0.1**

Mission Planner will start receiving MAVLink packets from the controller.

## QGroundControl setup

1. Start the bridge
2. QGroundControl: **Application Settings** → **Comm Links** → **Add**
3. Type: **UDP**
4. Port: **14550**
5. Server Address: **127.0.0.1**
6. Click **Connect**

## MAVProxy setup

```bash
mavproxy.py --master=udp:127.0.0.1:14550
```

## CLI arguments

| Argument | Default | Description |
|---|---|---|
| `--scan` | | Scan for BLE devices and exit |
| `--addr` | | Controller BLE MAC address |
| `--name` | | BLE device name to search for |
| `--udp-local` | `0.0.0.0:14551` | Local UDP bind address (receive from GCS) |
| `--udp-remote` | `127.0.0.1:14550` | Remote UDP address (send to GCS) |
| `-v` | | Verbose logging |

## SpeedyBee GATT profile

Service `0xABF0`:

| UUID | Role | Description |
|---|---|---|
| `0xABF1` | TX (Write) | Serial channel: host → FC (MSP/CLI/MAVLink) |
| `0xABF2` | RX (Notify) | Serial channel: FC → host |
| `0xABF3` | TX (Write) | Proprietary: handshake, device info request |
| `0xABF4` | RX (Notify) | Proprietary: handshake response |
