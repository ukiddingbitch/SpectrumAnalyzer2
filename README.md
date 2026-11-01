# Spectrum Analyzer2

An Android application developed in Kotlin that visualizes audio frequencies in a spectrum analyzer interface.

## Features

### Audio Analysis
- Real-time spectrum and spectrogram visualization.
- Linear and logarithmic frequency scales.
- Adjustable frequency range from 20 Hz to 20 kHz.
- Pinch-to-zoom graph controls.
- Pan the spectrum horizontally across frequencies and vertically through the dB range.
- Adjustable dB display range and peak hold.
- Frequency cursor with frequency and level information.
- Displays detected peak frequencies in Hz.
- Estimated SPL readout with reference calibration and a live level scale.

![Spectrum Analyzer 2 - Linear Spectrum](Screenshots/linear.png)

![Spectrum Analyzer 2 - Logarithmic Spectrum](Screenshots/log.png)

![Spectrum Analyzer 2 - Spectrogram](Screenshots/spectogram.png)

![Spectrum Analyzer 2 - SPL](Screenshots/SPL.png)

### Sound ID
- Offline sound identification powered by the bundled YAMNet model, with 521 sound classes.
- Shows a leading sound match and alternative predictions.
- Session summary chart showing the most detected sounds and their share of analyzed time.
- Export session summaries as PDF.

![Spectrum Analyzer 2 - Sound ID](Screenshots/soundid.png)

![Spectrum Analyzer 2 - Sound ID 2](Screenshots/soundid2.png)

### My Sound ID
- Record a 5- or 10-second sound sample after a countdown, name it, and save it for future matching.
- Play or pause, rename, delete, and scroll through saved recordings.
- Import WAV recordings.
- Import and export ZIP archives containing recordings, names, and sound profiles.

![Spectrum Analyzer 2 - My Sound ID](Screenshots/mysoundid.png)

### Tone Generator
- Built-in tone generator with adjustable frequency and volume.
- Sine, square, triangle, and sawtooth waveforms.
- Frequency presets and frequency sweep.
- White noise, pink noise, and DTMF tones.
- Peak-follow mode.
- Add custom frequency presets and delete them with a long press; built-in presets and PEAK are protected.
- Choose sweep direction and durations of 4, 8, 16, or 32 seconds.
- Select LEFT, RIGHT, or STEREO audio output.

![Spectrum Analyzer 2 - Tone Generator](Screenshots/tonegen.png)

### Piano Keyboard
- Multi-touch piano keyboard with volume, octave, and reverb controls.
- Full-screen keyboard mode.
- Piano samples load in the background, prioritizing the middle octave.

![Spectrum Analyzer 2 - Full-Scale Keys](Screenshots/fullscalekeys.png)

![Spectrum Analyzer 2 - Keys](Screenshots/keys.png)

### App Experience
- Audio analysis starts automatically after microphone permission is granted.
- Immersive full-screen interface with icon-based controls.

---

## Source Code

The complete source code is available in this repository.

## Android Compatibility

Minimum Android version: Android 8.0 Oreo (API 26).
Target SDK: API 37 (Android 17).
Supported devices: Android devices running API 26 or later, subject to compatibility testing.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
