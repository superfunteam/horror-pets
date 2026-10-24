package team.superfun.horrorpets.pvp.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * This phone as a contactless card for another phone to tap (see TapProtocol.kt). Android binds it when a reader
 * selects [PVP_AID]; [TapCard.shared] answers: the room this phone hosts, that it is ready for a tap fight, or "not
 * found".
 */
class PvpTapService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = TapCard.shared.answer(commandApdu)

    override fun onDeactivated(reason: Int) = TapCard.shared.deactivated()
}
