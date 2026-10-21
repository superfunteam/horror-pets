package team.superfun.horrorpets.audio

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The game's real audio: looping music (ExoPlayer, with a crossfade between tracks), one-shot effects and
 * the announcer's voice lines (SoundPool).
 *
 * - Effects (`audio/sfx/<name>.wav`) and voice lines (`audio/voice/<name>.wav`) are 16-bit PCM WAV, because
 *   SoundPool cannot decode Opus-in-Ogg below Android 10. They are all preloaded on a background thread at
 *   construction; a request that arrives before its clip has loaded waits for it.
 * - Voice lines play back to back, [VOICE_GAP_MS] apart, and duck the music while they run.
 * - Music (`audio/music/<track>.ogg`) plays one track at a time and loops. Switching tracks crossfades
 *   over [CROSSFADE_MS] using a second player; a null track fades the music out.
 *
 * Every call is logged under the `GameAudio` tag. Unknown names are logged and ignored, never thrown.
 * All playback calls belong on the main thread (a call from another thread is posted there); only
 * the asset reads happen elsewhere. Call [release] exactly once when the owner goes away.
 */
class GameAudio(context: Context) : SoundSink {
    private val appContext = context.applicationContext
    private val assets: AssetManager = appContext.assets
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var released = false
    private var paused = false

