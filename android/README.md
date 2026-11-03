# Flashcards for Android

The phone version of the desktop app: the same workbook, the same spaced repetition (FSRS), the
same deck rules and exercises, offline. A Kotlin port of the Python package in `../flashcards`,
written so the two schedule a word identically. The unit tests check this against numbers from
the Python `fsrs` package.

## Install

There is no Play Store listing; you install the APK file directly ("sideloading").

1. On GitHub, open the repository's **Actions** tab → the latest **Android** run → under
   *Artifacts*, download **flashcards-apk**. It is a zip with `Flashcards-<version>.apk` inside.
2. Copy the APK to the phone, or download it there, and tap it. Android asks once to allow
   installing apps from that source (your browser or file manager): allow it.
3. To update, install a newer APK the same way. Your settings and an unsaved session are kept.

Android 8.0 or newer. No permissions are requested.

## Your workbook

On first start, **Choose workbook** opens Android's file picker. Pick the same `.xlsx` the desktop
app uses: a copy on the phone (Downloads, Documents), or the file itself in Google Drive or
OneDrive through their apps. Picking it gives the app access to that one file; nothing else on the
phone is readable.

**Save** writes the grades into that file exactly as the desktop app does (columns L–R only,
with dynamic-array dashboards left untouched), after keeping a backup copy inside the app (the
newest 50 are kept). If the file changed since the session started, for example because you
reviewed on the laptop through OneDrive, Save refuses rather than overwrite it, and your grades
stay on screen.

If a storage app doesn't allow writing (some cloud apps open files read-only), copy the workbook
to the phone and choose the copy.

An unfinished session survives the app being closed or stopped by Android in the background:
it continues where you left it, as long as the workbook hasn't changed.

## Pronunciation

The web app uses the browser's speech engine. On the phone that is Android's **text-to-speech**
engine (usually *Speech Services by Google*), which speaks offline with the voices installed on
the phone. Nothing needs to be set up for languages the phone already has.

- **Start screen → Pronunciation** shows which voice is used for each language of the workbook.
- A missing language: **Install voices** opens the engine's voice list; download the language,
  then come back. The app notices the new voice.
- *Also use online voices* allows voices that need the internet (better quality for some
  languages). The text is then sent to the voice's provider, so it is off by default.
- **Listening** needs a voice for the word's language, as in the web app.

## What's the same, what differs

Same: deck modes (Due now, Topics, All topics, Difficult), new-word slots, weighted draw,
repeats, all exercises (flashcards, multiple choice, fill in the blank, type the word, listening,
sentence scramble, der/die/das, forms, mixed), suggested grades, the 1–5 buttons with their
previews, the summary, Light / Dark / Sepia / System themes.

Differs:

- Keyboard shortcuts are replaced by taps. The keyboard's **Done** key checks a typed answer.
- A flashcard turns over to show its answer.
- Backups and the review log (`reviews.csv`) are kept inside the app, not in `~/.flashcards`.
- The workbook's dates in column K are read as Excel date numbers. A date typed as text is
  ignored, as in the desktop app.

## Build it yourself

With [Android Studio](https://developer.android.com/studio): *Open* this `android` folder,
then *Run*. Or on the command line, with the Android SDK installed:

```
./gradlew testDebugUnitTest      # the unit tests (no phone needed)
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the debug key so it can be sideloaded. A Play Store release would
need its own signing key.

## Layout

```
app/src/main/java/com/fazelavb/flashcards/
  core/        pure Kotlin, unit-tested on the JVM; one file per Python module
    Models.kt      Card, language pair, confidence averaging       (models.py)
    Fsrs.kt        the FSRS-6 scheduler                             (the fsrs package)
    Srs.kt         schedule <-> columns M..R, due status            (srs.py)
    Xlsx.kt        a small read-only .xlsx reader                   (openpyxl's part)
    Workbook.kt    topic sheets -> cards                            (workbook.py)
    Writeback.kt   surgical cell edits in the .xlsx                 (writeback.py)
    Exercises.kt   the exercises and answer checking               (exercises.py)
    Session.kt     deck building, the review session               (session.py)
    Engine.kt      what each button does, as data for the screens  (service.py)
  data/        the workbook file (Storage Access Framework), settings, the saved session
  speech/      text-to-speech
  ui/          Jetpack Compose screens: start, review, summary
app/src/test/  unit tests; resources/make_fixtures.py makes the test workbooks
```
