package team.superfun.horrorpets.pvp.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val TAG = "PvpTap"

/** How long a reader asks a tapped phone whose room is opening for its code. */
private const val OPENING_WAIT_MS = 12_000L

/** How long a reader waits for its own room to open, to hand its code to the phone it claimed. */
private const val HAND_OVER_WAIT_MS = 12_000L

/** How often it asks again, or asks that phone "still there?". */
private const val ASK_MS = 150L

/** A reader-mode switch slower than this is worth a line in the log (it runs on the main thread). */
private const val SLOW_SWITCH_NS = 8_000_000L

/** What the game does with what a tap reads. Called on the main thread, and expected to answer at once. */
interface TapEvents {
    /** The tapped phone hosts a room: join it. */
    fun onCodeRead(code: String)

    /** Whether this phone would open a room for a tapped phone that is ready for a fight (before claiming it). */
    fun canHost(): Boolean

    /** The ready phone took this one's claim: open a room for both, returning whether this phone did. */
    fun onClaimed(): Boolean

    /** Whether this phone already opened a room for a tap, so a tap that finds the friend again hands it over. */
    fun hostingForTap(): Boolean

    /** Whether this phone is still opening (or waiting in) its room. */
    fun stillHosting(): Boolean

    /** This phone's room got over to the friend's phone ([ok]), or the phones parted first. */
    fun onHandOver(ok: Boolean)

    /** A tap caught: tell the player (a buzz), who cannot see the screen with the phones back to back. */
    fun onCaught()
}

/**
 * A phone's NFC for PvP taps: reading other phones, when [TapRadio] says to (see [setMode]). A tapped phone that
 * hosts a room (or is opening one) has its code read, and [events] joins it. A tapped phone that is ready for a fight
 * is claimed, [events] opens a room for both, and its code goes over the same tap. The rest of the time this phone is
 * a card ([TapCard]), and while it hosts a room it makes sure a reader reaches this app's card ([preferHosting]).
 *
 * Outside reader mode Android still finds phones now and then (both phones being cards at once): those taps come to
 * this app too ([onResume] takes them over), so no other app opens over the game, and they are read the same way.
 *
 * Reading only runs while [activity] is resumed ([onResume], [onPause]) and NFC is on. [create] returns null on a
 * device with no NFC at all (every TV).
 */
