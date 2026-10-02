# Pitillito

A small, playful Android app where you can talk to Pitillito, an animated straw who listens with curiosity, pauses for a moment, and always replies, “Mmm… I don’t know,” in a sweet, high-pitched voice.

## Features

- Ask questions in Spanish by voice and see a temporary transcript on screen.
- Tap Pitillito to trigger a cheerful or disgusted expression.
- Watch Pitillito react with curious, thinking, and speaking expressions.
- Try five sample questions narrated in a gentle Spanish voice before Pitillito replies.
- See a brief privacy notice when the app opens.

## Privacy

- The app does not request Internet access and has no accounts, ads, analytics, or question storage.
- The app requests speech recognition provided on the device. It does not upload audio or transcripts to an app server. If offline speech recognition is unavailable, the app explains this and does not fall back to cloud recognition.
- The transcript is shown temporarily and removed from the screen after the reply. The app does not write it to disk.
- Android's installed text-to-speech engine generates the spoken reply; the app does not record or store audio.
- The microphone button shows when listening starts and gives specific guidance if the offline speech service, language model, or microphone permission is unavailable.
- Android, the speech engine provider, and device settings manage their own services and data policies, which are outside this app's control.

## Install on Android

Download `app-universidad-test.apk`, open it on your phone, and confirm installation. Android may ask you to temporarily allow installs from the app you used to open the file. Grant microphone access when prompted to ask questions by voice. The app requires Android 12 (API 31) or later.

Spoken replies require a Spanish voice enabled in the device's text-to-speech engine. Sample questions do not require microphone access.

## Build from source

1. Install Android Studio, Android SDK 36, and JDK 17.
2. Clone this repository and open it in Android Studio, or run on Windows:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

3. The standard debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. `app-universidad-test.apk` is the separately prepared copy for installation and sharing.

## Tech stack

Kotlin, Jetpack Compose, on-device Android SpeechRecognizer, and Android TextToSpeech. No account or backend is required.
