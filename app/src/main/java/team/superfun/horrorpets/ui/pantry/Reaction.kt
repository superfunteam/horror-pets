package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SpriteManifest
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.ui.FedShow
import team.superfun.horrorpets.ui.common.OK_GUARD_MS
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.statusBanner
import team.superfun.horrorpets.ui.common.mealChipColor
import team.superfun.horrorpets.ui.common.statusSound
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextLo
import team.superfun.horrorpets.ui.training.GameClock
import team.superfun.horrorpets.ui.training.GlowText
import team.superfun.horrorpets.ui.training.TrainingActor
import team.superfun.horrorpets.ui.training.TrainingGold

/** How many times the killer plays its feed animation over one meal; the meal's bites spread across them. */
internal const val MUNCHES = 2

/** The reaction stays up this long before the kitchen carries on Home by itself (OK or a tap sooner). */
internal const val REACTION_HOLD_MS = 4_000L

/** A level-up's gold "LEVEL UP!" (and its sound) comes this long after the reaction, once its own beat has landed. */
internal const val LEVEL_UP_AFTER_MS = 900L

/** What the killer says when it is too full to eat (Home said it too, back when FEED fed there). */
internal const val TOO_FULL_LINE = "Too full!"

/** What a feed show is doing: the meal tossed to the floor, munched, the reaction, and done (carry on Home). */
internal enum class FeedPhase { TOSS, MUNCH, REACT, DONE }

/**
 * A feed show's timeline, in ms of the show's own clock from FEED (it stops while the app is away): the meal lands
 * (and the munching starts) at [landMs], the reaction comes at [reactMs] when the munching is done, OK or a tap may
 * carry on Home from [carryOnMs], and at [doneMs] the kitchen carries on Home by itself.
 */
internal data class FeedTimeline(val landMs: Long, val reactMs: Long, val carryOnMs: Long, val doneMs: Long) {
    fun phaseAt(ms: Long): FeedPhase = when {
        ms < landMs -> FeedPhase.TOSS
        ms < reactMs -> FeedPhase.MUNCH
        ms < doneMs -> FeedPhase.REACT
        else -> FeedPhase.DONE
    }

    /** Whether OK or a tap carries on Home [ms] in: only once the reaction has been up a moment, so nothing skips it. */
    fun carriesOn(ms: Long): Boolean = ms >= carryOnMs
}

/**
 * The feed show's timeline for a killer whose munching (all [MUNCHES] of its feed animation) takes [munchMs], and
 * whose reaction may be carried on from [carryOnAfterMs] into it (see [ReactionCue.carryOnAfterMs]).
 */
internal fun feedTimeline(munchMs: Long, carryOnAfterMs: Long): FeedTimeline {
    val land = FOOD_FALL_MS + LAND_SETTLE_MS
    val react = land + munchMs
    return FeedTimeline(land, react, react + carryOnAfterMs, react + REACTION_HOLD_MS)
}

/** Once LEVEL UP! (or the meal's status) is up, it stays this long before OK or a tap may carry on past it. */
internal const val LEVEL_UP_READ_MS = 500L

/** The meal's status ([statusBanner]) comes this long after the gains, or after LEVEL UP! when the meal levelled up. */
internal const val STATUS_AFTER_MS = 400L

/**
 * A meal that left a status has the killer's bubble swap its taste line for the status's own ([MealStatus.line]) this
 * long into the reaction, once the banner has landed; it says that until the reaction ends.
 */
internal const val STATUS_LINE_AFTER_MS = 1_400L

/**
 * How a reaction plays, as it starts (times in ms into it): its [sound], the killer's [anims], the favorite's
 * [hearts], when LEVEL UP! shows ([levelUpAtMs], null without a level-up) and whether it plays its sound
 * ([levelUpSound]), when the meal's status shows ([statusAtMs], null without one) and its sound ([statusSound]), when
 * OK or a tap may carry on ([carryOnAfterMs]), and the status's line ([statusLine]) and when the bubble swaps to it
 * ([statusLineAtMs]), both null without a status.
 */
