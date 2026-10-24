package team.superfun.horrorpets.audio

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
import kotlin.math.abs

/**
 * The game's real audio: looping music (ExoPlayer, with a crossfade between tracks), one-shot effects and
 * the announcer's voice lines (SoundPool).
 *
 * - Effects (`audio/sfx/<name>.wav`) and voice lines (`audio/voice/<name>.wav`) are 16-bit PCM WAV, because
 *   SoundPool cannot decode Opus-in-Ogg below Android 10. They are all preloaded on a background thread at
 *   construction; a request that arrives before its clip has loaded waits for it.
 * - Voice lines play back to back, [VOICE_GAP_MS] apart, and duck the music while they run (a [VoiceQueue]).
 *   [skipVoiceTo] cuts what an earlier screen left the announcer saying, fading the line out over [CUT_FADE_MS].
 * - Music (`audio/music/<track>.ogg`) plays one track at a time and loops. Switching tracks crossfades
 *   over [CROSSFADE_MS] using a second player; a null track fades the music out. A track that keeps its place (the
 *   fight music hardening, or easing back, see [keepsPlace]) comes in where the old one is, beat on beat, and
 *   crossfades over [INTENSE_CROSSFADE_MS].
 * - A special's riff ([riff]) loops on a third player over the music, which ducks under it (see [duckLevel]). It
 *   comes up over [RIFF_DUCK_DOWN_MS], as the music goes down, and fades out over [RIFF_FADE_MS]. It ducks too, to
 *   [DUCK_LEVEL], while the announcer speaks over it.
 * - On a phone an effect with a buzz ([hapticFor]) vibrates as its clip starts. A device with no vibrator (every TV)
 *   gets none.
 *
 * Every call is logged under the `GameAudio` tag. Unknown names are logged and ignored, never thrown.
 * All playback calls belong on the main thread (a call from another thread is posted there); only
 * the asset reads happen elsewhere. Call [release] exactly once when the owner goes away.
 */
class GameAudio(context: Context) : SoundSink {
    private val appContext = context.applicationContext
    private val assets: AssetManager = appContext.assets
    private val main = Handler(Looper.getMainLooper())

    /** The phone's vibrator; null on a device without one. */
    private val vibrator: Vibrator? = vibratorOf(appContext)

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

    /** The announcer's lines: the one sounding (through the gap after it) and those queued behind it. */
    private val voices = VoiceQueue()

    /** The stream of the line [voices] says is sounding, so [skipVoiceTo] can cut it; 0 when there is none. */
    private var voiceStream = 0
    private val voiceDone = Runnable {
        voices.finished()
        voiceStream = 0
        if (voices.idle) duckMusic() else pumpVoice()
    }

    // ---- music (ExoPlayer) ----

    // Created on first use: the second player only exists once a track has been crossfaded.
    private var slotA: MusicSlot? = null
    private var slotB: MusicSlot? = null
    private var activeSlot: MusicSlot? = null
    private var currentTrack: String? = null

    // A special's riff: a slot of its own, never one of the pair the music crossfades between. Created on first use.
    private var riffSlot: MusicSlot? = null
    private var riffOn = false

    private var duck = 1f
    private var duckTarget = 1f

