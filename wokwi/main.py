"""
ESP32 Wokwi Physical Feedback Station — Relay client
Polls the cloud relay for jumping jack data.
"""

import machine
import network
import json
import time
import socket
import _thread
try:
    import ussl
    HAS_SSL = True
except ImportError:
    HAS_SSL = False
    print("ussl not available")

# ═══════════════════════════════════════════════════════════════════════
# Config — set this to your relay URL
# ═══════════════════════════════════════════════════════════════════════
RELAY_URL        = "http://reasonable-unity-production.up.railway.app/api/jackresult"
POLL_INTERVAL_MS = 1000

# Hardware pins
TRIG_PIN, ECHO_PIN, BUZZER_PIN, BUTTON_PIN = 5, 18, 23, 19
SDA_PIN, SCL_PIN = 21, 22
LCD_ADDR = 0x27

trig   = machine.Pin(TRIG_PIN,   machine.Pin.OUT)
echo   = machine.Pin(ECHO_PIN,   machine.Pin.IN)
buzzer = machine.Pin(BUZZER_PIN, machine.Pin.OUT)
button = machine.Pin(BUTTON_PIN, machine.Pin.IN, machine.Pin.PULL_UP)

# ═══════════════════════════════════════════════════════════════════════
# Inline PCF8574 + HD44780 I2C LCD driver
# ═══════════════════════════════════════════════════════════════════════
LCD_CLEAR        = 0x01
LCD_ENTRY_MODE   = 0x06
LCD_DISPLAY_ON   = 0x0C
LCD_FUNCTION_4BIT= 0x28
LCD_ROW_OFFSETS  = (0x00, 0x40, 0x14, 0x54)
RS, RW, EN, BL   = 0x01, 0x02, 0x04, 0x08

class Lcd:
    def __init__(self, i2c, addr):
        self.i2c = i2c
        self.addr = addr
        self.backlight = BL
        self._pcf_write(self.backlight)
        time.sleep_ms(50)
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
        self._pulse_en(((nibble & 0x0F) << 4) | self.backlight | rs)

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
        self._command(0x80 | (col + LCD_ROW_OFFSETS[row]))

    def putstr(self, s):
        for c in s:
            self._data(ord(c))

# ── LCD init ──────────────────────────────────────────────────────────
LCD_OK = False
lcd = None
try:
    dbg("LCD I2C scan...")
    i2c = machine.I2C(0, scl=machine.Pin(SCL_PIN), sda=machine.Pin(SDA_PIN), freq=400000)
    devs = i2c.scan()
    dbg("I2C: " + str([hex(d) for d in devs])[:17])
    if LCD_ADDR in devs:
        lcd = Lcd(i2c, LCD_ADDR)
        lcd.putstr("LCD ready")
        time.sleep_ms(300)
        LCD_OK = True
        dbg("LCD ready at 0x27")
    else:
        dbg("LCD not found at 0x27")
except Exception as e:
    dbg("LCD err: " + str(e)[:14])

def lcd_text(line1="", line2="", line3="", line4=""):
    if not LCD_OK or lcd is None:
        return
    lcd.clear()
    for row, text in enumerate((line1[:20], line2[:20], line3[:20], line4[:20])):
        lcd.move_to(0, row)
        lcd.putstr(text)

# ═══════════════════════════════════════════════════════════════════════
# Global state
# ═══════════════════════════════════════════════════════════════════════
last_rep_seen   = 0
last_data_ms    = 0
show_stats      = False
first_data_ms   = 0
button_last_ms  = 0
last_debug      = ""   # shown on LCD when no relay data

state = {
    "isJacking":   False,
    "repCount":    0,
    "formQuality": "REST",
    "confidence":  0.0,
    "distanceCm":  0.0,
    "manualReps":  0,
}

INACTIVITY_MS    = 5_000
SWITCH_DELAY_MS  = 10_000

