# Jarvis' — known limitations

A running list of gaps and trade-offs, kept up to date as Jarvis is built. Includes what doesn't work yet, why, and what it would take to fix.

## Cost

- **YarnGPT costs credits on every use.** Listening costs about 2 credits per spoken command (billed
  in 15-second blocks). Igbo speech costs credits per sentence. There is no free tier and no test key.
  All users share one account that we pay for.
- **Gemini runs on the free tier.** Daily request limits are shared by every user of the app. On the
  free tier, Google may use the data to improve its products.

## Internet

- **Igbo speech needs internet** (YarnGPT). Offline, Igbo is read with the English voice.
- **Listening in Nigerian languages and code-switching need internet** (YarnGPT). Offline, Jarvis
  falls back to the phone's recogniser, which handles one language at a time and is weak for
  Yorùbá, Igbo and Hausa.
- **Text typed without tone marks needs internet to be read correctly.** Most Nigerians type
  Yorùbá, Igbo and Hausa without tone marks (WhatsApp style). Without tones, words are ambiguous
  ("oko" = husband, hoe, farm, vehicle or spear), so a voice reads them flatly or wrongly. Gemini adds
  the tone marks from context before reading; offline, the plain text is read as it is.
- **Polishing dictated text needs internet** (Gemini fixes tones and word breaks). Offline, YarnGPT's
  transcript is typed as it is (it usually has most tone marks).
- **Adding tone marks makes plain-typed text slower to start:** Gemini took 1.6–4.9 seconds in our
  tests before reading began. If it takes over 6 seconds, Jarvis reads the text as written.
- **Gemini's tone marks aren't always right.** In our tests it once swapped a word for a similar one
  ("daadaa" → "dáradára") and once misspelled a tone, despite being told not to change words. Everyday
  Hausa is normally written without tone marks, so Hausa text is left mostly as it is.
- **Meta's voices download once, on Wi-Fi only** (about 220 MB), the first time the setup screen opens.
  Until then, Yorùbá and Hausa use YarnGPT (internet) or the English voice. This keeps the app smaller
  and never uses the user's mobile data, but a phone that never joins Wi-Fi never gets the voices.

## Voices

- **No on-phone Igbo voice exists.** Meta's MMS has no Igbo model, and Google's phone voices don't
  cover Igbo. Changing the phone's language to Igbo does not add a voice.
- **Android has no Yorùbá, Igbo or Hausa voice.** Without our own voices, the phone (and TalkBack)
  reads them with an English voice.
- **Meta's on-phone voices sound flatter than YarnGPT** and ignore punctuation, so Jarvis splits text
  into sentences and adds the pauses itself.
- **Meta's voices take about 108 MB of storage per language** (Yorùbá and Hausa, about 220 MB in
  total), and the app itself grew from 57 MB to 108 MB for the engine that runs them on the phone
  (built for both 64-bit and older 32-bit phones).
- **Meta's voices need a reasonably fast phone.** On a one-core emulator they took about as long to
  make speech as the speech lasts, plus about 1 second to load. Real phones have 4–8 cores and should
  be several times faster, but we haven't measured one. Short phrases (previews, Jarvis's usual
  replies) are saved after the first time, so they play instantly.
- **Only one Meta voice is kept in memory** (about 120 MB of RAM), so switching between Yorùbá and
  Hausa within one text reloads the voice (about 1 second on the emulator).
- **Short Igbo sentences are padded with dots** to reach YarnGPT's 20-character minimum. The dots
  aren't voiced, but it's a workaround, not a supported feature.
- **Meta's MMS voices are licensed for non-commercial use only** (CC BY-NC 4.0). Fine for preliminary testing and demos;
  a commercial release would need another voice or a licence from Meta.
- **YarnGPT has a wait before speaking:** about 2.5 seconds for one sentence, longer for longer text,
  because it makes the whole clip before sending any of it. Jarvis fetches one sentence at a time, so
  only the first sentence waits.
- **YarnGPT refuses text under 20 characters.** Short Igbo sentences are joined to their neighbours
  or padded (see below).
- **Saved YarnGPT audio lives in Android's cache folder.** If the phone runs low on storage, Android
  may clear it and the language previews download again.

## Understanding

- **Language detection is a best guess.** It combines special letters, common-word lists and Google's
  on-phone detector (ML Kit). Short sentences ("Ok o") are hard to place, so Jarvis uses the language of
  the surrounding text; a short sentence alone on screen is treated as English. The word lists are
  hand-made and small, so unusual wording may be misread.
- **The reply language follows the user's last sentence.** If a command has no clear language
  ("WhatsApp"), Jarvis keeps the previous one. A new conversation starts in the setup language.
- **The 15 other languages' command words and phrases were written by AI** and haven't been checked
  by native speakers (Chinese, French, German, Hindi, Indonesian, Italian, Japanese, Korean, Polish,
  Portuguese, Russian, Spanish, Thai, Turkish, Vietnamese). Natural ways of phrasing a command may be
  missing.
- **Only languages the phone can both speak and recognise are offered.** Google's voice covers about
  70 languages, but its built-in recognizer only about 16, so languages like Arabic and Swahili aren't
  listed. The recognizer for a language may need a one-time download on the phone.
- **Command matching for verb-last and no-space languages is basic.** Korean, Japanese, Hindi and
  Turkish put the verb last ("WhatsApp 열어"), and Chinese, Japanese and Thai don't use spaces. Jarvis
  handles the common patterns and strips simple grammar endings (을/를, を/に), but more complex
  sentences may not match.
- **Dictated text is typed without being read back first,** so a mistake in recognition or tone marks
  goes into the message unnoticed.
- **Nigerian Pidgin has no detector or voice of its own.** It is detected by common words and read
  with the Nigerian English voice.
- **A sentence with two commands only runs the first** ("open WhatsApp, then call Mama").
- **Code-switching inside YarnGPT has no published accuracy figures.** In our tests a mixed
  Pidgin/Yorùbá/English sentence came back correctly, but the test audio was synthetic. Research shows
  mixed English–Yorùbá speech is the weakest point for every system tested.

## Security and privacy

- **API keys are built into the app.** A determined person can pull them out of the APK. A real
  product would send requests through its own small server that holds the keys.
- **Voice and screen text leave the phone.** Gemini is always on (the setup screen shows a data
  notice, there is no opt-in); YarnGPT stays off until the user ticks its checkbox. YarnGPT's API docs say uploaded audio may be kept for up
  to 12 months, while its privacy policy says "only as long as necessary".
- **The API keys live in `local.properties`,** so every developer building the app needs their own
  copy of the keys.
