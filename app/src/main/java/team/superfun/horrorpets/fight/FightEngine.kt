package team.superfun.horrorpets.fight

import kotlin.math.roundToInt
import kotlin.random.Random
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.model.SpecialEffect
import team.superfun.horrorpets.model.Trait
import team.superfun.horrorpets.model.kitOf
import team.superfun.horrorpets.model.spec

/** Hit points for a pet of [level] and [hunger] (0 = full, 100 = starving), times its passive's (Jason is built tougher). */
internal fun maxHpFor(id: PetId, level: Int, hunger: Double): Int {
    val hungerFactor = lerp(1.0, FightTuning.STARVING_HP_FACTOR, clampStat(hunger) / Tuning.STAT_MAX)
    val hp = (FightTuning.BASE_HP + FightTuning.HP_PER_LEVEL * level) * hungerFactor * id.spec.passive.hpMult
    return hp.roundToInt().coerceAtLeast(1)
}

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** The player's pet as a fresh fighter: full hit points, empty meter, its live strength and mood, and its equipped attacks. */
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
        kit = kitOf(p),
    )
}

/**
 * The CPU's fighter, scaled to [player], with the best attacks it has at its level. Draws, in this order: level
 * offset (`nextInt(-1, 2)`), then strength offset (`nextInt(-10, 11)`).
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
        kit = Kit.best(id, level),
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
 * - RUN (player only; see [forfeit] for either side's): the fight ends at once with `winner = CPU` and
 *   `playerRan = true`. No events, no dice, and `turn` and `playerHistory` are left as they were.
 * - POTION (while the side's potions last: [FightState.playerPotions], [FightState.cpuPotions]): no attack.
 *   The drinker heals `round(maxHp * (0.5 + 0.5 * roll))` (never past max HP) before the other side's move
 *   lands, so a potion can save it from a KO. Against it a STRIKE, HEAVY or SPECIAL attacks (nothing to
 *   interrupt or block), and a BLOCK does nothing. It costs a potion and leaves the meters alone.
 *
 * ## Attacks
 * A STRIKE, HEAVY or SPECIAL swings the fighter's attack for that slot ([Fighter.kit]; see the catalog in
 * `model/Attacks.kt`). Its power is the move's multiplier below (the starting attacks' are the numbers given);
 * its traits add to the crit chance, change the miss chance (never below 0), set how much meter a landed hit
 * fills, and, as it lands, bleed the target (a new bleed keeps the longer and harder of it and one still
 * running), drain (heals a share of the damage, never past max HP), daze (always, or with a chance that rolls one
 * more die, drawn after the crit roll and only when the attack lands) and shatter (knocks meter off the target,
 * after the target's own move this turn has filled or spent it; a SpecialReady it no longer has is dropped). A
 * counter is not an attack: it is the same for every kit.
 *
 * ## Damage, crits, misses, meter, combos
 * Damage is `(8 + strength / 8)` times variance (uniform 0.85..1.15) times the move (STRIKE 1.0, or
 * 1.15 for Chucky; HEAVY 1.8, times 1.2 for Leatherface; counter 0.5, or 1.0 for Pinhead; SPECIAL 2.5) times
 * the combo bonus (`1 + 0.15 * min(combo, 3)`, see below) times
 * 1.75 on a crit, times 0.85 when the defender is Michael, rounded once at the end and never below 1. Specials and counters
 * can crit but never miss. Attacks against GHOSTFACE miss 0.07 more often (STRIKE and HEAVY only).
 * Every per-pet number comes from the pet's [Passive][team.superfun.horrorpets.model.Passive] in the roster.
 * Each landed hit (counters included, specials excluded) adds 25 to the attacker's meter, capped
 * at 100; [FightEvent.SpecialReady] is emitted on the hit that takes it from below 100 to 100. A
 * SPECIAL needs a full meter and resets it to 0.
 *
 * [Fighter.combo] counts the turns in a row a side has landed a hit (a counter or a special included)
 * without being hit. After each turn a side that was hit, or whose own swing missed, drops to 0; one that
 * landed a hit and was not hit goes up by 1; one that did neither (both blocked, a potion in peace) keeps it.
 *
 * ## Special effects
 * A landed SPECIAL leaves its pet's effect ([team.superfun.horrorpets.model.SpecialStyle.effect]):
 * - BLEED: the target bleeds for the next 3 turns (a new bleed starts the count again, it never stacks): at
 *   the end of each, after both moves have landed, it loses 6% of its max HP (at least 1, never below 0),
 *   reported as [FightEvent.Bleed]. Bleeding is not being hit: it ends no combo.
 * - DRAIN: the attacker heals half the damage it dealt (rounded, never past its max HP), as a potion's heal
 *   does: before the other side's blow lands. Reported as [FightEvent.Drain] right after the hit (none if it
 *   healed nothing).
 * - DAZE: the target's next turn is spoiled: its STRIKE or HEAVY stumbles ([FightEvent.Miss] with
 *   `dazed = true`: it draws no dice, interrupts nothing and is blocked by nothing), and its BLOCK guards
 *   nothing (no [FightEvent.Blocked], no counter: a STRIKE against it lands). A dazed side's SPECIAL and
 *   POTION work as ever. The daze is gone after that one turn.
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
 * STRIKE, an interrupted HEAVY, a BLOCK that countered nothing, a dazed swing) draws nothing, and RUN draws
 * nothing.
 * A POTION draws one double, the heal roll, in its side's place (PLAYER's before the CPU's).
 *
 * ## Events and results
 * Events come in a fixed order: everything PLAYER's move caused, then everything the CPU's move
 * caused, then the bleeds (PLAYER's, then the CPU's), then [FightEvent.Ko]. A potion's group is just its
 * [FightEvent.Heal]. One side's group reads: [FightEvent.Blocked] (blocking sides only),
 * the Hit or Miss, [FightEvent.Drain], [FightEvent.Interrupted] (a STRIKE that cut off a HEAVY), then
 * [FightEvent.SpecialReady].
 *
 * Damage lands simultaneously and hit points floor at 0. The side at 0 loses; if both are at 0 the
 * side that dealt more damage this turn wins (the dealt damage, not capped by remaining hit
 * points), and PLAYER wins a tie. Only the loser gets a [FightEvent.Ko], so the winner of a double
 * KO is left standing at 0 hp. The returned state has `turn + 1`, the player's move appended to
 * `playerHistory`, and the turn tallied into [FightState.stats] and [FightState.cpuStats].
 *
 * @throws IllegalStateException if the fight already has a winner.
 * @throws IllegalArgumentException if the CPU plays RUN, either side plays POTION with none left, or
 *   SPECIAL with a meter below 100.
 */
