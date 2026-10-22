package team.superfun.horrorpets.model

import team.superfun.horrorpets.fight.FightTuning

/**
 * A killer's signature special: its callout name and which effect the fight screen draws for it. Each
 * pet has its own; a future pet can reuse one (no new effect code) or add one. A [danced] special is
 * performed with the attacker's `dance` animation instead of its ATTACK swing, when its sprites ship one.
 */
enum class SpecialStyle(val displayName: String, val danced: Boolean = false) {
    MACHETE_FRENZY("Machete Frenzy"),
    DREAM_CLAWS("Dream Claws"),
    CHAINS("Chains"),
    KNIFE_FLURRY("Knife Flurry"),
    VOODOO_STAB("Voodoo Stab"),
    THE_SHAPE("The Shape"),
    SAW_DANCE("Saw Dance", danced = true),
}

/**
 * A killer's passive: the fight numbers it changes. Every field defaults to "no change", so a passive
 * names only what makes it special.
 *
 * - [hpMult] multiplies its hit points.
 * - [critBonus] is added to its crit chance.
 * - [counterMult] is the damage multiplier of its block counters.
 * - [evadeBonus] is added to the miss chance of STRIKEs and HEAVYs aimed at it.
 * - [strikeMult] multiplies its STRIKE damage (not counters, heavies or specials).
 * - [heavyMult] multiplies its HEAVY damage (not strikes, counters or specials).
 * - [damageTakenMult] multiplies all damage it takes, after crits (the hit still does at least 1).
 * - [feintBonus] is added to its chance of showing a false tell as the CPU (see `cpuPlan`).
 */
data class Passive(
    val name: String,
    val hpMult: Double = 1.0,
    val critBonus: Double = 0.0,
    val counterMult: Double = FightTuning.COUNTER_MULT,
    val evadeBonus: Double = 0.0,
    val strikeMult: Double = 1.0,
    val heavyMult: Double = 1.0,
    val damageTakenMult: Double = 1.0,
    val feintBonus: Double = 0.0,
)

/**
 * Everything about one killer that is not art or sound: its fight [passive], its [special], and its
 * [walkSpeed] on Home (1 is the usual stride; a pet whose run rows are a slow walk moves slower so its feet
 * don't skate). [specialHitMs] is where its `special_<pet>` clip hits hardest (its strongest attack,
 * measured from the WAV and checked by RosterAssetsTest), so a special whose blow lands late in its effect
 * can start the clip early enough for the hit to land with it. Art and sounds are found by `id.name.lowercase()`
 * (`sprites/<pet>/`, `scenes.json`, `music/<pet>.ogg`, `sfx/special_<pet>.wav`, `voice/<pet>.wav`,
 * `voice/<pet>_never_dies.wav`), and the manifest's `displayScale` sets how tall it stands. A manifest that
 * ships the optional `dance` animation plays it for PLAY on Home (and for a [SpecialStyle.danced] special).
 *
 * Adding a killer: a [PetId] entry, an entry here, and those asset folders.
 */
data class PetSpec(
    val id: PetId,
    val passive: Passive,
    val special: SpecialStyle,
    val specialHitMs: Long,
    val walkSpeed: Float = 1f,
)

/** Every killer's [PetSpec], in [PetId] order. */
val Roster: Map<PetId, PetSpec> = listOf(
    PetSpec(PetId.JASON, Passive("Unstoppable", hpMult = FightTuning.JASON_HP_MULT), SpecialStyle.MACHETE_FRENZY, specialHitMs = 500),
    PetSpec(PetId.FREDDY, Passive("Lucid", critBonus = FightTuning.FREDDY_CRIT_BONUS), SpecialStyle.DREAM_CLAWS, specialHitMs = 0),
    PetSpec(PetId.PINHEAD, Passive("Retribution", counterMult = FightTuning.PINHEAD_COUNTER_MULT), SpecialStyle.CHAINS, specialHitMs = 750),
    PetSpec(PetId.GHOSTFACE, Passive("Slippery", evadeBonus = FightTuning.GHOSTFACE_EVADE_BONUS, feintBonus = FightTuning.GHOSTFACE_FEINT_BONUS), SpecialStyle.KNIFE_FLURRY, specialHitMs = 650),
    PetSpec(PetId.CHUCKY, Passive("Scrappy", strikeMult = FightTuning.CHUCKY_STRIKE_MULT), SpecialStyle.VOODOO_STAB, specialHitMs = 710),
    PetSpec(
        PetId.MICHAEL,
        Passive("Relentless", damageTakenMult = FightTuning.MICHAEL_DAMAGE_TAKEN_MULT),
        SpecialStyle.THE_SHAPE,
        specialHitMs = 420,
        walkSpeed = 0.6f,
    ),
    PetSpec(
        PetId.LEATHERFACE,
        Passive("Chainsaw", heavyMult = FightTuning.LEATHERFACE_HEAVY_MULT),
        SpecialStyle.SAW_DANCE,
        specialHitMs = 0,
        walkSpeed = 0.9f,
    ),
).associateBy { it.id }

/** This pet's [Roster] entry. */
val PetId.spec: PetSpec get() = Roster.getValue(this)