internal data class ReactionCue(
    val sound: String?,
    val anims: List<Anim>,
    val hearts: Boolean,
    val levelUpAtMs: Long?,
    val levelUpSound: Boolean,
    val carryOnAfterMs: Long,
    val statusAtMs: Long? = null,
    val statusSound: String? = null,
    val statusLine: String? = null,
    val statusLineAtMs: Long? = null,
) {
    /**
     * What the killer's bubble says [ms] into the reaction for a meal whose [taste] line ([FedShow.line]) it reacted
     * with: that line, then the status's own from [statusLineAtMs] on; only ever the taste line without a status.
     */
    fun lineAt(ms: Long, taste: String): String {
        val at = statusLineAtMs
        return if (statusLine != null && at != null && ms >= at) statusLine else taste
    }
}

/**
 * How [fed]'s reaction plays. Fresh, it plays in full: LEVEL UP! comes [LEVEL_UP_AFTER_MS] in, the meal's status
 * [STATUS_AFTER_MS] after the gains (after LEVEL UP!, with both), and the status's line in the bubble
 * [STATUS_LINE_AFTER_MS] in. OK or a tap counts only after [OK_GUARD_MS] (presses mashed through the munch can't skip
 * it the frame it shows), and after the last of LEVEL UP! and the status has been up [LEVEL_UP_READ_MS]. A [replay]
 * (the kitchen came back, its Activity made again, on a meal already eaten) is shown finished and silent: the line (the
 * status's, with one), the gains, LEVEL UP! and the status at once, with no sound, no hearts and no pose, and OK after
 * the guard as ever.
 */
internal fun reactionCue(fed: FedShow, replay: Boolean): ReactionCue {
    val levelUp = fed.levelsGained > 0
    val status = fed.status
    if (replay) {
        return ReactionCue(
            sound = null,
            anims = emptyList(),
            hearts = false,
            levelUpAtMs = if (levelUp) 0L else null,
            levelUpSound = false,
            carryOnAfterMs = OK_GUARD_MS,
            statusAtMs = if (status != null) 0L else null,
            statusLine = status?.line,
            statusLineAtMs = if (status != null) 0L else null,
        )
    }
    val levelUpAt = if (levelUp) LEVEL_UP_AFTER_MS else null
    val statusAt = if (status != null) (levelUpAt ?: 0L) + STATUS_AFTER_MS else null
    val lastWords = statusAt ?: levelUpAt
    return ReactionCue(
        sound = reactionSound(fed.reaction),
        anims = reactionAnims(fed.reaction),
        hearts = hasHearts(fed.reaction),
        levelUpAtMs = levelUpAt,
        levelUpSound = levelUp,
        carryOnAfterMs = if (lastWords != null) maxOf(OK_GUARD_MS, lastWords + LEVEL_UP_READ_MS) else OK_GUARD_MS,
        statusAtMs = statusAt,
        statusSound = status?.let(::statusSound),
        statusLine = status?.line,
        statusLineAtMs = if (status != null) STATUS_LINE_AFTER_MS else null,
    )
}

/**
 * What the killer does once it has eaten, one after another: a happy jump for a dish it likes, the FAIL pose for one
 * it doesn't, a celebration of jumps for its favorite, and a look about for a raw food it shrugs at.
 */
internal fun reactionAnims(reaction: Reaction): List<Anim> = when (reaction) {
    Reaction.LOVED, Reaction.CONTENT -> listOf(Anim.JUMP)
    Reaction.HATED, Reaction.YUCK -> listOf(Anim.FAIL)
    Reaction.FAVORITE -> listOf(Anim.JUMP, Anim.JUMP, Anim.JUMP)
    Reaction.SHRUG -> listOf(Anim.LOOK_A)
}

/** The reaction's sound, played as it starts; a shrug makes none beyond the munching. */
internal fun reactionSound(reaction: Reaction): String? = when (reaction) {
    Reaction.LOVED, Reaction.CONTENT -> "eat_happy"
    Reaction.HATED, Reaction.YUCK -> "eat_gross"
    Reaction.FAVORITE -> "favorite_fanfare"
    Reaction.SHRUG -> null
}

/** Whether the reaction throws up a burst of hearts: only for the killer's favorite. */
internal fun hasHearts(reaction: Reaction): Boolean = reaction == Reaction.FAVORITE

/** One line of a meal's gains: its [text] ("+6 STRENGTH"), and whether it is a [loss] ("-15 MOOD"). */
internal data class Gain(val text: String, val loss: Boolean)

