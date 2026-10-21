package team.superfun.horrorpets.fight

import kotlin.math.roundToInt
import kotlin.random.Random
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.spec

/** Hit points for a pet of [level] and [hunger] (0 = full, 100 = starving), times its passive's (Jason is built tougher). */
internal fun maxHpFor(id: PetId, level: Int, hunger: Double): Int {
    val hungerFactor = lerp(1.0, FightTuning.STARVING_HP_FACTOR, clampStat(hunger) / Tuning.STAT_MAX)
    val hp = (FightTuning.BASE_HP + FightTuning.HP_PER_LEVEL * level) * hungerFactor * id.spec.passive.hpMult
    return hp.roundToInt().coerceAtLeast(1)
}

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** The player's pet as a fresh fighter: full hit points, empty meter, and its live strength and mood. */
fun buildFighter(p: PetState): Fighter {
    val maxHp = maxHpFor(p.id, p.level, p.hunger)
    return Fighter(
        id = p.id,
        level = p.level,
        maxHp = maxHp,
        hp = maxHp,
        strength = p.strength,
        mood = mood(p),
        meter = 0,
    )
}

/**
 * The CPU's fighter, scaled to [player]. Draws, in this order: level offset (`nextInt(-1, 2)`),
 * then strength offset (`nextInt(-10, 11)`).
 */
fun buildOpponent(id: PetId, player: PetState, rng: Random): Fighter {
    val spread = FightTuning.OPPONENT_LEVEL_SPREAD
    val level = (player.level + rng.nextInt(-spread, spread + 1)).coerceAtLeast(1)
    val strengthSpread = FightTuning.OPPONENT_STRENGTH_SPREAD
    val strength = clampStat(player.strength + rng.nextInt(-strengthSpread, strengthSpread + 1))
    val maxHp = maxHpFor(id, level, FightTuning.OPPONENT_HUNGER)
    return Fighter(
        id = id,
        level = level,
        maxHp = maxHp,
        hp = maxHp,
        strength = strength,
        mood = FightTuning.OPPONENT_MOOD,
        meter = 0,
    )
}

/** Turn 1 of a fight between [player]'s pet and a freshly built [opponent]. Draws like [buildOpponent]. */
fun startFight(player: PetState, opponent: PetId, rng: Random): FightState =
    FightState(player = buildFighter(player), cpu = buildOpponent(opponent, player, rng))

