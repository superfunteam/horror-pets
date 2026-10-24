package team.superfun.horrorpets.pvp.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val TAG = "PvpTap"

/** How long a reader waits for its own room to open, to hand its code to the phone it found ready. */
private const val HAND_OVER_WAIT_MS = 7_000L

/** How often it asks that phone "still there?" meanwhile, so it stops once the phones part. */
private const val PING_MS = 150L

/** What the game does with what a tap reads. Called on the main thread. */
interface TapEvents {
    /** The tapped phone hosts a room: join it. */
    fun onCodeRead(code: String)

    /**
     * The tapped phone is ready for a tap fight and hosts no room: open one for it, returning whether this phone
     * does. Its code is then handed over once it is in [TapCard.hosted].
     */
    fun onReadyFound(): Boolean

    /** Whether this phone is still opening (or waiting in) the room it opened for a tap. */
    fun stillHosting(): Boolean
}

/**
 * A phone's NFC for PvP taps: reading other phones, when [TapRadio] says to (see [setMode]). A tapped phone that
 * hosts a room has its code read and [events] joins it. A tapped phone that is ready for a tap fight has [events]
 * open a room for it, and gets that room's code over the same tap. The rest of the time this phone is a card
 * ([TapCard]), and while it hosts a room it makes sure a reader reaches this app's card ([preferHosting]).
 *
 * Reading only runs while [activity] is resumed ([onResume], [onPause]). [create] returns null on a device with no
 * NFC at all (every TV).
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

    fun setMode(mode: TapMode) {
        radio.setMode(mode, now())
        update()
    }

    /** A reader found this phone ready: stay a card, whatever the turns say, until [release]. */
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
        update()
    }

    fun onPause() {
        radio.resumed = false
        update()
        runCatching { preferHosting(false) }
    }

    /** While this phone hosts a room (and is in front), a tapping reader is routed to this app's card service. */
    fun preferHosting(hosting: Boolean) {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return
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
        val on = radio.readerOn(now)
        if (on != readerOn) {
            readerOn = on
            if (on) start() else runCatching { adapter.disableReaderMode(activity) }
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

    /** On the NFC thread: ask the tapped phone what it has, and act on it while the phones still touch. */
    private fun onTag(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        // Reading stays on until this tap is done, whatever the turns say.
        radio.holding = true
        try {
            iso.connect()
            iso.timeout = 2_000
            when (val answer = answerFrom(iso.transceive(selectApdu()))) {
                is TapAnswer.Room -> {
                    Log.d(TAG, "Tapped a phone hosting room ${answer.code}")
                    main.post { events.onCodeRead(answer.code) }
                }
                TapAnswer.Ready -> handOver(iso)
                null -> Log.d(TAG, "Tapped a phone with no room")
            }
        } catch (e: IOException) {
            Log.d(TAG, "Tap read failed", e)
        } catch (e: SecurityException) {
            // The tag went away mid-read on some devices.
            Log.d(TAG, "Tap went away", e)
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Tap closed under us", e)
        } finally {
            runCatching { iso.close() }
            radio.holding = false
            main.post { update() }
        }
    }

    /** On the NFC thread: the tapped phone is ready, so open a room for it and hand over its code. */
    private fun handOver(iso: IsoDep) {
        Log.d(TAG, "Tapped a phone that is ready for a fight")
        if (!onMain(false) { events.onReadyFound() }) return
        val deadline = SystemClock.elapsedRealtime() + HAND_OVER_WAIT_MS
        while (true) {
            val code = TapCard.shared.hosted
            if (code != null) {
                val taken = handOverTaken(iso.transceive(handOverApdu(code)))
                Log.d(TAG, if (taken) "Handed over room $code" else "The tapped phone passed on room $code")
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline || !onMain(false) { events.stillHosting() }) {
                Log.d(TAG, "No room to hand over")
                return
            }
            Thread.sleep(PING_MS)
            // Throws once the phones have parted: then the friend taps again to read this phone's code.
            iso.transceive(pingApdu())
        }
    }

    /** Runs [block] on the main thread and waits (a second at most) for its answer, else [fallback]. */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        var result = fallback
        val done = CountDownLatch(1)
        main.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        return if (done.await(1, TimeUnit.SECONDS)) result else fallback
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        fun create(activity: Activity, events: TapEvents): NfcTapReader? =
            NfcAdapter.getDefaultAdapter(activity)?.let { NfcTapReader(activity, it, events) }
    }
}