fun resolveTurn(s: FightState, playerMove: Move, cpuMove: Move, rng: Random): TurnResult {
    check(s.winner == null) { "The fight is already over: ${s.winner} won" }
    require(cpuMove != Move.RUN) { "The CPU's side forfeits through forfeit(), not RUN" }
    require(playerMove != Move.POTION || s.playerPotions > 0) { "No potions left" }
    require(cpuMove != Move.POTION || s.cpuPotions > 0) { "The CPU's side has no potions left" }
    requireSpecialReady(Side.PLAYER, s.player, playerMove)
    requireSpecialReady(Side.CPU, s.cpu, cpuMove)

    if (playerMove == Move.RUN) {
        return TurnResult(s.copy(winner = Side.CPU, playerRan = true), emptyList())
    }

    val player = if (playerMove == Move.POTION) drink(Side.PLAYER, s.player, rng) else act(Side.PLAYER, s.player, s.cpu, playerMove, cpuMove, rng)
    val cpu = if (cpuMove == Move.POTION) drink(Side.CPU, s.cpu, rng) else act(Side.CPU, s.cpu, s.player, cpuMove, playerMove, rng)

    // A heal (a potion, a drain) lands before the other side's blow does; then whoever was bleeding bleeds.
    val playerBleed = bleedTick(s.player, (s.player.hp + player.healed - cpu.damage).coerceAtLeast(0))
    val cpuBleed = bleedTick(s.cpu, (s.cpu.hp + cpu.healed - player.damage).coerceAtLeast(0))
    val playerHp = (s.player.hp + player.healed - cpu.damage - playerBleed).coerceAtLeast(0)
    val cpuHp = (s.cpu.hp + cpu.healed - player.damage - cpuBleed).coerceAtLeast(0)
    val winner = when {
        playerHp > 0 && cpuHp > 0 -> null
        cpuHp == 0 && playerHp > 0 -> Side.PLAYER
        playerHp == 0 && cpuHp > 0 -> Side.CPU
        cpu.damage > player.damage -> Side.CPU // both down: the bigger hitter wins, a tie goes to PLAYER
        else -> Side.PLAYER
    }

    val bleeds = listOfNotNull(
        FightEvent.Bleed(Side.PLAYER, playerBleed).takeIf { playerBleed > 0 },
        FightEvent.Bleed(Side.CPU, cpuBleed).takeIf { cpuBleed > 0 },
    )
    // A shatter knocks the other side's meter back after its own move has filled (or spent) it, so a meter it
    // just filled may not stay full: then it is not announced.
    val playerMeter = (player.meter - cpu.shatter).coerceAtLeast(0)
    val cpuMeter = (cpu.meter - player.shatter).coerceAtLeast(0)
    val unready = setOfNotNull(Side.PLAYER.takeIf { playerMeter < FightTuning.METER_MAX }, Side.CPU.takeIf { cpuMeter < FightTuning.METER_MAX })
    val moves = (player.events + cpu.events).filterNot { it is FightEvent.SpecialReady && it.side in unready }
    val events = moves + bleeds + listOfNotNull(winner?.let { FightEvent.Ko(it.other()) })
    val playerCombo = comboAfter(s.player, player, hitBy = cpu)
    val (playerBleedTurns, playerBleedShare) = bleedAfter(s.player, cpu)
    val (cpuBleedTurns, cpuBleedShare) = bleedAfter(s.cpu, player)
    val next = s.copy(
        player = s.player.copy(
            hp = playerHp,
            meter = playerMeter,
            combo = playerCombo,
            bleed = playerBleedTurns,
            bleedShare = playerBleedShare,
            dazed = cpu.dazes,
        ),
        cpu = s.cpu.copy(
            hp = cpuHp,
            meter = cpuMeter,
            combo = comboAfter(s.cpu, cpu, hitBy = player),
            bleed = cpuBleedTurns,
            bleedShare = cpuBleedShare,
            dazed = player.dazes,
        ),
        turn = s.turn + 1,
        playerHistory = s.playerHistory + playerMove,
        winner = winner,
        playerPotions = s.playerPotions - if (playerMove == Move.POTION) 1 else 0,
        cpuPotions = s.cpuPotions - if (cpuMove == Move.POTION) 1 else 0,
        stats = tally(s.stats, events, Side.PLAYER),
        cpuStats = tally(s.cpuStats, events, Side.CPU),
    )
    return TurnResult(next, events)
}

