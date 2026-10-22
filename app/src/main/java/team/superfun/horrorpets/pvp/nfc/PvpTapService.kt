package team.superfun.horrorpets.pvp.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** The room code this phone is hosting, for a tap to read; null when it has none open. */
object HostedCode {
    @Volatile
    var current: String? = null
}

/**
 * This phone as a contactless card holding its PvP room's code, for another phone to read with a tap (see
 * TapProtocol.kt). Android binds it when a reader selects [PVP_AID]; it answers with
 * [HostedCode.current], or "not found" when no room is open.
 */
class PvpTapService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        if (isPvpSelect(commandApdu)) codeResponse(HostedCode.current) else UNKNOWN_COMMAND

    override fun onDeactivated(reason: Int) = Unit
}
