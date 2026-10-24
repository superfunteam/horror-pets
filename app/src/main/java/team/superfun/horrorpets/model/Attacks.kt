package team.superfun.horrorpets.model

import team.superfun.horrorpets.fight.FightTuning

/**
 * A killer's three attack slots: the three attacking commands of a fight. What an attack in a slot does in the
 * rock-paper-scissors never changes (a LIGHT is a STRIKE, a HEAVY a HEAVY); it changes how hard it hits and what
 * it adds (see [Attack]).
 */
enum class Slot(val label: String) {
    LIGHT("Light"),
    HEAVY("Heavy"),
    SPECIAL("Special"),
}

/** What an attack adds to (or costs) a hit, on top of its power. */
sealed interface Trait {
    /** Adds [bonus] to the crit chance. */
    data class Crit(val bonus: Double) : Trait

    /** Changes the hit chance by [change]: positive misses less, negative misses more. Specials never miss. */
    data class Accuracy(val change: Double) : Trait

    /** Each landed hit fills [gain] of the special meter instead of [FightTuning.METER_PER_HIT]. */
    data class Meter(val gain: Int) : Trait

    /** The target bleeds [share] of its max HP for [turns] turns. */
    data class Bleed(val turns: Int, val share: Double = FightTuning.BLEED_SHARE) : Trait

    /** The attacker heals by [share] of the damage it dealt. */
    data class Drain(val share: Double) : Trait

    /** With probability [chance] (1 for always), the target's next STRIKE, HEAVY or BLOCK fails. */
    data class Daze(val chance: Double) : Trait

    /** Knocks [amount] off the target's special meter ([FightTuning.METER_MAX] empties it). */
    data class Shatter(val amount: Int) : Trait
}

/**
 * One of a killer's attacks for one [slot]. [tier] 0 is the attack every killer starts with; tiers 1-3 unlock at
 * [level] (see [UNLOCK_LEVELS]). [power] is its damage multiplier (see [ATTACK_POWER]) and [traits] its perks and
 * catches. [id] is stable across renames: it is what a save remembers. [anim] is the sprite animation it swings
 * with, by its manifest key (Jaws's Barrel Breach plays `barrels`), or null for the usual `attack` swing.
 */
data class Attack(
    val id: String,
    val pet: PetId,
    val slot: Slot,
    val tier: Int,
    val name: String,
    val level: Int,
    val power: Double,
    val traits: List<Trait> = emptyList(),
    val anim: String? = null,
) {
    val critBonus: Double get() = traits.filterIsInstance<Trait.Crit>().sumOf { it.bonus }

    /** Added to the miss chance (so a catch is positive). */
    val missChange: Double get() = -traits.filterIsInstance<Trait.Accuracy>().sumOf { it.change }

    val meterGain: Int get() = traits.filterIsInstance<Trait.Meter>().firstOrNull()?.gain ?: FightTuning.METER_PER_HIT
    val bleed: Trait.Bleed? get() = traits.filterIsInstance<Trait.Bleed>().firstOrNull()
    val drain: Double get() = traits.filterIsInstance<Trait.Drain>().firstOrNull()?.share ?: 0.0
    val daze: Double get() = traits.filterIsInstance<Trait.Daze>().firstOrNull()?.chance ?: 0.0
    val shatter: Int get() = traits.filterIsInstance<Trait.Shatter>().firstOrNull()?.amount ?: 0
}

/** The longest attack name, in characters: every screen that shows one is laid out to fit this many. */
const val LONGEST_ATTACK_NAME = 21

/** The level each tier (0-3) of a slot unlocks at. Tier 0 is there from the start. */
val UNLOCK_LEVELS: Map<Slot, List<Int>> = mapOf(
    Slot.LIGHT to listOf(1, 2, 6, 10),
    Slot.HEAVY to listOf(1, 3, 7, 12),
    Slot.SPECIAL to listOf(1, 4, 8, 14),
)

