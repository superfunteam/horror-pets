package team.superfun.horrorpets.ui

import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.data.Clock
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.mirroredEvents
import team.superfun.horrorpets.game.training.FIGHT_PREREQUISITE_NAMES
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.pvp.Hosted
import team.superfun.horrorpets.pvp.Joined
import team.superfun.horrorpets.pvp.PvpException
import team.superfun.horrorpets.pvp.PvpRelay
import team.superfun.horrorpets.pvp.Role
import team.superfun.horrorpets.pvp.RoomView
import team.superfun.horrorpets.pvp.TurnView
import team.superfun.horrorpets.ui.fight.nearlyDone

private const val NOW = 1_800_000_000_000L

/**
 * The relay's rules (netlify/functions/pvp.mjs) in memory, shared by every app in a test. Each app talks to it
 * through its own [app], which speaks that app's version.
 */
private class RelayHub {
    class Room(val id: String, val code: String, val v: Int, val seed: Int, val host: Fighter, val hostToken: String) {
        var guest: Fighter? = null
        var guestToken: String? = null
        var codeOpen = true
        var left: Role? = null
        val moves = mutableMapOf<Pair<Int, Role>, Move>()

        fun roleOf(token: String): Role = when (token) {
            hostToken -> Role.HOST
            guestToken -> Role.GUEST
            else -> throw PvpException.Offline()
        }
    }

    val rooms = mutableListOf<Room>()
    var offline = false
    private var next = 0

    fun app(version: Int = 1): PvpRelay = object : PvpRelay {
        fun up() {
            if (offline) throw PvpException.Offline()
        }

        fun room(id: String) = rooms.firstOrNull { it.id == id } ?: throw PvpException.NoRoom()

        override suspend fun create(me: Fighter): Hosted {
            up()
            val n = next++
            val room = Room("room$n", "${4821 + n}", version, seed = 1234 + n, host = me, hostToken = "host$n")
            rooms += room
            return Hosted(room.id, room.code, room.hostToken, room.seed)
        }

        override suspend fun join(code: String, me: Fighter): Joined {
            up()
            val room = rooms.firstOrNull { it.code == code && it.codeOpen && it.left == null } ?: throw PvpException.NoRoom()
            if (room.v != version) throw PvpException.Version(room.v)
            if (room.guest != null) throw PvpException.Full()
            room.guest = me
            room.guestToken = "guest-${room.id}"
            room.codeOpen = false
            return Joined(room.id, room.guestToken!!, room.seed, room.host)
        }

        override suspend fun room(room: String, token: String): RoomView {
            up()
            val r = room(room).also { it.roleOf(token) }
            return RoomView(r.guest, r.left)
        }

        override suspend fun lockIn(room: String, token: String, turn: Int, move: Move) {
            up()
            val r = room(room)
            r.moves.putIfAbsent(turn to r.roleOf(token), move)
        }

        override suspend fun turn(room: String, token: String, turn: Int): TurnView {
            up()
            val r = room(room).also { it.roleOf(token) }
            val host = r.moves[turn to Role.HOST]
            val guest = r.moves[turn to Role.GUEST]
            val both = host != null && guest != null
            return TurnView(host != null, guest != null, host.takeIf { both }, guest.takeIf { both }, r.left)
        }

        override suspend fun leave(room: String, token: String) {
            up()
            val r = room(room)
            if (r.left == null) r.left = r.roleOf(token)
        }
    }
}

/**
 * One app in a PvP test: a view model on a fresh game with [pet] selected, talking to [relay]. The pet has done the
 * training a new player owes before the first fight, so FIGHT (and the PvP behind it) is open.
 */
private class App(pet: PetState, relay: PvpRelay?) {
    private val trained = pet.copy(trainedGames = FIGHT_PREREQUISITE_NAMES)
    val scope = TestScope(StandardTestDispatcher())
    val hostCodes = mutableListOf<String?>()
    val vm = GameViewModel(
        store = object : SaveStore {
            var saved: GameState? = null

            override suspend fun load() = GameState(pets = mapOf(pet.id to trained), selected = pet.id, lastSeenMs = NOW)

            override suspend fun save(s: GameState) {
                saved = s
            }
        },
        clock = object : Clock {
            override fun nowMs() = NOW
        },
        rng = Random(3),
        scope = scope,
        pvpRelay = relay,
        onHostCode = { hostCodes += it },
        uptimeMs = { scope.testScheduler.currentTime },
    )

