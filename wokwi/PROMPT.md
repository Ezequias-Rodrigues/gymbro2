# ESP32 Wokwi Physical Feedback Station — Project Prompt

Build an ESP32-based physical feedback station that connects to a phone app doing jumping jack detection (via IMU and/or camera pose detection). The phone sends exercise results to the ESP32 over HTTP, and the ESP32 responds with physical feedback.

## Components

| Component | Qty | Purpose |
|---|---|---|
| ESP32 DevKit V1 | 1 | Main controller |
| SSD1306 OLED (128x64, I2C) | 1 | Display IP on boot, live stats during exercise |
| HC-SR04 Ultrasonic Sensor | 1 | Measures distance to user |
| Passive Buzzer | 1 | Beeps on each rep |
| Push Button | 1 | Manual rep counter (press to add a rep) |

## Pin Wiring

| ESP32 Pin | Connects To | Component Pin |
|---|---|---|
| D21 | SDA | OLED SDA |
| D22 | SCL | OLED SCL |
| 3V3 | VCC | OLED VCC |
| GND | GND | OLED GND |
| D5 | TRIG | HC-SR04 TRIG |
| D18 | ECHO | HC-SR04 ECHO |
| 5V | VCC | HC-SR04 VCC |
| GND | GND | HC-SR04 GND |
| D23 | Signal (+) | Passive Buzzer + |
| GND | GND | Passive Buzzer - |
| D19 | Terminal 1 | Push Button |
| GND | Terminal 2 | Push Button (uses INPUT_PULLUP) |

## Functional Requirements

1. **WiFi + HTTP Server**: Connect to `Wokwi-GUEST` network, serve:
   - `POST /jackresult` — receives JSON `{isJacking, repCount, formQuality, confidence}`
   - `GET /verdict` — returns JSON `{rep_count, form_quality, is_jacking, confidence, distance_cm, manual_reps}`
   - `GET /` — simple HTML status page

2. **OLED Display**:
   - On boot: show "Connecting WiFi..." then display the IP address in large text
   - Switch to live stats (reps, form, score, distance) 10 seconds after first jack data received
   - Switch back to IP screen after 5 seconds of inactivity

3. **Ultrasonic Sensor**: Measure distance every loop iteration, include in verdict response

4. **Buzzer**: Beep on each rep (short 80ms tone at ~1200Hz)

5. **Button**: Press to increment manual rep counter (debounced at 300ms)

6. **Actuator feedback on `/jackresult` POST**:
   - If `isJacking == true`: beep
   - (Other optional feedback: adjust servo angle by form quality, light NeoPixel strip green/orange/blue — if those components are added)

## Behavior Flow

- ESP32 boots, connects to WiFi, starts HTTP server
- OLED shows IP address (user types this into phone app)
- Phone sends `POST /jackresult` with jack data periodically
- ESP32 updates internal state, beeps on each rep
- After 10s of receiving data, OLED switches to live stats
- After 5s without data, OLED returns to IP screen

## Constraints

- Use only libraries available in Wokwi's online editor Library Manager (`wokwi.com`)
- Prefer minimal libraries — the `U8g2` library (by olikraus) caused WebAssembly compilation errors on Wokwi's backend; use built-in ESP32 core or well-tested alternatives
- If a library fails compile, fall back to no external libraries (just `WiFi.h`, `WebServer.h`)

---

Generated from working project at `wokwi/` folder. For questions, refer to:
- `diagram.json` — component layout and wiring
- `firmware.ino` — complete firmware
- `libraries.txt` — library list (if using VS Code extension)
