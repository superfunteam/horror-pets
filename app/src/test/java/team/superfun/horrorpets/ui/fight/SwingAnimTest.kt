package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetFixtures
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.assets.parseManifest
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.baseAttack

/** Which animation a swing plays, and which way a fighter faces while it plays. */
class SwingAnimTest {
    private val jaws = parseManifest(AssetFixtures.manifest("jaws"))
    private val freddy = parseManifest(AssetFixtures.manifest("freddy"))
    private val breach = attackById("jaws_heavy_2")!!
    private val ultra = attackById("jaws_heavy_3")!!

    /** Jaws's starting kit, with [with] as its heavy. */
    private fun jawsKit(with: Attack = breach) = Kit(baseAttack(PetId.JAWS, Slot.LIGHT), with, baseAttack(PetId.JAWS, Slot.SPECIAL))

    /** What [event] swings with for an attacker with [kit] and sprites that ship what [ships] says. */
    private fun swingOf(event: FightEvent, kit: Kit, ships: (Anim) -> Boolean, dances: Boolean = false): Anim =
        swingAnim(event, dances, ownSwingAnim(event, kit, ships))

    @Test
    fun barrelBreachAndUltraThrashSwingWithTheirOwnAnimations() {
        val heavy = FightEvent.Hit(Side.PLAYER, Move.HEAVY, 30, crit = false)
        assertEquals("barrels", breach.anim)
        assertEquals("thrash", ultra.anim)
        assertEquals(Anim.BARRELS, swingOf(heavy, jawsKit(with = breach), jaws::has))
        assertEquals(Anim.THRASH, swingOf(heavy, jawsKit(with = ultra), jaws::has))
        // The CPU's swing is chosen the same way.
        val cpuHeavy = FightEvent.Hit(Side.CPU, Move.HEAVY, 30, crit = true)
        assertEquals(Anim.THRASH, swingOf(cpuHeavy, jawsKit(with = ultra), jaws::has))
    }

    @Test
    fun aMissedHeavyStillSwingsItsOwnAnimation() {
        assertEquals(Anim.BARRELS, swingOf(FightEvent.Miss(Side.CPU, Move.HEAVY), jawsKit(with = breach), jaws::has))
        assertEquals(Anim.THRASH, swingOf(FightEvent.Miss(Side.PLAYER, Move.HEAVY, dazed = true), jawsKit(with = ultra), jaws::has))
    }

    @Test
    fun withoutTheAnimationInTheManifestItSwingsTheAttack() {
        val heavy = FightEvent.Hit(Side.PLAYER, Move.HEAVY, 30, crit = false)
        // Sprites that don't ship barrels or a thrash (every other killer's): the usual ATTACK, never a missing strip.
        assertFalse(freddy.has(Anim.BARRELS))
        assertNull(ownSwingAnim(heavy, jawsKit(with = breach), freddy::has))
        assertEquals(Anim.ATTACK, swingOf(heavy, jawsKit(with = breach), freddy::has))
        assertEquals(Anim.ATTACK, swingOf(heavy, jawsKit(with = ultra), freddy::has))
        assertEquals(Anim.ATTACK, swingOf(heavy, jawsKit(with = ultra), ships = { false }))
    }

    @Test
    fun anAttackThatNamesNoAnimationSwingsTheAttack() {
        val chomp = baseAttack(PetId.JAWS, Slot.HEAVY)
        assertNull(chomp.anim)
        val heavy = FightEvent.Hit(Side.PLAYER, Move.HEAVY, 20, crit = false)
        assertEquals(Anim.ATTACK, swingOf(heavy, jawsKit(with = chomp), jaws::has))
        // His strikes and his specials (the Oxygen Tank is thrown with his ATTACK) swing the usual way too.
        for (move in listOf(Move.STRIKE, Move.SPECIAL)) {
            assertEquals("$move", Anim.ATTACK, swingOf(FightEvent.Hit(Side.CPU, move, 20, crit = false), jawsKit(), jaws::has))
        }
        // A key no manifest knows is no animation at all.
        val odd = breach.copy(anim = "backflip")
        assertNull(ownSwingAnim(heavy, jawsKit(with = odd), jaws::has))
    }

