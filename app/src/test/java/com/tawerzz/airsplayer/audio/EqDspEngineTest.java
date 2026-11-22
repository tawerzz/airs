package com.tawerzz.airsplayer.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class EqDspEngineTest {
    @Test public void flatSettingsAreTransparent() {
        EqDspEngine engine = new EqDspEngine();
        float[] samples = new float[4096];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(i * 0.071) * 0.7f;
        float[] expected = samples.clone();
        engine.processInPlace(samples, samples.length / 2, 2);
        for (int i = 0; i < samples.length; i++) assertEquals(expected[i], samples[i], 1.0e-6f);
    }

    @Test public void bassBoostMatchesAirs21HandlerValues() {
        EqDspEngine.Settings settings = EqDspEngine.Settings.bassBoost();
        assertEquals(5f, settings.gainsDb()[2], 0.0001f);
        assertEquals(120f, settings.frequenciesHz()[2], 0.0001f);
        assertEquals(0.75f, EqDspEngine.effectiveQ(2, settings.baseQ()[2], 5f), 0.0001f);
        for (int i = 0; i < EqDspEngine.FILTER_COUNT; i++) {
            if (i != 2) assertEquals(0f, settings.gainsDb()[i], 0.0001f);
        }
    }

    @Test public void qFollowsOriginalGainDependentRule() {
        float baseQ = 0.3f;
        assertEquals(0.6f, EqDspEngine.effectiveQ(3, baseQ, 0f), 0.0001f);
        assertEquals(0.3f, EqDspEngine.effectiveQ(3, baseQ, 30f), 0.0001f);
        assertEquals(baseQ, EqDspEngine.baseQFromEffective(3, 0.3f, 30f), 0.0001f);
        // The helper preserves the shelf node's Q parameter, but Web Audio's shelf equation ignores it.
        assertEquals(0.75f, EqDspEngine.effectiveQ(2, 0.75f, 15f), 0.0001f);
    }

    @Test public void bassShelfHasFiveDbLowFrequencyBoostAndIgnoresQ() {
        EqDspEngine engine = new EqDspEngine();
        EqDspEngine.Settings original = EqDspEngine.Settings.bassBoost();
        engine.setSettings(original);
        assertEquals(5.0, engine.getResponseDb(20.0), 0.2);
        assertEquals(2.5, engine.getResponseDb(120.0), 0.2);
        assertTrue(Math.abs(engine.getResponseDb(1000.0)) < 0.1);
        double before = engine.getResponseDb(120.0);
        engine.setSettings(original.withNode(2, 120f, 5f, 1.8f));
        assertEquals(before, engine.getResponseDb(120.0), 1.0e-6);
    }

    @Test public void changingFilterPresetPreservesOutputGain() {
        EqDspEngine.Settings settings = EqDspEngine.Settings.defaults().withVolume(0.37f);
        assertEquals(0.37f, settings.withBassBoostFilters().volume(), 0.0001f);
        assertEquals(0.37f, settings.withResetFilters().volume(), 0.0001f);
    }
}
