package app.ghostly.vpn.stage

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import app.ghostly.core.stage.LyricLine
import app.ghostly.core.stage.Lyrics
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.core.stage.StageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Needed only so Android lets us read the media sessions of other apps (which track plays where);
 * we never look at notifications themselves.
 */
class GhostlyMediaListener : NotificationListenerService()

/**
 * Android stage source: the ghost sings along with whatever plays on the phone.
 * - now playing from the system media sessions (needs "notification access" for Ghostly); Android
 *   reports the position with its update time and speed, so the clock is exact without polling tricks;
 * - the same lrclib.net lines as on Windows ([Lyrics]);
 * - audio from the global output mix through [Visualizer] (needs the microphone permission — Android's
 *   rule for analysing output; nothing is recorded). Without it the ghost still sings, the stage just
 *   doesn't pulse.
 */
class AndroidStage(private val context: Context) : StageSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: List<Job> = emptyList()

    private val _audio = MutableStateFlow(StageAudio())
    override val audio: StateFlow<StageAudio> = _audio.asStateFlow()
    private val _track = MutableStateFlow<NowPlaying?>(null)
    override val track: StateFlow<NowPlaying?> = _track.asStateFlow()
    private val _setup = MutableStateFlow<String?>(null)
    override val setupNeeded: StateFlow<String?> = _setup.asStateFlow()
    override val whereLabel = "на телефоне"

    /** Set by MainActivity: asks for the microphone permission (Visualizer on the output mix). */
    @Volatile var requestAudioPermission: (() -> Unit)? = null

    @Volatile private var controller: MediaController? = null

    override fun positionMs(): Long {
        val st = controller?.playbackState ?: return 0L
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() + LYRIC_LEAD_MS
        }
        val dur = _track.value?.durationMs ?: 0L
        return if (dur > 0) p.coerceIn(0, dur) else max(0, p)
    }

    override fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(scope.launch { pollSessions() }, scope.launch { analyse() })
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        releaseVisualizer()
        controller = null
        _audio.value = StageAudio()
    }

    override fun requestSetup() {
        when {
            !hasListenerAccess() -> context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            !hasAudioPermission() -> requestAudioPermission?.invoke()
        }
    }

    private fun hasListenerAccess(): Boolean =
        (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
            .contains(ComponentName(context, GhostlyMediaListener::class.java).flattenToString())

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateSetup() {
        _setup.value = when {
            !hasListenerAccess() -> "Разрешите Ghostly доступ к уведомлениям — так он видит, какой трек играет (сами уведомления не читаются)"
            !hasAudioPermission() -> "Разрешите «Микрофон» — Android так называет доступ к анализу звука, который играет. Ничего не записывается"
            else -> null
        }
    }

    // ------------------------------------------------------------------ now playing

    private suspend fun pollSessions() {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, GhostlyMediaListener::class.java)
        var key = ""
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            updateSetup()
            val sessions = runCatching { msm?.getActiveSessions(listener).orEmpty() }.getOrDefault(emptyList())
            val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
                ?: sessions.firstOrNull { it.metadata != null }
            controller = c
            val md = c?.metadata
            if (c == null || md == null) {
                if (_track.value != null) { _track.value = null; key = "" }
            } else {
                val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
                val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                val newKey = (artist + "|" + title).lowercase()
                if (newKey != key && title.isNotBlank()) {
                    key = newKey
                    _track.value = NowPlaying(title, artist, dur, playing, lyricsLoading = true)
                    scope.launch(Dispatchers.IO) { loadLines(newKey, title, artist, dur) }
                } else {
                    _track.value = _track.value?.copy(playing = playing, durationMs = dur)
                }
            }
            if (hasAudioPermission() && visualizer == null) startVisualizer()
            delay(if (c != null) 700 else 2500)
        }
    }

    // ------------------------------------------------------------------ lines

    private val cacheDir = File(context.cacheDir, "lines").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Pair<List<LyricLine>, Boolean>>()

    private fun loadLines(key: String, title: String, artist: String, durationMs: Long) {
        val file = File(cacheDir, Integer.toHexString(key.hashCode()) + ".lrc")
        val found = memory[key]
            ?: runCatching { Lyrics.decode(key, file.readText()) }.getOrNull()
            ?: runCatching { Lyrics.lookup(title, artist, durationMs, ::get) }.getOrNull()
                ?.also { runCatching { file.writeText(Lyrics.encode(key, it)) } }
        if (found != null) memory[key] = found
        val (lines, synced) = found ?: (emptyList<LyricLine>() to false)
        _track.value?.let { t ->
            if ((t.artist + "|" + t.title).lowercase() == key) _track.value = t.copy(lines = lines, synced = synced, lyricsLoading = false)
        }
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "GhostlyVPN (https://github.com/Nelxi/ghostly-vpn)")
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    // ------------------------------------------------------------------ audio

    private var visualizer: Visualizer? = null
    /** Latest raw bands from the FFT callback: bass, low-mid (melody), vocal, treble, overall. */
    private val raw = FloatArray(5)
    @Volatile private var rawAt = 0L

    private fun startVisualizer() {
        runCatching {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) bands(fft, samplingRate / 1000f)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
        }.onFailure { visualizer = null }
    }

    private fun releaseVisualizer() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
    }

    private fun bands(fft: ByteArray, sampleRate: Float) {
        val n = fft.size / 2
        val hzPerBin = sampleRate / fft.size
        var bass = 0f; var low = 0f; var voc = 0f; var tre = 0f; var all = 0f
        for (k in 1 until n) {
            val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val f = k * hzPerBin
            all += m
            when {
                f < 160f -> bass += m
                f < 1000f -> low += m
                f < 3400f -> voc += m
                else -> tre += m
            }
        }
        synchronized(raw) { raw[0] = bass; raw[1] = low; raw[2] = voc; raw[3] = tre; raw[4] = all }
        rawAt = SystemClock.elapsedRealtime()
    }

    /** Levels normalised by a slowly decaying peak (phones play at any volume), then the same mood layer as on Windows. */
    private suspend fun analyse() {
        val peak = FloatArray(5) { 1f }
        var bassOut = 0f; var beat = 0f; var bassAvg = 0f
        var shortE = 0f; var longE = 0f; var dark = 0.35f; var tempo = 0.4f; var aggrAvg = 0f
        var drop = 0f; var build = 0f; var lastDrop = 0L
        val beats = ArrayDeque<Long>()
        var bpm = 0f; var lastBeatAt = 0L
        var last = System.nanoTime()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.2f)
            last = now
            fun ema(cur: Float, target: Float, tau: Float) = cur + (target - cur) * (1f - exp(-dt / tau))
            val ms = SystemClock.elapsedRealtime()
            val active = visualizer != null && ms - rawAt < 500 && _track.value?.playing == true
            val v = FloatArray(5)
            synchronized(raw) { for (i in 0..4) v[i] = raw[i] }
            for (i in 0..4) {
                peak[i] = max(peak[i] * (1f - dt / 8f), v[i]).coerceAtLeast(1f)
                v[i] = if (active) sqrt((v[i] / peak[i]).coerceIn(0f, 1f)) else 0f
            }
            val (b, mel, voc, tre, e) = v.toList()

            // Beat: bass jumping clearly above its own recent average.
            bassAvg = ema(bassAvg, b, 0.4f)
            if (active && b > bassAvg * 1.35f + 0.08f && ms - lastBeatAt > 280) {
                beat = 1f
                if (lastBeatAt > 0) { beats.addLast(ms - lastBeatAt); if (beats.size > 12) beats.removeFirst() }
                lastBeatAt = ms
            }
            beat = max(0f, beat - dt * 4f)
            if (beats.size >= 4) {
                val med = beats.sorted()[beats.size / 2].coerceIn(300, 1500)
                bpm = 60000f / med
            }
            val beatPhase = if (bpm > 0 && lastBeatAt > 0) (((ms - lastBeatAt) * bpm / 60000f) % 1f) else 0f

            shortE = ema(shortE, e, 0.25f)
            longE = ema(longE, e, 7f)
            val rising = ((shortE - longE) * 3f).coerceIn(0f, 1f) * (0.5f + 0.5f * tre)
            build = ema(build, rising, 1.2f)
            if (active && shortE > 0.42f && shortE > longE * 1.8f + 0.08f && b > 0.45f && ms - lastDrop > 9000) {
                drop = 1f; lastDrop = ms; build = 0f
            }
            drop = max(0f, drop - dt / 3.2f)

            val aggression = (tre * 0.6f + e * 0.4f).coerceIn(0f, 1f)
            aggrAvg = ema(aggrAvg, aggression, 6f)
            val calm = (1f - e).coerceIn(0f, 1f)
            val slow = if (bpm in 1f..95f) 0.35f else 0f
            val darkTarget = (0.25f + calm * 0.6f + (1f - tre) * 0.35f + slow - aggrAvg * 0.45f).coerceIn(0f, 1f)
            dark = ema(dark, if (active) darkTarget else 0.45f, 5f)
            bassOut = ema(bassOut, b, 0.05f + 0.55f * dark)
            tempo = ema(tempo, if (bpm > 0) ((bpm - 70f) / 100f).coerceIn(0f, 1f) else 0.4f, 3f)

            // No audio access: the lines still say when someone sings — move the mouth to them.
            val vocal = if (active) voc else lineVoice(ms)
            _audio.value = StageAudio(
                active = active, bass = bassOut, melody = mel, vocal = vocal, treble = tre, energy = e,
                beat = beat * (1f - 0.7f * dark), bpm = bpm, beatPhase = beatPhase, aggression = aggression,
                calm = calm, darkness = dark, tempo = tempo, drop = drop, build = build.coerceIn(0f, 1f),
            )
            delay(if (active || _track.value?.playing == true) 16 else 250)
        }
    }

    private fun lineVoice(ms: Long): Float {
        val t = _track.value ?: return 0f
        if (!t.playing || !t.synced || t.lines.isEmpty()) return 0f
        val pos = positionMs()
        val i = t.lineAt(pos)
        if (i < 0) return 0f
        val end = t.lines.getOrNull(i + 1)?.timeMs ?: (t.lines[i].timeMs + 4000)
        if (pos > end - 250) return 0f
        return 0.45f + 0.35f * kotlin.math.abs(kotlin.math.sin(ms / 110.0)).toFloat()
    }

    private companion object {
        /** A line starts typing at its first letter; a hair early keeps it with the voice. */
        const val LYRIC_LEAD_MS = 200L
    }
}
