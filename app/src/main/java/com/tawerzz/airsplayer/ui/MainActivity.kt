package com.tawerzz.airsplayer.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.tawerzz.airsplayer.audio.DspHolder
import com.tawerzz.airsplayer.audio.EqDspEngine
import com.tawerzz.airsplayer.playback.MusicPlaybackService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.log10
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val uri: Uri,
        val artworkUri: Uri?
    )

    private val bg = Color.rgb(17, 21, 29)
    private val panel = Color.rgb(28, 35, 47)
    private val accent = Color.rgb(101, 214, 180)
    private val muted = Color.rgb(156, 169, 184)
    private val text = Color.rgb(239, 243, 247)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracks = mutableListOf<Track>()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var currentSection = "library"

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var libraryView: LinearLayout
    private lateinit var eqView: LinearLayout
    private lateinit var eqScrollView: ScrollView
    private lateinit var listView: ListView
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var graph: EqGraphView
    private lateinit var selectedNodeText: TextView
    private lateinit var qSeek: SeekBar
    private lateinit var outputSeek: SeekBar
    private lateinit var outputText: TextView
    private lateinit var currentTitle: TextView
    private lateinit var currentArtist: TextView
    private lateinit var currentArtwork: ImageView
    private lateinit var playButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var presetSpinner: Spinner
    private var suppressSeek = false
    private var suppressPreset = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            updatePlayerUi()
            if (::graph.isInitialized && currentSection == "eq") {
                graph.setSpectrum(DspHolder.engine.spectrum)
            }
            mainHandler.postDelayed(this, if (currentSection == "eq") 40L else 300L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayerUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        DspHolder.load(this)
        buildUi()
        connectPlayer()
        mainHandler.post(progressUpdater)
        checkAudioPermissionAndScan()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("AIRS PLAYER", 21f, text, true)
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        val status = label("LOCAL AUDIO", 10f, accent, true).apply { letterSpacing = 0.12f }
        header.addView(status)
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(10))
        }
        tabs.addView(actionButton("MUSIC") { showSection("library") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(space(dp(8), dp(1)))
        tabs.addView(actionButton("EQUALIZER") { showSection("eq") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(tabs)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildLibraryView()
        buildEqView()
        showSection("library")
        root.addView(buildPlayerPanel())
        setContentView(root)
    }

    private fun buildLibraryView() {
        libraryView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        row.addView(label("YOUR MUSIC", 13f, text, true), LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(actionButton("SCAN AGAIN") { checkAudioPermissionAndScan() }, LinearLayout.LayoutParams(dp(122), dp(36)))
        libraryView.addView(row)

        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(5)
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(bg)
        }
        trackAdapter = TrackAdapter(this)
        listView.adapter = trackAdapter
        listView.setOnItemClickListener { _, _, position, _ -> playTrack(position) }
        libraryView.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildEqView() {
        eqView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        eqScrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(eqView, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label("13-NODE GRAPHIC EQ", 13f, text, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        heading.addView(label("AIRS 2.1.0 DSP", 10f, muted, false))
        eqView.addView(heading)

        graph = EqGraphView(this).apply {
            onSettingsChanged = { settings, commit ->
                DspHolder.engine.setSettings(settings)
                updateNodeDetails()
                if (commit) DspHolder.save(this@MainActivity)
            }
            onNodeSelected = { updateNodeDetails() }
        }
        eqView.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        selectedNodeText = label("NODE 3 · 20 Hz · +0.0 dB · Q 0.75", 12f, text, true).apply {
            setPadding(dp(5), dp(8), dp(5), dp(2))
        }
        val nodePicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nodePicker.addView(actionButton("‹") {
            graph.selectNode((graph.selectedNode() - 1 + EqDspEngine.FILTER_COUNT) % EqDspEngine.FILTER_COUNT)
        }, LinearLayout.LayoutParams(dp(42), dp(38)))
        nodePicker.addView(selectedNodeText, LinearLayout.LayoutParams(0, dp(42), 1f))
        nodePicker.addView(actionButton("›") {
            graph.selectNode((graph.selectedNode() + 1) % EqDspEngine.FILTER_COUNT)
        }, LinearLayout.LayoutParams(dp(42), dp(38)))
        eqView.addView(nodePicker)

        val qLabel = label("FILTER WIDTH (Q) · SHELF FILTERS IGNORE Q", 10f, muted, true)
        qLabel.setPadding(dp(2), dp(5), 0, 0)
        eqView.addView(qLabel)
        qSeek = SeekBar(this).apply {
            max = 190
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val idx = graph.selectedNode()
                    val s = DspHolder.engine.settings
                    val baseQ = 0.1f + (progress / 190f) * 1.9f
                    val updated = s.withNode(idx, s.frequenciesHz()[idx], s.gainsDb()[idx], baseQ)
                    DspHolder.engine.setSettings(updated)
                    updateNodeDetails()
                    graph.invalidate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { DspHolder.save(this@MainActivity) }
            })
        }
        eqView.addView(qSeek)

        val bassReset = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        bassReset.addView(actionButton("BASS BOOST +5 dB") {
            DspHolder.engine.setSettings(DspHolder.engine.settings.withBassBoostFilters())
            DspHolder.save(this)
            graph.selectNode(2)
            updateNodeDetails()
            updateOutputControl()
            graph.invalidate()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        bassReset.addView(space(dp(8), dp(1)))
        bassReset.addView(actionButton("RESET EQ") {
            DspHolder.engine.setSettings(DspHolder.engine.settings.withResetFilters())
            DspHolder.save(this)
            graph.selectNode(2)
            updateNodeDetails()
            updateOutputControl()
            graph.invalidate()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        eqView.addView(bassReset)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetSpinner = Spinner(this)
        presetRow.addView(presetSpinner, LinearLayout.LayoutParams(0, dp(42), 1f))
        presetRow.addView(space(dp(8), dp(1)))
        presetRow.addView(actionButton("SAVE PRESET") { showSavePresetDialog() }, LinearLayout.LayoutParams(dp(124), dp(40)))
        eqView.addView(presetRow)
        reloadPresetSpinner()

        val outTitle = label("OUTPUT GAIN", 10f, muted, true).apply { setPadding(dp(2), dp(8), 0, 0) }
        eqView.addView(outTitle)
        outputText = label("0.0 dB", 11f, text, true)
        eqView.addView(outputText)
        outputSeek = SeekBar(this).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val db = EqDspEngine.volumeDbFromSlider(progress / 100f)
                    val next = DspHolder.engine.settings.withVolume(EqDspEngine.volumeFromDb(db))
                    DspHolder.engine.setSettings(next)
                    outputText.text = String.format(Locale.US, "%+.1f dB", db)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { DspHolder.save(this@MainActivity) }
            })
        }
        eqView.addView(outputSeek)

        eqView.addView(label("No limiter or compressor is added: the signal path follows Airs Audio.", 10f, muted, false).apply {
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })
        updateNodeDetails()
        updateOutputControl()
    }

    private fun buildPlayerPanel(): View {
        val panelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16)
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(38, 48, 62), 10)
            setImageResource(android.R.drawable.ic_media_play)
        }
        infoRow.addView(currentArtwork, LinearLayout.LayoutParams(dp(48), dp(48)))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        currentTitle = label("Choose a track", 14f, text, true)
        currentArtist = label("Local music library", 11f, muted, false)
        textColumn.addView(currentTitle)
        textColumn.addView(currentArtist)
        infoRow.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panelLayout.addView(infoRow)
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimeText()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val c = controller ?: return
                    val duration = c.duration
                    if (duration > 0) c.seekTo((duration * (seekBar?.progress ?: 0) / 1000).coerceIn(0, duration))
                }
            })
        }
        panelLayout.addView(seekBar)
        timeText = label("0:00 / 0:00", 10f, muted, false).apply { gravity = Gravity.CENTER_HORIZONTAL }
        panelLayout.addView(timeText)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("PREV") { controller?.seekToPreviousMediaItem() }, LinearLayout.LayoutParams(dp(76), dp(38)))
        controls.addView(space(dp(12), dp(1)))
        playButton = actionButton("PLAY") { togglePlayback() }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(100), dp(40)))
        controls.addView(space(dp(12), dp(1)))
        controls.addView(actionButton("NEXT") { controller?.seekToNextMediaItem() }, LinearLayout.LayoutParams(dp(76), dp(38)))
        panelLayout.addView(controls)
        return panelLayout
    }

    private fun showSection(section: String) {
        currentSection = section
        content.removeAllViews()
        content.addView(if (section == "eq") eqScrollView else libraryView)
        if (section == "eq") {
            updateNodeDetails()
            updateOutputControl()
        }
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                updatePlayerUi()
            } catch (e: Exception) {
                Toast.makeText(this, "Player connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, Executor { command -> runOnUiThread(command) })
    }

    private fun checkAudioPermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            scanMusic()
        } else {
            requestPermissions(arrayOf(permission), 301)
        }
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanMusic()
            else Toast.makeText(this, "Allow audio access to show local tracks", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanMusic() {
        val found = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            sort
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol)?.takeIf(String::isNotBlank) ?: "Unknown track"
                val artist = it.getString(artistCol)?.takeIf(String::isNotBlank) ?: "Unknown artist"
                val duration = it.getLong(durationCol)
                val albumId = it.getLong(albumCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val artwork = if (albumId > 0) ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ) else null
                found += Track(id, title, artist, duration, uri, artwork)
            }
        }
        tracks.clear()
        tracks.addAll(found)
        trackAdapter.notifyDataSetChanged()
        if (tracks.isEmpty()) Toast.makeText(this, "No music files found on the device", Toast.LENGTH_SHORT).show()
    }

    private fun playTrack(position: Int) {
        val c = controller ?: run {
            Toast.makeText(this, "Player is still starting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        if (tracks.isEmpty()) return
        val items = tracks.map { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .apply { track.artworkUri?.let { setArtworkUri(it) } }
                .build()
            MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(metadata).build()
        }
        c.setMediaItems(items, position, 0L)
        c.prepare()
        c.play()
        showSection("library")
    }

    private fun togglePlayback() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.mediaItemCount == 0 && tracks.isNotEmpty()) playTrack(0) else c.play()
        }
    }

    private fun updatePlayerUi() {
        val c = controller
        if (c == null) {
            currentTitle.text = "Choose a track"
            currentArtist.text = "Local music library"
            if (::currentArtwork.isInitialized) currentArtwork.setImageResource(android.R.drawable.ic_media_play)
            playButton.text = "PLAY"
            return
        }
        val metadata = c.currentMediaItem?.mediaMetadata
        currentTitle.text = metadata?.title?.toString()?.takeIf(String::isNotBlank) ?: "Choose a track"
        currentArtist.text = metadata?.artist?.toString()?.takeIf(String::isNotBlank) ?: "Local music library"
        val artwork = metadata?.artworkUri
        try {
            if (artwork != null) currentArtwork.setImageURI(artwork)
            else currentArtwork.setImageResource(android.R.drawable.ic_media_play)
        } catch (_: Exception) {
            currentArtwork.setImageResource(android.R.drawable.ic_media_play)
        }
        playButton.text = if (c.isPlaying) "PAUSE" else "PLAY"
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        val position = c.currentPosition.coerceAtLeast(0L)
        if (!suppressSeek && duration > 0) {
            suppressSeek = true
            seekBar.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
            suppressSeek = false
        }
        timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun updateTimeText() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: return
        val position = duration * seekBar.progress / 1000
        timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun updateNodeDetails() {
        if (!::graph.isInitialized || !::selectedNodeText.isInitialized || !::qSeek.isInitialized) return
        val s = DspHolder.engine.settings
        val idx = graph.selectedNode()
        val freq = s.frequenciesHz()[idx]
        val gain = s.gainsDb()[idx]
        val baseQ = s.baseQ()[idx]
        val shelf = idx == 2 || idx == 12
        val effectiveQ = EqDspEngine.effectiveQ(idx, baseQ, gain)
        val freqText = if (freq >= 1000f) String.format(Locale.US, "%.2f kHz", freq / 1000f) else String.format(Locale.US, "%.1f Hz", freq)
        val qText = if (shelf) "Q not used by Web Audio shelf filters" else "Q ${String.format(Locale.US, "%.2f", effectiveQ)}"
        selectedNodeText.text = "NODE ${idx + 1}  ·  $freqText  ·  ${String.format(Locale.US, "%+.1f", gain)} dB  ·  $qText"
        val progress = ((baseQ - 0.1f) / 1.9f * 190f).roundToInt().coerceIn(0, 190)
        qSeek.progress = progress
        qSeek.isEnabled = !shelf
        qSeek.alpha = if (shelf) 0.38f else 1f
    }

    private fun updateOutputControl() {
        if (!::outputSeek.isInitialized || !::outputText.isInitialized) return
        val volume = DspHolder.engine.settings.volume().coerceAtLeast(0.0001f)
        val db = (20.0 * log10(volume.toDouble())).toFloat().coerceIn(-80f, 30f)
        val progress = when {
            db <= -30f -> if (db <= -80f) 0 else ((db + 30f) / 30f * 75f).roundToInt()
            db <= 0f -> ((db + 30f) / 30f * 75f).roundToInt()
            else -> (75f + db / 30f * 25f).roundToInt()
        }.coerceIn(0, 100)
        outputSeek.progress = progress
        outputText.text = String.format(Locale.US, "%+.1f dB", db)
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Preset name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Save EQ preset")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val prefs = getSharedPreferences("airs_player_presets", Context.MODE_PRIVATE)
                    val all = try { JSONObject(prefs.getString("presets", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
                    all.put(name, settingsToJson(DspHolder.engine.settings))
                    prefs.edit().putString("presets", all.toString()).apply()
                    reloadPresetSpinner(name)
                    Toast.makeText(this, "Preset saved", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun reloadPresetSpinner(selectName: String? = null) {
        if (!::presetSpinner.isInitialized) return
        val names = mutableListOf("Current settings", "Flat", "Bass Boost")
        val prefs = getSharedPreferences("airs_player_presets", Context.MODE_PRIVATE)
        try {
            val all = JSONObject(prefs.getString("presets", "{}") ?: "{}")
            val keys = all.keys()
            while (keys.hasNext()) names += keys.next()
        } catch (_: Exception) { }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressPreset = true
        presetSpinner.adapter = adapter
        val desired = selectName ?: "Current settings"
        presetSpinner.setSelection(names.indexOf(desired).coerceAtLeast(0))
        suppressPreset = false
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressPreset) return
                val name = names.getOrNull(position) ?: return
                val next = when (name) {
                    "Current settings" -> null
                    "Flat" -> DspHolder.engine.settings.withResetFilters()
                    "Bass Boost" -> DspHolder.engine.settings.withBassBoostFilters()
                    else -> try {
                        val all = JSONObject(prefs.getString("presets", "{}") ?: "{}")
                        settingsFromJson(all.getJSONObject(name))
                    } catch (_: Exception) { null }
                } ?: return
                DspHolder.engine.setSettings(next)
                DspHolder.save(this@MainActivity)
                graph.selectNode(2)
                updateNodeDetails()
                updateOutputControl()
                graph.invalidate()
            }
        }
    }

    private fun settingsToJson(s: EqDspEngine.Settings): JSONObject = JSONObject()
        .put("gain", JSONArray(s.gainsDb().toList()))
        .put("frequency", JSONArray(s.frequenciesHz().toList()))
        .put("baseQ", JSONArray(s.baseQ().toList()))
        .put("volume", s.volume().toDouble())

    private fun settingsFromJson(json: JSONObject): EqDspEngine.Settings {
        val gains = json.getJSONArray("gain")
        val freqs = json.getJSONArray("frequency")
        val qs = json.getJSONArray("baseQ")
        var s = EqDspEngine.Settings.defaults()
        for (i in 0 until EqDspEngine.FILTER_COUNT) {
            s = s.withNode(i, freqs.getDouble(i).toFloat(), gains.getDouble(i).toFloat(), qs.getDouble(i).toFloat())
        }
        return s.withVolume(json.optDouble("volume", 1.0).toFloat())
    }

    private inner class TrackAdapter(context: Context) : BaseAdapter() {
        override fun getCount(): Int = tracks.size
        override fun getItem(position: Int): Track = tracks[position]
        override fun getItemId(position: Int): Long = tracks[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = rounded(panel, 12)
                val title = TextView(this@MainActivity).apply { tag = "track_title"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(this@MainActivity.text); maxLines = 1 }
                val artist = TextView(this@MainActivity).apply { tag = "track_artist"; textSize = 11f; setTextColor(this@MainActivity.muted); maxLines = 1 }
                addView(title)
                addView(artist)
            }
            row.findViewWithTag<TextView>("track_title").text = tracks[position].title
            row.findViewWithTag<TextView>("track_artist").text = "${tracks[position].artist}  ·  ${formatTime(tracks[position].durationMs)}"
            return row
        }
    }

    private fun actionButton(textValue: String, click: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 11f
        setTextColor(this@MainActivity.text)
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(panel, 10)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { click() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun space(width: Int, height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressUpdater)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }
}