/** Each tier's (0-3) damage multiplier, by slot. Tier 0 is the game's original STRIKE, HEAVY and SPECIAL. */
val ATTACK_POWER: Map<Slot, List<Double>> = mapOf(
    Slot.LIGHT to listOf(FightTuning.STRIKE_MULT, 1.15, 1.30, 1.45),
    Slot.HEAVY to listOf(FightTuning.HEAVY_MULT, 2.0, 2.2, 2.45),
    Slot.SPECIAL to listOf(FightTuning.SPECIAL_MULT, 2.75, 3.0, 3.4),
)

/** What a special of [effect] leaves behind at each tier (0-3): stronger bleeds and drains, dazes that shatter. */
fun specialTraits(effect: SpecialEffect, tier: Int): List<Trait> = when (effect) {
    SpecialEffect.BLEED -> listOf(
        listOf(Trait.Bleed(FightTuning.BLEED_TURNS, FightTuning.BLEED_SHARE)),
        listOf(Trait.Bleed(3, 0.07)),
        listOf(Trait.Bleed(4, 0.07)),
        listOf(Trait.Bleed(4, 0.09)),
    )[tier]
    SpecialEffect.DRAIN -> listOf(FightTuning.DRAIN_SHARE, 0.6, 0.7, 0.85).map { listOf(Trait.Drain(it)) }[tier]
    SpecialEffect.DAZE -> listOf(0, 25, 50, FightTuning.METER_MAX).map { shatter ->
        listOf(Trait.Daze(1.0)) + if (shatter > 0) listOf(Trait.Shatter(shatter)) else emptyList()
    }[tier]
}

/**
 * One killer's attack names, tiers 0-3 per slot (its special's tier 0 is its roster special), tiers 2-3's traits, and
 * the attacks that swing with their own animation ([anims], by slot and tier, a manifest key: see [Attack.anim]).
 */
private class Moveset(
    val pet: PetId,
    val light: List<String>,
    val heavy: List<String>,
    val special: List<String>,
    val lightTraits: List<List<Trait>>,
    val heavyTraits: List<List<Trait>>,
    val anims: Map<Pair<Slot, Int>, String> = emptyMap(),
)

