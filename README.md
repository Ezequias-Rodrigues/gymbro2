# GymBro

Sistema de Detecção de Polichinelos com Feedback Físico Utilizando Acelerômetro, Câmera e Microcontrolador ESP32

## Resumo

O GymBro é um sistema integrado de detecção de polichinelos (jumping jacks) que combina sensores inerciais de um dispositivo Android, visão computacional via MediaPipe Pose, e uma estação de feedback físico baseada no microcontrolador ESP32 simulado no ambiente Wokwi. O sistema adota uma arquitetura orientada a eventos na qual o telefone envia os resultados da detecção para um retransmissor (relay) HTTP na nuvem (Railway), que por sua vez é consultado periodicamente pelo ESP32. Um worker do Cloudflare faz a ponte entre o protocolo HTTP (usado pelo ESP32) e o HTTPS (exigido pela plataforma Railway). A estação ESP32 exibe as informações em um display LCD 20×4 I2C, emite sinais sonoros via buzzer a cada repetição e mede a distância do usuário com sensor ultrassônico HC-SR04.

## Arquitetura do Sistema

O sistema adota uma arquitetura de três camadas com comunicação orientada a eventos e sondagem periódica (polling):

1. **Camada de Aquisição e Classificação** — aplicativo Android que coleta dados dos sensores inerciais e da câmera e produz um `JackResult`
2. **Camada de Retransmissão** — relay HTTP em Python (Railway) + worker Cloudflare como proxy de protocolo
3. **Camada de Feedback Físico** — ESP32 simulado no Wokwi com display LCD, buzzer e sensor ultrassônico

```
┌─────────────────────┐     POST /api/jackresult     ┌──────────────────┐
│   Android Phone     │ ──────────────────────────►  │   Railway Relay  │
│                     │      (JSON: isJacking,       │  (Python stdlib) │
│  IMU ──► JackClassifier ──► StreamClient ──────┐   │                  │
│  Camera ─► PoseDetector                        │   │  Armazena DATA   │
│                                                │   │  em memória      │
│  EmbeddedServer (LAN, porta 8080)              │   └────────┬─────────┘
└────────────────────────────────────────────────┘            │
                                                               │ HTTPS
                                                               ▼
                                                      ┌──────────────────┐
                                                      │  Cloudflare      │
                                                      │  Worker          │
                                                      │  (proxy HTTP)    │
                                                      │  ice-bonus-4742… │
                                                      └────────┬─────────┘
                                                               │ HTTP (porta 80)
                                                               ▼
                                                      ┌──────────────────┐
                                                      │  ESP32 (Wokwi)   │
                                                      │  MicroPython     │
                                                      │  Poll a cada 1s  │
                                                      │  LCD 20×4 I2C    │
                                                      │  HC-SR04         │
                                                      │  Buzzer          │
                                                      └──────────────────┘
```

A escolha por um relay na nuvem em vez de comunicação direta entre telefone e ESP32 deve-se a duas limitações: (i) o Wokwi gratuito não pode receber pacotes de entrada, apenas realizar requisições de saída; e (ii) o telefone e o simulador estão em redes diferentes.

## Aplicativo Android

Desenvolvido em **Kotlin** com **Jetpack Compose**, arquitetura **MVVM** com `MainViewModel` como coordenador central.

### Detecção por Sensores Inerciais

O módulo `SensorTracker.kt` registra ouvintes para `TYPE_ACCELEROMETER` e `TYPE_GYROSCOPE`. Um filtro passa-baixas com fator α = 0,85 separa a gravidade da aceleração linear.

O classificador `JackClassifier.classifyImu()` implementa uma máquina de estados de duas fases:
1. **Elevação dos braços** — magnitude giroscópica > 2,5 rad/s E aceleração > 3,0 m/s²
2. **Aterrissagem** — impacto com magnitude > 6,0 m/s²

### Detecção por Visão Computacional

O módulo `CameraPreview.kt` utiliza **CameraX** (ImageAnalysis) para capturar quadros em 480×360 pixels. O `PoseDetector.kt` carrega o modelo **MediaPipe Pose Landmarker** (`pose_landmarker_lite.task`) e detecta 33 landmarks corporais.

O classificador `JackClassifier.classifyPose()` avalia ângulos dos braços e abertura das pernas com uma máquina de estados de três fases: `REST → ARMS_UP → COMPLETE`. A qualidade do movimento é avaliada pelo ângulo do cotovelo (120° a 180°).