    // The riff's own duck: the music ducks under the riff, the riff only under the announcer.
    private var voiceDuck = 1f
    private var voiceDuckTarget = 1f

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
        voices.add(names)
        pumpVoice()
    }

    override fun skipVoiceTo(lines: List<String>) = onMain {
        if (lines.isEmpty() || released || paused) return@onMain
        val cutting = voices.sounding
        val dropped = voices.queued.size
        val cut = voices.skipTo(lines)
        if (cut) {
            main.removeCallbacks(voiceDone)
            fadeOut(voiceStream)
            voiceStream = 0
        }
        val gone = dropped - voices.queued.size
        Log.d(TAG, "voice skip to ${lines.joinToString(" > ")}: cut ${if (cut) cutting else "nothing"}, dropped $gone")
        if (voices.idle) duckMusic()
        pumpVoice()
    }

    override fun riff(on: Boolean) = onMain {
        Log.d(TAG, "riff ${if (on) "on" else "off"}")
        if (released || on == riffOn) return@onMain
        if (on && paused) {
            Log.d(TAG, "riff dropped: paused")
            return@onMain
        }
        riffOn = on
        val slot = riffSlot ?: MusicSlot("R", ducked = false).also { riffSlot = it }
        if (on) {
            // It comes up as fast as the music goes down under it. One still fading out picks up where it is.
            slot.fadeMs = RIFF_DUCK_DOWN_MS
            if (slot.track == null) {
                slot.start(RIFF_TRACK)
                Log.d(TAG, "riff slot R playing $RIFF_TRACK")
            } else {
                slot.target = 1f
                Log.d(TAG, "riff slot R back up")
            }
        } else {
            slot.fadeMs = RIFF_FADE_MS
            slot.target = 0f
            Log.d(TAG, "riff slot R fading out over ${RIFF_FADE_MS.toInt()}ms")
        }
        duckMusic()
    }

    // ================================================================ music and lifecycle

    /**
     * Plays [track] (`audio/music/<track>.ogg`) on a loop, crossfading from whatever is playing. The track
     * already playing is left alone; null fades the music out. With [keepPlace] (see [keepsPlace]) the new track
     * comes in where the one playing is, beat on beat, not from its start (see `MusicSlot.joinInStep`).
     */
    fun music(track: String?, keepPlace: Boolean = false) = onMain {
        Log.d(TAG, "music ${track ?: "none"}${if (keepPlace) ", keeping its place" else ""}")
        if (released || track == currentTrack) return@onMain
        currentTrack = track
        // A track still waiting for its beat to come in never does now.
        slots().forEach { it.cancelJoin() }
        val outgoing = activeSlot
        if (track == null) {
            outgoing?.apply {
                fadeMs = CROSSFADE_MS
                target = 0f
            }
            activeSlot = null
            startTicking()
            return@onMain
        }
        if (outgoing?.track == track) {
            // Back to the track a join just called off was coming in over: it plays on, and the other one stops.
            startTicking()
            return@onMain
        }
        val incoming = quieterSlot()
        if (keepPlace && outgoing?.track != null && outgoing !== incoming) {
            // The outgoing slot stays the active one, at full, until the incoming one comes in.
            incoming.joinInStep(track, outgoing)
            return@onMain
        }
        if (outgoing != null && outgoing !== incoming) {
            outgoing.fadeMs = CROSSFADE_MS
            outgoing.target = 0f
        }
        incoming.fadeMs = CROSSFADE_MS
        incoming.start(track)
        activeSlot = incoming
        startTicking()
    }

    /** The app went to the background: silence everything. Idempotent; [resume] undoes it. */
    fun pause() = onMain {
        if (released || paused) return@onMain
        paused = true
        Log.d(TAG, "pause")
        voices.clear()
        voiceStream = 0
        main.removeCallbacks(voiceDone)
        pendingSfx.clear()
        recentStreams.forEach(pool::stop)
        recentStreams.clear()
        slots().forEach { it.pauseJoin() }
        riffOn = false
        riffSlot?.apply {
            target = 0f
            level = 0f
            stop()
        }
        duck = 1f
        duckTarget = duckLevel(voice = !voices.idle, riff = riffOn)
        voiceDuck = 1f
        voiceDuckTarget = duckLevel(voice = !voices.idle, riff = false)
        slots().forEach {
            it.player.playWhenReady = false
            it.applyVolume()
        }
    }

    fun resume() = onMain {
        if (released || !paused) return@onMain
        paused = false
        Log.d(TAG, "resume")
        slots().forEach {
            if (it.track != null) it.player.playWhenReady = true
            it.resumeJoin()
        }
    }

    /** Frees the players and the sound pool. Safe to call more than once; every later call does nothing. */
    fun release() = onMain {
        if (released) return@onMain
        released = true
        Log.d(TAG, "release")
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        voices.clear()
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
                    if (now - request.at <= PENDING_SFX_MAX_AGE_MS && play(clips.getValue(key), SFX_PRIORITY) != 0) {
                        hapticFor(request.name)?.let(::buzz)
                    }
                }
            }
        }
    }

    /** Vibrates [pulses] (alternating buzz and pause lengths in ms, starting with a buzz) as their sound starts. */
    private fun buzz(pulses: LongArray) {
        val vibrator = vibrator ?: return
        // A waveform starts with a pause, which here is none.
        val pattern = longArrayOf(0, *pulses)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.play(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }.onFailure { Log.w(TAG, "buzz failed", it) }
    }

    /** Plays queued voice lines one after another. Re-entered when a clip ends, a load finishes or a call adds lines. */
    private fun pumpVoice() {
        while (voices.sounding == null && !paused) {
            val name = voices.next ?: return
            val key = "voice/$name"
            when (stateOf(key)) {
                ClipState.WAIT -> return
                ClipState.MISSING -> {
                    voices.dropNext()
                    Log.w(TAG, "unknown voice '$name': no loadable audio/$key$WAV")
                }
                ClipState.READY -> {
                    val clip = clips.getValue(key)
                    val stream = play(clip, VOICE_PRIORITY)
                    if (stream == 0) {
                        voices.dropNext()
                        continue
                    }
                    voices.start()
                    voiceStream = stream
                    Log.d(TAG, "voice playing $name (${clip.durationMs}ms)")
                    duckMusic()
                    main.postDelayed(voiceDone, clip.durationMs + VOICE_GAP_MS)
                }
            }
        }
    }

    /** Silences [stream] (a voice line being cut) over [CUT_FADE_MS] in a few steps, so it never clicks off. */
    private fun fadeOut(stream: Int) {
        if (stream == 0) return
        for (step in 1..CUT_FADE_STEPS) {
            main.postDelayed({
                if (released) return@postDelayed
                if (step == CUT_FADE_STEPS) {
                    pool.stop(stream)
                } else {
                    val v = 1f - step / CUT_FADE_STEPS.toFloat()
                    pool.setVolume(stream, v, v)
                }
            }, CUT_FADE_MS * step / CUT_FADE_STEPS)
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

    /**
     * Points the music's duck at what sounds over it now, the announcer and the riff (see [duckLevel]), and the riff's
     * at the announcer alone.
     */
    private fun duckMusic() {
        val target = duckLevel(voice = !voices.idle, riff = riffOn)
        if (target != duckTarget) Log.d(TAG, if (target < 1f) "music ducked to $target" else "music restored")
        duckTarget = target
        voiceDuckTarget = duckLevel(voice = !voices.idle, riff = false)
        startTicking()
    }

    private fun slots(): List<MusicSlot> = listOfNotNull(slotA, slotB, riffSlot)

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
        val duckMs = when {
            duckTarget >= duck -> DUCK_UP_MS
            riffOn -> RIFF_DUCK_DOWN_MS
            else -> DUCK_DOWN_MS
        }
        duck = approach(duck, duckTarget, dt / duckMs)
        val voiceDuckMs = if (voiceDuckTarget < voiceDuck) DUCK_DOWN_MS else DUCK_UP_MS
        voiceDuck = approach(voiceDuck, voiceDuckTarget, dt / voiceDuckMs)
        var settled = duck == duckTarget && voiceDuck == voiceDuckTarget
        for (slot in slots()) {
            slot.level = approach(slot.level, slot.target, dt / slot.fadeMs)
            if (slot.target == 0f && slot.level == 0f && slot.joining == null) slot.stop()
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

    /**
     * A music player, with the fade level it is currently at: one of the two the music crossfades between, or the
     * riff's. The music ([ducked]) ducks under the announcer and the riff; the riff under the announcer alone.
     */
    private inner class MusicSlot(private val label: String, private val ducked: Boolean = true) {
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

        /** 0 = silent, 1 = full; moves toward [target] over [fadeMs]. */
        var level = 0f
        var target = 0f

        /**
         * How long a fade all the way between silent and full takes: [CROSSFADE_MS] for the music, or
         * [INTENSE_CROSSFADE_MS] for a switch that keeps its place.
         */
        var fadeMs = CROSSFADE_MS

        /** The slot this one is lined up to come in with, beat on beat (see [joinInStep]), until it does. */
        var joining: MusicSlot? = null
            private set

        /** How many times [checkStep] has had to nudge this one toward [joining]. */
        private var nudges = 0

        /** How many looks in a row (with the game in front) have found this one or [joining] not playing. */
        private var stalls = 0

        /** Since when (uptime ms) this one and [joining] have both played at normal speed; 0 while either has not. */
        private var steadySince = 0L
        private val stepCheck = Runnable { checkStep() }
        private val nudgeEnd = Runnable { endNudge() }

        fun start(newTrack: String) {
            track = newTrack
            target = 1f
            player.setMediaItem(MediaItem.fromUri("asset:///audio/music/$newTrack.ogg"))
            player.prepare()
            player.playWhenReady = !paused
            applyVolume()
        }

        /**
         * Brings [newTrack] in where [other] is in its own track, beat on beat. It starts silent from [other]'s place,
         * which leaves it behind by however long it takes to prepare and sound (some 170 ms on the emulator, a third of
         * a beat), so it is nudged faster or slower until it is within [IN_STEP_MS] of [other] (see [checkStep]); only
         * then does it fade up as [other] fades out, over [INTENSE_CROSSFADE_MS].
         */
        fun joinInStep(newTrack: String, other: MusicSlot) {
            track = newTrack
            level = 0f
            target = 0f
            joining = other
            nudges = 0
            stalls = 0
            steadySince = 0L
            val at = other.player.currentPosition
            player.setMediaItem(MediaItem.fromUri("asset:///audio/music/$newTrack.ogg"))
            player.seekTo(at)
            player.prepare()
            player.playWhenReady = !paused
            applyVolume()
            Log.d(TAG, "music slot $label playing $newTrack from ${at}ms, silent till in step with slot ${other.label}")
            lookAgainIn(STEP_POLL_MS)
        }

        /** Calls off a [joinInStep] that has not come in yet. */
        fun cancelJoin() {
            if (joining == null) return
            joining = null
            holdSteps()
        }

        /** The game went to the background: a join waits, with no looks or nudges, until [resumeJoin]. */
        fun pauseJoin() {
            if (joining != null) holdSteps()
        }

        /** The game is back: a join held by [pauseJoin] starts looking again. */
        fun resumeJoin() {
            if (joining == null) return
            stalls = 0
            lookAgainIn(STEP_POLL_MS)
        }

        /** Runs [checkStep] in [ms], in place of any look already due, so there is only ever one. */
        private fun lookAgainIn(ms: Long) {
            main.removeCallbacks(stepCheck)
            main.postDelayed(stepCheck, ms)
        }

        private fun holdSteps() {
            main.removeCallbacks(stepCheck)
            main.removeCallbacks(nudgeEnd)
            player.setPlaybackSpeed(1f)
            steadySince = 0L
        }

        /**
         * Once this slot and [joining] have both played steadily, at normal speed, for [STEP_SETTLE_MS], how far this
         * one is behind (see [loopLag]): in step (or after [MAX_NUDGES] nudges), it comes in; else it is nudged (see
         * [nudgeFor]). A speed change reaches the speaker late, but its end does too, so the two cancel out; what is
         * left (the main thread running the nudge's end a little late) shrinks with the gap. Until both play it looks
         * again every [STEP_POLL_MS], and after [MAX_STALLS] looks gives up: the track playing plays on. With the game
         * in the background it does not look at all ([resumeJoin] starts it again).
         */
        private fun checkStep() {
            val other = joining ?: return
            if (paused) return
            if (!player.isPlaying || !other.player.isPlaying) {
                steadySince = 0L
                if (++stalls >= MAX_STALLS) {
                    giveUp(other)
                } else {
                    lookAgainIn(STEP_POLL_MS)
                }
                return
            }
            stalls = 0
            val now = SystemClock.uptimeMillis()
            if (steadySince == 0L) steadySince = now
            val steadyFor = now - steadySince
            if (steadyFor < STEP_SETTLE_MS) {
                lookAgainIn(STEP_SETTLE_MS - steadyFor)
                return
            }
            val lag = loopLag(other.player.currentPosition, player.currentPosition, other.player.duration)
            if (abs(lag) <= IN_STEP_MS || nudges++ >= MAX_NUDGES) {
                comeIn(other, lag)
                return
            }
            val nudge = nudgeFor(lag)
            player.setPlaybackSpeed(nudge.speed)
            main.postDelayed(nudgeEnd, nudge.durationMs)
        }

        /** A nudge is over: back to normal speed, which counts as steady from now (see [checkStep]). */
        private fun endNudge() {
            player.setPlaybackSpeed(1f)
            steadySince = SystemClock.uptimeMillis()
            lookAgainIn(STEP_SETTLE_MS)
        }

        /** One of the two never came to play: [other]'s track stays the music, and this one stops. */
        private fun giveUp(other: MusicSlot) {
            Log.w(TAG, "music slot $label gave up coming in with slot ${other.label}: not playing")
            cancelJoin()
            currentTrack = other.track
            startTicking()
        }

        private fun comeIn(other: MusicSlot, lag: Long) {
            joining = null
            val fade = INTENSE_CROSSFADE_MS.toInt()
            Log.d(TAG, "music slot $label in step with slot ${other.label} (${lag}ms): crossfading over ${fade}ms")
            fadeMs = INTENSE_CROSSFADE_MS
            other.fadeMs = INTENSE_CROSSFADE_MS
            target = 1f
            other.target = 0f
            activeSlot = this
            startTicking()
        }

        fun stop() {
            cancelJoin()
            if (track == null) return
            track = null
            player.stop()
            player.clearMediaItems()
        }

        fun applyVolume() {
            player.volume = MUSIC_VOLUME * level * (if (ducked) duck else voiceDuck)
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
        const val CUT_FADE_MS = 80L
        const val CUT_FADE_STEPS = 4
        const val CROSSFADE_MS = 600f
        const val INTENSE_CROSSFADE_MS = 1500f
        const val STEP_POLL_MS = 100L
        const val STEP_SETTLE_MS = 600L // longer than a speed change takes to reach the speaker (some 350 ms)
        const val IN_STEP_MS = 20L
        const val MAX_NUDGES = 4
        const val MAX_STALLS = 30 // 3 s of looks
        const val DUCK_DOWN_MS = 120f
        const val DUCK_UP_MS = 400f
        const val RIFF_TRACK = "special_riff"
        const val RIFF_DUCK_DOWN_MS = 150f
        const val RIFF_FADE_MS = 600f
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
