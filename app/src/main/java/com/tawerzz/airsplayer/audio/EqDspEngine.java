package com.tawerzz.airsplayer.audio;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DSP port of Airs Audio 2.1.0's Web Audio chain:
 * 13 serial BiquadFilterNodes followed by one GainNode.
 *
 * The original extension's parameter rules are kept in Settings.bassBoost(),
 * effectiveQ(), and the filter ordering/type selection below.
 */
public final class EqDspEngine {
    public static final int FILTER_COUNT = 13;
    public static final float[] DEFAULT_FREQUENCIES = {
            5f, 10f, 20f, 40f, 80f, 160f, 320f,
            640f, 1280f, 2560f, 5120f, 10240f, 20480f
    };
    public static final int FFT_SIZE = 2048;
    private static final int SPECTRUM_BINS = FFT_SIZE / 2;
    private static final float DEFAULT_PEAK_Q = 0.3f;
    private static final float DEFAULT_SHELF_Q = 0.75f;
    private static final float Q_FACTOR = 2.0f;
    private static final AtomicLong VERSION = new AtomicLong();

    public static final class Settings {
        private final float[] gainsDb;
        private final float[] frequenciesHz;
        private final float[] baseQ;
        private final float volume;
        private final long version;

        private Settings(float[] gainsDb, float[] frequenciesHz, float[] baseQ, float volume) {
            this.gainsDb = Arrays.copyOf(gainsDb, FILTER_COUNT);
            this.frequenciesHz = Arrays.copyOf(frequenciesHz, FILTER_COUNT);
            this.baseQ = Arrays.copyOf(baseQ, FILTER_COUNT);
            this.volume = Math.max(0.0001f, Math.min(31.62278f, volume));
            this.version = VERSION.incrementAndGet();
        }

        /** First-run values match the original offscreen graph's actual Q values. */
        public static Settings defaults() {
            float[] gain = new float[FILTER_COUNT];
            float[] freq = Arrays.copyOf(DEFAULT_FREQUENCIES, FILTER_COUNT);
            float[] q = new float[FILTER_COUNT];
            for (int i = 0; i < FILTER_COUNT; i++) {
                // Airs initially creates peaking nodes with Q=0.3 and shelf nodes with Q=0.75.
                // qCalculations derives baseQ from those effective values at 0 dB.
                q[i] = isShelf(i) ? DEFAULT_SHELF_Q : DEFAULT_PEAK_Q / 2f;
            }
            return new Settings(gain, freq, q, 1f);
        }

        /** Mirrors the original Reset Filters handler's ac({}, {}, {}) defaults. */
        public static Settings resetFilters() {
            float[] gain = new float[FILTER_COUNT];
            float[] freq = Arrays.copyOf(DEFAULT_FREQUENCIES, FILTER_COUNT);
            float[] q = new float[FILTER_COUNT];
            for (int i = 0; i < FILTER_COUNT; i++) q[i] = isShelf(i) ? DEFAULT_SHELF_Q : DEFAULT_PEAK_Q;
            return new Settings(gain, freq, q, 1f);
        }

        /** Exact values created by Airs Audio 2.1.0's Bass Boost button handler. */
        public static Settings bassBoost() {
            float[] gain = new float[FILTER_COUNT];
            float[] freq = Arrays.copyOf(DEFAULT_FREQUENCIES, FILTER_COUNT);
            float[] q = new float[FILTER_COUNT];
            for (int i = 0; i < FILTER_COUNT; i++) q[i] = isShelf(i) ? DEFAULT_SHELF_Q : DEFAULT_PEAK_Q;
            gain[2] = 5f;
            freq[2] = 120f;
            return new Settings(gain, freq, q, 1f);
        }

        public float[] gainsDb() { return Arrays.copyOf(gainsDb, FILTER_COUNT); }
        public float[] frequenciesHz() { return Arrays.copyOf(frequenciesHz, FILTER_COUNT); }
        public float[] baseQ() { return Arrays.copyOf(baseQ, FILTER_COUNT); }
        public float volume() { return volume; }
        public long version() { return version; }