/**
 * A PvP fight between two players' fighters: the host as PLAYER and the guest as CPU (the guest's app
 * [mirrors][FightState.mirrored] it), each with its potions.
 */
fun startPvp(host: Fighter, guest: Fighter): FightState =
    FightState(player = host, cpu = guest, playerPotions = FightTuning.POTIONS_PER_FIGHT, cpuPotions = FightTuning.POTIONS_PER_FIGHT)

/** [s] with [side] forfeiting: the other side wins at once, nothing else changes. */
fun forfeit(s: FightState, side: Side): FightState {
    check(s.winner == null) { "The fight is already over: ${s.winner} won" }
    return s.copy(winner = side.other(), playerRan = side == Side.PLAYER, cpuRan = side == Side.CPU)
}

/** [stats], [me]'s, with one more turn's [events] counted in. */
private fun tally(stats: FightStats, events: List<FightEvent>, me: Side): FightStats {
    val them = me.other()
    var t = stats
    for (e in events) {
        t = when (e) {
            is FightEvent.Hit -> if (e.attacker == me) {
                t.copy(
                    damageDealt = t.damageDealt + e.damage,
                    hits = t.hits + 1,
                    crits = t.crits + if (e.crit) 1 else 0,
                    specials = t.specials + if (e.move == Move.SPECIAL) 1 else 0,
                    // This hit is the (combo + 1)th in a row, even if the other side's lands on the same turn.
                    bestCombo = maxOf(t.bestCombo, e.combo + 1),
                )
            } else {
                t.copy(damageTaken = t.damageTaken + e.damage)
            }
            is FightEvent.Miss -> if (e.attacker == me) t.copy(misses = t.misses + 1) else t
            is FightEvent.Bleed -> if (e.side == them) t.copy(damageDealt = t.damageDealt + e.damage) else t.copy(damageTaken = t.damageTaken + e.damage)
            is FightEvent.Heal -> if (e.side == me) t.copy(healed = t.healed + e.amount) else t
            is FightEvent.Drain -> if (e.side == me) t.copy(healed = t.healed + e.amount) else t
            is FightEvent.Blocked -> if (e.blocker == them) t.copy(stopped = t.stopped + 1) else t
            is FightEvent.Interrupted -> if (e.side == me) t.copy(stopped = t.stopped + 1) else t
            is FightEvent.SpecialReady, is FightEvent.Ko -> t
        }
    }
    return t
}

