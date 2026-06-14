"""
ESP32 Wokwi Physical Feedback Station — Relay client
Polls the cloud relay for jumping jack data.

FIXES applied:
  1. dbg() moved before first use (was called before definition → immediate crash)
  2. show_stats function renamed to show_jack_stats() — was shadowed by the
     boolean variable 'show_stats', causing a TypeError when called
  3. update_display() corrected to call show_jack_stats() consistently
  4. TLS forced off by default (Wokwi ussl can't verify Railway certs);
     set FORCE_HTTP = False to re-enable TLS attempts
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

# ═══════════════════════════════════════════════════════════════════════
# Config
# ═══════════════════════════════════════════════════════════════════════
RELAY_URL        = "https://icy-bonus-4742.ezequiasrpg.workers.dev/"
POLL_INTERVAL_MS = 1000


FORCE_HTTP = True

# Hardware pins
TRIG_PIN, ECHO_PIN, BUZZER_PIN, BUTTON_PIN = 5, 18, 23, 19
SDA_PIN, SCL_PIN = 21, 22
LCD_ADDR = 0x27

trig   = machine.Pin(TRIG_PIN,   machine.Pin.OUT)
echo   = machine.Pin(ECHO_PIN,   machine.Pin.IN)
buzzer = machine.Pin(BUZZER_PIN, machine.Pin.OUT)
button = machine.Pin(BUTTON_PIN, machine.Pin.IN, machine.Pin.PULL_UP)

last_debug = ""

def dbg(msg):
    global last_debug
    last_debug = str(msg)[:20]
    print(msg)

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
        lcd.putstr("Tela: Ok!")
        time.sleep_ms(300)
        LCD_OK = True
        dbg("LCD ready at 0x27")
    else:
        dbg("LCD not found 0x27")
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

show_jack_stats_flag = False
first_data_ms   = 0
button_last_ms  = 0

state = {
    "isJacking":   False,
    "repCount":    0,
    "formQuality": "REST",
    "confidence":  0.0,
    "distanceCm":  0.0,
    "manualReps":  0,
}

INACTIVITY_MS  = 5_000
SWITCH_DELAY_MS = 10_000

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

def _http_get(host, port, path, use_tls=False):
    try:
        dbg("DNS %s:%d" % (host[:12], port))
        addrs = socket.getaddrinfo(host, port)
        if not addrs:
            dbg("DNS fail: " + host[:14])
            return None
        dbg("DNS OK " + str(addrs[0][-1][0])[:14])
        addr = addrs[0][-1]

        s = socket.socket()
        s.settimeout(8)
        s.connect(addr)
        dbg("TCP connected")

        if use_tls:
            if not HAS_SSL:
                dbg("TLS fail no ussl")
                s.close()
                return None
            dbg("TLS handshake...")
            s = ussl.wrap_socket(s, server_hostname=host)
            dbg("TLS OK")

        req = "GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n" % (path, host)
        dbg("GET " + path[:14])
        s.write(req)

        resp = b""
        while True:
            c = s.recv(256)
            if not c:
                break
            resp += c
        s.close()
        dbg("Recv %d bytes" % len(resp))

        hdr_end = resp.find(b"\r\n\r\n")
        if hdr_end < 0:
            dbg("No header sep")
            return None
        header_lines = resp[:hdr_end].split(b"\r\n")
        status_code = int(header_lines[0].split(b" ")[1])
        dbg("Status %d" % status_code)
        headers = {}
        for line in header_lines[1:]:
            if b":" in line:
                k, v = line.split(b":", 1)
                headers[k.strip().lower().decode()] = v.strip().decode()
        body = resp[hdr_end + 4:]
        return status_code, headers, body
    except Exception as e:
        dbg("_http err:" + str(e)[:12])
        return None

def poll_relay():
    url = RELAY_URL
    if "://" in url:
        _, rest = url.split("://", 1)
        host = rest.split("/")[0]
        path = "/" + "/".join(rest.split("/")[1:])
    else:
        host = url
        path = "/"


    dbg("Poll " + host[:14])
    result = _http_get(host, 80, path, use_tls=False)

    if result is None:
        dbg("No response")
        lcd_text("POLL FAIL", "No response", host[:20], "")
        return None

    status, headers, body = result

    # Show redirect target on LCD so we know where it's going
    if status in (301, 302, 307, 308):
        loc = headers.get("location", "?")
        dbg("Redirect!" + loc[:10])
        lcd_text("REDIRECT %d" % status, loc[:20], loc[20:40], "Disable on Railway")
        time.sleep_ms(3000)
        return None

    raw = ""
    try:
        dbg("Decoding body...")
        raw = body.decode("utf-8", "ignore").strip()
        dbg("Raw:" + raw[:16])
        data = json.loads(raw)
        dbg("Got data OK")
        return data
    except Exception as e:
        dbg("JSON err:" + str(e)[:12])
        # Show what actually arrived so we can diagnose
        lcd_text("JSON FAIL", raw[:20] if raw else "empty body", raw[20:40], str(e)[:20])
        time.sleep_ms(3000)
        return None

# ═══════════════════════════════════════════════════════════════════════
# WiFi
# ═══════════════════════════════════════════════════════════════════════
def connect_wifi():
    dbg("WiFi connecting...")
    lcd_text("GymBro Tracker", "Conectando WiFi...")
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
        lcd_text("GymBro Tracker", "WiFi OK:", ip, "Aquecendo...")
    else:
        dbg("WiFi FAILED!")
        lcd_text("GymBro Tracker", "WiFi FALHOU!")
    return wlan


def show_idle():
    lcd_text("GymBro Tracker",
             last_debug[:20],
             "",
             "Poll %dms" % POLL_INTERVAL_MS)

def show_jack_stats():
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
    global show_jack_stats_flag
    if not LCD_OK:
        return
    have   = last_data_ms > 0
    recent = have and time.ticks_diff(now, last_data_ms) < INACTIVITY_MS

    if show_jack_stats_flag and not recent:
        show_jack_stats_flag = False
    elif not show_jack_stats_flag and have and recent and first_data_ms > 0:
        if time.ticks_diff(now, first_data_ms) >= SWITCH_DELAY_MS:
            show_jack_stats_flag = True

    # FIX 2 (continued): both branches now call show_jack_stats() — a real
    # function — instead of the former bool 'show_stats'
    if show_jack_stats_flag:
        show_jack_stats()
    elif have and recent:
        show_jack_stats()
    else:
        show_idle()

# ═══════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════
def main():
    global last_rep_seen, last_data_ms, show_jack_stats_flag, first_data_ms
    global button_last_ms

    dbg("=== Boot ===")
    lcd_text("GymBro Tracker", "Prepare-se...")
    time.sleep(0.5)

    dbg("SSL: " + ("yes" if HAS_SSL else "no"))
    dbg("FORCE_HTTP: " + str(FORCE_HTTP))
    wlan = connect_wifi()
    dbg("=== Main loop ===")

    last_poll_ms = 0

    while True:
        now = time.ticks_ms()

        state["distanceCm"] = measure_distance_cm()

        if button.value() == 0:
            if time.ticks_diff(now, button_last_ms) > 300:
                button_last_ms = now
                state["manualReps"] += 1
                _thread.start_new_thread(beep, (900, 60))

        if time.ticks_diff(now, last_poll_ms) >= POLL_INTERVAL_MS:
            last_poll_ms = now
            data = poll_relay()
            if data:
                last_data_ms = now
                if first_data_ms == 0:
                    first_data_ms = now
                new_rep = int(data.get("repCount", 0))
                state["repCount"]    = new_rep
                state["isJacking"]   = bool(data.get("isJacking", False))
                state["formQuality"] = str(data.get("formQuality", "REST"))
                state["confidence"]  = float(data.get("confidence", 0.0))
                if new_rep > last_rep_seen:
                    last_rep_seen = new_rep
                    _thread.start_new_thread(beep, (1200, 80))

        update_display(now)
        time.sleep_ms(100)

main()