    @Test
    fun theSwingIsTheAttackThatMadeIt() {
        // A light that named an animation (none does yet) would swing it on a strike and into a shield, but a
        // counter is a plain swing back, whatever the kit holds.
        val namedLight = breach.copy(slot = Slot.LIGHT)
        val kit = Kit(namedLight, baseAttack(PetId.JAWS, Slot.HEAVY), baseAttack(PetId.JAWS, Slot.SPECIAL))
        assertEquals(Anim.BARRELS, swingOf(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 8, crit = false), kit, jaws::has))
        assertEquals(Anim.BARRELS, swingOf(FightEvent.Blocked(Side.CPU), kit, jaws::has))
        assertEquals(Anim.ATTACK, swingOf(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 8, crit = false, counter = true), kit, jaws::has))
        // Not a swing at all: nothing of its own.
        assertNull(ownSwingAnim(FightEvent.Interrupted(Side.PLAYER), kit, jaws::has))
    }

    @Test
    fun aDancedSpecialStillDances() {
        val special = FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)
        val leatherface = Kit.base(PetId.LEATHERFACE)
        assertEquals(Anim.DANCE, swingOf(special, leatherface, { it == Anim.DANCE }, dances = true))
        // An attack's own animation is the most particular choice of all.
        assertEquals(Anim.THRASH, swingAnim(special, dancesSpecial = true, own = Anim.THRASH))
    }

    @Test
    fun anAttacksSwingIsTheOneAFightPicksForAHitWithIt() {
        // What the MOVES card's preview plays: the attack's own animation, a danced special's DANCE, else ATTACK.
        assertEquals(Anim.BARRELS, attackSwing(breach, jaws::has))
        assertEquals(Anim.THRASH, attackSwing(ultra, jaws::has))
        assertEquals(Anim.ATTACK, attackSwing(baseAttack(PetId.JAWS, Slot.HEAVY), jaws::has))
        assertEquals(Anim.ATTACK, attackSwing(baseAttack(PetId.JAWS, Slot.SPECIAL), jaws::has))
        // Sprites without the strip swing the usual ATTACK, never a missing one.
        assertEquals(Anim.ATTACK, attackSwing(ultra, freddy::has))
        val leatherface = parseManifest(AssetFixtures.manifest("leatherface"))
        for (tier in 0..3) assertEquals(Anim.DANCE, attackSwing(attackById("leatherface_special_$tier")!!, leatherface::has))
        assertEquals(Anim.ATTACK, attackSwing(baseAttack(PetId.LEATHERFACE, Slot.HEAVY), leatherface::has))
        assertEquals(Anim.ATTACK, attackSwing(attackById("freddy_special_3")!!, freddy::has))
    }

    @Test
    fun hisOwnAnimationsLandOnTheirSnapAndPlayToTheirEnd() {
        // Barrels: 200,120,70,260,160,240 (1050 ms). They burst up 320 ms in, and he holds the breach until they
        // have sunk again (730 ms) rather than cutting them off half-sunk.
        val barrels = jaws.spec(Anim.BARRELS)
        assertEquals(320L, swingImpactMs(Anim.BARRELS, barrels))
        assertEquals(730L, swingHoldMs(Anim.BARRELS, barrels, impact = 320L))
        // Thrash: 180,90,110,150,70,240 (840 ms). The second jaw-shut lands 530 ms in; the 310 ms left fit in the usual hold.
        val thrash = jaws.spec(Anim.THRASH)
        assertEquals(530L, swingImpactMs(Anim.THRASH, thrash))
        assertEquals(AFTER_IMPACT_MS, swingHoldMs(Anim.THRASH, thrash, impact = 530L))
        // His ATTACK is still cut short after its snap, like everyone's.
        val attack = jaws.spec(Anim.ATTACK)
        assertEquals(660L, swingImpactMs(Anim.ATTACK, attack))
        assertEquals(AFTER_IMPACT_MS, swingHoldMs(Anim.ATTACK, attack, impact = 660L))
    }

    // ---- facing ----

    @Test
    fun jawsTurnsToFaceHisOpponentFromTheLeft() {
        // His art faces screen-left: on the player's side (the left) he is mirrored to face right, at the CPU.
        for (anim in Anim.entries - Anim.RUN_LEFT - Anim.RUN_RIGHT) {
            assertTrue("$anim", mirroredInFight(PetId.JAWS, Side.PLAYER, anim))
            assertFalse("$anim", mirroredInFight(PetId.JAWS, Side.CPU, anim))
        }
    }

    @Test
    fun runsFaceTheWayTheyGoAndAreNeverMirrored() {
        // Every killer's run strips already face their way: in, at the other fighter, and off when running away.
        for (pet in PetId.entries) for (side in Side.entries) for (run in listOf(Anim.RUN_LEFT, Anim.RUN_RIGHT)) {
            assertFalse("$pet $side $run", mirroredInFight(pet, side, run))
        }
    }

    @Test
    fun theOthersFaceTheCameraAndAreNeverMirrored() {
        for (pet in PetId.entries - PetId.JAWS) for (side in Side.entries) for (anim in Anim.entries) {
            assertFalse("$pet $side $anim", mirroredInFight(pet, side, anim))
        }
    }
}