# ═══════════════════════════════════════════════════════════════════════
# Hardware helpers
# ═══════════════════════════════════════════════════════════════════════
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
    dur = time.ticks_diff(time.ticks_us(), start)
    return (dur * 0.0343) / 2.0

def beep(freq=1200, dur_ms=80):
    half = 1_000_000 // (freq * 2)
    end = time.ticks_ms() + dur_ms
    while time.ticks_diff(end, time.ticks_ms()) > 0:
        buzzer.on()
        time.sleep_us(half)
        buzzer.off()
        time.sleep_us(half)

# ═══════════════════════════════════════════════════════════════════════
# HTTP poll relay (HTTP/1.0 — no chunking, close after response)
# ═══════════════════════════════════════════════════════════════════════
def dbg(msg):
    global last_debug
    last_debug = msg[:20]
    print(msg)

def _http_get(host, port, path, use_tls=False):
    """Low-level HTTP GET. Returns (status_code, headers, body_bytes) or None."""
    global last_debug
    try:
        dbg("DNS lookup %s:%d" % (host[:12], port))
        addrs = socket.getaddrinfo(host, port)
        if not addrs:
            dbg("DNS fail: " + host[:14])
            return None
        dbg("DNS OK -> " + str(addrs[0][-1][0])[:14])
        addr = addrs[0][-1]

        dbg("Connecting...")
        s = socket.socket()
        s.settimeout(8)
        s.connect(addr)
        dbg("Connected")
        if use_tls:
            if not HAS_SSL:
                dbg("TLS fail: no ussl")
                s.close()
                return None
            dbg("Starting TLS...")
            s = ussl.wrap_socket(s, server_hostname=host)
            dbg("TLS OK")
        dbg("Sending GET " + path[:10])
        s.write("GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n" % (path, host))
        resp = b""
        while True:
            c = s.recv(256)
            if not c:
                break
            resp += c
        s.close()
        dbg("Got %d bytes" % len(resp))

        # Parse status line
        hdr_end = resp.find(b"\r\n\r\n")
        if hdr_end < 0:
            dbg("No header end")
            return None
        header_lines = resp[:hdr_end].split(b"\r\n")
        status_code = int(header_lines[0].split(b" ")[1])
        dbg("Status: %d" % status_code)
        headers = {}
        for line in header_lines[1:]:
            if b":" in line:
                k, v = line.split(b":", 1)
                headers[k.strip().lower().decode()] = v.strip().decode()
        body = resp[hdr_end + 4:]
        return status_code, headers, body
    except Exception as e:
        dbg("Err: " + str(e)[:17])
        return None

def poll_relay():
    global last_debug
    try:
        url = RELAY_URL
        scheme = "http"
        host = url
        path = "/"
        if "://" in url:
            scheme, rest = url.split("://", 1)
            host = rest.split("/")[0]
            path = "/" + "/".join(rest.split("/")[1:])

        dbg("Poll: " + host[:14])
        # Try HTTPS (443) first, fall back to HTTP (80)
        result = _http_get(host, 443, path, use_tls=True)
        if result is None:
            dbg("Port 443 fail, try 80")
            result = _http_get(host, 80, path, use_tls=False)

        if result is None:
            dbg("All relay attempts fail")
            return None

        status, headers, body = result

        # Follow redirect if needed (e.g. HTTP→HTTPS redirect)
        if status in (301, 302, 307, 308) and "location" in headers:
            loc = headers["location"]
            dbg("Redirect " + loc[:16])
            if loc.startswith("http://"):
                h2 = loc[7:].split("/")[0]
                p2 = "/" + "/".join(loc[7:].split("/")[1:])
                result = _http_get(h2, 80, p2, use_tls=False)
            elif loc.startswith("https://"):
                h2 = loc[8:].split("/")[0]
                p2 = "/" + "/".join(loc[8:].split("/")[1:])
                result = _http_get(h2, 443, p2, use_tls=True)
            else:
                result = _http_get(host, 443, loc, use_tls=True)
            if result is None:
                return None
            _, _, body = result

        dbg("JSON parse...")
        data = json.loads(body.decode())
        dbg("Got data!")
        return data
    except Exception as e:
        dbg("Poll err: " + str(e)[:11])
        return None