class NfcTapReader private constructor(
    private val activity: Activity,
    private val adapter: NfcAdapter,
    private val events: TapEvents,
) {
    private val main = Handler(Looper.getMainLooper())
    private val radio = TapRadio(Random.Default)
    private var readerOn = false
    private val tick = Runnable { update() }

    /** Without card emulation this phone can never be read, so it reads all the time instead of taking turns. */
    private val canBeCard = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    /** Where taps found outside reader mode are read: one at a time, off the main thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private val action = "${activity.packageName}.PVP_TAP"
    private val dispatched = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            } ?: return
            worker.execute { onTag(tag) }
        }
    }
    private val dispatch = PendingIntent.getBroadcast(
        activity,
        0,
        Intent(action).setPackage(activity.packageName),
        // Mutable: Android adds the tag to it.
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Whether NFC is on (the player can switch it off in Settings): no tap reads or is read without it. */
    val nfcOn: Boolean get() = runCatching { adapter.isEnabled }.getOrDefault(false)

    fun setMode(mode: TapMode) {
        radio.setMode(if (mode == TapMode.TAKE_TURNS && !canBeCard) TapMode.LISTEN else mode, now())
        update()
    }

    /** A reader claimed this phone: stay a card, whatever the turns say, until [release]. */
    fun holdAsCard() {
        radio.holdAsCard(now())
        update()
    }

    fun release() {
        radio.release()
        update()
    }

    fun onResume() {
        radio.resumed = true
        ContextCompat.registerReceiver(activity, dispatched, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { adapter.enableForegroundDispatch(activity, dispatch, null, null) }
            .onFailure { Log.w(TAG, "Foreground dispatch refused", it) }
        update()
    }

    fun onPause() {
        radio.resumed = false
        runCatching { adapter.disableForegroundDispatch(activity) }
        runCatching { activity.unregisterReceiver(dispatched) }
        update()
        runCatching { preferHosting(false) }
    }

    /** The Activity is going: no more taps are read. */
    fun close() {
        main.removeCallbacks(tick)
        worker.shutdown()
    }

    /** While this phone hosts a room (and is in front), a tapping reader is routed to this app's card service. */
    fun preferHosting(hosting: Boolean) {
        if (!canBeCard) return
        val emulation = CardEmulation.getInstance(adapter)
        if (hosting) {
            emulation.setPreferredService(activity, ComponentName(activity, PvpTapService::class.java))
        } else {
            emulation.unsetPreferredService(activity)
        }
    }

    /** On the main thread: reading on or off as [radio] says now, and a wake-up for its next change. */
    private fun update() {
        main.removeCallbacks(tick)
        val now = now()
        val due = radio.advance(now)
        val on = radio.readerOn(now, readerOn) && nfcOn
        if (on != readerOn) {
            readerOn = on
            val started = SystemClock.elapsedRealtimeNanos()
            if (on) start() else runCatching { adapter.disableReaderMode(activity) }
            val took = SystemClock.elapsedRealtimeNanos() - started
            if (took > SLOW_SWITCH_NS) Log.d(TAG, "Reader mode ${if (on) "on" else "off"} took ${took / 1_000_000} ms")
        }
        if (due != null && radio.resumed) main.postDelayed(tick, due)
    }

    private fun start() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        runCatching { adapter.enableReaderMode(activity, ::onTag, flags, extras) }
            .onFailure { Log.w(TAG, "Reader mode refused", it) }
    }

    /** Off the main thread: ask the tapped phone what it has, and act on it while the phones still touch. */
    private fun onTag(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        // Reading stays as it is until this tap is done: switching it would drop the link.
        radio.holding = true
        try {
            iso.connect()
            iso.timeout = 2_000
            var answer = answerFrom(iso.transceive(selectApdu()))
            // A room on its way: ask until its code is in.
            val deadline = now() + OPENING_WAIT_MS
            while (answer == TapAnswer.Opening && now() < deadline) {
                Thread.sleep(ASK_MS)
                answer = answerFrom(iso.transceive(selectApdu()))
            }
            when (answer) {
                is TapAnswer.Room -> {
                    Log.d(TAG, "Tapped a phone hosting room ${answer.code}")
                    main.post {
                        events.onCaught()
                        events.onCodeRead(answer.code)
                    }
                }
                TapAnswer.Ready -> claim(iso)
                TapAnswer.Opening -> Log.d(TAG, "The tapped phone's room never opened")
                null -> Log.d(TAG, "Tapped a phone with no room")
            }
        } catch (e: IOException) {
            Log.d(TAG, "Tap read failed", e)
        } catch (e: SecurityException) {
            // The tag went away mid-read on some devices.
            Log.d(TAG, "Tap went away", e)
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Tap closed under us", e)
        } catch (e: InterruptedException) {
            // The game is closing.
            Thread.currentThread().interrupt()
        } finally {
            runCatching { iso.close() }
            radio.holding = false
            main.post { update() }
        }
    }

    /** The tapped phone is ready for a fight: claim it, open a room for both (if not open already), hand it over. */
    private fun claim(iso: IsoDep) {
        val already = onMain(false) { events.hostingForTap() }
        if (!already && !onMain(false) { events.canHost() }) {
            Log.d(TAG, "Tapped a ready phone, but this one can't fight from here")
            return
        }
        if (!saidYes(iso.transceive(claimApdu()))) {
            Log.d(TAG, "The tapped phone turned the claim down")
            return
        }
        main.post { events.onCaught() }
        if (!already && !onMain(false) { events.onClaimed() }) return
        handOver(iso)
    }

    /** Waits for this phone's room to open, then hands its code to the claimed phone. */
    private fun handOver(iso: IsoDep) {
        val deadline = now() + HAND_OVER_WAIT_MS
        try {
            while (true) {
                val code = TapCard.shared.hosted
                if (code != null) {
                    val taken = saidYes(iso.transceive(handOverApdu(code)))
                    Log.d(TAG, if (taken) "Handed over room $code" else "The tapped phone passed on room $code")
                    main.post { events.onHandOver(taken) }
                    return
                }
                if (now() >= deadline || !onMain(false) { events.stillHosting() }) {
                    Log.d(TAG, "No room to hand over")
                    main.post { events.onHandOver(false) }
                    return
                }
                Thread.sleep(ASK_MS)
                if (!saidYes(iso.transceive(pingApdu()))) {
                    Log.d(TAG, "The tapped phone moved on")
                    main.post { events.onHandOver(false) }
                    return
                }
            }
        } catch (e: IOException) {
            // The phones parted: tap again, and the friend's phone reads this one's room.
            main.post { events.onHandOver(false) }
            throw e
        }
    }

    /**
     * Runs [block] on the main thread and waits (a second at most) for its answer, else gives [fallback] and makes
     * sure [block] never runs late.
     */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val state = AtomicInteger(WAITING)
        var result = fallback
        val done = CountDownLatch(1)
        main.post {
            if (!state.compareAndSet(WAITING, RUNNING)) return@post
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        if (done.await(1, TimeUnit.SECONDS)) return result
        if (state.compareAndSet(WAITING, ABANDONED)) return fallback
        // It started just in time: let it finish.
        done.await()
        return result
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        private const val WAITING = 0
        private const val RUNNING = 1
        private const val ABANDONED = 2

        fun create(activity: Activity, events: TapEvents): NfcTapReader? =
            NfcAdapter.getDefaultAdapter(activity)?.let { NfcTapReader(activity, it, events) }
    }
}
