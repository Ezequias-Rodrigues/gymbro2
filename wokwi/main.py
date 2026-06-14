"""
ESP32 Wokwi Physical Feedback Station
MicroPython firmware — main.py

Hardware:
  LCD2004 I2C 20x4   SDA=D21  SCL=D22
  HC-SR04       TRIG=D5  ECHO=D18
  Passive Buzzer            PIN=D23
  Push Button               PIN=D19  (INPUT_PULLUP)

HTTP endpoints:
  POST /jackresult  { isJacking, repCount, formQuality, confidence }
  GET  /verdict     { rep_count, form_quality, is_jacking, confidence,
                      distance_cm, manual_reps }
  GET  /            HTML status page
"""

import network
import socket
import machine
import time
import json
import _thread

# ── Pin setup ──────────────────────────────────────────────────────────────────
TRIG_PIN   = 5
ECHO_PIN   = 18
BUZZER_PIN = 23
BUTTON_PIN = 19
SDA_PIN    = 21
SCL_PIN    = 22
LCD_ADDR   = 0x27

trig   = machine.Pin(TRIG_PIN,   machine.Pin.OUT)
echo   = machine.Pin(ECHO_PIN,   machine.Pin.IN)
buzzer = machine.Pin(BUZZER_PIN, machine.Pin.OUT)
button = machine.Pin(BUTTON_PIN, machine.Pin.IN, machine.Pin.PULL_UP)

# ── Inline PCF8574 + HD44780 I2C LCD driver ───────────────────────────────────

LCD_CLEAR        = 0x01
LCD_HOME         = 0x02
LCD_ENTRY_MODE   = 0x06
LCD_DISPLAY_ON   = 0x0C
LCD_FUNCTION_4BIT= 0x28
LCD_ROW_OFFSETS  = (0x00, 0x40, 0x14, 0x54)

RS = 0x01  # PCF8574 bit: RS
RW = 0x02  # PCF8574 bit: R/W
EN = 0x04  # PCF8574 bit: Enable
BL = 0x08  # PCF8574 bit: Backlight

class Lcd:
    def __init__(self, i2c, addr):
        self.i2c = i2c
        self.addr = addr
        self.backlight = BL
        self._pcf_write(self.backlight)
        time.sleep_ms(50)
        # HD44780 4-bit init sequence
        for cmd in (0x30, 0x30, 0x30, 0x20):
            self._write_nibble(cmd >> 4, 0)
            time.sleep_ms(5)
        self._command(LCD_FUNCTION_4BIT)
        self._command(LCD_DISPLAY_ON)
        self._command(LCD_CLEAR)
        time.sleep_ms(2)
        self._command(LCD_ENTRY_MODE)
        self.clear()

    def _pcf_write(self, byte):
        self.i2c.writeto(self.addr, bytes([byte]))

    def _pulse_en(self, byte):
        self._pcf_write(byte & ~EN)
        self._pcf_write(byte | EN)
        time.sleep_us(1)
        self._pcf_write(byte & ~EN)
        time.sleep_us(50)

    def _write_nibble(self, nibble, rs):
        byte = ((nibble & 0x0F) << 4) | self.backlight | rs
        self._pulse_en(byte)

    def _command(self, cmd):
        self._write_nibble(cmd >> 4, 0)
        self._write_nibble(cmd & 0x0F, 0)

    def _data(self, byte):
        self._write_nibble(byte >> 4, RS)
        self._write_nibble(byte & 0x0F, RS)

    def clear(self):
        self._command(LCD_CLEAR)
        time.sleep_ms(2)

    def move_to(self, col, row):
        addr = col + LCD_ROW_OFFSETS[row]
        self._command(0x80 | addr)

    def putstr(self, s):
        for c in s:
            self._data(ord(c))

    def backlight_on(self):
        self.backlight = BL
        self._pcf_write(self.backlight)

    def backlight_off(self):
        self.backlight = 0
        self._pcf_write(0)

# ── LCD init ───────────────────────────────────────────────────────────────────

LCD_OK = False
i2c = None
lcd = None

try:
    print("Scanning I2C...")
    i2c = machine.I2C(0, scl=machine.Pin(SCL_PIN), sda=machine.Pin(SDA_PIN), freq=400000)
    devices = i2c.scan()
    print("I2C devices found:", [hex(d) for d in devices])

    if LCD_ADDR in devices:
        lcd = Lcd(i2c, LCD_ADDR)
        lcd.putstr("LCD init OK!")
        time.sleep_ms(500)
        LCD_OK = True
        print("LCD ready at", hex(LCD_ADDR))
    else:
        print("LCD not found at", hex(LCD_ADDR))

