# SpeedyBee BLE-to-UDP Bridge

[![English version](https://img.shields.io/badge/lang-en-green)](README.en.md)

Мост между полётным контроллером SpeedyBee (BLE UART) и наземными станциями управления (GCS) по UDP.

Сейчас в репозитории есть два варианта:
- Python-скрипт для Linux
- бета Android-приложение, которое делает то же самое: подключается к FC по BLE и пробрасывает трафик по UDP

## Зачем это нужно

Контроллеры SpeedyBee (F405 V4, F405 Wing, eFLY-BLE и др.) имеют встроенный BLE-модуль (ESP32), через который приложение SpeedyBee на телефоне общается с FC по проприетарному BLE-протоколу и MSP. Однако полноценные GCS — **Mission Planner** и **QGroundControl** — не умеют подключаться к FC по BLE напрямую.

Этот скрипт решает проблему: он подключается к контроллеру по BLE с компьютера и пробрасывает прозрачный serial-канал через UDP. GCS видит это как обычное MAVLink-соединение.

Это позволяет:
- Настраивать ArduPilot/Betaflight через Mission Planner или QGroundControl без USB-кабеля
- Получать телеметрию в реальном времени по BLE
- Использовать CLI полётного контроллера удалённо

## Что нового

- Скрипт теперь поддерживает полный SpeedyBee handshake, а не только базовый вход в serial passthrough
- Добавлена поддержка BLE-пароля для новых плат, где требуется аутентификация
- Проверено на платах, которым нужен дополнительный стартовый MSP-пакет, чтобы начать поток данных
- В репозитории появился бета Android-клиент с тем же назначением, исходники лежат в `android/`, готовая сборка сейчас есть как `app-debug.apk`

## Требования

- Linux с BlueZ
- Python 3.11+
- [uv](https://github.com/astral-sh/uv)

## Использование

```bash
# Сканировать BLE-устройства
uv run speedybee_ble_bridge.py --scan

# Подключиться по MAC-адресу
uv run speedybee_ble_bridge.py --addr 10:B4:1D:BD:B6:12

# Подключиться по имени
uv run speedybee_ble_bridge.py --name "SpeedyBee F405 V4"

# Для плат с BLE-паролем
uv run speedybee_ble_bridge.py --addr 6C:C8:40:E0:86:64 --password XXXX

# С отладочным выводом
uv run speedybee_ble_bridge.py --addr 10:B4:1D:BD:B6:12 -v
```

По умолчанию мост слушает UDP на `0.0.0.0:14551` и отправляет данные на `127.0.0.1:14550`.

## Настройка Mission Planner

1. Запустить мост
2. В Mission Planner: **CONNECT** → выбрать **UDP** → нажать **Connect**
3. Ввести порт: **14550**
4. Listening host: **127.0.0.1**

Mission Planner начнёт принимать MAVLink-пакеты с контроллера.

## Настройка QGroundControl

1. Запустить мост
2. QGroundControl: **Application Settings** → **Comm Links** → **Add**
3. Тип: **UDP**
4. Port: **14550**
5. Server Address: **127.0.0.1**
6. Нажать **Connect**

## Настройка MAVProxy

```bash
mavproxy.py --master=udp:127.0.0.1:14550
```

## Параметры командной строки

| Параметр | По умолчанию | Описание |
|---|---|---|
| `--scan` | | Сканировать BLE-устройства и выйти |
| `--addr` | | MAC-адрес контроллера |
| `--name` | | Имя BLE-устройства для поиска |
| `--password` | | BLE-пароль, если плата требует аутентификацию |
| `--udp-local` | `0.0.0.0:14551` | Локальный UDP-адрес (приём от GCS) |
| `--udp-remote` | `127.0.0.1:14550` | UDP-адрес GCS (отправка данных) |
| `-v` | | Подробный лог |

## GATT-профиль SpeedyBee

Сервис `0xABF0`:

| UUID | Роль | Описание |
|---|---|---|
| `0xABF1` | TX (Write) | Serial-канал: хост → FC (MSP/CLI/MAVLink) |
| `0xABF2` | RX (Notify) | Serial-канал: FC → хост |
| `0xABF3` | TX (Write) | Проприетарный: хендшейк, запрос инфо |
| `0xABF4` | RX (Notify) | Проприетарный: ответ на хендшейк |