    val ui: UiState get() = vm.state.value
    val lobby: LobbyPhase get() = (ui.screen as Screen.PvpLobby).phase
    val fight: Screen.Fight get() = ui.screen as Screen.Fight

    init {
        vm.start()
        scope.advanceUntilIdle()
        vm.onStart()
        scope.advanceUntilIdle()
    }

    /** Lets [ms] go by: the relay's requests and this app's polls in that time all run. */
    fun pump(ms: Long = 1_000) {
        scope.runCurrent()
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    fun toLobby() {
        vm.onFightPressed()
        vm.onPvpPicked()
        check(ui.screen is Screen.PvpLobby) { "expected the lobby, got ${ui.screen}" }
    }

    fun finishAnim() = vm.onAnimFinished(ui.petAnim!!.token)
}

class PvpFlowTest {
    private val hub = RelayHub()
    /** A strong level-3 Jason who has already been offered the attacks he unlocked (so no learn screen comes up). */
    private fun jasonApp(relay: PvpRelay = hub.app()) =
        App(PetState(PetId.JASON, strength = 100.0, level = 3, known = setOf("jason_light_1", "jason_heavy_1")), relay)
    private fun freddyApp(relay: PvpRelay = hub.app()) = App(PetState(PetId.FREDDY, strength = 30.0), relay)

    /** Pumps both apps in step, so neither runs ahead of the other's requests. */
    private fun pumpBoth(a: App, b: App, ms: Long = 1_000) {
        a.scope.runCurrent()
        b.scope.runCurrent()
        a.pump(ms)
        b.pump(ms)
    }

    /** The host opens a room, the guest types its code, and both are on the VS card. */
    private fun meet(host: App, guest: App): String {
        host.toLobby()
        host.vm.onLobbyHost()
        host.pump(0)
        val code = (host.lobby as LobbyPhase.Hosting).code!!
        guest.toLobby()
        guest.vm.onLobbyJoin()
        code.forEach { guest.vm.onLobbyDigit(it.digitToInt()) }
        guest.pump(0)
        host.pump(2_000)
        return code
    }

