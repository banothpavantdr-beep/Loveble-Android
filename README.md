# JARVIS – Personal Device Assistant (Android)

JARVIS is an Android voice assistant that can lock your screen on command ("Screen off", "Lock my phone").

## Background wake word ("Hey Jarvis")

JARVIS keeps listening for its wake word even after you leave the app, but **only after you turn it on yourself**.

How it works:

1. Open JARVIS and tap **ACTIVATE JARVIS**.
2. Allow **Microphone**, **Notifications**, and **Display over other apps** when asked.
3. A persistent notification shows "JARVIS is listening for "Hey Jarvis"". This is a normal Android foreground service (`JarvisOverlayService`, type `microphone`).
4. Press Home and use any other app.
5. Say **"Hey Jarvis"** or **"Jarvis"**. The JARVIS popup appears over the current screen and JARVIS says *"Yes, I'm listening."*
6. Say your command, for example **"Screen off"**. The wake word itself is never treated as a command.
7. JARVIS then goes back to waiting for the wake word.

To stop: tap **DEACTIVATE JARVIS** in the app, or tap **Deactivate** in the notification. This stops the service and the microphone.

Privacy: speech is handled by Android's built-in speech recognizer. JARVIS does not save or upload recordings.

Tips for reliable background listening:
- Enable **Screen lock** (device admin) in the app so "Screen off" works.
- On some phones (Xiaomi, Oppo, Vivo, Samsung and others), set JARVIS battery usage to **Unrestricted** so the system does not stop it.
- After a phone restart, open JARVIS and tap ACTIVATE JARVIS again (Android does not allow microphone services to start by themselves).

## Build

```
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (Codemagic workflow in codemagic.yaml)
```