    // ---- effects and voice (SoundPool) ----

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    /** Every clip found at startup, keyed `sfx/<name>` or `voice/<name>` (the two folders share names). */
    private val clips = ConcurrentHashMap<String, Clip>()
    private val loadedIds = HashSet<Int>()
    private val failedIds = HashSet<Int>()
    private var preloadDone = false
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "GameAudio-load") }

    /** Effects asked for before their clip finished loading (only possible in the first moments). */
    private val pendingSfx = ArrayList<PendingSfx>()

    /** Stream ids handed out recently, so [pause] can silence whatever is still sounding. */
    private val recentStreams = ArrayDeque<Int>()

    private val voiceQueue = ArrayDeque<String>()

    /** True while a voice clip sounds and during the gap that follows it. */
    private var voiceBusy = false
    private val voiceDone = Runnable {
        voiceBusy = false
        if (voiceQueue.isEmpty()) duckMusic(false) else pumpVoice()
    }

    // ---- music (ExoPlayer) ----

    // Created on first use: the second player only exists once a track has been crossfaded.
    private var slotA: MusicSlot? = null
    private var slotB: MusicSlot? = null
    private var activeSlot: MusicSlot? = null
    private var currentTrack: String? = null
    private var duck = 1f
    private var duckTarget = 1f
    private var tickRunning = false
    private var lastTickAt = 0L
    private val tick = Runnable { onTick() }

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedIds += sampleId
            } else {
                failedIds += sampleId
                Log.e(TAG, "SoundPool failed to load sample $sampleId (status $status)")
            }
            onClipsChanged()
        }
        loader.execute(::preload)
    }

    // ================================================================ SoundSink

    override fun sfx(name: String) = onMain {
        Log.d(TAG, "sfx $name")
        if (released) return@onMain
        if (paused) {
            Log.d(TAG, "sfx $name dropped: paused")
            return@onMain
        }
        pendingSfx += PendingSfx(name, SystemClock.uptimeMillis())
        flushPendingSfx()
    }

    override fun voice(names: List<String>) = onMain {
        if (names.isEmpty()) return@onMain
        Log.d(TAG, "voice ${names.joinToString(" > ")}")
        if (released) return@onMain
        if (paused) {
            Log.d(TAG, "voice dropped: paused")
            return@onMain
        }
        voiceQueue.addAll(names)
        pumpVoice()
    }

    // ================================================================ music and lifecycle

    /**
     * Plays [track] (`audio/music/<track>.ogg`) on a loop, crossfading from whatever is playing. The track
     * already playing is left alone; null fades the music out.
     */
    fun music(track: String?) = onMain {
        Log.d(TAG, "music ${track ?: "none"}")
        if (released || track == currentTrack) return@onMain
        currentTrack = track
        val outgoing = activeSlot
        if (track == null) {
            outgoing?.target = 0f
            activeSlot = null
            startTicking()
            return@onMain
        }
        val incoming = quieterSlot()
        if (outgoing != null && outgoing !== incoming) outgoing.target = 0f
        incoming.start(track)
        activeSlot = incoming
        startTicking()
    }

    /** The app went to the background: silence everything. Idempotent; [resume] undoes it. */
    fun pause() = onMain {
        if (released || paused) return@onMain
        paused = true
        Log.d(TAG, "pause")
        voiceQueue.clear()
        main.removeCallbacks(voiceDone)
        voiceBusy = false
        pendingSfx.clear()
        recentStreams.forEach(pool::stop)
        recentStreams.clear()
        duck = 1f
        duckTarget = 1f
        slots().forEach {
            it.player.playWhenReady = false
            it.applyVolume()
        }
    }

    fun resume() = onMain {
        if (released || !paused) return@onMain
        paused = false
        Log.d(TAG, "resume")
        slots().forEach { if (it.track != null) it.player.playWhenReady = true }
    }

    /** Frees the players and the sound pool. Safe to call more than once; every later call does nothing. */
    fun release() = onMain {
        if (released) return@onMain
        released = true
        Log.d(TAG, "release")
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        voiceQueue.clear()
        pendingSfx.clear()
        slots().forEach { it.player.release() }
        pool.release()
    }

    // ================================================================ preload (background thread)

    private fun preload() {
        var count = 0
        for (kind in listOf("sfx", "voice")) {
            val files = try {
                assets.list("audio/$kind").orEmpty()
            } catch (e: IOException) {
                Log.e(TAG, "Could not list audio/$kind", e)
                emptyArray()
            }
            for (file in files.sorted()) {
                if (released) return
                if (!file.endsWith(WAV)) continue
                val path = "audio/$kind/$file"
                try {
                    val header = assets.open(path).use(::readHeader)
                    assets.openFd(path).use { afd ->
                        val durationMs = parseWavDurationMs(header, afd.length)
                            ?: (afd.length * 1000 / FALLBACK_BYTES_PER_SECOND).also {
                                Log.w(TAG, "$path: no usable WAV header, guessing ${it}ms")
                            }
                        val id = pool.load(afd, 1)
                        if (id == 0) {
                            Log.w(TAG, "SoundPool refused $path")
                        } else {
                            clips["$kind/${file.removeSuffix(WAV)}"] = Clip(id, durationMs)
                            count++
                        }
                    }
                } catch (e: Exception) {
                    // Any one bad clip (unreadable, odd header, refused by the pool) only loses that clip.
                    Log.e(TAG, "Could not preload $path", e)
                }
            }
        }
        Log.d(TAG, "preload queued $count clips")
        main.post {
            preloadDone = true
            onClipsChanged()
        }
    }

    private fun readHeader(input: InputStream): ByteArray {
        val buf = ByteArray(HEADER_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    // ================================================================ effects and voice (main thread)

    private fun onClipsChanged() {
        if (released) return
        flushPendingSfx()
        pumpVoice()
    }

    private fun stateOf(key: String): ClipState {
        val clip = clips[key] ?: return if (preloadDone) ClipState.MISSING else ClipState.WAIT
        return when (clip.id) {
            in failedIds -> ClipState.MISSING
            in loadedIds -> ClipState.READY
            else -> ClipState.WAIT
        }
    }

    private fun flushPendingSfx() {
        val now = SystemClock.uptimeMillis()
        val it = pendingSfx.iterator()
        while (it.hasNext()) {
            val request = it.next()
            val key = "sfx/${request.name}"
            when (stateOf(key)) {
                ClipState.WAIT -> if (now - request.at > PENDING_SFX_MAX_AGE_MS) {
                    Log.w(TAG, "sfx ${request.name} dropped: still loading after ${PENDING_SFX_MAX_AGE_MS}ms")
                    it.remove()
                }
                ClipState.MISSING -> {
                    Log.w(TAG, "unknown sfx '${request.name}': no loadable audio/$key$WAV")
                    it.remove()
                }
                ClipState.READY -> {
                    it.remove()
                    // A request that waited a long time for the pool is no longer worth playing late.
                    if (now - request.at <= PENDING_SFX_MAX_AGE_MS) play(clips.getValue(key), SFX_PRIORITY)
                }
            }
        }
    }

    /** Plays queued voice lines one after another. Re-entered when a clip ends, a load finishes or a call adds lines. */
    private fun pumpVoice() {
        while (!voiceBusy && !paused) {
            val name = voiceQueue.firstOrNull() ?: return
            val key = "voice/$name"
            when (stateOf(key)) {
                ClipState.WAIT -> return
                ClipState.MISSING -> {
                    voiceQueue.removeFirst()
                    Log.w(TAG, "unknown voice '$name': no loadable audio/$key$WAV")
                }
                ClipState.READY -> {
                    voiceQueue.removeFirst()
                    val clip = clips.getValue(key)
                    if (play(clip, VOICE_PRIORITY) == 0) continue
                    Log.d(TAG, "voice playing $name (${clip.durationMs}ms)")
                    voiceBusy = true
                    duckMusic(true)
                    main.postDelayed(voiceDone, clip.durationMs + VOICE_GAP_MS)
                }
            }
        }
    }

    /** Starts [clip] at full volume; returns the stream id, or 0 when the pool refused (and logs it). */
    private fun play(clip: Clip, priority: Int): Int {
        val stream = pool.play(clip.id, 1f, 1f, priority, 0, 1f)
        if (stream == 0) {
            Log.w(TAG, "SoundPool would not play sample ${clip.id}")
            return 0
        }
        recentStreams.addLast(stream)
        while (recentStreams.size > RECENT_STREAMS) recentStreams.removeFirst()
        return stream
    }

    // ================================================================ music fades (main thread)

    private fun duckMusic(on: Boolean) {
        val target = if (on) DUCK_LEVEL else 1f
        if (target != duckTarget) Log.d(TAG, if (on) "music ducked" else "music restored")
        duckTarget = target
        startTicking()
    }

    private fun slots(): List<MusicSlot> = listOfNotNull(slotA, slotB)

    /** The slot that takes a new track: an idle one, else the quieter of the two (the newest of overlapping fades). */
    private fun quieterSlot(): MusicSlot {
        val a = slotA ?: return MusicSlot("A").also { slotA = it }
        if (a.level == 0f && a.target == 0f) return a
        val b = slotB ?: MusicSlot("B").also { slotB = it }
        return if (b.level < a.level) b else a
    }

    private fun startTicking() {
        if (tickRunning || released) return
        tickRunning = true
        lastTickAt = SystemClock.uptimeMillis()
        main.postDelayed(tick, FRAME_MS)
    }

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTickAt).coerceAtLeast(1L).toFloat()
        lastTickAt = now
        val duckMs = if (duckTarget < duck) DUCK_DOWN_MS else DUCK_UP_MS
        duck = approach(duck, duckTarget, dt / duckMs)
        var settled = duck == duckTarget
        for (slot in slots()) {
            slot.level = approach(slot.level, slot.target, dt / CROSSFADE_MS)
            if (slot.target == 0f && slot.level == 0f) slot.stop()
            slot.applyVolume()
            if (slot.level != slot.target) settled = false
        }
        if (settled || released) {
            tickRunning = false
        } else {
            main.postDelayed(tick, FRAME_MS)
        }
    }

    private fun approach(current: Float, target: Float, step: Float): Float =
        if (current < target) minOf(current + step, target) else maxOf(current - step, target)

    /** One of the two music players, with the fade level it is currently at. */
    private inner class MusicSlot(private val label: String) {
        val player: ExoPlayer = ExoPlayer.Builder(appContext)
            // Focus handling stays off: two players asking for it would take it from each other.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_GAME)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "ExoPlayer $label failed on ${track ?: "no track"}", error)
                        }
                    },
                )
            }

        var track: String? = null
            private set

        /** 0 = silent, 1 = full; moves toward [target] over [CROSSFADE_MS]. */
        var level = 0f
        var target = 0f

        fun start(newTrack: String) {
            track = newTrack
            target = 1f
            player.setMediaItem(MediaItem.fromUri("asset:///audio/music/$newTrack.ogg"))
            player.prepare()
            player.playWhenReady = !paused
            applyVolume()
        }

        fun stop() {
            if (track == null) return
            track = null
            player.stop()
            player.clearMediaItems()
        }

        fun applyVolume() {
            player.volume = MUSIC_VOLUME * level * duck
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    private data class Clip(val id: Int, val durationMs: Long)

    private class PendingSfx(val name: String, val at: Long)

    private enum class ClipState { WAIT, READY, MISSING }

    private companion object {
        const val TAG = "GameAudio"
        const val WAV = ".wav"

        const val MAX_STREAMS = 6
        const val SFX_PRIORITY = 1
        const val VOICE_PRIORITY = 2
        const val RECENT_STREAMS = 16
        const val PENDING_SFX_MAX_AGE_MS = 2_000L
        const val HEADER_BYTES = 256
        const val FALLBACK_BYTES_PER_SECOND = 88_200L // 44.1 kHz, mono, 16-bit

        const val MUSIC_VOLUME = 0.5f
        const val DUCK_LEVEL = 0.3f
        const val VOICE_GAP_MS = 150L
        const val CROSSFADE_MS = 600f
        const val DUCK_DOWN_MS = 120f
        const val DUCK_UP_MS = 400f
        const val FRAME_MS = 30L
    }
}

