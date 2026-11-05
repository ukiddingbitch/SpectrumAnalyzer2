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

<p align="center">
  <img src="Screenshots/linear.png" width="360" alt="Spectrum Analyzer 2 - Linear Spectrum">
  <img src="Screenshots/log.png" width="360" alt="Spectrum Analyzer 2 - Logarithmic Spectrum">
</p>

<p align="center">
  <img src="Screenshots/spectogram.png" width="360" alt="Spectrum Analyzer 2 - Spectrogram">
  <img src="Screenshots/SPL.png" width="360" alt="Spectrum Analyzer 2 - SPL">
</p>

### Sound ID
- Offline sound identification powered by the bundled YAMNet model, with 521 sound classes.
- Shows a leading sound match and alternative predictions.
- Session summary chart showing the most detected sounds and their share of analyzed time.
- Export session summaries as PDF.

<p align="center">
  <img src="Screenshots/soundid.png" width="220" alt="Spectrum Analyzer 2 - Sound ID">
  <img src="Screenshots/soundid2.png" width="220" alt="Spectrum Analyzer 2 - Sound ID 2">
</p>

### My Sound ID
- Record a 5- or 10-second sound sample after a countdown, name it, and save it for future matching.
- Play or pause, rename, delete, and scroll through saved recordings.
- Import WAV recordings.
- Import and export ZIP archives containing recordings, names, and sound profiles.

<p align="center">
  <img src="Screenshots/mysoundid.png" width="220" alt="Spectrum Analyzer 2 - My Sound ID">
</p>

### Tone Generator
- Built-in tone generator with adjustable frequency and volume.
- Sine, square, triangle, and sawtooth waveforms.
- Frequency presets and frequency sweep.
- White noise, pink noise, and DTMF tones.
- Peak-follow mode.
- Add custom frequency presets and delete them with a long press; built-in presets and PEAK are protected.
- Choose sweep direction and durations of 4, 8, 16, or 32 seconds.
- Select LEFT, RIGHT, or STEREO audio output.

<p align="center">
  <img src="Screenshots/tonegen.png" width="360" alt="Spectrum Analyzer 2 - Tone Generator">
</p>

### Piano Keyboard
- Multi-touch piano keyboard with volume, octave, and reverb controls.
- Full-screen keyboard mode.
- Piano samples load in the background, prioritizing the middle octave.

<p align="center">
  <img src="Screenshots/fullscalekeys.png" width="360" alt="Spectrum Analyzer 2 - Full-Scale Keys">
  <img src="Screenshots/keys.png" width="360" alt="Spectrum Analyzer 2 - Keys">
</p>

### App Experience
- Audio analysis starts automatically after microphone permission is granted.
- Immersive full-screen interface with icon-based controls.

---

## Download & Installation

Spectrum Analyzer2 is a free, open-source native Android application.

**Download the latest APK** from the GitHub Releases page:

[Download Spectrum Analyzer2](https://github.com/ukiddingbitch/SpectrumAnalyzer2/releases)

Alternatively, clone this repository and build the application using Android Studio.

## Source Code

The complete source code is available in this repository under the MIT License.

## Android Compatibility

- **Minimum Android version:** Android 8.0 Oreo (API 26).
- **Target SDK:** API 37 (Android 17).
- **Supported devices:** Android devices running API 26 or later, subject to compatibility testing.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
