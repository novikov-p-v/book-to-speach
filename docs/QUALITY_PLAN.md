# Speech quality plan

This plan is meant to be run in a new Claude Code session. Start it with:

> Read docs/QUALITY_PLAN.md and do Phase N.

Do one phase per session. Each phase ends with passing tests and a commit.

## Context (current state, Nov 2026)

- **App:** Android, Kotlin, Jetpack Compose. On-device TTS runs through sherpa-onnx 1.13.8. Voices come in three families (Supertonic, Kokoro, Piper/VITS) and are listed in `app/src/main/assets/voices.json`.
- **Import pipeline:** `BookParsers` → `BookBuilder` → `TextNormalizer.normalize` → `SentenceSplitter.split` (in `text/TextProcessing.kt`) → `Chunk`s, which are stored with the book.
  - Splitting changes only affect books imported after the change.
- **Speech-only transforms** happen in `SpeechTextPreparer.prepare(text, lang)` (`text/RussianText.kt`). They run at synthesis time, and the displayed text stays unchanged.
  - Current order: `Abbreviations.expand` → ё restoration (ru, setting `restoreYo`).
  - `prepare` has three callers: `PlaybackController` (around line 224), `ExportWorker` (around line 74) and `VoicePreview`.
- **Pauses:**
  - Playback (`PlaybackController` ~226) and export (`ExportWorker` ~56): there is a fixed gap after a sentence (120 ms in export) and `settings.paragraphPauseMs` (default 400) after a paragraph end or heading.
  - The model's own leading and trailing silence is not trimmed.
- **Abbreviations** are done (`text/Abbreviations.kt`, `AbbreviationsTest.kt`):
  - Splitting: periods are protected with U+E000 before the BreakIterator runs.
  - Speech: ru/en expansions.
- **Tests:** JVM unit tests in `app/src/test/java/app/booktts/`, run with `./gradlew testDebugUnitTest`.
  - JVM tests use the JDK `BreakIterator` and do not have `android.icu`. Keep the text logic in pure Kotlin so it can be tested.
- **Conventions:**
  - Commit messages must not contain any Claude attribution or `Co-Authored-By` lines.
  - Every user-visible string goes in both `values/strings.xml` and `values-ru/strings.xml`.
  - Each setting is a `Settings` property plus a switch in the Voices screen, following the pattern of `restoreYo`.

## Phase 0: Test corpus and quick listening (small, do first)

Goal: hear what the voices actually do before changing anything.

1. Add `app/src/test/resources/speech_corpus_{ru,en}.txt`: about 40 hard lines each. Cover:
   - numbers, years, dates, times, %, currency
   - ordinals ("5-й", "XIX век", "1st")
   - ranges ("1941–1945")
   - units ("5 км", "10 kg")
   - Roman numerals in headings
   - dialogue dashes, ellipses, quotes, parentheses
   - ALL CAPS words, acronyms (СССР, NASA), URLs and e-mails
2. Add a test, `SpeechCorpusTest`, that runs the pure-Kotlin parts of `prepare` (abbreviations, and numbers once they exist) over the corpus and writes the before/after pairs to `build/speech_corpus_out.txt`. It is a review aid, not an assertion.
3. Debug builds only: add a "Speak custom text" field in VoicesScreen. It sends any text through `SpeechTextPreparer` and `VoicePreview`, so a line can be checked on each voice by ear.
4. Listen to the corpus on one voice of each family. Record which families already read digits correctly and how. Piper/espeak-ng expands digits, but only in the nominative case; Supertonic and Kokoro are unknown. Write the findings at the end of this file. They decide how far Phase 1 has to go.

## Phase 1: Numbers and symbols

Goal: say numbers as words, using correct forms where a simple rule can find them.

- **New file:** `text/Numbers.kt`, pure Kotlin.
  - `NumberSpeller`: cardinal and ordinal numbers for ru and en.
    - Russian needs gender (один/одна/одно, две тысячи) and case. Support at least nominative, genitive and prepositional for ordinals.
    - Write it by hand rather than using `android.icu.text.RuleBasedNumberFormat`, so it can be unit-tested and the Russian cases are under our control.
    - For other languages, use ICU `%spellout-cardinal` on the device through a small interface, or leave the digits as they are.
  - `Numbers.expand(text, lang)`: regex rules applied in order, most specific first:
    1. Times: `10:30` → "десять тридцать" / "ten thirty".
    2. Dates: `12.04.1961`, and "12 апреля 1961 г." (year as an ordinal in the genitive; this runs after `г.` is expanded).
    3. Year patterns, ru:
       - "в 1812 году" → prepositional ordinal
       - "1812 год" → nominative
       - "1941–1945 гг." / "в 1941–1945 годах"
    4. Ordinals with suffix:
       - ru: "5-й", "1-го", "21-м", where the suffix gives the case and gender
       - en: "1st", "22nd"
    5. Roman numerals: only next to век/глава/том/часть/Chapter/Part, or as a heading on its own line.
       - "XIX век" → "девятнадцатый век"; "Глава IV" → "Глава четвёртая".
    6. Percent, currency and units after a number (`%`, `₽ руб. $ € £`, `км м см мм кг г л ч мин с °C`): pick the plural form (1 рубль / 2 рубля / 5 рублей) and expand the unit.
       - Also move the "5 см." centimetre case out of `Abbreviations`.
    7. Decimals: "3,5" (ru) and "3.5" (en) → "три целых пять десятых" / "three point five".
    8. Grouped thousands ("1 000 000", "1,000,000") → cardinal.
    9. Long digit strings (more than 9 digits, phone numbers, ISBNs) → read digit by digit.
    10. Plain integers → cardinal in the nominative.
    11. Symbols: `№` → номер/number, `§` → параграф, `&` → и/and, `+`, `=`, `×`, `°`.