/**
 * The length of a WAV file's audio in milliseconds, read from the start of the file ([header]) and its
 * full size ([fileLength]); null when it is not a PCM RIFF/WAVE file this can make sense of. Never
 * negative, even for a file length that is unknown (-1) or shorter than the header.
 */
internal fun parseWavDurationMs(header: ByteArray, fileLength: Long): Long? {
    fun tag(at: Int) = String(header, at, 4, Charsets.US_ASCII)
    fun u32(at: Int): Long =
        (header[at].toLong() and 0xFF) or
            ((header[at + 1].toLong() and 0xFF) shl 8) or
            ((header[at + 2].toLong() and 0xFF) shl 16) or
            ((header[at + 3].toLong() and 0xFF) shl 24)

    if (header.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") return null
    var pos = 12
    var byteRate = 0L
    while (pos + 8 <= header.size) {
        val size = u32(pos + 4)
        val body = pos + 8
        when (tag(pos)) {
            "fmt " -> if (body + 12 <= header.size) byteRate = u32(body + 8)
            "data" -> {
                if (byteRate <= 0) return null
                val available = fileLength - body
                // A streaming writer may leave the size at 0 or 0xFFFFFFFF: trust the file length then.
                val bytes = if (size in 1..available) size else available
                return (bytes * 1000 / byteRate).coerceAtLeast(0L)
            }
        }
        val next = body + size + (size and 1L) // chunks are padded to an even length
        if (next > header.size) return null
        pos = next.toInt()
    }
    return null
}