    @Test
    fun pvpIsOnOfferOnlyWithARelay() {
        val offline = App(PetState(PetId.JASON), relay = null)
        assertFalse(offline.vm.pvpAvailable)
        offline.vm.onFightPressed()
        offline.vm.onPvpPicked()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), offline.ui.screen)
        assertTrue(jasonApp().vm.pvpAvailable)
    }

    @Test
    fun aHostGetsACodeAndOffersItToATap() {
        val host = jasonApp()
        host.toLobby()
        host.vm.onLobbyHost()
        assertEquals("opening", LobbyPhase.Hosting(), host.lobby)
        host.pump(0)
        assertEquals(LobbyPhase.Hosting("4821"), host.lobby)
        assertEquals(listOf<String?>("4821"), host.hostCodes)
    }

    @Test
    fun twoPlayersFightTheSameFightEachFromTheirOwnSide() {
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        assertEquals(Screen.Versus(PetId.FREDDY, pvp = true), host.ui.screen)
        assertEquals(Screen.Versus(PetId.JASON, pvp = true), guest.ui.screen)
        assertEquals(listOf("jason", "versus", "freddy"), host.ui.voice.takeLast(3))
        assertNull("the code is withdrawn once someone joins", host.hostCodes.last())

        host.vm.onVersusDone()
        guest.vm.onVersusDone()
        assertEquals(PetId.JASON, host.fight.fight.player.id)
        assertEquals(PetId.FREDDY, guest.fight.fight.player.id)
        assertEquals(host.fight.fight.mirrored(), guest.fight.fight)

        var turns = 0
        var hardened = false
        while (host.ui.screen is Screen.Fight) {
            host.vm.onMove(Move.STRIKE)
            assertTrue("the host waits for the guest", host.fight.pvp!!.waiting)
            guest.vm.onMove(Move.HEAVY)
            pumpBoth(host, guest)
            pumpBoth(host, guest)
            val h = host.fight
            val g = guest.fight
            assertNotNull("the turn plays on the host", host.ui.petAnim)
            assertNotNull("and on the guest", guest.ui.petAnim)
            assertEquals(Move.STRIKE, h.playerMove)
            assertEquals(Move.HEAVY, h.cpuMove)
            assertEquals(Move.HEAVY, g.playerMove)
            assertEquals(Move.STRIKE, g.cpuMove)
            assertEquals("both apps played the same turn", h.fight.mirrored(), g.fight)
            assertEquals(mirroredEvents(h.lastEvents), g.lastEvents)
            assertEquals("the music waits for the turn to play out", hardened, h.intense)
            assertEquals(hardened, g.intense)
            host.finishAnim()
            guest.finishAnim()
            if (host.ui.screen is Screen.Fight) {
                assertEquals("the music hardens on both apps at once", host.fight.intense, guest.fight.intense)
                assertEquals(
                    "once a turn that leaves either killer nearly done has played out, and stays so",
                    hardened || nearlyDone(host.fight.fight),
                    host.fight.intense,
                )
                hardened = host.fight.intense
            }
            check(++turns < 30) { "the fight never ended" }
        }
        assertTrue("Freddy dropped under the line before the knockout, and the music hardened", hardened)
        val hostResults = host.ui.screen as Screen.Results
        val guestResults = guest.ui.screen as Screen.Results
        assertTrue("strong Jason's strikes cut off every HEAVY", hostResults.won)
        assertFalse(guestResults.won)
        assertEquals(1, host.ui.game.pets.getValue(PetId.JASON).wins)
        assertEquals(1, guest.ui.game.pets.getValue(PetId.FREDDY).losses)
        assertEquals("beating a friend's killer unlocks no one", setOf(PetId.JASON), host.ui.game.pets.keys)
        assertEquals(listOf("you_win"), host.ui.voice.takeLast(1))
        host.pump(0)
        assertEquals("the room is left once the fight is over", Role.HOST, hub.rooms.single().left)
    }

    @Test
    fun aKnockoutFromAboveTheLineEndsAPvpFightPlainOnBothApps() {
        // Both swing HEAVY: Freddy goes from 64 to 24 (still above 30%), Jason from 95 to 55, then Freddy is knocked
        // out. Nobody was nearly done while the fight went on.
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        guest.vm.onVersusDone()
        var turns = 0
        while (host.ui.screen is Screen.Fight) {
            host.vm.onMove(Move.HEAVY)
            guest.vm.onMove(Move.HEAVY)
            pumpBoth(host, guest)
            pumpBoth(host, guest)
            assertFalse(host.fight.intense)
            assertFalse(guest.fight.intense)
            host.finishAnim()
            guest.finishAnim()
            check(++turns < 30) { "the fight never ended" }
        }
        assertEquals(2, turns)
        assertTrue((host.ui.screen as Screen.Results).won)
        assertFalse((guest.ui.screen as Screen.Results).won)
    }

    @Test
    fun eachPlayerFightsWithTheirOwnAttacksOnBothApps() {
        val host = App(
            PetState(PetId.JASON, level = 3, known = setOf("jason_light_1", "jason_heavy_1"), equipped = mapOf(Slot.HEAVY to "jason_heavy_1")),
            hub.app(),
        )
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        guest.vm.onVersusDone()
        assertEquals("Sleeping Bag Slam", host.fight.fight.player.kit.heavy.name)
        assertEquals("the guest sees the host's attacks too", "Sleeping Bag Slam", guest.fight.fight.cpu.kit.heavy.name)
        assertEquals(host.fight.fight.mirrored(), guest.fight.fight)
    }

    @Test
    fun theOtherPlayerLockingInFirstShowsAsReady() {
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        guest.vm.onVersusDone()
        guest.vm.onMove(Move.BLOCK)
        pumpBoth(host, guest)
        assertTrue(host.fight.pvp!!.opponentIn)
        assertFalse(host.fight.pvp!!.waiting)
        assertNull("no turn plays until both are in", host.ui.petAnim)

        guest.vm.onMove(Move.STRIKE) // already locked in: ignored
        assertEquals(Move.BLOCK, hub.rooms.single().moves[1 to Role.GUEST])
    }

    @Test
    fun aForfeitKeepsAnIntenseFightIntense() {
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        guest.vm.onVersusDone()
        // As in twoPlayersFightTheSameFightEachFromTheirOwnSide, until Freddy is nearly done.
        var turns = 0
        while (!host.fight.intense) {
            host.vm.onMove(Move.STRIKE)
            guest.vm.onMove(Move.HEAVY)
            pumpBoth(host, guest)
            pumpBoth(host, guest)
            host.finishAnim()
            guest.finishAnim()
            check(++turns < 30) { "the fight never got close" }
        }
        assertNull("the fight is still on", host.fight.fight.winner)
        assertTrue(guest.fight.intense)

        guest.vm.onMove(Move.RUN)
        assertTrue(guest.fight.fight.playerRan)
        assertTrue("the runner's forfeit plays out intense", guest.fight.intense)
        pumpBoth(host, guest)
        assertEquals("the host sees them run off", Move.RUN, host.fight.cpuMove)
        assertTrue("and so does the one left standing", host.fight.intense)
    }

    @Test
    fun runningForfeitsAndTheOtherPlayerWins() {
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        guest.vm.onVersusDone()

        guest.vm.onMove(Move.RUN)
        assertEquals(Move.RUN, guest.fight.playerMove)
        assertTrue(guest.fight.fight.playerRan)
        guest.finishAnim()
        assertTrue((guest.ui.screen as Screen.Results).ran)

        pumpBoth(host, guest)
        assertEquals("the host sees them run off", Move.RUN, host.fight.cpuMove)
        assertTrue(host.fight.fight.cpuRan)
        host.finishAnim()
        val results = host.ui.screen as Screen.Results
        assertTrue(results.won)
        assertNull("a forfeit is not ranked", results.reward.scored)
    }

    @Test
    fun aWrongCodeAVersionMismatchAndNoInternetAreExplained() {
        val host = jasonApp()
        meet(host, freddyApp()) // room 4821 is taken
        val late = freddyApp()
        late.toLobby()
        late.vm.onLobbyJoin()
        "4821".forEach { late.vm.onLobbyDigit(it.digitToInt()) }
        late.pump(0)
        assertEquals("joined, so the code is gone", LobbyPhase.Joining("", problem = PvpProblem.NO_ROOM), late.lobby)

        val other = jasonApp()
        other.toLobby()
        other.vm.onLobbyHost()
        other.pump(0)
        val code = (other.lobby as LobbyPhase.Hosting).code!!
        val old = freddyApp(hub.app(version = 2))
        old.toLobby()
        old.vm.onLobbyJoin()
        code.forEach { old.vm.onLobbyDigit(it.digitToInt()) }
        old.pump(0)
        assertEquals(LobbyPhase.Joining("", problem = PvpProblem.VERSION), old.lobby)

        hub.offline = true
        val cut = jasonApp()
        cut.toLobby()
        cut.vm.onLobbyHost()
        cut.pump(0)
        assertEquals(LobbyPhase.Menu(PvpProblem.OFFLINE), cut.lobby)
    }

    @Test
    fun theKeypadTypesErasesAndJoinsOnTheFourthDigit() {
        val guest = freddyApp()
        guest.toLobby()
        guest.vm.onLobbyJoin()
        guest.vm.onLobbyDigit(4)
        guest.vm.onLobbyDigit(8)
        guest.vm.onLobbyErase()
        guest.vm.onLobbyDigit(9)
        assertEquals(LobbyPhase.Joining("49"), guest.lobby)
        guest.vm.onLobbyDigit(1)
        guest.vm.onLobbyDigit(1)
        assertEquals(LobbyPhase.Joining("4911", busy = true), guest.lobby)
        guest.vm.onLobbyDigit(5) // busy: ignored
        assertEquals(LobbyPhase.Joining("4911", busy = true), guest.lobby)
    }

    @Test
    fun aTapJoinsFromTheMenuButNeverWhileHosting() {
        val host = jasonApp()
        host.toLobby()
        host.vm.onLobbyHost()
        host.pump(0)
        val guest = freddyApp()
        guest.toLobby()
        guest.vm.onTapCode("4821")
        guest.pump(0)
        assertEquals(Screen.Versus(PetId.JASON, pvp = true), guest.ui.screen)

        host.vm.onTapCode("9999") // hosting: a tap reads this phone, it does not join
        assertTrue(host.lobby is LobbyPhase.Hosting)
    }

    /** Two phones on Home, tapped together: one claims the other, opens a room and hands its code over. */
    @Test
    fun aTapBetweenTwoPhonesOnHomeStartsAFight() {
        val reader = jasonApp()
        val card = freddyApp()
        assertEquals(Screen.Home, reader.ui.screen)
        assertEquals(Screen.Home, card.ui.screen)

        assertTrue("the reader would host", reader.vm.canHostForTap())
        assertEquals("asking changes nothing", Screen.Home, reader.ui.screen)
        assertTrue("the card can be claimed", card.vm.canBeTapped())
        card.vm.onTappedAsCard()
        assertEquals(LobbyPhase.Tapped(), card.lobby)
        assertTrue(card.ui.sfx.contains("gotcha"))
        assertTrue(reader.vm.onHostForTap())
        assertEquals("no code on show", LobbyPhase.Hosting(fromTap = true), reader.lobby)
        assertTrue(reader.vm.hostingForTap())
        reader.pump(0)
        assertEquals(LobbyPhase.Hosting("4821", fromTap = true), reader.lobby)
        assertEquals("the code goes to the tap", listOf<String?>("4821"), reader.hostCodes)
        reader.vm.onTapHandOver(ok = true)

        assertTrue("handed over: the card takes it", card.vm.onTapHandedCode("4821"))
        assertEquals("still no code on show", LobbyPhase.Joining("4821", busy = true, fromTap = true), card.lobby)
        card.pump(0)
        reader.pump(500)
        assertEquals("straight to the VS card", Screen.Versus(PetId.JASON, pvp = true), card.ui.screen)
        assertEquals(Screen.Versus(PetId.FREDDY, pvp = true), reader.ui.screen)
    }

    @Test
    fun phonesPartedBeforeTheHandOverTapAgain() {
        val reader = jasonApp()
        val card = freddyApp()
        card.vm.onTappedAsCard()
        reader.vm.onHostForTap()
        reader.pump(0)
        reader.vm.onTapHandOver(ok = false)
        assertEquals(LobbyPhase.Hosting("4821", fromTap = true, tapAgain = true), reader.lobby)
        card.vm.onTapLost()
        assertEquals(LobbyPhase.Tapped(again = true), card.lobby)

        // Tapped again: the card, reading now, reads the reader's room.
        card.vm.onTapCode("4821")
        card.pump(0)
        reader.pump(500)
        assertEquals(Screen.Versus(PetId.JASON, pvp = true), card.ui.screen)
        assertEquals(Screen.Versus(PetId.FREDDY, pvp = true), reader.ui.screen)
    }

    @Test
    fun aTapFromHomeJoinsAPhoneThatHosts() {
        val host = jasonApp()
        host.toLobby()
        host.vm.onLobbyHost()
        host.pump(0)
        val guest = freddyApp()
        assertEquals(Screen.Home, guest.ui.screen)
        guest.vm.onTapCode("4821")
        assertEquals("no code on show", LobbyPhase.Joining("4821", busy = true, fromTap = true), guest.lobby)
        guest.pump(0)
        assertEquals(Screen.Versus(PetId.JASON, pvp = true), guest.ui.screen)
    }

    @Test
    fun aClaimedPhoneAsksForAnotherTapIfNoRoomComes() {
        val card = freddyApp()
        card.vm.onTappedAsCard()
        card.pump(TAP_WAIT_MS - 1)
        assertEquals(LobbyPhase.Tapped(), card.lobby)
        card.pump(1)
        assertEquals(LobbyPhase.Tapped(again = true), card.lobby)
        assertTrue("its turn to read, and claimable again", card.vm.canBeTapped())
    }

    @Test
    fun aClaimedPhoneOpensNoRoomOfItsOwn() {
        val card = freddyApp()
        card.vm.onTappedAsCard()
        assertFalse(card.vm.canHostForTap())
        card.vm.onTapLost()
        assertTrue("asking for another tap, it may", card.vm.canHostForTap())
    }

    @Test
    fun aFailedTapJoinAsksForAnotherTapWithTheProblem() {
        val guest = freddyApp()
        assertTrue(guest.vm.onTapCode("9999"))
        guest.pump(0)
        assertEquals(LobbyPhase.Tapped(again = true, problem = PvpProblem.NO_ROOM), guest.lobby)
        assertFalse("phones still touching don't try again at once", guest.vm.onTapCode("9999"))
        guest.pump(TAP_RETRY_MS)
        assertTrue("a moment later, they may", guest.vm.onTapCode("9999"))
    }

    @Test
    fun aTapNeverJoinsAnotherVersionsRoomTwice() {
        val old = App(PetState(PetId.FREDDY, strength = 30.0), hub.app(version = 0))
        old.toLobby()
        old.vm.onLobbyHost()
        old.pump(0)
        val code = (old.lobby as LobbyPhase.Hosting).code!!
        val guest = freddyApp()
        guest.vm.onTapCode(code)
        guest.pump(0)
        assertEquals(LobbyPhase.Tapped(again = true, problem = PvpProblem.VERSION), guest.lobby)
        guest.pump(TAP_RETRY_MS)
        assertFalse(guest.vm.onTapCode(code))
    }

    @Test
    fun aRoomATapOpenedIsClosedIfTheFriendNeverJoins() {
        val reader = jasonApp()
        reader.vm.onHostForTap()
        reader.pump(0)
        assertEquals(LobbyPhase.Hosting("4821", fromTap = true), reader.lobby)
        reader.pump(TAP_GUEST_MS)
        assertEquals("ask for another tap, which starts over", LobbyPhase.Tapped(again = true), reader.lobby)
        assertEquals(Role.HOST, hub.rooms.single().left)
        assertNull(reader.hostCodes.last())
        assertTrue(reader.vm.canBeTapped())
    }

    @Test
    fun steppingOutOfAJoinUnderWayLeavesTheRoom() {
        val host = jasonApp()
        host.toLobby()
        host.vm.onLobbyHost()
        host.pump(0)
        val guest = freddyApp()
        guest.vm.onTapCode("4821")
        guest.vm.onLobbyBack()
        assertEquals(Screen.Home, guest.ui.screen)
        guest.pump(0)
        assertEquals("the relay let it in, so it left", Role.GUEST, hub.rooms.single().left)
        assertEquals(Screen.Home, guest.ui.screen)
    }

    @Test
    fun cancellingATapGoesHomeAndIgnoresTapsForAMoment() {
        val card = freddyApp()
        card.vm.onTappedAsCard()
        card.vm.onLobbyBack()
        assertEquals(Screen.Home, card.ui.screen)
        assertFalse("the code that comes anyway is turned down", card.vm.onTapHandedCode("4821"))
        assertFalse(card.vm.canBeTapped())
        card.vm.onTapCode("4821")
        assertEquals("and so is the friend's room, read a moment later", Screen.Home, card.ui.screen)
        card.pump(TAP_WAIT_MS)
        assertEquals(Screen.Home, card.ui.screen)

        val reader = jasonApp()
        reader.vm.onHostForTap()
        reader.pump(0)
        reader.vm.onLobbyBack()
        reader.pump(0)
        assertEquals(Screen.Home, reader.ui.screen)
        assertEquals("its room is closed", Role.HOST, hub.rooms.single().left)
        assertFalse(reader.vm.canHostForTap())
    }

    @Test
    fun aStarvingPetTakesNoTapFight() {
        val starving = App(PetState(PetId.FREDDY, hunger = 90.0), hub.app())
        assertFalse(tapFightOpen(starving.ui))
        assertFalse(starving.vm.canBeTapped())
        starving.vm.onTappedAsCard()
        assertEquals(Screen.Home, starving.ui.screen)
        assertFalse(starving.vm.canHostForTap())
        assertFalse(starving.vm.onHostForTap())
        starving.vm.onTapCode("4821")
        assertEquals(Screen.Home, starving.ui.screen)
    }

    @Test
    fun aTapIsIgnoredWithoutARelay() {
        val offline = App(PetState(PetId.JASON), relay = null)
        assertFalse(offline.vm.canBeTapped())
        assertFalse(offline.vm.onHostForTap())
        offline.vm.onTapCode("4821")
        assertEquals(Screen.Home, offline.ui.screen)
    }

    @Test
    fun aTapHostWhoseRoomWillNotOpenSaysSo() {
        hub.offline = true
        val reader = jasonApp()
        assertTrue(reader.vm.onHostForTap())
        reader.pump(0)
        assertEquals(LobbyPhase.Tapped(again = true, problem = PvpProblem.OFFLINE), reader.lobby)
        assertFalse("so the tap stops waiting for a code", reader.vm.tapHosting())
        assertFalse("and phones still touching don't try again at once", reader.vm.canBeTapped())
        reader.pump(TAP_RETRY_MS)
        assertTrue(reader.vm.canBeTapped())
    }

    @Test
    fun backingOutOfHostingClosesTheRoom() {
        val host = jasonApp()
        host.toLobby()
        host.vm.onLobbyHost()
        host.pump(0)
        host.vm.onLobbyBack()
        host.pump(0)
        assertEquals(LobbyPhase.Menu(), host.lobby)
        assertNull(host.hostCodes.last())
        assertEquals(Role.HOST, hub.rooms.single().left)
        host.vm.onLobbyBack()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), host.ui.screen)
    }

    @Test
    fun losingTheRelayMidFightCallsItOff() {
        val host = jasonApp()
        val guest = freddyApp()
        meet(host, guest)
        host.vm.onVersusDone()
        hub.offline = true
        repeat(40) { host.pump(1_000) }
        assertEquals(LobbyPhase.Menu(PvpProblem.LOST), host.lobby)
    }
}