/**
 * What [fed] gave, read out over the board: the hunger it took off first ("-35 HUNGER", the one line that is good news
 * with a minus), then its XP, then the strength, fun and mood it moved (as clamped, rounded to whole points), each
 * only when it is not 0, so no line ever reads "+0". A botch shows the mood it cost; a meal that moved nothing shows
 * nothing.
 */
internal fun gainsOf(fed: FedShow): List<Gain> = buildList {
    val hungerOff = (fed.hungerBefore - fed.hungerAfter).roundToInt()
    if (hungerOff > 0) add(Gain("-$hungerOff HUNGER", loss = false))
    if (fed.xp > 0) add(Gain("+${fed.xp} XP", loss = false))
    for ((amount, name) in listOf(fed.strength to "STRENGTH", fed.funLevel to "FUN", fed.mood to "MOOD")) {
        val n = amount.roundToInt()
        if (n != 0) add(Gain(if (n > 0) "+$n $name" else "-${-n} $name", loss = n < 0))
    }
}

/** "LEVEL UP!" when the meal carried the killer past a level, else null. */
internal fun levelUpLine(fed: FedShow): String? = if (fed.levelsGained > 0) "LEVEL UP!" else null

/** How long the killer's [anim] plays once, in ms. */
internal fun SpriteManifest.lengthMs(anim: Anim): Long = spec(anim).durationsMs.sumOf { it.coerceAtLeast(0).toLong() }

/**
 * The pantry's feed show as it plays, for the kitchen to draw from: the meal in the air or on the floor ([meal], from
 * its toss to its last crumb), the line over the killer's head ([line]), the gains read out over the board ([gains],
 * and [levelUp] and the meal's [status] once each has come), the favorite's [hearts], and whether OK or a tap carries
 * on Home yet ([carryOn]). Main thread only.
 */
@Stable
internal class FeedStage {
    var meal by mutableStateOf<Meal?>(null)
    var line by mutableStateOf<String?>(null)
    var gains by mutableStateOf<List<Gain>?>(null)
    var levelUp by mutableStateOf<String?>(null)
    var status by mutableStateOf<MealStatus?>(null)
    var hearts by mutableStateOf(false)
    var carryOn by mutableStateOf(false)

    /** Whether the show is playing, so its clock runs (only then: it asks for frames). */
    var playing by mutableStateOf(false)
}

/** A meal on its way to being eaten: its picture and its crumbs' colors, and when it has landed ([landed]). */
internal class Meal(val image: ImageBitmap, val crumbs: List<Color>) {
    val landed = CompletableDeferred<Unit>()
}

/**
 * Plays [fed]'s show on [stage] and [actor] (the killer), from the FEED until it carries on Home ([onDone]):
 *
 * - the meal's picture is read (and its crumbs' colors from it), then it is tossed from the board to the killer's feet
 *   with the `food_drop` whistle (the kitchen draws it from [FeedStage.meal], see [FoodDrop]);
 * - as it lands the killer munches it, [MUNCHES] times its feed animation, while it loses its bites;
 * - then the reaction, as [reactionCue] has it: its pose and sound, the line over its head, the gains over the board
 *   ([gainsOf]), the favorite's hearts, a level-up's words and sound a beat later, and the meal's status with its
 *   sound ([statusSound]) a beat after those, then the status's own line in the bubble in place of the taste line
 *   ([ReactionCue.lineAt]); OK or a tap carries on once [FeedStage.carryOn] says so;
 * - and [REACTION_HOLD_MS] later the show is over ([onDone]): the kitchen carries on Home by itself, unless the killer
 *   could eat more, when it waits for OK (FEED again) or Back.
 *
 * Every wait is on [clock], the show's own, which stops while the app is away (and for the beat after it comes back),
 * so nothing plays off screen and it never carries on behind the player's back. [replay] starts at the reaction,
 * shown finished and silent (the kitchen came back to a meal already eaten: it played once already).
 */
