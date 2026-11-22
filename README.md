# Ambilight TV — Android TV + ESP32 + WS2815

O app roda na própria TV (Android TV / Google TV), lê as cores das bordas da imagem e manda pela **USB** para um ESP32, que acende uma fita **WS2815 12V** atrás da TV.

```
[Android TV] --USB (dados)--> [ESP32] --GPIO13--> [level shifter] --> DI da fita WS2815
                                                       Fonte 12V ----> +12V / GND da fita
```

> **Limitação do Android:** Netflix, Prime Video, Disney+, Max e outros apps com DRM aparecem **pretos** para qualquer captura de tela. A fita apaga nesses apps. YouTube, jogos, IPTV, Plex/Kodi e a maioria dos apps funcionam. O firmware também fala o protocolo **Adalight**, então dá para usar o mesmo ESP32 com HyperHDR + captura HDMI no futuro (isso funciona com tudo).

---

## 1. Material (TV de até 50")

| Item | Observação |
|---|---|
| ESP32 DevKit (WROOM) | o comum, com chip USB CH340 ou CP2102 |
| Fita WS2815 12V, 60 LEDs/m, ~4 m | uma TV de 50" usa cerca de 200 LEDs no contorno |
| Fonte 12V **5A** | 200 LEDs × ~15 mA = 3 A no branco máximo, mais uma folga |
| Level shifter 74AHCT125 (ou 74HCT245) | converte os 3,3V do ESP32 para os 5V do sinal da fita |
| Resistor 330 Ω | em série com o sinal, perto da fita |
| Capacitor 1000 µF / 16V | nos terminais da fonte, junto da fita |
| Cabo USB de dados | ESP32 → porta USB da TV |

**Medindo:** cole a fita uns 3 a 5 cm para dentro da borda traseira. Conte quantos LEDs couberam em cada lado; esses números vão no app. Se a TV fica num pé central e a base não tem fita no meio, use o campo **"Vão na base"**.

## 2. Ligação

| De | Para |
|---|---|
| Fonte +12V | +12V da fita (entrada **e** final da fita, para o brilho ficar uniforme) |
| Fonte GND | GND da fita **e** GND do ESP32 (**obrigatório** ter GND comum) |
| ESP32 GPIO13 | entrada A do 74AHCT125 → saída Y → resistor 330 Ω → **DI** da fita |
| 74AHCT125 VCC / GND | pino 5V (VIN) / GND do ESP32; pino OE em GND |
| **BI** da fita (só se ela tiver 4 fios) | GND. Fita de 3 fios: ignore |
| ESP32 USB | porta USB da TV |

- A WS2815 costuma funcionar até **sem** level shifter se o fio de dados for curto (< 30 cm). Se piscar ou mostrar cores erradas, coloque o level shifter.
- **Nunca** alimente a fita pelo ESP32. Os 12V vêm só da fonte.
- **TV em standby:** o app apaga a fita quando a tela desliga. Se a sua TV corta a USB antes disso, o ESP32 desliga e a fita pode ficar acesa na última cor. Para resolver de vez, alimente o ESP32 também pelos 12V com um conversor step-down (MP1584/LM2596 ajustado em 5V) no pino 5V/VIN. Confira se sua placa tem diodo entre a USB e o VIN (a maioria das DevKit tem). Assim o ESP32 continua ligado e o firmware apaga a fita sozinho após 2,5 s sem dados.

## 3. Gravar o firmware no ESP32

1. Instale o **Arduino IDE 2**.
2. Em *Arquivo → Preferências → URLs adicionais*, cole:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. *Gerenciador de placas* → instale **esp32 (Espressif Systems)**.
4. *Gerenciador de bibliotecas* → instale **FastLED**.
5. Abra `firmware/AmbilightESP32/AmbilightESP32.ino`, escolha a placa **ESP32 Dev Module**, a porta COM, e clique em **Carregar**.
6. Ao ligar, a fita pisca **vermelho → verde → azul**. Se as cores vierem trocadas, mude `COLOR_ORDER` para `RGB`.

