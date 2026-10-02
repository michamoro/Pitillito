# Pitillito

Pitillito is a playful Android app featuring a friendly striped-straw mascot. Ask a question by microphone and Pitillito will act curious, think dramatically, and answer in a sweet voice: “Mmm… mmm… no sé.”

## Features

- Tap **Preguntar a Pitillito** to start the microphone, then tap again to finish.
- Pitillito shows listening, curious, and analyzing expressions while the microphone is active. It occasionally opens its mouth in a surprised gesture and animates its mouth while speaking.
- The app measures how many seconds the microphone was active. It does not recognize or transcribe the spoken question.
- After listening, it displays one of 15 playful, generic phrases based on the measured duration, pauses for a short dramatic beat, and replies “Mmm… mmm… no sé.”
- Tap Pitillito to trigger a cheerful or disgusted reaction.
- Choose from five sample questions. A soft Spanish narrator reads the selected example before Pitillito answers.
- The launcher icon features a centered illustration of Pitillito.

## Privacy

- The app requests microphone permission because it opens the microphone while the user asks a question.
- Audio is read briefly in memory only to keep the microphone active and measure elapsed time. The app discards the audio as it is read; it does not transcribe, save, or send the recording or spoken question.
- After recording, the app shows a randomly chosen generic phrase with the elapsed seconds. The phrase is not a transcription and does not reveal what was said.
- The app does not request Internet access and has no account, advertising, analytics, backend, or persistent question storage.
- Android's installed text-to-speech engine generates spoken examples and replies. Available voices and sound quality depend on device settings.

## Install on Android

Download `Pitillito-Answers.apk`, open it on an Android phone, and confirm installation. Android may ask you to allow installs from the app used to open the file. Grant microphone access when prompted to use the voice-question interaction. The app requires Android 12 (API 31) or later.

## Build from source

1. Install Android Studio, Android SDK 36, and JDK 17.
2. Clone this repository and open it in Android Studio, or run the following command on Windows:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

3. Gradle writes the debug APK to `app/build/outputs/apk/debug/app-debug.apk`. Rename or copy that file to `Pitillito-Answers.apk` when preparing a shareable build.

## Tech stack

Kotlin, Jetpack Compose, Android `AudioRecord`, and Android TextToSpeech. No account or backend is required.
