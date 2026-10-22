package team.superfun.horrorpets.pvp.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import java.io.IOException
import team.superfun.horrorpets.ui.pvp.TapToJoin

private const val TAG = "PvpTap"

/**
 * A phone's NFC for PvP: reading a room code off a hosting phone tapped against this one ([listen], reader
 * mode), and, while this phone hosts, making sure its own [PvpTapService] is the one a reader reaches
 * ([preferHosting]). Reader mode only runs while [activity] is resumed: [onResume] and [onPause] carry a
 * [listen] across a trip to the background.
 *
 * [create] returns null on a device with no NFC at all (every TV), so the lobby never offers the tap there.
 */
class NfcTapReader private constructor(private val activity: Activity, private val adapter: NfcAdapter) : TapToJoin {
    private var onCode: ((String) -> Unit)? = null
    private var resumed = false

    override fun listen(onCode: (String) -> Unit) {
        this.onCode = onCode
        if (resumed) start()
    }

    override fun stop() {
        onCode = null
        if (resumed) runCatching { adapter.disableReaderMode(activity) }
    }

    fun onResume() {
        resumed = true
        if (onCode != null) start()
    }

    fun onPause() {
        resumed = false
        runCatching { adapter.disableReaderMode(activity) }
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

    private fun start() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        runCatching { adapter.enableReaderMode(activity, ::onTag, flags, extras) }
            .onFailure { Log.w(TAG, "Reader mode refused", it) }
    }

    /** On the NFC thread: ask the tapped phone for its room code, and hand it to the lobby on the main thread. */
    private fun onTag(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        val code = try {
            iso.connect()
            iso.timeout = 2_000
            codeFrom(iso.transceive(selectApdu()))
        } catch (e: IOException) {
            Log.d(TAG, "Tap read failed", e)
            null
        } catch (e: SecurityException) {
            // The tag went away mid-read on some devices.
            null
        } finally {
            runCatching { iso.close() }
        }
        Log.d(TAG, "Tapped a phone: ${if (code == null) "no room" else "room $code"}")
        if (code != null) activity.runOnUiThread { onCode?.invoke(code) }
    }

    companion object {
        fun create(activity: Activity): NfcTapReader? = NfcAdapter.getDefaultAdapter(activity)?.let { NfcTapReader(activity, it) }
    }
}