private val Movesets = listOf(
    Moveset(
        PetId.JASON,
        light = listOf("Machete Jab", "Machete Swipe", "Lakeside Hack", "Camp Blood Cleave"),
        heavy = listOf("Overhead Chop", "Sleeping Bag Slam", "Crystal Lake Crush", "Final Chapter"),
        special = listOf("Crimson Frenzy", "Ki Ki Ma Ma", "Never Dies"),
        lightTraits = listOf(listOf(Trait.Crit(0.08), Trait.Accuracy(-0.04)), listOf(Trait.Bleed(2), Trait.Accuracy(-0.06))),
        heavyTraits = listOf(listOf(Trait.Daze(0.25), Trait.Accuracy(-0.05)), listOf(Trait.Bleed(3), Trait.Meter(20))),
    ),
    Moveset(
        PetId.FREDDY,
        light = listOf("Claw Swipe", "Finger Knives", "Nightmare Scratch", "Dream Demon Rake"),
        heavy = listOf("Glove Slash", "Boiler Room Slash", "Furnace Claws", "Freddy's Coming"),
        special = listOf("Sweet Dreams", "Never Sleep Again", "Welcome to Prime Time"),
        lightTraits = listOf(listOf(Trait.Crit(0.10), Trait.Meter(20)), listOf(Trait.Drain(0.25), Trait.Accuracy(-0.05))),
        heavyTraits = listOf(listOf(Trait.Bleed(2), Trait.Accuracy(-0.04)), listOf(Trait.Crit(0.12), Trait.Meter(20))),
    ),
    Moveset(
        PetId.PINHEAD,
        light = listOf("Hook Flick", "Barbed Hook", "Pin Prick", "Exquisite Agony"),
        heavy = listOf("Chain Lash", "Hooked Chains", "Puzzle Box Snare", "Tear Your Soul Apart"),
        special = listOf("Lament Configuration", "Such Sights", "Leviathan"),
        lightTraits = listOf(listOf(Trait.Accuracy(0.05), Trait.Meter(20)), listOf(Trait.Shatter(25), Trait.Accuracy(-0.04))),
        heavyTraits = listOf(listOf(Trait.Daze(0.25), Trait.Meter(20)), listOf(Trait.Daze(0.35), Trait.Accuracy(-0.06))),
    ),
    Moveset(
        PetId.GHOSTFACE,
        light = listOf("Knife Poke", "Quick Stab", "Caller Slash", "Scream Slash"),
        heavy = listOf("Knife Lunge", "Pratfall Pounce", "Gutting Lunge", "Final Act"),
        special = listOf("Scary Movie", "Hello, Sidney", "Ultimate Scream"),
        lightTraits = listOf(listOf(Trait.Meter(34), Trait.Accuracy(-0.03)), listOf(Trait.Bleed(2), Trait.Meter(20))),
        heavyTraits = listOf(listOf(Trait.Bleed(2), Trait.Accuracy(-0.05)), listOf(Trait.Crit(0.10), Trait.Meter(20))),
    ),
    Moveset(
        PetId.CHUCKY,
        light = listOf("Knife Jab", "Ankle Slash", "Good Guy Jab", "Wanna Play?"),
        heavy = listOf("Cleaver Chop", "Toy Hammer Bonk", "Doll's Revenge", "Charles Lee Ray"),
        special = listOf("Damballa", "Soul Transfer", "Friends to the End"),
        lightTraits = listOf(listOf(Trait.Meter(34), Trait.Accuracy(-0.03)), listOf(Trait.Crit(0.10), Trait.Accuracy(-0.05))),
        heavyTraits = listOf(listOf(Trait.Daze(0.25), Trait.Meter(20)), listOf(Trait.Bleed(2), Trait.Accuracy(-0.06))),
    ),
    Moveset(
        PetId.MICHAEL,
        light = listOf("Knife Stab", "Head Tilt Stab", "Kitchen Knife", "Haddonfield Slash"),
        heavy = listOf("Overhand Stab", "Closet Ambush", "Pinned to the Wall", "The Boogeyman"),
        special = listOf("Halloween Night", "Pure Evil", "He Came Home"),
        lightTraits = listOf(listOf(Trait.Crit(0.08), Trait.Meter(20)), listOf(Trait.Daze(0.15), Trait.Accuracy(-0.04))),
        heavyTraits = listOf(listOf(Trait.Bleed(2), Trait.Meter(20)), listOf(Trait.Daze(0.30), Trait.Accuracy(-0.06))),
    ),
    Moveset(
        PetId.LEATHERFACE,
        light = listOf("Hammer Tap", "Sledgehammer Bonk", "Meat Hook Jab", "Butcher's Cut"),
        heavy = listOf("Chainsaw Swing", "Revving Rush", "Grinding Saw", "Texas Chainsaw"),
        special = listOf("Saw Twirl", "Family Dinner", "Chainsaw Massacre"),
        lightTraits = listOf(listOf(Trait.Bleed(2), Trait.Accuracy(-0.04)), listOf(Trait.Crit(0.08), Trait.Meter(20))),
        heavyTraits = listOf(listOf(Trait.Bleed(2), Trait.Accuracy(-0.05)), listOf(Trait.Daze(0.30), Trait.Accuracy(-0.07))),
    ),
    Moveset(
        PetId.JAWS,
        light = listOf("Fin Swipe", "Tail Slap", "Nibble", "Bump the Boat"),
        heavy = listOf("Chomp", "Death Roll", "Barrel Breach", "Ultra Thrash"),
        special = listOf("Bigger Boat", "Amity Island", "Smile!"),
        lightTraits = listOf(listOf(Trait.Bleed(2), Trait.Meter(20)), listOf(Trait.Daze(0.20), Trait.Accuracy(-0.05))),
        heavyTraits = listOf(listOf(Trait.Shatter(25), Trait.Accuracy(-0.05)), listOf(Trait.Bleed(3), Trait.Accuracy(-0.07))),
        anims = mapOf((Slot.HEAVY to 2) to "barrels", (Slot.HEAVY to 3) to "thrash"),
    ),
)