- **Wire-up:** `SpeechTextPreparer.prepare` order becomes abbreviations → numbers → ё.
  - Add the setting "Read numbers as words" (default on; ru: "Читать числа словами").
- **Tests:** `NumbersTest.kt`:
  - speller tables for 0–21, tens, hundreds, 1000/2000/5000, 1 000 000, 1 000 000 000
  - each rule above in ru and en
  - text without digits stays unchanged
- **Splitting:** check that "3.5", "12.04.1961" and "1." in numbered lists do not cause bad sentence breaks. If they do, add a protection rule in `Abbreviations.protect`.

## Phase 2: Text cleanup for speech

Goal: remove things that sound wrong. These are all speech-only, in `prepare`, unless stated otherwise.

- **ALL CAPS words** of more than 4 letters that are not known acronyms → lowercase, so voices don't spell them out or shout. Short caps tokens (СССР, NASA, ФСБ) are acronyms:
  - Russian acronyms → letter names (эс-эс-эс-эр) where the voice can't handle them.
  - Keep a small list of acronyms that are read as words (НАТО, ООН, NASA).
- **URLs and e-mails** → "ссылка" / "link", or drop them.
- **Brackets, quotes and dashes:**
  - Remove `[ ] { } * _ ~` and decorative characters.
  - Map the quote characters «»„“"' to plain quotes or nothing.
  - "..." → "…".
  - Russian dialogue: "— Привет, — сказал он." Make sure each dash turns into a pause (a comma) rather than being read aloud or ignored.
- **At import** (`TextNormalizer`, PDF only, `PdfParser`):
  - Join words hyphenated across lines ("пере-\nнос").
  - Drop repeated page headers, footers and bare page numbers.
  - Join lines broken in the middle of a sentence.
  - Add tests to `ParsingTest`.

## Phase 3: Pauses and rhythm

Goal: natural, even timing on every voice.

1. Trim the model's leading and trailing silence from each generated PCM chunk (amplitude threshold plus a few ms margin), then add our own gaps. This makes timing the same across voice families. Put the code in one helper used by both `PlaybackController` and `ExportWorker`; they currently duplicate the gap logic.
2. Choose the gap from how the chunk ends:
   - `.` → sentence gap
   - `?` / `!` → a little longer
   - `…` → longer still
   - `:` / `;` / `,` (a chunk split mid-sentence because of `MAX_CHUNK`) → short
   - heading → long, and a pause before the heading too
   - paragraph end → `paragraphPauseMs`
3. Optional: a "Pause length" setting (short / normal / long) that scales all the gaps.

## Phase 4: User pronunciation dictionary

Goal: let users fix whatever is left: names, foreign words, wrong stress.

- Global rules: "write → say", whole word, case-insensitive, optional language. Stored as JSON in `filesDir`.
- UI: a "Pronunciation" screen opened from Voices, with a list, add/edit/delete and a ▶ button that plays the replacement.
- Apply the rules first in `prepare`, before abbreviations and numbers, so user rules win.
- Optional: per-book rules.
- Optional: a long press on a sentence in the reader opens "Fix pronunciation…" with the words of that sentence filled in.

## Later / ideas (not planned yet)

- Russian stress for Supertonic and Kokoro: use `ru_dict.gz` to add stress marks, if those models accept them. Check this first in Phase 0.
- Detect the language of each paragraph in mixed-language books (`LanguageDetector`) and pick a voice for each language.
- Footnotes: read them inline at the end of the paragraph, or skip them (setting).

## Findings (fill in during Phase 0)

Phase 0 steps 1–3 are done: the corpora are in `app/src/test/resources/speech_corpus_{ru,en}.txt`, `./gradlew testDebugUnitTest` writes `app/build/speech_corpus_out.txt`, and debug builds have "Speak custom text (debug)" in Voices, which uses the default voice of the chosen language. Step 4 (listening) is still open.

- Piper / espeak-ng:
- Kokoro:
- Supertonic:
