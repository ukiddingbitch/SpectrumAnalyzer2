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
- Peak detection with frequency and dB labels.

### Sound ID
- Offline sound identification powered by the bundled YAMNet model, with 521 sound classes.
- Shows a leading sound match and alternative predictions.
- Session summary chart showing the most detected sounds and their share of analyzed time.
- Export session summaries as PDF.

### My Sound ID
- Record a 10-second sound sample after a countdown, name it, and save it for future matching.
- Play or pause, delete, and scroll through saved recordings.
- Import and export ZIP archives containing recordings, names, and sound profiles.

### Tone Generator
- Built-in tone generator with adjustable frequency and volume.
- Sine, square, triangle, and sawtooth waveforms.
- Frequency presets and frequency sweep.

### Piano Keyboard
- Multi-touch piano keyboard with volume, octave, and reverb controls.
- Full-screen keyboard mode.
- Piano samples load in the background, prioritizing the middle octave.

### App Experience
- Audio analysis starts automatically after microphone permission is granted.
- Immersive full-screen interface with icon-based controls.

## Screenshots

Linear frequency scale

![Spectrum Analyzer 2 - Linear Spectrum](Screenshots/SpectrumAnalyzer2_lin_spectrum.png)

---

Logarithmic frequency scale for detailed low-frequency analysis

![Spectrum Analyzer 2 - Logarithmic Spectrum](Screenshots/SpectrumAnalyzer2_log_spectrum.png)

---

Real-time spectrogram showing frequency changes over time

![Spectrum Analyzer 2 - Spectrogram](Screenshots/SpectrumAnalyzer2_spectogram.png)

---

Frequency range selection

![Spectrum Analyzer 2 - Frequency Range Selection](Screenshots/SpectrumAnalyzer2_range_select.png)

---

Frequency range information

![Spectrum Analyzer 2 - FreqFrequency nge Information](Screenshots/SpectrumAnalyzer2_range_info.png)

---

Keys

![Spectrum Analyzer 2 - Musical Keys](Screenshots/SpectrumAnalyzer2_keys.png)

---

Tone generator

![Spectrum Analyzer 2 - Tone Generator](Screenshots/SpectrumAnalyzer2_tone_generator.png)

---

## Source Code

The complete source code is available in this repository.

## Android Compatibility

Minimum Android version: Android 8.0 Oreo (API 26).
Target SDK: API 37 (Android 17).
Supported devices: Android devices running API 26 or later, subject to compatibility testing.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