private fun Moveset.attack(slot: Slot, tier: Int, name: String, traits: List<Trait>) = Attack(
    id = "${pet.name.lowercase()}_${slot.name.lowercase()}_$tier",
    pet = pet,
    slot = slot,
    tier = tier,
    name = name,
    level = UNLOCK_LEVELS.getValue(slot)[tier],
    power = ATTACK_POWER.getValue(slot)[tier],
    traits = traits,
    anim = anims[slot to tier],
)

/** Every killer's attacks: 3 slots × 4 tiers each, in [PetId], then [Slot], then tier order. */
val Attacks: List<Attack> = Movesets.sortedBy { it.pet.ordinal }.flatMap { m ->
    val effect = m.pet.spec.special.effect
    (0..3).map { t -> m.attack(Slot.LIGHT, t, m.light[t], if (t >= 2) m.lightTraits[t - 2] else emptyList()) } +
        (0..3).map { t -> m.attack(Slot.HEAVY, t, m.heavy[t], if (t >= 2) m.heavyTraits[t - 2] else emptyList()) } +
        (0..3).map { t -> m.attack(Slot.SPECIAL, t, if (t == 0) m.pet.spec.special.displayName else m.special[t - 1], specialTraits(effect, t)) }
}

private val ById: Map<String, Attack> = Attacks.associateBy { it.id }

/** The attack with [id], if there is one. */
fun attackById(id: String): Attack? = ById[id]

/** [pet]'s attacks for [slot], tiers 0-3. */
fun attacksOf(pet: PetId, slot: Slot): List<Attack> = Attacks.filter { it.pet == pet && it.slot == slot }

/** The attack [pet] starts with in [slot]. */
fun baseAttack(pet: PetId, slot: Slot): Attack = attacksOf(pet, slot).first()

/** [pet]'s strongest attack for [slot] at [level]: what a CPU opponent of that level fights with. */
fun bestAttack(pet: PetId, slot: Slot, level: Int): Attack = attacksOf(pet, slot).last { it.level <= level }

/** The three attacks a fighter fights with, one per slot. */
data class Kit(val light: Attack, val heavy: Attack, val special: Attack) {
    fun of(slot: Slot): Attack = when (slot) {
        Slot.LIGHT -> light
        Slot.HEAVY -> heavy
        Slot.SPECIAL -> special
    }

    companion object {
        /** What every [pet] starts with. */
        fun base(pet: PetId) = Kit(baseAttack(pet, Slot.LIGHT), baseAttack(pet, Slot.HEAVY), baseAttack(pet, Slot.SPECIAL))

        /** The best [pet] has at [level], as a CPU fights. */
        fun best(pet: PetId, level: Int) = Kit(bestAttack(pet, Slot.LIGHT, level), bestAttack(pet, Slot.HEAVY, level), bestAttack(pet, Slot.SPECIAL, level))
    }
}

/** Whether [p] may fight with [a]: its own, unlocked by its level, and either a starting attack or one it has learned. */
fun canUse(p: PetState, a: Attack): Boolean = a.pet == p.id && a.level <= p.level && (a.tier == 0 || a.id in p.known)

/** What [p] fights with: its equipped attack per slot, or the slot's starting one where that is missing or not its to use. */
fun kitOf(p: PetState): Kit {
    fun slot(s: Slot) = p.equipped[s]?.let(::attackById)?.takeIf { it.slot == s && canUse(p, it) } ?: baseAttack(p.id, s)
    return Kit(slot(Slot.LIGHT), slot(Slot.HEAVY), slot(Slot.SPECIAL))
}

/** The attacks [p] has unlocked by its level but not yet been offered (learned or kept the old one): one learn screen each, in order. */
fun pendingAttacks(p: PetState): List<Attack> =
    Attacks.filter { it.pet == p.id && it.tier > 0 && it.level <= p.level && it.id !in p.known }
        .sortedWith(compareBy({ it.level }, { it.slot.ordinal }))
