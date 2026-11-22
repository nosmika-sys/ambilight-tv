/*
 * Ambilight TV - Firmware ESP32
 * ------------------------------------------------------------
 * Recebe as cores pela USB (protocolo Adalight) e acende uma
 * fita WS2815 (12V). Compativel com:
 *   - o app "Ambilight TV" para Android/Google TV (deste projeto)
 *   - HyperHDR / Hyperion (dispositivo "Adalight"), caso um dia
 *     voce queira usar captura via HDMI.
 *
 * Bibliotecas: FastLED (3.6 ou mais nova)
 * Placa: "ESP32 Dev Module" (core esp32 da Espressif, 2.x ou 3.x)
 */

#include <FastLED.h>

// ===================== CONFIGURACAO =====================
#define DATA_PIN        13        // GPIO ligado ao DIN da fita (via level shifter)
#define MAX_LEDS        300       // maximo aceito (o app envia a quantidade real)
#define LED_CHIPSET     WS2815    // fita 12V de 3 fios com 1 chip a cada 3 LEDs: use WS2811
#define COLOR_ORDER     GRB       // WS2815: GRB. WS2811 12V: geralmente BRG. Teste no startup (vermelho, verde, azul)
#define SERIAL_BAUD     921600    // tem que ser igual ao configurado no app
#define MAX_BRIGHTNESS  255       // teto de brilho no firmware (0-255)
#define TIMEOUT_MS      2500      // sem dados por esse tempo -> apaga suavemente
#define FADE_STEP       8         // velocidade do apagar (maior = mais rapido)
#define STATUS_LED_PIN  2         // LED azul da placa DevKit (-1 para desativar)
// ========================================================

CRGB leds[MAX_LEDS];

// Estado do parser Adalight
enum ParseState : uint8_t { WAIT_A, WAIT_D, WAIT_A2, WAIT_HI, WAIT_LO, WAIT_CHK, READ_DATA };
static ParseState state = WAIT_A;
static uint8_t  hiByte = 0, loByte = 0;
static uint16_t frameLeds = 0;     // LEDs no quadro atual
static uint16_t activeLeds = 0;    // LEDs do ultimo quadro valido
static uint32_t dataIndex = 0;     // bytes de cor ja lidos no quadro
static uint8_t  rgb[3];

static uint32_t lastFrameMs = 0;
static uint32_t lastHelloMs = 0;
static uint32_t lastFadeMs = 0;
static uint32_t lastByteMs = 0;
static bool     blanked = true;

static void startupTest() {
  // Varredura rapida para conferir a fita ao ligar
  const CRGB colors[] = { CRGB::Red, CRGB::Green, CRGB::Blue };
  for (const CRGB &c : colors) {
    fill_solid(leds, MAX_LEDS, c);
    FastLED.setBrightness(40);
    FastLED.show();
    delay(250);
  }
  FastLED.clear(true);
  FastLED.setBrightness(MAX_BRIGHTNESS);
}

static void handleByte(uint8_t b) {
  switch (state) {
    case WAIT_A:   state = (b == 'A') ? WAIT_D : WAIT_A; break;
    case WAIT_D:   state = (b == 'd') ? WAIT_A2 : (b == 'A' ? WAIT_D : WAIT_A); break;
    case WAIT_A2:  state = (b == 'a') ? WAIT_HI : (b == 'A' ? WAIT_D : WAIT_A); break;
    case WAIT_HI:  hiByte = b; state = WAIT_LO; break;
    case WAIT_LO:  loByte = b; state = WAIT_CHK; break;
    case WAIT_CHK:
      if (b == (uint8_t)(hiByte ^ loByte ^ 0x55)) {
        // Adalight envia (quantidade - 1)
        frameLeds = (uint16_t)(((uint16_t)hiByte << 8) | loByte) + 1;
        dataIndex = 0;
        state = READ_DATA;
      } else {
        state = WAIT_A;   // checksum invalido: procura o proximo cabecalho
      }
      break;
    case READ_DATA: {
      rgb[dataIndex % 3] = b;
      dataIndex++;
      if (dataIndex % 3 == 0) {
        uint32_t led = dataIndex / 3 - 1;
        if (led < MAX_LEDS) leds[led] = CRGB(rgb[0], rgb[1], rgb[2]);
      }
      if (dataIndex >= (uint32_t)frameLeds * 3) {
        uint16_t n = frameLeds > MAX_LEDS ? MAX_LEDS : frameLeds;
        // Se o quadro ficou menor que o anterior, apaga o excedente
        for (uint16_t i = n; i < activeLeds; i++) leds[i] = CRGB::Black;
        activeLeds = n;
        FastLED.setBrightness(MAX_BRIGHTNESS);
        FastLED.show();
        lastFrameMs = millis();
        blanked = false;
        state = WAIT_A;
      }
      break;
    }
  }
}

void setup() {
  Serial.setRxBufferSize(4096);   // precisa vir antes do begin
  Serial.begin(SERIAL_BAUD);

  if (STATUS_LED_PIN >= 0) pinMode(STATUS_LED_PIN, OUTPUT);

  FastLED.addLeds<LED_CHIPSET, DATA_PIN, COLOR_ORDER>(leds, MAX_LEDS);
  FastLED.setBrightness(MAX_BRIGHTNESS);
  FastLED.setDither(0);           // evita cintilar em cores escuras
  FastLED.clear(true);

  startupTest();
  Serial.print("Ada\n");          // handshake Adalight
  lastHelloMs = millis();
}

void loop() {
  // Le tudo que chegou pela USB
  uint32_t now = millis();
  int avail = Serial.available();
  if (avail > 0) {
    // Quadro pela metade ha muito tempo (cabo mexeu, app travou): descarta
    if (state != WAIT_A && (now - lastByteMs) > 100) state = WAIT_A;
    lastByteMs = now;
    while (avail-- > 0) handleByte((uint8_t)Serial.read());
    now = millis();
  }

  // LED da placa aceso enquanto recebe dados
  if (STATUS_LED_PIN >= 0) digitalWrite(STATUS_LED_PIN, (now - lastFrameMs) < 500 ? HIGH : LOW);

  // Sem dados: apaga suavemente (TV desligada, app fechado, cabo solto...)
  if (!blanked && (now - lastFrameMs) > TIMEOUT_MS && (now - lastFadeMs) > 15) {
    lastFadeMs = now;
    bool anyOn = false;
    for (uint16_t i = 0; i < activeLeds; i++) {
      leds[i].fadeToBlackBy(FADE_STEP);
      if (leds[i]) anyOn = true;
    }
    if (!anyOn) {
      FastLED.clear();
      blanked = true;
      state = WAIT_A;
    }
    FastLED.show();
  }

  // Enquanto ocioso, anuncia "Ada" a cada 1s (o app e o HyperHDR usam isso)
  if (blanked && (now - lastHelloMs) > 1000) {
    Serial.print("Ada\n");
    lastHelloMs = now;
  }
}