internal suspend fun playFeedShow(
    fed: FedShow,
    loader: AssetLoader,
    manifest: SpriteManifest,
    stage: FeedStage,
    actor: TrainingActor,
    sounds: SoundSink,
    clock: GameClock,
    replay: Boolean,
    onDone: () -> Unit,
) {
    val munch = manifest.feedAnim()
    val cue = reactionCue(fed, replay)
    val timeline = feedTimeline(MUNCHES * manifest.lengthMs(munch), cue.carryOnAfterMs)
    stage.playing = true
    try {
        if (!replay) {
            val meal = withContext(Dispatchers.IO) {
                val image = loader.pantryItem(fed.item)
                Meal(image, crumbColorsOf(image))
            }
            stage.meal = meal
            sounds.sfx("food_drop")
            // Landed (or, should the kitchen never draw it, as long as a landing takes and a moment more).
            coroutineScope {
                val giveUp = launch {
                    clock.waitMs(timeline.landMs + LANDING_SLACK_MS)
                    meal.landed.complete(Unit)
                }
                meal.landed.await()
                giveUp.cancel()
            }
            actOut(actor, List(MUNCHES) { munch }, manifest, clock)
        }
        stage.line = cue.lineAt(0L, fed.line)
        stage.gains = gainsOf(fed)
        cue.sound?.let(sounds::sfx)
        if (cue.hearts) stage.hearts = true
        coroutineScope {
            launch { actOut(actor, cue.anims, manifest, clock) }
            launch {
                clock.waitMs(timeline.carryOnMs - timeline.reactMs)
                stage.carryOn = true
            }
            val words = levelUpLine(fed)
            val at = cue.levelUpAtMs
            if (words != null && at != null) {
                launch {
                    clock.waitMs(at)
                    stage.levelUp = words
                    if (cue.levelUpSound) sounds.sfx("levelup")
                }
            }
            val status = fed.status
            val statusAt = cue.statusAtMs
            if (status != null && statusAt != null) {
                launch {
                    clock.waitMs(statusAt)
                    stage.status = status
                    cue.statusSound?.let(sounds::sfx)
                }
            }
            val statusLineAt = cue.statusLineAtMs
            if (statusLineAt != null && statusLineAt > 0L) {
                launch {
                    clock.waitMs(statusLineAt)
                    stage.line = cue.lineAt(statusLineAt, fed.line)
                }
            }
            clock.waitMs(timeline.doneMs - timeline.reactMs)
        }
    } finally {
        stage.playing = false
    }
    onDone()
}

/** A meal that has not landed this long after it should have is given up on, and the munching starts anyway. */
private const val LANDING_SLACK_MS = 1_000L

/**
 * Plays [anims] on [actor] one after another, each for its length in [manifest]; all but the last hold their last
 * frame until the next starts, so the killer never drops to idle for a frame between them.
 */
private suspend fun actOut(actor: TrainingActor, anims: List<Anim>, manifest: SpriteManifest, clock: GameClock) {
    for ((i, anim) in anims.withIndex()) {
        actor.act(anim, hold = i < anims.lastIndex)
        clock.waitMs(manifest.lengthMs(anim))
    }
}

/**
 * The meal of [stage] beside the killer standing at [killerX] (px; a body [bodyHalf] wide each side), tossed from
 * [from] on the board onto the kitchen floor at [floorY], [sizePx] across (the size it had on the board), through the
 * kitchen's [filter], munched over [munchMs] of the show's [clock]. Gone once its last crumbs have landed.
 */
@Composable
internal fun MealView(
    stage: FeedStage,
    killerX: Float,
    bodyHalf: Float,
    screenW: Float,
    inset: Float,
    floorY: Float,
    sizePx: Float,
    munchMs: Long,
    filter: ColorFilter?,
    from: Offset,
    clock: GameClock,
) {
    val meal = stage.meal ?: return
    key(meal) {
        FoodDrop(
            image = meal.image,
            crumbs = meal.crumbs,
            petX = killerX,
            bodyHalf = bodyHalf,
            screenW = screenW,
            inset = inset,
            floorY = floorY,
            sizePx = sizePx,
            munchMs = munchMs,
            filter = filter,
            from = from,
            wait = clock::waitMs,
            onLanded = { meal.landed.complete(Unit) },
            onDone = { if (stage.meal === meal) stage.meal = null },
        )
    }
}

/** A loss in the gains reads in a pale blood red, under the gold of what was gained. */
private val LossRed = Color(0xFFFF8F8F)

