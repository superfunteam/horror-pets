package team.superfun.horrorpets.pvp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.PetId

/** Which side of a PvP room a player is: the one who opened it (and shared its code) or the one who joined. */
enum class Role { HOST, GUEST }

/** A room this player just opened: its [code] to share, and what the relay will know this player by. */
data class Hosted(val room: String, val code: String, val token: String, val seed: Int)

/** A room this player just joined, with the [host]'s fighter. */
data class Joined(val room: String, val token: String, val seed: Int, val host: Fighter)

/** A room as it stands: the guest's fighter once someone has joined, and who has [left], if anyone. */
data class RoomView(val guest: Fighter?, val left: Role?)

/**
 * One turn as it stands: whether each side has locked in, both moves once both have (never one before),
 * and who has [left], if anyone.
 */
data class TurnView(val hostIn: Boolean, val guestIn: Boolean, val host: Move?, val guest: Move?, val left: Role?) {
    fun isIn(role: Role): Boolean = if (role == Role.HOST) hostIn else guestIn
    fun moveOf(role: Role): Move? = if (role == Role.HOST) host else guest
}

/** Why the relay said no. */
sealed class PvpException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No open room has that code (never was, already joined, or gone stale). */
    class NoRoom : PvpException("no room")

    /** Someone else joined first. */
    class Full : PvpException("room full")

    /** The room's host runs a different version of the game, whose fights would not play out the same. */
    class Version(val theirs: Int) : PvpException("version $theirs")

    /** The relay could not be reached, or answered with something this app does not understand. */
    class Offline(cause: Throwable? = null) : PvpException("offline", cause)
}

/**
 * The PvP relay at pets.superfun.games/api/pvp (netlify/functions/pvp.mjs), as the app talks to it. The relay
 * only passes things between the two players; the fight is played by both apps (see [resolvePvpTurn]).
 */
interface PvpRelay {
    suspend fun create(me: Fighter): Hosted

    suspend fun join(code: String, me: Fighter): Joined

    suspend fun room(room: String, token: String): RoomView

    /** Locks in [move] for [turn]; locking in again is harmless (the first one stands). */
    suspend fun lockIn(room: String, token: String, turn: Int, move: Move)

    suspend fun turn(room: String, token: String, turn: Int): TurnView

    suspend fun leave(room: String, token: String)
}

/** The live relay at [base] (no trailing slash), for an app whose fights play like [version]'s. */
class HttpPvpRelay(private val base: String, private val version: Int) : PvpRelay {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class WireFighter(
        val id: String,
        val level: Int,
        val maxHp: Int,
        val strength: Double,
        val mood: Int,
        val attacks: List<String>? = null,
    )

    @Serializable
    private data class Opening(val v: Int, val fighter: WireFighter)

    @Serializable
    private data class Created(val room: String, val code: String, val token: String, val seed: Int)

    @Serializable
    private data class Entered(val room: String, val token: String, val seed: Int, val host: WireFighter)

    @Serializable
    private data class WireRoom(val guest: WireFighter? = null, val left: String? = null)

    @Serializable
    private data class LockIn(val token: String, val move: String)

    @Serializable
    private data class WireTurn(val hostIn: Boolean, val guestIn: Boolean, val host: String? = null, val guest: String? = null, val left: String? = null)

    @Serializable
    private data class Token(val token: String)

    @Serializable
    private data class Failure(val error: String, val v: Int? = null)

    override suspend fun create(me: Fighter): Hosted {
        val c = json.decodeFromString<Created>(call("POST", "/rooms", json.encodeToString(Opening(version, wire(me)))))
        return Hosted(c.room, c.code, c.token, c.seed)
    }

    override suspend fun join(code: String, me: Fighter): Joined {
        val e = json.decodeFromString<Entered>(call("POST", "/join/$code", json.encodeToString(Opening(version, wire(me)))))
        return Joined(e.room, e.token, e.seed, fighterOf(e.host))
    }

    override suspend fun room(room: String, token: String): RoomView {
        val r = json.decodeFromString<WireRoom>(call("GET", "/rooms/$room?token=${q(token)}", null))
        return RoomView(r.guest?.let(::fighterOf), roleOf(r.left))
    }

    override suspend fun lockIn(room: String, token: String, turn: Int, move: Move) {
        call("POST", "/rooms/$room/turns/$turn", json.encodeToString(LockIn(token, move.name)))
    }

    override suspend fun turn(room: String, token: String, turn: Int): TurnView {
        val t = json.decodeFromString<WireTurn>(call("GET", "/rooms/$room/turns/$turn?token=${q(token)}", null))
        return TurnView(t.hostIn, t.guestIn, t.host?.let(::moveOf), t.guest?.let(::moveOf), roleOf(t.left))
    }

    override suspend fun leave(room: String, token: String) {
        call("POST", "/rooms/$room/leave", json.encodeToString(Token(token)))
    }

    private fun wire(f: Fighter) =
        WireFighter(f.id.name, f.level, f.maxHp, f.strength, f.mood, attacks = listOf(f.kit.light.id, f.kit.heavy.id, f.kit.special.id))

    private fun fighterOf(w: WireFighter): Fighter {
        val id = PetId.entries.firstOrNull { it.name == w.id } ?: throw PvpException.Offline()
        return pvpFighter(Fighter(id, w.level, w.maxHp, w.maxHp, w.strength, w.mood, meter = 0, kit = kitFromIds(id, w.level, w.attacks)))
    }

    private fun moveOf(name: String): Move = Move.entries.firstOrNull { it.name == name } ?: throw PvpException.Offline()

    private fun roleOf(left: String?): Role? = when (left) {
        "host" -> Role.HOST
        "guest" -> Role.GUEST
        else -> null
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** One request; the body of a 2xx answer, or the [PvpException] its error means. */
    private suspend fun call(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw PvpException.Offline(e)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status < 400) return@withContext text
            val failure = runCatching { json.decodeFromString<Failure>(text) }.getOrNull()
            throw when (failure?.error) {
                "no_room" -> PvpException.NoRoom()
                "full" -> PvpException.Full()
                "version" -> PvpException.Version(failure.v ?: 0)
                else -> PvpException.Offline(IOException("HTTP $status ${failure?.error.orEmpty()}"))
            }
        } catch (e: IOException) {
            throw PvpException.Offline(e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
    }
}
