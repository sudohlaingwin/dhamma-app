# Dhamma Audio App

Myanmar Dhamma talks app — rebuilds
https://www.dhammadownload.com/audioinmyanmar.htm as a neat native Android
app. All sayadaws, grouped by tradition; talks stream and download from the
original dhammadownload.com MP3 links (never rehosted).

- Native Android, Java, framework Views — no Gradle, no Compose, no AndroidX
- minSdk 24, targetSdk 34
- Package `com.h2h.dhamma`
- Features: Room altar with 6 themes, real queue, mini-player, downloaded
  library, favourites, resume-from-position, sleep timer, data saver,
  home-screen widget, Wi-Fi auto-download

## Build

Needs JDK 17 + Android SDK build-tools 34.0.0. From the project root:

```sh
bash build.sh
```

The script runs aapt2 → javac → d8 → zipalign → apksigner and writes the
signed APK. Toolchain paths in `build.sh` point at the author's machine;
adjust `JAVA_HOME` and the SDK path for yours.

## Credit

Talks: https://www.dhammadownload.com/audioinmyanmar.htm