/**
 * The meal's gains over the board, centred on [centerX] at [y] (px): what it gave ([gainsOf]), the first line big and
 * the rest under it, gold for a gain and red for a loss, popping up together; a level-up's "LEVEL UP!" under them
 * when that comes, and the meal's status under that when it comes ([StatusBanner]). Nothing at all for a meal that
 * gave nothing.
 */
@Composable
internal fun GainsReadout(stage: FeedStage, centerX: Float, y: Float) {
    val gains = stage.gains ?: return
    val levelUp = stage.levelUp
    val status = stage.status
    if (gains.isEmpty() && levelUp == null && status == null) return
    val pop = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
    Column(
        Modifier
            .placeAt(x = { centerX }, y = { y }, pivotY = 0.5f)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                alpha = ((pop.value - 0.5f) * 4f).coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((i, gain) in gains.withIndex()) {
            val size = if (i == 0) 34 else 26
            GlowText(
                gain.text,
                displayStyle(size.sp, color = if (gain.loss) LossRed else TrainingGold, tracking = 0.04f, lineHeight = (size + 2).sp),
            )
        }
        levelUp?.let { words ->
            val up = remember { Animatable(0.4f) }
            LaunchedEffect(Unit) { up.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 380f)) }
            GlowText(
                words,
                displayStyle(30.sp, FontWeight.ExtraBold, color = Color.White, tracking = 0.06f, lineHeight = 32.sp),
                modifier = Modifier.graphicsLayer {
                    scaleX = up.value
                    scaleY = up.value
                },
                glow = BloodGlow,
            )
        }
        status?.let { StatusBanner(it) }
    }
}

/**
 * The meal's [status] called out, popping in as LEVEL UP! does: its title big in its colour ([mealChipColor]), with
 * the glow LEVEL UP! has, and what it does in the next fights small under it ([statusBanner]).
 */
@Composable
private fun StatusBanner(status: MealStatus) {
    val (title, meaning) = statusBanner(status)
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 380f)) }
    Column(
        Modifier
            .padding(top = 4.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlowText(
            title,
            displayStyle(34.sp, FontWeight.ExtraBold, color = mealChipColor(status), tracking = 0.06f, lineHeight = 36.sp),
            glow = BloodGlow,
        )
        BasicText(
            meaning,
            style = displayStyle(16.sp, color = TextLo, tracking = 0.1f, halo = true, lineHeight = 18.sp),
            maxLines = 1,
        )
    }
}

/** How long the favorite's hearts fly. */
private const val HEARTS_MS = 1_700

/** One heart of the burst: thrown along [angle] (radians) at [speed] (killer heights a second), [size] across. */
private class FlyingHeart(val angle: Float, val speed: Float, val size: Float, val spin: Float, val delay: Float)

/**
 * The favorite's burst of hearts, thrown up and out from [origin] (px) for a killer [height] px tall: they fly, slow,
 * drift down a little and fade, once. It calls [onDone] when the last has gone, and draws nothing after.
 */
@Composable
internal fun HeartBurst(origin: Offset, height: Float, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val hearts = remember {
        val rng = Random(7)
        List(16) {
            val angle = (-PI / 2 + rng.nextDouble(-1.15, 1.15)).toFloat()
            FlyingHeart(angle, speed = 0.55f + rng.nextFloat() * 0.5f, size = 0.07f + rng.nextFloat() * 0.06f, spin = rng.nextFloat() * 2f - 1f, delay = rng.nextFloat() * 0.25f)
        }
    }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(HEARTS_MS, easing = LinearEasing))
        done()
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        for (h in hearts) {
            val local = ((p - h.delay) / (1f - h.delay)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val t = local * HEARTS_MS / 1000f
            // Thrown out fast, slowing (drag), then drifting down a little.
            val travel = h.speed * height * (1f - (1f - local) * (1f - local)) * 0.9f
            val sway = sin(local * 9f + h.spin * 3f) * height * 0.02f
            val center = Offset(
                origin.x + cos(h.angle) * travel + sway,
                origin.y + sin(h.angle) * travel + 0.35f * height * t * t * 0.3f,
            )
            val alpha = if (local < 0.7f) 1f else 1f - (local - 0.7f) / 0.3f
            val grow = 0.6f + 0.4f * (local * 4f).coerceAtMost(1f)
            drawHeart(center, h.size * height * grow, alpha = alpha.coerceIn(0f, 1f))
        }
    }
}