### Transmissão dos Resultados

O módulo `StreamClient.kt` utiliza **OkHttp** para requisições POST periódicas ao relay. A cada 500 ms, envia um payload JSON com `isJacking`, `repCount`, `formQuality`, `confidence` e `timestamp`.

O aplicativo também executa um servidor HTTP embutido na porta 8080 para depuração local:

| Endpoint | Método | Descrição |
|---|---|---|
| `/` | GET | Página HTML de status |
| `/sensor-data` | GET | Dados brutos do IMU em JSON |
| `/post-data` | POST | Echo de depuração |
| `/api/jackresult` | GET | Último veredito local em JSON |

```
SensorListener ──► Filtro passa-baixas (α=0.85) ──► ImuData
       │                                          │
       ▼                                          ▼
CameraX ──► ImageAnalysis ──► Bitmap ──► PoseLandmarker ──► PoseResult
                                                              │
                                                              ▼
                   JackClassifier (máquina de estados)
                                                              │
                                                              ▼
                   JackResult { isJacking, repCount,
                   formQuality, confidence }
                                                              │
                                                              ▼
                   StreamClient ──► POST /api/jackresult (relay)
```

## Servidor Retransmissor (Relay) HTTP

Implementado em **Python puro** com `http.server` e `json`, sem dependências externas. Atua como buffer de estado entre o telefone (produtor) e o ESP32 (consumidor).

| Endpoint | Método | Função |
|---|---|---|
| `/api/jackresult` | POST | Recebe dados do telefone e atualiza o estado global |
| `/api/jackresult` | GET | Retorna o estado atual para o ESP32 |
| `/` | GET | Retorna status e lista de endpoints |

O estado global é um dicionário Python com as chaves `isJacking`, `repCount`, `formQuality`, `confidence` e `timestamp`. Apenas chaves reconhecidas são atualizadas no POST (proteção contra injeção).

## Worker Cloudflare

Script **JavaScript** executado na borda da rede Cloudflare que atua como proxy de protocolo entre o ESP32 e o relay Railway, convertendo HTTP → HTTPS.

Implantado em: `icy-bonus-4742.ezequiasrpg.workers.dev`

Fluxo:
1. Recebe requisição HTTP GET ou POST do ESP32 em `/api/jackresult`
2. Encaminha ao relay Railway via HTTPS
3. Retorna a resposta JSON ao ESP32 com cabeçalhos CORS

```
ESP32 ──► HTTP GET /api/jackresult ──► Cloudflare Worker ──► HTTPS ──► Railway Relay
      ◄─────── JSON response ◄───────            ◄───────
          Porta 80 (sem TLS)                 Porta 443 (com TLS)
```

## Firmware ESP32 (Wokwi)

### Componentes de Hardware

| Componente | Quantidade | Função | Pinos |
|---|---|---|---|
| ESP32 DevKit V1 | 1 | Controlador principal | — |
| LCD 2004 (I2C, PCF8574) | 1 | Display 20×4 caracteres | SDA=D21, SCL=D22 |
| HC-SR04 | 1 | Sensor ultrassônico de distância | TRIG=D5, ECHO=D18 |
| Buzzer passivo | 1 | Sinalização sonora | Sinal=D23 |

### Driver do Display LCD

Implementado integralmente em MicroPython sem dependências externas, controlando o HD44780 em modo 4 bits via PCF8574. A função `lcd_text()` escreve até quatro linhas de 20 caracteres.

Endereços DDRAM: `LCD_ROW_OFFSETS = (0x00, 0x40, 0x14, 0x54)`

### Sondagem do Relay

A função `poll_relay()` executa a cada segundo no laço principal. Constrói uma requisição HTTP GET crua sobre socket TCP usando HTTP/1.0. A resposta é analisada manualmente (status, cabeçalhos, corpo JSON). Suporta redirecionamentos (301, 302, 307, 308).

### Medição de Distância Ultrassônica

O sensor HC-SR04 é acionado a cada iteração (~10 Hz). Um pulso de 10 µs no TRIG inicia a medição. Cálculo:

```
distância = (duração_us × 0,0343) / 2,0
```

Timeout de 30 ms aplicado em ambas as transições do ECHO.

