package com.tawerzz.airsplayer.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DspHolder {
    val engine = EqDspEngine()
    private const val PREFS = "airs_player_audio"
    private const val SETTINGS = "settings_v1"

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SETTINGS, null)
            ?: return
        try {
            val json = JSONObject(raw)
            val base = EqDspEngine.Settings.defaults()
            val gains = json.optJSONArray("gain") ?: return
            val freqs = json.optJSONArray("frequency") ?: return
            val q = json.optJSONArray("baseQ") ?: return
            var settings = base
            for (i in 0 until EqDspEngine.FILTER_COUNT) {
                settings = settings.withNode(
                    i,
                    freqs.optDouble(i, EqDspEngine.DEFAULT_FREQUENCIES[i].toDouble()).toFloat(),
                    gains.optDouble(i, 0.0).toFloat(),
                    q.optDouble(i, if (i == 2 || i == 12) 0.75 else 0.15).toFloat()
                )
            }
            settings = settings.withVolume(json.optDouble("volume", 1.0).toFloat())
            engine.setSettings(settings)
        } catch (_: Exception) {
            // Corrupt preference data is ignored; safe flat defaults remain active.
        }
    }

    fun save(context: Context) {
        val s = engine.settings
        val json = JSONObject()
            .put("gain", JSONArray(s.gainsDb().toList()))
            .put("frequency", JSONArray(s.frequenciesHz().toList()))
            .put("baseQ", JSONArray(s.baseQ().toList()))
            .put("volume", s.volume().toDouble())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SETTINGS, json.toString()).apply()
    }
}