        public Settings withNode(int index, float frequencyHz, float gainDb, float newBaseQ) {
            checkIndex(index);
            float[] g = gainsDb();
            float[] f = frequenciesHz();
            float[] q = baseQ();
            f[index] = clamp(frequencyHz, 1f, 21500f);
            g[index] = clamp(gainDb, -30f, 30f);
            q[index] = clamp(newBaseQ, 0.1f, 2f);
            return new Settings(g, f, q, volume);
        }

        public Settings withVolume(float newVolume) {
            return new Settings(gainsDb, frequenciesHz, baseQ, newVolume);
        }

        /** Apply the original Bass Boost filter state without touching the separate output GainNode. */
        public Settings withBassBoostFilters() {
            Settings preset = Settings.bassBoost();
            return new Settings(preset.gainsDb, preset.frequenciesHz, preset.baseQ, volume);
        }

        /** Reset the 13 filters while preserving the independent output GainNode setting. */
        public Settings withResetFilters() {
            Settings preset = Settings.resetFilters();
            return new Settings(preset.gainsDb, preset.frequenciesHz, preset.baseQ, volume);
        }
    }

    private volatile Settings settings = Settings.defaults();
    private Settings appliedSettings;
    private int sampleRate = 48000;
    private int channelCount = 2;
    private Biquad[] filters = new Biquad[FILTER_COUNT];

    private final Object spectrumLock = new Object();
    private final float[] spectrumRing = new float[FFT_SIZE];
    private final float[] spectrumSmoothed = new float[SPECTRUM_BINS];
    private int spectrumWrite;
    private int spectrumCount;

    public EqDspEngine() {
        configure(48000, 2);
    }

    public Settings getSettings() { return settings; }
    public int getSampleRate() { return sampleRate; }

    public void setSettings(Settings newSettings) {
        if (newSettings != null) settings = newSettings;
    }

    /** Clear filter delay lines and analyser history at a media-stream boundary. */
    public void resetStreamState() {
        for (Biquad filter : filters) filter.reset();
        synchronized (spectrumLock) {
            Arrays.fill(spectrumRing, 0f);
            Arrays.fill(spectrumSmoothed, 0f);
            spectrumWrite = 0;
            spectrumCount = 0;
        }
    }

    public void configure(int newSampleRate, int newChannelCount) {
        sampleRate = Math.max(8000, newSampleRate);
        channelCount = Math.max(1, Math.min(8, newChannelCount));
        filters = new Biquad[FILTER_COUNT];
        for (int i = 0; i < FILTER_COUNT; i++) filters[i] = new Biquad(channelCount);
        appliedSettings = null;
        synchronized (spectrumLock) {
            Arrays.fill(spectrumRing, 0f);
            Arrays.fill(spectrumSmoothed, 0f);
            spectrumWrite = 0;
            spectrumCount = 0;
        }
        updateCoefficientsIfNeeded(settings);
    }

    /** Processes interleaved PCM float samples in-place. No limiter/compressor is inserted. */
    public void processInPlace(float[] pcm, int frames, int channels) {
        if (pcm == null || frames <= 0 || channels <= 0) return;
        Settings current = settings;
        updateCoefficientsIfNeeded(current);
        int actualChannels = Math.min(channels, channelCount);
        float volume = current.volume;
        for (int frame = 0; frame < frames; frame++) {
            double mono = 0.0;
            int offset = frame * channels;
            for (int ch = 0; ch < channels; ch++) {
                float value = pcm[offset + ch];
                if (ch < actualChannels) {
                    for (int i = 0; i < FILTER_COUNT; i++) value = filters[i].process(ch, value);
                    value *= volume;
                }
                pcm[offset + ch] = value;
                mono += value;
            }
            appendSpectrumSample((float) (mono / channels));
        }
    }