### Sinalização Sonora

A função `beep(freq, dur_ms)` gera onda quadrada por software. A cada repetição detectada, um tom de 1200 Hz é emitido por 80 ms.

### Máquina de Estados do Display

- **Modo de espera (idle)**: título "GymBro Tracker" e mensagem "Prepare-se..."
- **Modo de estatísticas**: repetições, status (ACTIVE/REST), qualidade do movimento, confiança, distância e repetições manuais
- Transição para estatísticas após 10 segundos contínuos de dados; retorno ao idle após 5 segundos sem dados

```
main():
    ├─► lcd_text("GymBro Tracker", "Prepare-se...")
    ├─► connect_wifi() → "WiFi OK: <ip>"
    │
    └─► loop (100ms):
          ├─► measure_distance_cm()
          │
          └─► a cada 1000ms:
                ├─► poll_relay()
                │     ├─► _http_get(worker:80, /api/jackresult)
                │     ├─► parse HTTP response
                │     ├─► redirect? → segue Location
                │     └─► JSON → state
                │
                └─► update_display()
                      ├─► 10s de dados → modo estatísticas
                      ├─► 5s sem dados → modo espera (idle)
                      └─► lcd_text()
```

## Integração e Fluxo de Dados

1. **Aquisição** — Android coleta dados dos sensores/câmera a cada 50 ms
2. **Classificação** — `JackClassifier` processa e produz `JackResult`
3. **Publicação** — `StreamClient` envia POST ao relay a cada 500 ms
4. **Armazenamento** — Relay atualiza dicionário de estado global
5. **Sondagem** — ESP32 faz GET ao worker Cloudflare a cada 1 segundo
6. **Feedback** — Firmware atualiza LCD e aciona buzzer

O fluxo é assíncrono: o telefone publica a 2 Hz (500 ms) e o ESP32 consome a 1 Hz (1 s).

## Resultados

- **Classificador inercial**: confiança > 85% em movimento típico
- **Classificador por visão**: resultados consistentes com iluminação adequada
- **Transmissão**: estável a 2 Hz, latência < 200 ms
- **Relay Railway**: tempo de resposta < 50 ms
- **Worker Cloudflare**: latência adicional < 100 ms
- **Sensor HC-SR04**: variação < 2 cm
- **Tempo total detecção→exibição**: 500 ms a 1,5 s

## Conclusão

Principais contribuições:
1. Combinação de sensores inerciais e visão computacional para detecção robusta de polichinelos
2. Arquitetura de comunicação assíncrona contornando limitações de redes distintas
3. Worker Cloudflare como adaptador HTTP↔HTTPS, eliminando necessidade de TLS no microcontrolador
4. Firmware MicroPython completo e autocontido para ESP32

Trabalhos futuros:
- Substituição do relay por WebSockets para reduzir latência
- Persistência de dados para análise histórica
- Expansão do classificador para outros exercícios
- Aplicativo web complementar para visualização remota

## Referências

- ASSOCIAÇÃO BRASILEIRA DE NORMAS TÉCNICAS. NBR 6023. Rio de Janeiro: ABNT, 2018.
- ASSOCIAÇÃO BRASILEIRA DE NORMAS TÉCNICAS. NBR 14724. Rio de Janeiro: ABNT, 2011.
- GOOGLE LLC. MediaPipe Pose Landmarker. 2024. https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
- ESPRESSIF SYSTEMS. ESP32 Series Datasheet. 2024. https://www.espressif.com/sites/default/files/documentation/esp32_datasheet_en.pdf
- CLOUDFLARE, INC. Cloudflare Workers Documentation. 2026. https://developers.cloudflare.com/workers/
- WOKWI. Wokwi ESP32 Simulator. 2026. https://docs.wokwi.com/
- MICROPYTHON. MicroPython Documentation. 2026. https://docs.micropython.org/
- HITACHI. HD44780 LCD Controller Datasheet. 1998.
- TEXAS INSTRUMENTS. PCF8574 Remote 8-Bit I/O Expander Datasheet. 2015.
- KOTLIN FOUNDATION. Kotlin Programming Language. 2026. https://kotlinlang.org/
- SQUARE, INC. OkHttp Documentation. 2026. https://square.github.io/okhttp/
- RAILWAY. Railway Documentation. 2026. https://docs.railway.app/