# ═══════════════════════════════════════════════════════════════════════
# WiFi
# ═══════════════════════════════════════════════════════════════════════
def connect_wifi():
    global last_debug
    dbg("WiFi connecting...")
    lcd_text("Jump Jack Station", "WiFi connecting...")
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    wlan.connect("Wokwi-GUEST", "")
    for _ in range(30):
        if wlan.isconnected():
            break
        time.sleep(0.3)
    if wlan.isconnected():
        ip = wlan.ifconfig()[0]
        dbg("WiFi OK: " + ip[:14])
        lcd_text("Jump Jack Station", "WiFi OK: " + ip, "Starting...")
        return wlan
    else:
        dbg("WiFi FAILED!")
        lcd_text("Jump Jack Station", "WiFi FAILED!")
        return wlan

# ═══════════════════════════════════════════════════════════════════════
# Display
# ═══════════════════════════════════════════════════════════════════════
def show_idle():
    lcd_text("Jump Jack Station",
             last_debug[:20],
             "",
             "Poll %dms" % POLL_INTERVAL_MS)

def show_stats():
    r  = state["repCount"]
    mr = state["manualReps"]
    jk = "ACTIVE" if state["isJacking"] else "REST"
    cf = int(state["confidence"] * 100)
    fq = state["formQuality"][:12]
    d  = state["distanceCm"]
    ds = "%dcm" % d if d > 0 else "--cm"
    lcd_text("Reps:%d  %s" % (r, jk),
             "Form:%s  %d%%" % (fq, cf),
             "Dist:%s  Man:%d" % (ds, mr),
             "")

def update_display(now):
    global show_stats
    if not LCD_OK:
        return
    have = last_data_ms > 0
    recent = have and time.ticks_diff(now, last_data_ms) < INACTIVITY_MS
    if show_stats and not recent:
        show_stats = False
    elif not show_stats and have and recent and first_data_ms > 0:
        if time.ticks_diff(now, first_data_ms) >= SWITCH_DELAY_MS:
            show_stats = True
    if show_stats:
        show_stats()
    elif have and recent:
        show_stats()
    else:
        show_idle()

# ═══════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════
def main():
    global last_rep_seen, last_data_ms, show_stats, first_data_ms, button_last_ms, last_debug

    dbg("=== Boot ===")
    lcd_text("Jump Jack Station", "Booting...")
    time.sleep(0.5)

    dbg("SSL available" if HAS_SSL else "No SSL module")
    wlan = connect_wifi()
    dbg("=== Main loop ===")

    while True:
        now = time.ticks_ms()

        state["distanceCm"] = measure_distance_cm()

        if button.value() == 0:
            if time.ticks_diff(now, button_last_ms) > 300:
                button_last_ms = now
                state["manualReps"] += 1
                _thread.start_new_thread(beep, (900, 60))

        if time.ticks_diff(now, last_data_ms) >= POLL_INTERVAL_MS:
            data = poll_relay()
            if data:
                last_data_ms = now
                if first_data_ms == 0:
                    first_data_ms = now
                new_rep = int(data.get("repCount", 0))
                state["repCount"] = new_rep
                state["isJacking"] = bool(data.get("isJacking", False))
                state["formQuality"] = str(data.get("formQuality", "REST"))
                state["confidence"] = float(data.get("confidence", 0.0))
                if new_rep > last_rep_seen:
                    last_rep_seen = new_rep
                    _thread.start_new_thread(beep, (1200, 80))

        update_display(now)
        time.sleep_ms(100)

main()