except Exception as e:
    print("LCD init error:", e)

def lcd_text(line1="", line2="", line3="", line4=""):
    if not LCD_OK:
        return
    lcd.clear()
    for row, text in enumerate((line1[:20], line2[:20], line3[:20], line4[:20])):
        lcd.move_to(0, row)
        lcd.putstr(text)

# ── Global state ───────────────────────────────────────────────────────────────
state = {
    "rep_count":    0,
    "form_quality": "\u2014",
    "is_jacking":   False,
    "confidence":   0.0,
    "distance_cm":  0.0,
    "manual_reps":  0,
}

last_data_ms   = 0
last_rep_seen  = 0
show_stats     = False
ip_address     = ""
button_last_ms = 0

INACTIVITY_MS   = 5_000
SWITCH_DELAY_MS = 10_000
first_data_ms   = 0

# ── Ultrasonic distance ────────────────────────────────────────────────────────

def measure_distance_cm():
    trig.off()
    time.sleep_us(2)
    trig.on()
    time.sleep_us(10)
    trig.off()

    timeout = time.ticks_us() + 30_000
    while echo.value() == 0:
        if time.ticks_diff(timeout, time.ticks_us()) <= 0:
            return -1.0

    start = time.ticks_us()
    timeout = start + 30_000
    while echo.value() == 1:
        if time.ticks_diff(timeout, time.ticks_us()) <= 0:
            return -1.0

    duration_us = time.ticks_diff(time.ticks_us(), start)
    return (duration_us * 0.0343) / 2.0

# ── Buzzer ────────────────────────────────────────────────────────────────────

def beep(freq=1200, duration_ms=80):
    half_period_us = 1_000_000 // (freq * 2)
    end = time.ticks_ms() + duration_ms
    while time.ticks_diff(end, time.ticks_ms()) > 0:
        buzzer.on()
        time.sleep_us(half_period_us)
        buzzer.off()
        time.sleep_us(half_period_us)

# ── WiFi ──────────────────────────────────────────────────────────────────────

def connect_wifi(ssid="Wokwi-GUEST", password=""):
    global ip_address
    lcd_text("Connecting to", ssid)
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    wlan.connect(ssid, password)
    for _ in range(20):
        if wlan.isconnected():
            break
        time.sleep(0.5)
    if wlan.isconnected():
        ip_address = wlan.ifconfig()[0]
        print("WiFi connected:", ip_address)
    else:
        ip_address = "NO WIFI"
        print("WiFi failed")
    return wlan.isconnected()

# ── HTTP helpers ──────────────────────────────────────────────────────────────

def parse_request(raw):
    try:
        header_part, _, body = raw.partition(b"\r\n\r\n")
        lines = header_part.split(b"\r\n")
        method, path, _ = lines[0].decode().split(" ", 2)
        headers = {}
        for h in lines[1:]:
            if b":" in h:
                k, v = h.split(b":", 1)
                headers[k.strip().lower().decode()] = v.strip().decode()
        return method, path, headers, body.decode()
    except Exception:
        return "GET", "/", {}, ""

def http_response(client, status, content_type, body):
    body_bytes = body.encode() if isinstance(body, str) else body
    resp = (
        f"HTTP/1.1 {status}\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(body_bytes)}\r\n"
        "Connection: close\r\n\r\n"
    ).encode() + body_bytes
    client.sendall(resp)

# ── Route handlers ────────────────────────────────────────────────────────────

def handle_jackresult(body_str):
    global last_data_ms, last_rep_seen, show_stats, first_data_ms
    try:
        data = json.loads(body_str)
    except Exception:
        return 400, '{"error":"bad json"}'

    now = time.ticks_ms()

    if first_data_ms == 0:
        first_data_ms = now

    last_data_ms = now

    is_jacking   = bool(data.get("isJacking",    False))
    rep_count    = int(data.get("repCount",       0))
    form_quality = str(data.get("formQuality",   "\u2014"))
    confidence   = float(data.get("confidence",   0.0))

    state["is_jacking"]   = is_jacking
    state["rep_count"]    = rep_count
    state["form_quality"] = form_quality
    state["confidence"]   = confidence

    if rep_count > last_rep_seen:
        last_rep_seen = rep_count
        _thread.start_new_thread(beep, (1200, 80))

    if time.ticks_diff(now, first_data_ms) >= SWITCH_DELAY_MS:
        show_stats = True

    return 200, '{"ok":true}'

