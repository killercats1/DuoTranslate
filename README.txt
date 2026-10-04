1. Install Android Studio. File > Open > select this DuoTranslate folder (it creates the Gradle wrapper for you).
2. Let Gradle sync, then Build > Build APK(s). Find it under app/build/outputs/apk/debug/app-debug.apk
3. Install the APK on both phones (real devices, with Google Play services; emulators won't pair).
4. On each phone: pick your language. One taps Host, the other taps Join. Then both tap "Start talking".
5. First use needs internet once to download the translation models (about 30 MB each way).
