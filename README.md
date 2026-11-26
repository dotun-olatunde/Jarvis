# Jarvis

A voice assistant for Android, built first for blind and low-vision users, useful to everyone.
Say **"Hey Jarvis"**, hear one beep, then speak. Jarvis can press buttons, read the screen, fill forms,
open apps, call and text people, and answer questions, in English, Nigerian Pidgin, Yoruba, Igbo, Hausa,
French, Spanish, Portuguese, German and etc.

It works next to TalkBack or Jieshuo and does not depend on them.

## What it does

- **Wake word**: on-device "Hey Jarvis" (openWakeWord). One beep to start, one beep to end.
- **Intelligent conversations**
- **Barge-in**: say "hold on", "wait", "stop", "keep quiet" and Jarvis stops talking. It then stays quiet until you call its name
  ("Are you talking to me?" if it hears speech without the name).
- **Screen control**: tap, scroll, back, home, recents, notifications, type, fill fields. Labels come from the accessibility tree,
  then on-device OCR, then Gemini vision for icon-only buttons.
- **Proactive help**: offers to fill real forms (two or more fields, not search or message boxes), offers to name unlabeled buttons,
  and says what an unlabeled button is when your screen-reader focus lands on it.
- **Phone tasks without opening apps**: call (asks which SIM), SMS, WhatsApp.
- **Questions**: factual questions never open apps. Jarvis uses what is on screen if it answers, otherwise Google Search through Gemini.
- **Voices**: phone text-to-speech by default; optional yarnGPT and Meta MMS voices for natural Yoruba, Igbo, Hausa and Pidgin
  (fixed phrases only, cached on the phone).
- **Reply language**: match the language you spoke.

## Build

1. Android Studio (Koala or newer), JDK 17. Open this folder.
2. Ensure you save your keys in local.properties before building (see **[LIMITATIONS.md](LIMITATIONS.md)** for more context).
3. Run on a phone with Android 8 or newer (Android 11+ for screenshot features).
4. In the app: Step 1 turn on Jarvis in accessibility settings (if blocked: app info, three dots, *Allow restricted settings*),
   Step 2 allow permissions.

## Keys (Keys are built into the app, see below if you are a developer))

- **Gemini API key** (free tier at aistudio.google.com) for questions, chat and icon naming. Tick the consent box; nothing is sent without it,
  and never from a screen showing a password.
- **YARNGPT_API_KEY** for natural sounding Igbo since Meta MMS already handles Yoruba and Hausa.

**Important:** Write your API keys in **local.properties** and build it with the app and never commit your **local.properties** file.
## Architecture

| File | Role |
|---|---|
| `VoiceService` | wake word, listening sessions, idle timers, barge-in |
| `Brain` | decides what to do: rules, OCR, Gemini, contacts, calls, messages, proactive help |
| `Nlu`, `Pack`, `Matcher` | language packs in `assets/packs/*.json`; add a language by adding a file |
| `JarvisAccessibilityService` | reads the screen, clicks, types, screenshots |
| `Speaker`, `CloudVoice` | speech output (phone TTS or 9jaLingo) |
| `AiClient` | Gemini REST with model discovery and search grounding |
| `Kin` | family words across languages |

## Status and known gaps

Reading of incoming messages, account balance (USSD), captcha and verification-code reading, password-safe reading
(headphones only), and a custom "Jarvis" wake model are planned. 
Refining task memory, live object recognition and conversations are also planned.

See `[ROADMAP.md](ROADMAP.md)` and `[LIMITATIONS.md](LIMITATIONS.md)` for more information and context.

## Licenses

openWakeWord code is Apache-2.0; the pretrained "hey jarvis" model carries its own license terms that must be checked before commercial release.
ML Kit and ONNX Runtime follow their own licenses.
Meta MMS, Gemini and YarnGPT are equally governed by licenses that may restrict use of their models for anything beyond prototyping on the free tier.

## Collaborations

Everyone is welcome to collaborate on this project. All questions, concerns and discussions can also be directed to either [me](https://wa.me/+2348027834543) or olasumboeniola1@gmail.com

**Happy coding!**