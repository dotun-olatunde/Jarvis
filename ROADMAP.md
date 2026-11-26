# Jarvis' roadmap: where it stands and where it's going.

## Done in 0.2 
- **Wake word like "Hey Google":** an on-device openWakeWord model waits quietly. One beep, then it listens; after you go quiet it plays a closing beep. No more beep storm. Ships with the "Hey Jarvis" model as a stand-in. To wake on "Nova", train a model (see below) and drop it in as `app/src/main/assets/nova.onnx`.
- **Spoken welcome on first start,** with the steps to turn Nova on in accessibility settings and, if the switch is greyed out, to allow restricted settings. You can also say "open accessibility settings" or "open app settings".
- **Agentic tapping:** accessibility labels, then on-device OCR, then Gemini naming the unlabeled icons, then Gemini looking at the whole screenshot. Only the last two use the internet, and only after you tick the consent box. Never on a screen with a password.
- **Internet questions** (weather, news): when a sentence is a question about current facts, Nova asks Gemini with Google Search switched on and just speaks the answer, without opening a browser.
- **Gemini fix:** Google's model list changed. As of today `gemini-2.5-flash` is limited to accounts that used it before, and new projects are told to use `gemini-3.5-flash-lite` or `gemini-3.8-flash`. The app now asks Google which models your key can use, tries those, and the Test AI button reports each step with timings.

## Current versio 0.5
Jarvis is now the name of the app, although the open wake word model still needs to be checked for any licensing issues especially when going commercial with the app.
Achieved multi-lingual capabilities and can now perform several screen and online actions.

## To do, in suggested order

1. **Captcha and verification codes.** Read captcha text aloud in Jarvis' voice while you type with the reader. Read an SMS code, or copy it and paste it into the box.
2. **Password safety.** Read a password back only when headphones, Bluetooth or the earpiece are in use; otherwise ask you to plug in or lower the volume under about 20 percent.
3. **Offline screen understanding (Florence-2 family).** See the notes below.
4. **Play Store build.** Remove the restricted-settings step, add a privacy policy, and justify the accessibility permission.

## Research notes

**Wake word model for "Jarvis."** openWakeWord trains custom wake words from synthetic speech in a Google Colab notebook (github.com/dscripka/openWakeWord). The library code is Apache-2.0. I believe the pretrained models, including "Hey Jarvis," are CC BY-NC-SA, which means either we get another license or we train our own model. Picovoice Porcupine's free tier stopped working after 30 June 2026, so it is not an option.



**Florence-2 on the phone.** A Florence-2-based model called GoClick finds on-screen elements from a text description and runs fully on the phone with ONNX Runtime under an MIT license. It is about 300 MB in its smallest (INT8) form. It could replace the Gemini screenshot lookup, but as an optional download after install, not bundled in the app.

## Training "Jarvis" (when you are ready)
Open the openWakeWord training notebook in Colab, set the phrase to "Jarvis" (try "hey Jarvis" as well, which is easier for the model), run it, download the `.onnx` file, rename it `jarvis.onnx` and put it in `app/src/main/assets/`. I can walk you through it step by step.

## 0.4 additions
Name capture, spoken confirmations, hold/barge-in, idle check-ins, factual-question routing, kin contact lookup, calls with SIM choice,
SMS, WhatsApp auto-send, proactive form/button help, 9jaLingo voices, reply-language setting.

## Voice research notes
- 9jaLingo: hosted REST, works now (voices adeola_yo, aisha_ha, adaeze_ig, ada_pcm); pricing unknown, cold start returns 202 (Jarvis falls back to phone voice).
- YarnGPT: local Python model, no hosted API found, not phone-suitable.
- Meta MMS-TTS (yor/hau/ibo) via sherpa-onnx: possible free on-device option; check non-commercial license first.