    public float[] getSpectrum() {
        float[] time = new float[FFT_SIZE];
        synchronized (spectrumLock) {
            int start = spectrumWrite;
            for (int i = 0; i < FFT_SIZE; i++) time[i] = spectrumRing[(start + i) % FFT_SIZE];
        }

        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            // Web Audio AnalyserNode uses a Blackman window for its FFT.
            double phase = 2.0 * Math.PI * i / (FFT_SIZE - 1);
            double window = 0.42 - 0.5 * Math.cos(phase) + 0.08 * Math.cos(2.0 * phase);
            re[i] = time[i] * window;
        }
        fft(re, im);

        float[] result = new float[SPECTRUM_BINS];
        synchronized (spectrumLock) {
            for (int i = 0; i < SPECTRUM_BINS; i++) {
                double magnitude = Math.hypot(re[i], im[i]) * (2.0 / FFT_SIZE);
                double db = 20.0 * Math.log10(Math.max(1.0e-8, magnitude));
                // AnalyserNode's default byte range is -100 dB to -30 dB.
                float normalized = clamp((float) ((db + 100.0) / 70.0), 0f, 1f);
                spectrumSmoothed[i] = 0.85f * spectrumSmoothed[i] + 0.15f * normalized;
                result[i] = spectrumSmoothed[i];
            }
        }
        return result;
    }

    /** Combined cascade response in dB, used to draw the EQ response curve. */
    public double getResponseDb(double frequencyHz) {
        Settings s = settings;
        double freq = Math.max(1.0, Math.min(frequencyHz, Math.min(21500.0, sampleRate * 0.499)));
        double totalDb = 0.0;
        for (int i = 0; i < FILTER_COUNT; i++) {
            float gain = s.gainsDb[i];
            if (Math.abs(gain) < 1.0e-6f) continue;
            float q = effectiveQ(i, s.baseQ[i], gain);
            Coeff c = coefficients(i, s.frequenciesHz[i], gain, q, sampleRate);
            double w = 2.0 * Math.PI * freq / sampleRate;
            double cos1 = Math.cos(w), sin1 = -Math.sin(w);
            double cos2 = Math.cos(2.0 * w), sin2 = -Math.sin(2.0 * w);
            double nr = c.b0 + c.b1 * cos1 + c.b2 * cos2;
            double ni = c.b1 * sin1 + c.b2 * sin2;
            double dr = 1.0 + c.a1 * cos1 + c.a2 * cos2;
            double di = c.a1 * sin1 + c.a2 * sin2;
            double mag = Math.hypot(nr, ni) / Math.max(1.0e-12, Math.hypot(dr, di));
            totalDb += 20.0 * Math.log10(Math.max(1.0e-12, mag));
        }
        return totalDb;
    }

    public static float effectiveQ(int index, float baseQ, float gainDb) {
        if (isShelf(index)) return baseQ;
        double scale = Math.pow(Q_FACTOR, 1.0 - Math.abs(gainDb) / 30.0);
        return (float) (baseQ * scale);
    }

    public static float baseQFromEffective(int index, float effectiveQ, float gainDb) {
        if (isShelf(index)) return effectiveQ;
        double scale = Math.pow(Q_FACTOR, 1.0 - Math.abs(gainDb) / 30.0);
        return (float) (effectiveQ / scale);
    }

    public static float volumeFromDb(float db) {
        return (float) Math.pow(10.0, clamp(db, -80f, 30f) / 20.0);
    }

    public static float volumeDbFromSlider(float normalized) {
        float v = clamp(normalized, 0f, 1f);
        if (v == 0f) return -80f;
        return v <= 0.75f ? -30f + (v / 0.75f) * 30f : ((v - 0.75f) / 0.25f) * 30f;
    }

    private void updateCoefficientsIfNeeded(Settings s) {
        if (s == appliedSettings) return;
        for (int i = 0; i < FILTER_COUNT; i++) {
            float q = effectiveQ(i, s.baseQ[i], s.gainsDb[i]);
            filters[i].setCoefficients(coefficients(i, s.frequenciesHz[i], s.gainsDb[i], q, sampleRate));
        }
        appliedSettings = s;
    }

    private void appendSpectrumSample(float value) {
        synchronized (spectrumLock) {
            spectrumRing[spectrumWrite] = value;
            spectrumWrite = (spectrumWrite + 1) % FFT_SIZE;
            if (spectrumCount < FFT_SIZE) spectrumCount++;
        }
    }

    private static Coeff coefficients(int index, double frequency, double gainDb, double q, int sampleRate) {
        double nyquist = sampleRate * 0.5;
        double f = Math.max(1.0, Math.min(frequency, nyquist * 0.999));
        double w0 = 2.0 * Math.PI * f / sampleRate;
        double sin = Math.sin(w0), cos = Math.cos(w0);
        double A = Math.pow(10.0, gainDb / 40.0);
        double alpha = sin / (2.0 * Math.max(0.0001, q));
        double b0, b1, b2, a0, a1, a2;

        if (index == 2) { // Web Audio lowshelf: S=1, so the Biquad Q AudioParam is unused.
            double sqrtA = Math.sqrt(A);
            double alphaS = sin * 0.5 * Math.sqrt(2.0);
            b0 = A * (A + 1.0 - (A - 1.0) * cos + 2.0 * sqrtA * alphaS);
            b1 = 2.0 * A * (A - 1.0 - (A + 1.0) * cos);
            b2 = A * (A + 1.0 - (A - 1.0) * cos - 2.0 * sqrtA * alphaS);
            a0 = A + 1.0 + (A - 1.0) * cos + 2.0 * sqrtA * alphaS;
            a1 = -2.0 * (A - 1.0 + (A + 1.0) * cos);
            a2 = A + 1.0 + (A - 1.0) * cos - 2.0 * sqrtA * alphaS;
        } else if (index == 12) { // Web Audio highshelf: S=1, so the Biquad Q AudioParam is unused.
            double sqrtA = Math.sqrt(A);
            double alphaS = sin * 0.5 * Math.sqrt(2.0);
            b0 = A * (A + 1.0 + (A - 1.0) * cos + 2.0 * sqrtA * alphaS);
            b1 = -2.0 * A * (A - 1.0 + (A + 1.0) * cos);
            b2 = A * (A + 1.0 + (A - 1.0) * cos - 2.0 * sqrtA * alphaS);
            a0 = A + 1.0 - (A - 1.0) * cos + 2.0 * sqrtA * alphaS;
            a1 = 2.0 * (A - 1.0 - (A + 1.0) * cos);
            a2 = A + 1.0 - (A - 1.0) * cos - 2.0 * sqrtA * alphaS;
        } else { // Web Audio "peaking".
            b0 = 1.0 + alpha * A;
            b1 = -2.0 * cos;
            b2 = 1.0 - alpha * A;
            a0 = 1.0 + alpha / A;
            a1 = -2.0 * cos;
            a2 = 1.0 - alpha / A;
        }

        if (Math.abs(a0) < 1.0e-20) a0 = 1.0;
        return new Coeff(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static final class Coeff {
        final double b0, b1, b2, a1, a2;
        Coeff(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2;
        }
    }

    private static final class Biquad {
        private final double[] z1;
        private final double[] z2;
        private Coeff c = new Coeff(1, 0, 0, 0, 0);
        Biquad(int channels) { z1 = new double[channels]; z2 = new double[channels]; }
        void setCoefficients(Coeff next) { c = next; }
        float process(int channel, float x) {
            double y = c.b0 * x + z1[channel];
            z1[channel] = c.b1 * x - c.a1 * y + z2[channel];
            z2[channel] = c.b2 * x - c.a2 * y;
            return (float) y;
        }
        void reset() { Arrays.fill(z1, 0.0); Arrays.fill(z2, 0.0); }
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenRe = Math.cos(angle), wLenIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0, wIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    double vr = re[v] * wRe - im[v] * wIm;
                    double vi = re[v] * wIm + im[v] * wRe;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr; im[u] += vi;
                    double nextRe = wRe * wLenRe - wIm * wLenIm;
                    wIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nextRe;
                }
            }
        }
    }

    private static boolean isShelf(int index) { return index == 2 || index == 12; }
    private static void checkIndex(int i) { if (i < 0 || i >= FILTER_COUNT) throw new IllegalArgumentException("filter index: " + i); }
    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
}