No topo do arquivo dá para mudar o pino (`DATA_PIN`), o máximo de LEDs (`MAX_LEDS`, padrão 300) e a velocidade (`SERIAL_BAUD`, padrão 921600). Se mudar a velocidade, mude também no app.

## 4. Gerar o APK

**Opção A, sem instalar nada (GitHub):**
1. Crie um repositório no GitHub e envie esta pasta inteira.
2. Vá em **Actions**: o workflow `Build` compila o APK **e** o firmware sozinho.
3. Quando terminar, baixe o artefato **AmbilightTV-apk**.

**Opção B (Android Studio):** abra a pasta `android/`, espere sincronizar e use *Build → Build APK(s)*.

## 5. Instalar na TV

- Ative *Configurações → Sobre → Build* (clique 7 vezes) e permita **fontes desconhecidas** para o app que você vai usar para instalar.
- Jeitos fáceis: app **Send Files to TV** (do celular para a TV), um **pen drive** + gerenciador de arquivos, ou por ADB: `adb install AmbilightTV.apk`.

## 6. Configurar

1. Conecte o ESP32 na USB da TV. O Android pergunta *"Abrir Ambilight TV quando este dispositivo for conectado?"* → marque **usar por padrão** → OK.
2. No app, preencha a quantidade de LEDs de cada lado.
3. Ligue o **Modo teste**: esquerda **vermelha**, topo **verde**, direita **azul**, base **branca**, e o **primeiro LED pisca em rosa**.
   - Lados trocados ou ao contrário → ligue **Inverter sentido**.
   - Cores certas mas "deslocadas" → ajuste **Deslocamento do início** até cada cor ficar no lado certo.
4. Desligue o modo teste e ajuste brilho, saturação e suavização a gosto.

**Ligando sozinho:** com "usar por padrão" marcado, quando a TV liga, o app abre ao detectar o ESP32 e já pede a captura. Aperte **Iniciar agora** e ele some da frente. Esse aviso é exigido pelo Android a cada início. Em muitas TVs com Android 13 ou anterior dá para pular o aviso uma vez por ADB:
`adb shell appops set com.ambilight.tv PROJECT_MEDIA allow`

## 7. Ajustes

| Ajuste | O que faz |
|---|---|
| Brilho | Limita a intensidade |
| Saturação | >100% deixa as cores mais vivas (a luz na parede costuma lavar as cores) |
| Suavização | Maior = transições mais lentas e sem tremer; menor = reação instantânea (jogos) |
| Profundidade da borda | Quanto da imagem, a partir da borda, cada LED lê |
| Gama | Maior = escuros mais escuros e cores mais densas |
| Ignorar barras pretas | Detecta filmes 21:9 / vídeos 4:3 e usa só a imagem real |
| Atualizações por segundo | 30 é ótimo; 60 para jogos, se a TV aguentar |

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| "ESP32 não encontrado" | Cabo USB só de carga; troque por um de dados |
| Fita apagada na Netflix | DRM (veja o aviso lá em cima) |
| Primeiros LEDs piscando aleatórios | Falta GND comum, ou falta o level shifter / resistor |
| Cores vermelho/verde trocadas | `COLOR_ORDER` no firmware |
| Fita amarelada no fim | Alimente os 12V também no final da fita |
| Para de funcionar depois de um tempo | Algumas TVs matam apps em segundo plano: desative a otimização de bateria/memória para o app, se a TV tiver essa opção |

## Estrutura

```
firmware/AmbilightESP32/AmbilightESP32.ino   firmware (Arduino + FastLED, protocolo Adalight)
android/                                     app Android TV (Kotlin)
  app/src/main/java/com/ambilight/tv/
    MainActivity.kt      tela de configuração (controle remoto)
    AmbilightService.kt  captura da tela + envio pela USB
    ScreenAnalyzer.kt    cores das bordas e detecção de barras pretas
    Config.kt            configurações e mapa dos LEDs
    UsbLink.kt           serial USB (CH340, CP2102, FTDI, USB nativa)
.github/workflows/build.yml                  compila APK e firmware no GitHub
```