/**
 * Plays one turn: both sides choose a move and the two are resolved simultaneously.
 *
 * ## The matrix
 * Which attacks happen is decided by the two moves alone; dice only decide whether and how hard a
 * landed swing hits.
 * - STRIKE vs STRIKE, HEAVY vs HEAVY: both sides attack.
 * - STRIKE vs HEAVY: the striker attacks and the heavy side gets [FightEvent.Interrupted] and does
 *   not attack. The interrupt happens even if the strike then misses.
 * - HEAVY vs BLOCK: the heavy attacks in full (the block is broken: no [FightEvent.Blocked], no counter).
 * - BLOCK vs STRIKE: [FightEvent.Blocked], the strike is absorbed (it does not attack) and the
 *   blocker counters with a [FightEvent.Hit] (`move = STRIKE`, `counter = true`).
 * - BLOCK vs BLOCK, BLOCK vs HEAVY/SPECIAL: a block that does not meet a STRIKE does nothing.
 * - SPECIAL always attacks and always lands; the other side's STRIKE or HEAVY also attacks (no
 *   interrupt), and a BLOCK against it does nothing (no [FightEvent.Blocked], no counter).
 * - RUN (player only): the fight ends at once with `winner = CPU` and `playerRan = true`. No
 *   events, no dice, and `turn` and `playerHistory` are left as they were.
 *
 * ## Damage, crits, misses, meter
 * Damage is `(8 + strength / 8)` times variance (uniform 0.85..1.15) times the move (STRIKE 1.0, or
 * 1.15 for Chucky; HEAVY 1.8; counter 0.5, or 1.0 for Pinhead; SPECIAL 2.5) times 1.75 on a crit, times
 * 0.85 when the defender is Michael, rounded once at the end and never below 1. Specials and counters
 * can crit but never miss. Attacks against GHOSTFACE miss 0.07 more often (STRIKE and HEAVY only).
 * Every per-pet number comes from the pet's [Passive][team.superfun.horrorpets.model.Passive] in the roster.
 * Each landed hit (counters included, specials excluded) adds 25 to the attacker's meter, capped
 * at 100; [FightEvent.SpecialReady] is emitted on the hit that takes it from below 100 to 100. A
 * SPECIAL needs a full meter and resets it to 0.
 *
 * ## Dice (the contract for tests and replays)
 * Only [Random.nextDouble] is used. Every attack that happens draws exactly three doubles, always,
 * even when one of them goes unused (a special or counter ignores its miss roll):
 * 1. miss roll: the attack misses when `roll < missChance`;
 * 2. variance roll: damage variance is `1 + (2 * roll - 1) * 0.15`;
 * 3. crit roll: the attack crits when `roll < critChance`, where
 *    `critChance = 0.05 + mood / 100 * 0.15`, plus the attacker's passive bonus (0.08 for FREDDY).
 *
 * PLAYER's attack (if any) is rolled before the CPU's. An attack that does not happen (an absorbed
 * STRIKE, an interrupted HEAVY, a BLOCK that countered nothing) draws nothing, and RUN draws nothing.
 *
 * ## Events and results
 * Events come in a fixed order: everything PLAYER's move caused, then everything the CPU's move
 * caused, then [FightEvent.Ko]. One side's group reads: [FightEvent.Blocked] (blocking sides only),
 * the Hit or Miss, [FightEvent.Interrupted] (a STRIKE that cut off a HEAVY), then
 * [FightEvent.SpecialReady].
 *
 * Damage lands simultaneously and hit points floor at 0. The side at 0 loses; if both are at 0 the
 * side that dealt more damage this turn wins (the dealt damage, not capped by remaining hit
 * points), and PLAYER wins a tie. Only the loser gets a [FightEvent.Ko], so the winner of a double
 * KO is left standing at 0 hp. The returned state has `turn + 1` and the player's move appended to
 * `playerHistory`.
 *
 * @throws IllegalStateException if the fight already has a winner.
 * @throws IllegalArgumentException if the CPU plays RUN, or either side plays SPECIAL with a meter
 *   below 100.
 */
fun resolveTurn(s: FightState, playerMove: Move, cpuMove: Move, rng: Random): TurnResult {
    check(s.winner == null) { "The fight is already over: ${s.winner} won" }
    require(cpuMove != Move.RUN) { "Only the player can run" }
    requireSpecialReady(Side.PLAYER, s.player, playerMove)
    requireSpecialReady(Side.CPU, s.cpu, cpuMove)

    if (playerMove == Move.RUN) {
        return TurnResult(s.copy(winner = Side.CPU, playerRan = true), emptyList())
    }

    val player = act(Side.PLAYER, s.player, s.cpu, playerMove, cpuMove, rng)
    val cpu = act(Side.CPU, s.cpu, s.player, cpuMove, playerMove, rng)

    val playerHp = (s.player.hp - cpu.damage).coerceAtLeast(0)
    val cpuHp = (s.cpu.hp - player.damage).coerceAtLeast(0)
    val winner = when {
        playerHp > 0 && cpuHp > 0 -> null
        cpuHp == 0 && playerHp > 0 -> Side.PLAYER
        playerHp == 0 && cpuHp > 0 -> Side.CPU
        cpu.damage > player.damage -> Side.CPU // both down: the bigger hitter wins, a tie goes to PLAYER
        else -> Side.PLAYER
    }

    val events = player.events + cpu.events + listOfNotNull(winner?.let { FightEvent.Ko(it.other()) })
    val next = s.copy(
        player = s.player.copy(hp = playerHp, meter = player.meter),
        cpu = s.cpu.copy(hp = cpuHp, meter = cpu.meter),
        turn = s.turn + 1,
        playerHistory = s.playerHistory + playerMove,
        winner = winner,
    )
    return TurnResult(next, events)
}