private fun requireSpecialReady(side: Side, f: Fighter, move: Move) {
    require(move != Move.SPECIAL || f.meter >= FightTuning.METER_MAX) {
        "$side cannot use SPECIAL with the meter at ${f.meter}/${FightTuning.METER_MAX}"
    }
}

/**
 * What one side's move did this turn: its events, the damage it dealt, its meter afterwards, what it healed, and
 * what its attack did to the other side: the bleed it [bleeds], whether it [dazes], how much meter it [shatter]s.
 */
private class Action(
    val events: List<FightEvent>,
    val damage: Int,
    val meter: Int,
    val healed: Int = 0,
    val bleeds: Trait.Bleed? = null,
    val dazes: Boolean = false,
    val shatter: Int = 0,
) {
    val missed: Boolean get() = events.any { it is FightEvent.Miss }
}

/** What [f] bleeds at the end of this turn, now at [hp]: its bleed's share of its max HP if it was bleeding (and still up), else 0. */
private fun bleedTick(f: Fighter, hp: Int): Int {
    if (f.bleed <= 0 || hp <= 0) return 0
    return (f.maxHp * f.bleedShare).roundToInt().coerceAtLeast(1).coerceAtMost(hp)
}

/**
 * [f]'s bleed after this turn (turns left, and its share): one turn less, or the [other] side's new bleed. A new bleed
 * on top of one still running never stacks: it keeps the longer and the harder of the two.
 */
private fun bleedAfter(f: Fighter, other: Action): Pair<Int, Double> {
    val left = (f.bleed - 1).coerceAtLeast(0)
    val new = other.bleeds ?: return left to f.bleedShare
    return if (left > 0) maxOf(left, new.turns) to maxOf(f.bleedShare, new.share) else new.turns to new.share
}

/** [me]'s combo after a turn in which it played [mine] and was hit by [hitBy] (see [resolveTurn]). */
private fun comboAfter(me: Fighter, mine: Action, hitBy: Action): Int = when {
    hitBy.damage > 0 || mine.missed -> 0
    mine.damage > 0 -> me.combo + 1
    else -> me.combo
}

/** [side] drinks a potion: one roll for how much of its max HP it gives back, capped at what is missing. */
private fun drink(side: Side, me: Fighter, rng: Random): Action {
    val share = FightTuning.POTION_HEAL_MIN + (FightTuning.POTION_HEAL_MAX - FightTuning.POTION_HEAL_MIN) * rng.nextDouble()
    val healed = (me.maxHp * share).roundToInt().coerceAtMost(me.maxHp - me.hp).coerceAtLeast(0)
    return Action(listOf(FightEvent.Heal(side, healed)), damage = 0, meter = me.meter, healed = healed)
}

