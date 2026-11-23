# Tricky — assistente de voz para o Xiaomi Mi Smart Clock (LineageOS)

App Android para o Mi Smart Clock (X04G, 32 bits, Android 13) com assistente de voz em português.
O reconhecimento de voz é feito **no próprio relógio** (Vosk, modelo `vosk-model-small-pt`).

## O que faz

Diz **"Hey Tricky"**, espera pelo bip e pede:

| Pedido | Exemplo |
|---|---|
| Horas e data | "Que horas são?", "Que dia é hoje?" |
| Temporizador | "Temporizador de dez minutos" |
| Alarme | "Acorda-me às sete e meia" |
| Meteorologia | "Como está o tempo?", "Vai chover amanhã em Braga?" |
| Pesquisa | "O que é a fotossíntese?", "Pesquisa no Google receitas de bacalhau" |
| Home Assistant | "Liga a resistência", "Desliga a tomada" |
| Música (YouTube) | "Toca Xutos e Pontapés" |
| Rádios | "Põe a Rádio Comercial", "Toca a rádio Antena um" |
| Controlo | "Pára", "Continua", "Próxima", "Anterior", "Mais alto", "Volume a 50 por cento" |

## Instalar

O GitHub compila o APK automaticamente em cada alteração (Actions) e publica-o
na release `latest`. Instalar com:

```
adb install -r relogio-voz.apk
```

Na primeira vez a app descarrega o modelo de voz (~30 MB).

## Requisitos no relógio

- Uma voz de síntese em português (ex.: eSpeak NG ou RHVoice), escolhida em
  Definições → Sistema → Idiomas → Saída de texto para voz.
- Permissão "Mostrar sobre outras apps" para o Tricky (para abrir alarmes e o browser).
- Para o Home Assistant: endereço e token de longa duração nas definições da app.
