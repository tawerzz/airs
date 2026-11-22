# Airs Player (Android)

Android local-music player with a native DSP port of the audio chain in the user-provided Airs Audio 2.1.0 extension archive.

## Implemented

- Local audio library discovery through Android MediaStore.
- Playback through AndroidX Media3 / ExoPlayer and MediaSessionService.
- Background playback, media notification, lock-screen controls, headset/Bluetooth route handling.
- 13 cascaded biquad filters in Airs order, then a separate output gain stage.
- Airs parameter ranges and gain-dependent Q rule for peaking filters.
- Airs Bass Boost settings: node 2 low-shelf, +5 dB at 120 Hz; other filter state reset as in the extension handler.
- Live FFT spectrum analyser (2048 samples, 0.85 smoothing, post-EQ/post-gain signal).
- Persistent EQ settings and user presets.

## DSP fidelity notes

The project uses the Web Audio BiquadFilterNode coefficient equations. In particular, the Web Audio low-shelf and high-shelf filters use slope S=1 and ignore their Q AudioParam in the filter equations, even though the extension stores a Q value for those nodes. The Android response curve therefore represents the coefficients that actually affect sound.

The app is an implementation port, not the original browser engine. Different sample rates, decoder output formats, floating-point rounding, and Android audio output paths mean sample-for-sample identity cannot be guaranteed without an A/B measurement against the browser on the same source material and conditions.

## Build

The project uses Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 17, Android SDK platform 36, and AndroidX Media3 1.11.1.

The included Colab notebook automates toolchain installation and builds an installable, debug-signed APK. The GitHub Actions workflow is an alternative cloud build route.

## Tests

`EqDspEngineTest` checks flat transparency, the Bass Boost values and shelf response, the peaking Q rule, and that filter presets do not change the output gain. These are JVM unit tests; this project has not been installed or exercised on a physical Android device in this environment.