private fun act(side: Side, me: Fighter, other: Fighter, myMove: Move, otherMove: Move, rng: Random): Action {
    // Dazed, a STRIKE or HEAVY stumbles: no dice, nothing cut off, nothing for a block to catch.
    if (me.dazed && (myMove == Move.STRIKE || myMove == Move.HEAVY)) {
        return Action(listOf(FightEvent.Miss(side, myMove, dazed = true)), damage = 0, meter = me.meter)
    }
    // The other side's STRIKE, HEAVY or BLOCK only counts if it is not dazed.
    val otherActs = !other.dazed
    val events = mutableListOf<FightEvent>()
    val counter = myMove == Move.BLOCK && otherMove == Move.STRIKE && !me.dazed && otherActs
    if (counter) events += FightEvent.Blocked(side)

    // Does a swing of mine happen at all? Decided by the moves, never by the dice.
    val attacks = when (myMove) {
        Move.STRIKE -> !(otherMove == Move.BLOCK && otherActs) // absorbed by a block
        Move.HEAVY -> !(otherMove == Move.STRIKE && otherActs) // interrupted by a strike
        Move.SPECIAL -> true
        Move.BLOCK -> counter
        Move.POTION, Move.RUN -> false
    }

    var damage = 0
    var landed = false
    var healed = 0
    var bleeds: Trait.Bleed? = null
    var dazes = false
    var shatter = 0
    // The attack swung: the kit's for the move, or none for a counter (a block's riposte, the same for every kit).
    val attack = if (counter) null else me.kit.attackFor(myMove)
    if (attacks) {
        val missRoll = rng.nextDouble()
        val varianceRoll = rng.nextDouble()
        val critRoll = rng.nextDouble()
        val hitMove = if (counter) Move.STRIKE else myMove
        if (missRoll < missChance(myMove, counter, other, attack)) {
            events += FightEvent.Miss(side, myMove)
        } else {
            val crit = critRoll < critChance(me, attack)
            damage = damageOf(me, other, moveMultiplier(me, myMove, counter, attack) * comboMultiplier(me), varianceRoll, crit)
            landed = true
            if (attack != null) {
                // What the attack leaves as it lands. A chance to daze rolls one more die, only for an attack that has one.
                val dazeChance = attack.daze
                dazes = dazeChance >= 1.0 || (dazeChance > 0.0 && rng.nextDouble() < dazeChance)
                bleeds = attack.bleed
                shatter = attack.shatter.coerceAtMost(other.meter)
                if (attack.drain > 0.0) healed = (damage * attack.drain).roundToInt().coerceAtMost(me.maxHp - me.hp).coerceAtLeast(0)
            }
            val effect = when {
                bleeds != null -> SpecialEffect.BLEED
                dazes -> SpecialEffect.DAZE
                attack != null && attack.drain > 0.0 -> SpecialEffect.DRAIN
                else -> null
            }
            events += FightEvent.Hit(side, hitMove, damage, crit, counter, combo = me.combo, effect = effect, shattered = shatter)
            if (healed > 0) events += FightEvent.Drain(side, healed)
        }
    }

    if (myMove == Move.STRIKE && otherMove == Move.HEAVY && otherActs) events += FightEvent.Interrupted(side.other())

    val meter = when {
        myMove == Move.SPECIAL -> 0
        landed -> (me.meter + (attack?.meterGain ?: FightTuning.METER_PER_HIT)).coerceAtMost(FightTuning.METER_MAX)
        else -> me.meter
    }
    if (meter == FightTuning.METER_MAX && me.meter < FightTuning.METER_MAX) events += FightEvent.SpecialReady(side)
    return Action(events, damage, meter, healed, bleeds, dazes, shatter)
}

private fun missChance(move: Move, counter: Boolean, defender: Fighter, attack: Attack?): Double {
    if (counter) return 0.0
    val base = when (move) {
        Move.STRIKE -> FightTuning.MISS_STRIKE
        Move.HEAVY -> FightTuning.MISS_HEAVY
        else -> return 0.0 // SPECIAL never misses
    }
    return (base + (attack?.missChange ?: 0.0) + defender.id.spec.passive.evadeBonus).coerceAtLeast(0.0)
}

private fun critChance(attacker: Fighter, attack: Attack?): Double {
    val moodShare = attacker.mood.coerceIn(0, 100) / 100.0
    return FightTuning.CRIT_BASE + moodShare * FightTuning.CRIT_MOOD_SPAN + attacker.id.spec.passive.critBonus + (attack?.critBonus ?: 0.0)
}

/** A hit's multiplier for its move: a counter's from the passive, otherwise the attack's power (times the passive's for its slot). */
private fun moveMultiplier(attacker: Fighter, move: Move, counter: Boolean, attack: Attack?): Double {
    val passive = attacker.id.spec.passive
    if (counter) return passive.counterMult
    val power = attack?.power ?: return FightTuning.STRIKE_MULT
    return when (move) {
        Move.HEAVY -> power * passive.heavyMult
        Move.SPECIAL -> power
        else -> power * passive.strikeMult
    }
}

private fun comboMultiplier(attacker: Fighter): Double =
    1.0 + FightTuning.COMBO_STEP * attacker.combo.coerceAtMost(FightTuning.COMBO_MAX_STEPS)

private fun damageOf(attacker: Fighter, defender: Fighter, multiplier: Double, varianceRoll: Double, crit: Boolean): Int {
    val base = FightTuning.BASE_DAMAGE + attacker.strength / FightTuning.STRENGTH_DIVISOR
    val variance = 1.0 + (2.0 * varianceRoll - 1.0) * FightTuning.DAMAGE_VARIANCE
    val critMult = if (crit) FightTuning.CRIT_MULT else 1.0
    val taken = defender.id.spec.passive.damageTakenMult
    return (base * variance * multiplier * critMult * taken).roundToInt().coerceAtLeast(1)
}