private fun requireSpecialReady(side: Side, f: Fighter, move: Move) {
    require(move != Move.SPECIAL || f.meter >= FightTuning.METER_MAX) {
        "$side cannot use SPECIAL with the meter at ${f.meter}/${FightTuning.METER_MAX}"
    }
}

/** What one side's move did this turn: its events, the damage it dealt, and its meter afterwards. */
private class Action(val events: List<FightEvent>, val damage: Int, val meter: Int)

private fun act(side: Side, me: Fighter, other: Fighter, myMove: Move, otherMove: Move, rng: Random): Action {
    val events = mutableListOf<FightEvent>()
    val counter = myMove == Move.BLOCK && otherMove == Move.STRIKE
    if (counter) events += FightEvent.Blocked(side)

    // Does a swing of mine happen at all? Decided by the moves, never by the dice.
    val attacks = when (myMove) {
        Move.STRIKE -> otherMove != Move.BLOCK // absorbed by a block
        Move.HEAVY -> otherMove != Move.STRIKE // interrupted by a strike
        Move.SPECIAL -> true
        Move.BLOCK -> counter
        Move.RUN -> false
    }

    var damage = 0
    var landed = false
    if (attacks) {
        val missRoll = rng.nextDouble()
        val varianceRoll = rng.nextDouble()
        val critRoll = rng.nextDouble()
        val hitMove = if (counter) Move.STRIKE else myMove
        if (missRoll < missChance(myMove, counter, other)) {
            events += FightEvent.Miss(side, myMove)
        } else {
            val crit = critRoll < critChance(me)
            damage = damageOf(me, other, moveMultiplier(me, myMove, counter), varianceRoll, crit)
            events += FightEvent.Hit(side, hitMove, damage, crit, counter)
            landed = true
        }
    }

    if (myMove == Move.STRIKE && otherMove == Move.HEAVY) events += FightEvent.Interrupted(side.other())

    val meter = when {
        myMove == Move.SPECIAL -> 0
        landed -> (me.meter + FightTuning.METER_PER_HIT).coerceAtMost(FightTuning.METER_MAX)
        else -> me.meter
    }
    if (meter == FightTuning.METER_MAX && me.meter < FightTuning.METER_MAX) events += FightEvent.SpecialReady(side)
    return Action(events, damage, meter)
}

private fun missChance(move: Move, counter: Boolean, defender: Fighter): Double {
    if (counter) return 0.0
    val base = when (move) {
        Move.STRIKE -> FightTuning.MISS_STRIKE
        Move.HEAVY -> FightTuning.MISS_HEAVY
        else -> return 0.0 // SPECIAL never misses
    }
    return base + defender.id.spec.passive.evadeBonus
}

private fun critChance(attacker: Fighter): Double {
    val moodShare = attacker.mood.coerceIn(0, 100) / 100.0
    return FightTuning.CRIT_BASE + moodShare * FightTuning.CRIT_MOOD_SPAN + attacker.id.spec.passive.critBonus
}

private fun moveMultiplier(attacker: Fighter, move: Move, counter: Boolean): Double = when {
    counter -> attacker.id.spec.passive.counterMult
    move == Move.HEAVY -> FightTuning.HEAVY_MULT
    move == Move.SPECIAL -> FightTuning.SPECIAL_MULT
    else -> FightTuning.STRIKE_MULT * attacker.id.spec.passive.strikeMult
}

private fun damageOf(attacker: Fighter, defender: Fighter, multiplier: Double, varianceRoll: Double, crit: Boolean): Int {
    val base = FightTuning.BASE_DAMAGE + attacker.strength / FightTuning.STRENGTH_DIVISOR
    val variance = 1.0 + (2.0 * varianceRoll - 1.0) * FightTuning.DAMAGE_VARIANCE
    val critMult = if (crit) FightTuning.CRIT_MULT else 1.0
    val taken = defender.id.spec.passive.damageTakenMult
    return (base * variance * multiplier * critMult * taken).roundToInt().coerceAtLeast(1)
}