def handle_verdict():
    d = state["distance_cm"]
    body = json.dumps({
        "rep_count":    state["rep_count"],
        "form_quality": state["form_quality"],
        "is_jacking":   state["is_jacking"],
        "confidence":   round(state["confidence"], 3),
        "distance_cm":  round(d, 1),
        "manual_reps":  state["manual_reps"],
    })
    return 200, body

def handle_root():
    html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8">
<title>Feedback Station</title>
<style>body{{font-family:monospace;background:#111;color:#0f0;padding:20px}}
table{{border-collapse:collapse}}td{{padding:4px 12px;border:1px solid #0f0}}
</style></head><body>
<h2>ESP32 Feedback Station</h2>
<table>
<tr><td>IP</td><td>{ip_address}</td></tr>
<tr><td>Reps (phone)</td><td>{state['rep_count']}</td></tr>
<tr><td>Manual reps</td><td>{state['manual_reps']}</td></tr>
<tr><td>Is jacking</td><td>{state['is_jacking']}</td></tr>
<tr><td>Form quality</td><td>{state['form_quality']}</td></tr>
<tr><td>Confidence</td><td>{state['confidence']:.1%}</td></tr>
<tr><td>Distance cm</td><td>{state['distance_cm']:.1f}</td></tr>
</table>
<p>POST /jackresult &nbsp;|&nbsp; GET /verdict</p>
</body></html>"""
    return 200, html

# ── HTTP server ────────────────────────────────────────────────────────────────

def http_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("", 80))
    srv.listen(4)
    srv.settimeout(0.5)
    print("HTTP server listening on port 80")

    while True:
        try:
            client, addr = srv.accept()
        except OSError:
            continue

        try:
            client.settimeout(3.0)
            raw = b""
            while True:
                chunk = client.recv(1024)
                if not chunk:
                    break
                raw += chunk
                if b"\r\n\r\n" in raw:
                    hdr, _, body_so_far = raw.partition(b"\r\n\r\n")
                    cl = 0
                    for line in hdr.split(b"\r\n"):
                        if line.lower().startswith(b"content-length:"):
                            cl = int(line.split(b":")[1].strip())
                    if len(body_so_far) >= cl:
                        break

            method, path, headers, body = parse_request(raw)

            if method == "POST" and path == "/jackresult":
                code, resp_body = handle_jackresult(body)
                http_response(client, f"{code} OK",
                              "application/json", resp_body)

            elif method == "GET" and path == "/verdict":
                code, resp_body = handle_verdict()
                http_response(client, "200 OK",
                              "application/json", resp_body)

            elif method == "GET" and path == "/":
                _, resp_body = handle_root()
                http_response(client, "200 OK", "text/html", resp_body)

            else:
                http_response(client, "404 Not Found",
                              "text/plain", "Not found")

        except Exception as e:
            print("HTTP error:", e)
        finally:
            client.close()

# ── Display loop ───────────────────────────────────────────────────────────────

def update_display():
    global show_stats
    now = time.ticks_ms()

    if show_stats and last_data_ms > 0:
        if time.ticks_diff(now, last_data_ms) > INACTIVITY_MS:
            show_stats = False

    if show_stats:
        d   = state["distance_cm"]
        fq  = state["form_quality"][:8]
        rep = state["rep_count"]
        mr  = state["manual_reps"]
        jk  = "YES" if state["is_jacking"] else "NO"
        conf= int(state["confidence"] * 100)
        lcd_text(
            f"Reps:{rep}  M:{mr}",
            f"Jack:{jk} {conf}%",
            f"Form:{fq}",
            f"Dist:{d:.1f}cm",
        )
    else:
        lcd_text(
            "IP Address:",
            ip_address if ip_address else "...",
            "POST /jackresult",
            "",
        )

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    global button_last_ms

    lcd_text("Feedback Station", "Booting...")
    time.sleep(1)

    connect_wifi()

    lcd_text("IP Address:", ip_address[:20], "POST /jackresult")

    _thread.start_new_thread(http_server, ())

    print("IP:", ip_address)
    print("Entering main loop")

    while True:
        state["distance_cm"] = measure_distance_cm()

        now_ms = time.ticks_ms()
        if button.value() == 0:
            if time.ticks_diff(now_ms, button_last_ms) > 300:
                button_last_ms = now_ms
                state["manual_reps"] += 1
                print("Manual rep:", state["manual_reps"])
                _thread.start_new_thread(beep, (900, 60))

        update_display()
        time.sleep_ms(100)

main()
