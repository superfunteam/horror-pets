package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.BOARD_PLACES
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.BUBBLE_ABOVE_FEET
import team.superfun.horrorpets.ui.common.BUBBLE_MS
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SlabHeight
import team.superfun.horrorpets.ui.common.SpeechBubble
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.spriteFilter
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo
import team.superfun.horrorpets.ui.training.GlowText
import team.superfun.horrorpets.ui.training.HUD_STATUS_SP
import team.superfun.horrorpets.ui.training.HUD_STATUS_TRACKING
import team.superfun.horrorpets.ui.training.HUD_TITLE_LINE_SP
import team.superfun.horrorpets.ui.training.HUD_TITLE_SP
import team.superfun.horrorpets.ui.training.HUD_TITLE_TRACKING
import team.superfun.horrorpets.ui.training.HelpItem
import team.superfun.horrorpets.ui.training.HelpStrip
import team.superfun.horrorpets.ui.training.TrainingActor
import team.superfun.horrorpets.ui.training.TrainingGold
import team.superfun.horrorpets.ui.training.reachesGame
import team.superfun.horrorpets.ui.training.rememberAwayPause
import team.superfun.horrorpets.ui.training.rememberGameClock
import team.superfun.horrorpets.ui.training.swallowTouches
import team.superfun.horrorpets.ui.training.swallowedWhileHeld
import team.superfun.horrorpets.ui.training.trainingKeyOf
import team.superfun.horrorpets.game.pantry.canEat
import team.superfun.horrorpets.game.pantry.DISHES
import team.superfun.horrorpets.game.pantry.dishesFound
import team.superfun.horrorpets.game.pantry.knownDish

/** How long a refused cook's note stays up, over the shaking board (see [REFUSED_COOK_NOTE]). */
private const val NOTE_MS = 1_500L

/**
 * What a refused cook says over the board. The only cook refused is a botch with something that isn't one, so it
 * teaches the rule.
 */
internal const val REFUSED_COOK_NOTE = "Slop only cooks with slop."

/** What the help strip says once the reaction is up: on Home, or, while the killer could eat more, to FEED again. */
private val CarryOnHelp = listOf(HelpItem(listOf(TrainingKey.OK), "Carry on"))
private const val CARRY_ON_TOUCH = "Tap to carry on"
private val FeedMoreHelp = listOf(HelpItem(listOf(TrainingKey.OK), "Feed more · Back when done"))
private const val FEED_MORE_TOUCH = "Tap to feed more · Back when done"

/** How far the board swings in a refused cook's shake. */
private val ShakeReach = 12.dp

/** The shelf and the buttons fade to this while the killer eats: the kitchen's work is done. */
private const val SERVED_ALPHA = 0.35f

/**
 * FEED: the pantry, in its horror kitchen. [pet] stands on the kitchen floor at the left; in the middle is the cutting
 * board with its two places and COOK and FEED under it, and along the bottom the shelf: everything in [shelf], a
 * carousel that slides left and right. The help strip under it says what the keys (or a finger) do right now.
 *
 * - On a TV the remote moves over three rows, as they stand: the shelf at the bottom, COOK and FEED over it, and the
 *   board's items at the top. Left and Right move along a row, Up and Down between rows (a held one moves one row). OK
 *   puts the focused shelf item on the board or takes it off again, takes the focused board item off, or presses the
 *   button. A held OK places nothing twice; a held Left or Right keeps moving, ticking only on the press.
 * - Under a finger: a tap on an item puts it on the board (or takes it off), a tap on the board takes it off, a drag
 *   slides the shelf, and the buttons take taps.
 * - COOK acts with two items on the board, FEED with one ([Screen.Pantry.board]); either is dim otherwise, and a
 *   press on it buzzes. Back leaves at any time.
 *
 * A cook is shown (see [CookShowView]): the two items meet in the board's middle, poof, the dish pops up with its
 * name and flies to its slot. A refused cook shakes the board. Once the killer has been fed, its meal is tossed to its
 * feet and munched, and it reacts (see [playFeedShow]); OK or a tap then carries on Home, or it does by itself after a
 * few seconds. A killer too full to eat fails and says so. All of it plays from the view model's tokens and shows,
 * never from the shelf, and nothing runs once it has played but the killer's idle.
 *
 * The remote's focus is this screen's own, not Compose focus (see [KitchenCursor]), and so are its key sounds (see
 * `Screen.ownsKeySounds`). Input is held (see [kitchenInputHeld]) while the screen comes in or goes out, while the app
 * is away and for a moment after, through a cook's show, and once the killer has eaten (the reaction has the keys).
 * Everything it reports names [Screen.Pantry.session].
 */
@Composable
fun PantryScreen(
    screen: Screen.Pantry,
    pet: PetState,
    shelf: List<PantryItem>,
    found: Set<String>,
    loader: AssetLoader,
    onPick: (Long, Int) -> Unit,
    onCook: (Long) -> Unit,
    onFeed: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onMore: (Long) -> Unit,
    onBack: (Long) -> Unit,
    focusEntry: Any?,
) {
    // Each visit starts afresh (focus on the first item), even on a screen that never left: FEED, Back and FEED again
    // within a frame (as the debug puppet can) hands this screen the next session while it still shows.
    key(screen.session) {
        // Shown in kind order (see [ShelfView]): the visit works in shown slots, the view model in shelf indices.
        val view = remember(shelf) { ShelfView(shelf) }
        val shown = screen.copy(
            board = screen.board.map(view::place),
            cook = screen.cook?.let { if (it.at in shelf.indices) it.copy(at = view.place(it.at)) else it },
        )
        PantryVisit(
            shown, pet, view.items, view.breaks, found, loader,
            onPick = { s, slot -> onPick(s, view.order[slot]) },
            onCook, onFeed, onDone, onMore, onBack, focusEntry,
        )
    }
}

/** One visit ([Screen.Pantry.session]) of [PantryScreen]. */
@Composable
private fun PantryVisit(
    screen: Screen.Pantry,
    pet: PetState,
    shelf: List<PantryItem>,
    breaks: Set<Int>,
    found: Set<String>,
    loader: AssetLoader,
    onPick: (Long, Int) -> Unit,
    onCook: (Long) -> Unit,
    onFeed: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onMore: (Long) -> Unit,
    onBack: (Long) -> Unit,
    focusEntry: Any?,
) {
    val session = screen.session
    val sounds = LocalSoundSink.current
    GameBackHandler(enabled = focusEntry != null) { onBack(session) }
    val rootFocus = remember { FocusRequester() }
    RequestFocusOnEntry(rootFocus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val away = rememberAwayPause()
    val fed = screen.fed
    val kitchen = remember(loader) { loader.kitchenScene() }
    val manifest = remember(loader, pet.id) { loader.manifest(pet.id) }
    val actor = remember { TrainingActor() }
    val heart = favoriteHeart(pet)

    // Warm what the killer may play here, so a meal's munch and its reaction start on time.
    LaunchedEffect(loader, pet.id) {
        withContext(Dispatchers.IO) {
            for (anim in listOf(manifest.feedAnim(), Anim.JUMP, Anim.FAIL, Anim.LOOK_A)) loader.strip(pet.id, anim)
        }
    }

    // The remote's place, kept on the shelf (and the board) as they change; and where each board item sits.
    val cursor = remember { KitchenCursor() }
    val lastPlaces = remember { arrayOf<List<Int?>>(emptyList()) }
    val places = remember(screen.board) { boardPlaces(lastPlaces[0], screen.board).also { lastPlaces[0] = it } }
    SideEffect { cursor.follow(shelf, screen, places) }
    // What stood in each of the board's places when it last held anything: where a cook's two inputs and a meal set
    // off from, as the board is already empty when their show starts.
    val lastOnBoard = remember { arrayOf<List<PantryItem?>>(emptyList()) }
    SideEffect { if (screen.board.isNotEmpty()) lastOnBoard[0] = places.map { it?.let(shelf::getOrNull) } }

    // Read when a key or a tap comes in, so it acts on the shelf and board as they are drawn now.
    val latestShelf by rememberUpdatedState(shelf)
    val latestScreen by rememberUpdatedState(screen)
    val latestPlaces by rememberUpdatedState(places)
    val latestPet by rememberUpdatedState(pet)
    // With one item on the board, the shelf items it has made a dish with before wear a mark (see [knownDish]).
    val known: Set<Int> = remember(screen.board, shelf, found) {
        val index = screen.board.singleOrNull() ?: return@remember emptySet()
        val on = shelf.getOrNull(index) ?: return@remember emptySet()
        shelf.indices.filterTo(mutableSetOf()) { it != index && knownDish(on, shelf[it], found) != null }
    }
    // Once fed: whether OK feeds again (the killer could eat more, and there is something to eat) rather than go Home.
    val feedMore = fed != null && canEat(pet) && shelf.isNotEmpty()

    // The cook on show, from its token: one already made when the visit started is not shown again.
    val cook = screen.cook
    var shownCook by remember { mutableStateOf(cook?.token) }
    val cooking = cook != null && cook.token != shownCook
    val cookFrom = remember(cook?.token) { cook?.let { convergeFrom(it.inputs, lastOnBoard[0]) } ?: emptyList() }

    // A refused cook: the board shakes, and says why for a moment. Tokens there when the visit started are old news.
    val seenRefusal = remember { screen.refusedCook }
    val shake = remember { Animatable(SHAKE_MS.toFloat()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.refusedCook) {
        if (screen.refusedCook == 0L || screen.refusedCook == seenRefusal) return@LaunchedEffect
        note = REFUSED_COOK_NOTE
        shake.snapTo(0f)
        shake.animateTo(SHAKE_MS.toFloat(), tween(SHAKE_MS.toInt(), easing = LinearEasing))
        delay(max(0L, NOTE_MS - SHAKE_MS))
        note = null
    }

    // Too full to eat: the killer fails, and says so.
    val seenTooFull = remember { screen.tooFull }
    var tooFull by remember { mutableStateOf<Said?>(null) }
    LaunchedEffect(screen.tooFull) {
        if (screen.tooFull == 0L || screen.tooFull == seenTooFull) return@LaunchedEffect
        actor.act(Anim.FAIL)
        val said = Said(TOO_FULL_LINE)
        tooFull = said
        delay(BUBBLE_MS)
        if (tooFull === said) tooFull = null
    }

    // Fed: the meal, the munch and the reaction, then Home, on the show's own clock (stopped while the app is away, and
    // only running while the show plays), unless the killer could eat more: then the kitchen waits for OK (FEED again)
    // or Back (Home). A visit that started with the meal eaten (the Activity was made again on it) shows the reaction
    // finished and silent: it has played once already.
    val feedStage = remember { FeedStage() }
    val showClock = rememberGameClock(paused = away.paused || !feedStage.playing)
    val fedAtStart = remember { fed != null }
    val latestOnDone by rememberUpdatedState(onDone)
    val latestOnMore by rememberUpdatedState(onMore)
    LaunchedEffect(fed) {
        if (fed == null) return@LaunchedEffect
        playFeedShow(fed, loader, manifest, feedStage, actor, sounds, showClock, replay = fedAtStart) {
            if (!(canEat(latestPet) && latestShelf.isNotEmpty())) latestOnDone(session)
        }
    }
    // OK or a tap once the reaction is up (read when it comes, so the tap's handler set up once still sees it now): a
    // select's tick, as training's results card has, and Home, or another visit while the killer could eat more. Once
    // only: a second press in the same frame does nothing.
    val carryOn = {
        if (feedStage.carryOn && !away.paused) {
            feedStage.carryOn = false
            sounds.sfx("ui_select")
            if (canEat(latestPet) && latestShelf.isNotEmpty()) latestOnMore(session) else latestOnDone(session)
        }
    }

    val held = kitchenInputHeld(
        entered = focusEntry != null,
        okReady = okReady.value,
        away = away.paused,
        cooking = cooking,
        fed = fed != null,
    )
    val latestHeld by rememberUpdatedState(held)

    /** OK or a tap on shelf item [index]: on the board, or off it again; a full board buzzes. */
    fun pick(index: Int) {
        val board = latestScreen.board
        if (index !in latestShelf.indices) return
        if (index in board || board.size < BOARD_PLACES) {
            sounds.sfx("pantry_pick")
            onPick(session, index)
        } else {
            sounds.sfx("refuse")
        }
    }

    /** OK or a tap on [button]: it acts (the view model and the shows make its sounds), or buzzes while it can't. */
    fun press(button: KitchenButton) {
        val board = latestScreen.board
        when (button) {
            KitchenButton.COOK -> if (board.size == BOARD_PLACES) onCook(session) else sounds.sfx("refuse")
            KitchenButton.FEED -> if (board.size == 1) onFeed(session) else sounds.sfx("refuse")
        }
    }

    fun onKey(key: TrainingKey, repeat: Int) {
        when (val action = cursor.move(key, latestPlaces, latestShelf.size, repeat)) {
            KitchenAction.None -> Unit
            KitchenAction.Moved -> if (ticks(action, repeat)) sounds.sfx("ui_move")
            is KitchenAction.Pick -> pick(action.index)
            is KitchenAction.Press -> press(action.button)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .ignoreOkUnless(okReady)
            // Once the killer has eaten the reaction has the keys: OK carries on Home once it is up, and nothing else
            // reaches the kitchen (Back is the back handler's own).
            .onPreviewKeyEvent { event ->
                if (fed == null) return@onPreviewKeyEvent false
                val key = trainingKeyOf(event.key) ?: return@onPreviewKeyEvent false
                if (key == TrainingKey.OK && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) carryOn()
                true
            }
            .onPreviewKeyEvent { swallowedWhileHeld(it.key, it.type, held) }
            .kitchenKeys(::onKey)
            .focusable(),
    ) {
        val safe = safeInset()
        val density = LocalDensity.current
        fun px(dp: Float): Float = with(density) { dp.dp.toPx() }
        val killerHeight = maxHeight.value * HOME_PET_HEIGHT
        val killerHalf = killerHeight * manifest.cell[0] / manifest.cell[1] * KILLER_BODY_HALF
        val layout = KitchenLayout.of(
            width = maxWidth.value,
            height = maxHeight.value,
            insetX = safe.horizontal.value,
            insetY = safe.vertical.value,
            killerHalf = killerHalf,
        )
        val touch = isTouchInput()
        val window = shelfWindow(cursor.focus, shelf.size, layout.slots, cursor.first)
        SideEffect { cursor.first = window.first }
        val latestWindow by rememberUpdatedState(window)
        val floorY = remember { mutableFloatStateOf(Float.NaN) }
        val tint = remember(kitchen) { hexColor(kitchen.tint) }
        val drawnHeight = px(killerHeight) * manifest.bodyScale
        val served by animateFloatAsState(if (fed != null) SERVED_ALPHA else 1f, tween(400), label = "kitchenServed")

        // The kitchen, and the killer standing on its floor at the left, in its light.
        SceneBackdrop(loader, kitchen) { floor ->
            SideEffect { floorY.floatValue = floor }
            SpriteView(
                loader = loader,
                pet = pet.id,
                anim = actor.anim,
                heightPx = px(killerHeight),
                tint = tint,
                tintStrength = kitchen.tintStrength,
                shadowOpacity = kitchen.shadowOpacity,
                playKey = actor.playKey,
                onFinished = actor.onFinished,
                modifier = Modifier.placeAt(x = { px(layout.killerX) }, y = { floor }),
            )
        }

        // The title, as training's HUD has its game's.
        Column(Modifier.padding(start = safe.horizontal, top = safe.vertical)) {
            GlowText("THE PANTRY", displayStyle(HUD_TITLE_SP.sp, tracking = HUD_TITLE_TRACKING, lineHeight = HUD_TITLE_LINE_SP.sp))
            BasicText(
                if (shelf.size >= Tuning.PANTRY_MAX) "THE SHELF IS FULL" else "${shelf.size} ON THE SHELF",
                style = displayStyle(HUD_STATUS_SP.sp, FontWeight.Bold, color = TrainingGold, tracking = HUD_STATUS_TRACKING, halo = true),
                maxLines = 1,
            )
            // How hungry the killer is (live: it drops as the meal goes down), so the player knows what to feed.
            BasicText(
                hungerLine(pet.hunger),
                style = displayStyle(HUD_STATUS_SP.sp, FontWeight.Bold, color = hungerColor(pet.hunger), tracking = HUD_STATUS_TRACKING, halo = true),
                maxLines = 1,
            )
            // Out of the way of the killer's speech bubble, which comes up this high over its head.
            val bubbleUp = feedStage.line != null || tooFull != null
            val recipesShown by animateFloatAsState(if (bubbleUp) 0f else 1f, tween(200), label = "recipesShown")
            BasicText(
                "RECIPES ${dishesFound(found)} / ${DISHES.size}",
                style = displayStyle(HUD_STATUS_SP.sp, FontWeight.Bold, color = TrainingGold, tracking = HUD_STATUS_TRACKING, halo = true),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = recipesShown },
            )
        }

        val boardLeft = layout.columnCenter - layout.boardWidth / 2f
        val boardHeight = layout.boardWidth / BOARD_ASPECT
        val itemPx = px(layout.boardWidth * BOARD_ITEM_SHARE)
        fun boardPoint(at: Offset) = Offset(px(boardLeft + layout.boardWidth * at.x), px(layout.boardTop + boardHeight * at.y))

        Board(
            loader = loader,
            shelf = shelf,
            places = places,
            heart = heart,
            width = layout.boardWidth.dp,
            focus = cursor.place.takeIf { cursor.row == KitchenRow.BOARD && !touch && fed == null },
            plus = !cooking && fed == null,
            onTap = ::pick,
            modifier = Modifier
                .offset(x = boardLeft.dp, y = layout.boardTop.dp)
                .graphicsLayer { translationX = shakeAt(shake.value.toLong()) * ShakeReach.toPx() },
        )
        KitchenButtons(
            canCook = screen.board.size == BOARD_PLACES,
            canFeed = screen.board.size == 1,
            focused = cursor.button.takeIf { cursor.row == KitchenRow.BUTTONS && !touch && fed == null },
            onPress = ::press,
            modifier = Modifier
                .offset(x = (layout.columnCenter - KitchenButtonsWidth / 2f).dp, y = layout.buttonsTop.dp)
                .graphicsLayer { alpha = served },
        )
        Shelf(
            loader = loader,
            shelf = shelf,
            board = screen.board,
            heart = heart,
            breaks = breaks,
            known = known,
            window = window,
            focus = cursor.focus.takeIf { cursor.row == KitchenRow.SHELF && !touch && shelf.isNotEmpty() && fed == null },
            hidden = cook?.at?.takeIf { cooking },
            enabled = { !latestHeld },
            onTap = ::pick,
            onScrolled = { first ->
                cursor.first = first
                cursor.focus = ShelfWindow(first, layout.slots).focusWithin(cursor.focus, latestShelf.size)
            },
            modifier = Modifier
                .offset(x = (layout.columnCenter - layout.columnHalf).dp, y = layout.shelfTop.dp)
                .size((layout.columnHalf * 2f).dp, ShelfHeight)
                .graphicsLayer { alpha = served },
        )
        if (shelf.isEmpty() && fed == null) {
            BasicText(
                "THE SHELF IS EMPTY",
                style = displayStyle(16.sp, FontWeight.Bold, color = TextLo, tracking = 0.1f, halo = true),
                modifier = Modifier.placeAt(
                    x = { px(layout.columnCenter) },
                    y = { with(density) { (layout.shelfTop.dp + ShelfStandY).toPx() } },
                ),
            )
        }
        // Nothing while a show holds the keys; then what carries on, once it may.
        HelpStrip(
            help = when {
                fed != null -> if (!feedStage.carryOn) emptyList() else if (feedMore) FeedMoreHelp else CarryOnHelp
                cooking -> emptyList()
                else -> kitchenHelp(cursor.row, cursor.button, screen.board, cursor.focus, shelf.size)
            },
            touchHelp = when {
                fed != null -> if (!feedStage.carryOn) "" else if (feedMore) FEED_MORE_TOUCH else CARRY_ON_TOUCH
                cooking -> ""
                else -> kitchenTouchHelp(screen.board.size)
            },
            modifier = Modifier.placeAt(x = { px(layout.columnCenter) }, y = { px(layout.helpBottom) }),
        )
        note?.let {
            BasicText(
                it.uppercase(),
                style = displayStyle(16.sp, FontWeight.Bold, color = TextHi, tracking = 0.06f),
                maxLines = 1,
                modifier = Modifier
                    .placeAt(x = { px(layout.columnCenter) }, y = { px(layout.boardTop + 6f) }, pivotY = 0f)
                    .gameFrame(topEnd = 7.dp, bottomStart = 7.dp, inner = false)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }

        // The cook on show, over the board and the shelf.
        if (cooking && cook != null) {
            key(cook.token) {
                val stage = CookStage(
                    place = { boardPoint(PlaceAt[it.coerceIn(0, PlaceAt.lastIndex)]) },
                    middle = boardPoint(PlusAt),
                    item = itemPx,
                    shown = itemPx * 1.3f,
                    slot = {
                        val w = latestWindow
                        val lead = shelfLead(latestShelf.size, w.slots)
                        val pitch = layout.columnHalf * 2f / w.slots
                        val x = layout.columnCenter - layout.columnHalf + (cook.at - w.first + lead + 0.5f) * pitch
                        val lit = !touch && cursor.row == KitchenRow.SHELF && cursor.focus == cook.at
                        Offset(px(x), px(layout.shelfTop + ShelfStandY.value)) to px(ShelfItemSize.value * if (lit) FOCUS_SCALE else 1f)
                    },
                )
                CookShowView(loader, cook, cookFrom, heart, stage) { shownCook = cook.token }
            }
        }

        // The meal, the hearts and the gains: over everything in the kitchen.
        val floor = floorY.floatValue
        if (fed != null && !floor.isNaN()) {
            val place = remember(fed) { lastOnBoard[0].indexOf(fed.item).takeIf { it >= 0 } ?: 0 }
            val from = boardPoint(PlaceAt[place]).let { Offset(it.x, it.y + itemPx / 2f) }
            val filter = remember(tint, kitchen.tintStrength) { spriteFilter(tint, kitchen.tintStrength, 0f, 1f, 0f) }
            MealView(
                stage = feedStage,
                killerX = px(layout.killerX),
                bodyHalf = px(killerHalf) * manifest.bodyScale,
                screenW = constraints.maxWidth.toFloat(),
                inset = px(safe.horizontal.value),
                floorY = floor,
                sizePx = itemPx,
                munchMs = MUNCHES * manifest.lengthMs(manifest.feedAnim()),
                filter = filter,
                from = from,
                clock = showClock,
            )
            if (feedStage.hearts) {
                HeartBurst(origin = Offset(px(layout.killerX), floor - drawnHeight * 0.62f), height = drawnHeight) {
                    feedStage.hearts = false
                }
            }
            GainsReadout(feedStage, centerX = px(layout.columnCenter), y = px(layout.boardTop + boardHeight * 0.45f))
        }
        if (!floor.isNaN()) {
            SpeechBubble(
                text = feedStage.line ?: tooFull?.text,
                headX = { px(layout.killerX) },
                bottomY = { floorY.floatValue - drawnHeight * BUBBLE_ABOVE_FEET },
                minLeft = { px(safe.horizontal.value) },
                maxRight = constraints.maxWidth - px(safe.horizontal.value),
            )
        }

        // While held, no touch reaches the kitchen; once the reaction is up, a tap anywhere carries on Home.
        if (held) Spacer(Modifier.fillMaxSize().swallowTouches())
        if (fed != null) Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { carryOn() } })
    }
}

/** One showing of a line over the killer's head; a new one restarts its timer even with the same words. */
@Immutable
private class Said(val text: String)

/**
 * Hands the kitchen its keys while it has focus: [onKey] gets each training key going down with its repeat count (0
 * for the press itself), all but a held OK's repeats (see [reachesGame]). Keys let go are taken and do nothing.
 */
private fun Modifier.kitchenKeys(onKey: (key: TrainingKey, repeat: Int) -> Unit): Modifier = onKeyEvent { event ->
    val key = trainingKeyOf(event.key) ?: return@onKeyEvent false
    when (event.type) {
        KeyEventType.KeyDown -> {
            val repeat = event.nativeKeyEvent.repeatCount
            if (reachesGame(key, down = true, repeat = repeat)) onKey(key, repeat)
            true
        }
        KeyEventType.KeyUp -> true
        else -> false
    }
}

/**
 * Whether the kitchen holds the player's input (keys and touches): until it has been [entered] and its entry guard
 * is [okReady], while the app is [away] (and the beat after it comes back), while a cook is on show ([cooking]), and
 * once the killer has been [fed] (its reaction takes OK and taps itself).
 */
internal fun kitchenInputHeld(entered: Boolean, okReady: Boolean, away: Boolean, cooking: Boolean, fed: Boolean): Boolean =
    !entered || !okReady || away || cooking || fed

/** Whether [action], from a key's [repeat] (0 for the press itself), ticks: a move, on the press only. */
internal fun ticks(action: KitchenAction, repeat: Int): Boolean = action == KitchenAction.Moved && repeat == 0

/** The kitchen's rows for the remote, top to bottom as they stand: the board's items, COOK and FEED, the shelf. */
internal enum class KitchenRow { BOARD, BUTTONS, SHELF }

/** What a key did in the kitchen (see [KitchenCursor.move]). */
internal sealed interface KitchenAction {
    /** Nothing: there was nowhere to go. */
    data object None : KitchenAction

    /** The focus moved. */
    data object Moved : KitchenAction

    /** OK on shelf item [index]: onto the board, or off it (from the shelf or from the board). */
    data class Pick(val index: Int) : KitchenAction

    /** OK on [button]. */
    data class Press(val button: KitchenButton) : KitchenAction
}

/**
 * Where the remote is in the kitchen: its [row], the [focus] on the shelf (kept while it is elsewhere), the [button] it
 * is on, the board [place] it is on, and the [first] slot the shelf's window shows. [move] answers a key; [follow]
 * keeps the focus on the shelf and the board as they change. Main thread only.
 */
@Stable
internal class KitchenCursor {
    var row by mutableStateOf(KitchenRow.SHELF)
    var button by mutableStateOf(KitchenButton.COOK)
    var focus by mutableIntStateOf(0)
    var place by mutableIntStateOf(0)
    var first by mutableIntStateOf(0)

    private var seenShelf: List<PantryItem>? = null
    private var seenBoard: List<Int> = emptyList()
    private var seenCook: Long? = null
    private var seenFed = false

    /**
     * [key] on a shelf of [shelfSize] items with the board's [places] (shelf indices, null for an empty place; see
     * [boardPlaces]), its [repeat]th repeat (0 for the press). The rows stand as on screen: Up from the shelf goes to
     * the button that can act, Up from the buttons onto the board's items (the one on the button's side, when both
     * are there), and Down back again. Left and Right move along a row; a held Up or Down moves one row only. OK picks
     * the focused shelf item, takes the focused board item off, or presses the button.
     */
    fun move(key: TrainingKey, places: List<Int?>, shelfSize: Int, repeat: Int = 0): KitchenAction {
        if (repeat > 0 && (key == TrainingKey.UP || key == TrainingKey.DOWN)) return KitchenAction.None
        val filled = places.indices.filter { places[it] != null }
        return when (row) {
            KitchenRow.SHELF -> when (key) {
                TrainingKey.LEFT, TrainingKey.RIGHT -> {
                    val next = (focus + if (key == TrainingKey.LEFT) -1 else 1).coerceIn(0, max(0, shelfSize - 1))
                    moved(next != focus).also { focus = next }
                }
                TrainingKey.UP -> {
                    row = KitchenRow.BUTTONS
                    button = when (filled.size) {
                        BOARD_PLACES -> KitchenButton.COOK
                        1 -> KitchenButton.FEED
                        else -> button
                    }
                    KitchenAction.Moved
                }
                TrainingKey.DOWN -> KitchenAction.None
                TrainingKey.OK -> if (shelfSize > 0) KitchenAction.Pick(focus.coerceIn(0, shelfSize - 1)) else KitchenAction.None
            }
            KitchenRow.BUTTONS -> when (key) {
                TrainingKey.DOWN -> {
                    row = KitchenRow.SHELF
                    KitchenAction.Moved
                }
                TrainingKey.UP -> {
                    if (filled.isEmpty()) return KitchenAction.None
                    // The place on the button's side when it holds something, else the one that does.
                    val side = if (button == KitchenButton.FEED) filled.last() else filled.first()
                    place = side
                    row = KitchenRow.BOARD
                    KitchenAction.Moved
                }
                TrainingKey.LEFT -> moved(button == KitchenButton.FEED).also { button = KitchenButton.COOK }
                TrainingKey.RIGHT -> moved(button == KitchenButton.COOK).also { button = KitchenButton.FEED }
                TrainingKey.OK -> KitchenAction.Press(button)
            }
            KitchenRow.BOARD -> when (key) {
                TrainingKey.LEFT, TrainingKey.RIGHT -> {
                    val next = (if (key == TrainingKey.LEFT) filled.lastOrNull { it < place } else filled.firstOrNull { it > place })
                    if (next == null) KitchenAction.None else KitchenAction.Moved.also { place = next }
                }
                TrainingKey.DOWN -> {
                    row = KitchenRow.BUTTONS
                    KitchenAction.Moved
                }
                TrainingKey.UP -> KitchenAction.None
                TrainingKey.OK -> places.getOrNull(place)?.let { KitchenAction.Pick(it) } ?: KitchenAction.None
            }
        }
    }

    private fun moved(did: Boolean): KitchenAction = if (did) KitchenAction.Moved else KitchenAction.None

    /**
     * Brings the remote along to [shelf] (and the board's [places]) from what it saw last time: on the board, an item
     * taken off moves it to the one left there, or back to the buttons once the board is empty; a cook's new dish
     * takes the shelf's focus (and the remote goes back to the shelf, the board being empty now), an item eaten off
     * the board shifts it as [focusAfter] says, and items stocked onto the end leave it be.
     */
    fun follow(shelf: List<PantryItem>, screen: Screen.Pantry, places: List<Int?>) {
        val old = seenShelf
        val cook = screen.cook
        val cooked = cook != null && cook.token != seenCook
        val eaten = screen.fed != null && !seenFed
        if (row == KitchenRow.BOARD && places.getOrNull(place) == null) {
            val left = places.indices.firstOrNull { places[it] != null }
            if (left == null) row = KitchenRow.BUTTONS else place = left
        }
        if (old != null && (old != shelf || cooked)) {
            val removed = if (cooked || eaten) seenBoard else emptyList()
            focus = focusAfter(focus, old.size, shelf.size, removed, cook?.at?.takeIf { cooked })
            if (cooked) row = KitchenRow.SHELF
        }
        seenShelf = shelf
        seenBoard = screen.board
        seenCook = cook?.token
        seenFed = screen.fed != null
    }
}

/** The killer's body reaches this share of its cell's width either side of its feet (as on Home). */
private const val KILLER_BODY_HALF = 0.3f

/** COOK and FEED side by side. */
private val KitchenButtonsWidth: Float get() = KitchenButtonWidth.value * 2f + KitchenButtonGap.value

/**
 * Where everything in the kitchen goes on a [width] x [height] screen (dp; the height is always the 540 dp design),
 * inside the safe inset ([insetX], [insetY]), for a killer whose body reaches [killerHalf] either side of its feet.
 *
 * The killer stands at the left, just inside the inset. The board, its buttons, the shelf and the help strip stack
 * down a column centred on the screen when that leaves the column [COLUMN_MIN_HALF] either side of its centre clear
 * of the killer (16:9 and wider), else one pushed right into the room past the killer (4:3). From the bottom up: the
 * help strip on the inset, the shelf over it, COOK and FEED over that, and the board in the room left above them.
 */
internal class KitchenLayout(
    val killerX: Float,
    val columnCenter: Float,
    val columnHalf: Float,
    val boardWidth: Float,
    val boardTop: Float,
    val buttonsTop: Float,
    val shelfTop: Float,
    val helpBottom: Float,
    val slots: Int,
) {
    companion object {
        private const val KILLER_GAP = 8f
        private const val STAGE_GAP = 18f
        const val COLUMN_MIN_HALF = 250f
        private const val SLOT_MIN_PITCH = 84f
        private const val BOARD_MAX_WIDTH = 290f
        private const val HELP_HEIGHT = 32f
        private const val GAP = 8f

        fun of(width: Float, height: Float, insetX: Float, insetY: Float, killerHalf: Float): KitchenLayout {
            val killerX = insetX + KILLER_GAP + killerHalf
            val stageLeft = killerX + killerHalf + STAGE_GAP
            var half = width / 2f - stageLeft
            var center = width / 2f
            if (half < COLUMN_MIN_HALF) {
                half = min(COLUMN_MIN_HALF, (width - insetX - stageLeft) / 2f)
                center = stageLeft + half
            }
            val helpBottom = height - insetY
            val shelfTop = helpBottom - HELP_HEIGHT - GAP - ShelfHeight.value
            val buttonsTop = shelfTop - SlabHeight.value
            val boardBottom = buttonsTop - GAP * 1.5f
            val room = boardBottom - insetY
            val boardWidth = minOf(BOARD_MAX_WIDTH, room * BOARD_ASPECT, half * 2f)
            return KitchenLayout(
                killerX = killerX,
                columnCenter = center,
                columnHalf = half,
                boardWidth = boardWidth,
                boardTop = boardBottom - boardWidth / BOARD_ASPECT,
                buttonsTop = buttonsTop,
                shelfTop = shelfTop,
                helpBottom = helpBottom,
                slots = shelfSlots(half * 2f, SLOT_MIN_PITCH),
            )
        }
    }
}

/**
 * The help strip on a TV, for the remote on [row] (on [button] there, or [focus] on a shelf of [shelfSize]) with
 * [board] on the board: what the arrows and OK do now. The rows stand board, buttons, shelf from the top.
 */
internal fun kitchenHelp(row: KitchenRow, button: KitchenButton, board: List<Int>, focus: Int, shelfSize: Int): List<HelpItem> {
    val act = when (board.size) {
        BOARD_PLACES -> "Cook"
        1 -> "Feed"
        else -> null
    }
    return when (row) {
        KitchenRow.SHELF -> buildList {
            if (shelfSize > 1) add(HelpItem(listOf(TrainingKey.LEFT, TrainingKey.RIGHT), "Shelf"))
            when {
                shelfSize == 0 -> Unit
                focus in board -> add(HelpItem(listOf(TrainingKey.OK), "Off the board"))
                board.size < BOARD_PLACES -> add(HelpItem(listOf(TrainingKey.OK), "On the board"))
            }
            act?.let { add(HelpItem(listOf(TrainingKey.UP), it)) }
        }
        KitchenRow.BUTTONS -> buildList {
            add(HelpItem(listOf(TrainingKey.DOWN), "Shelf"))
            if (board.isNotEmpty()) add(HelpItem(listOf(TrainingKey.UP), "Board"))
            when {
                button == KitchenButton.COOK && board.size == BOARD_PLACES -> add(HelpItem(listOf(TrainingKey.OK), "Cook"))
                button == KitchenButton.FEED && board.size == 1 -> add(HelpItem(listOf(TrainingKey.OK), "Feed"))
            }
        }
        KitchenRow.BOARD -> buildList {
            if (board.size > 1) add(HelpItem(listOf(TrainingKey.LEFT, TrainingKey.RIGHT), "Board"))
            if (board.isNotEmpty()) add(HelpItem(listOf(TrainingKey.OK), "Off the board"))
            act?.let { add(HelpItem(listOf(TrainingKey.DOWN), it)) }
        }
    }
}

/** The help strip under a finger, with [boardSize] items on the board. */
internal fun kitchenTouchHelp(boardSize: Int): String = when (boardSize) {
    0 -> "Tap something on the shelf"
    1 -> "Feed it, or add one more to cook"
    else -> "Cook them, or tap one to take it off"
}
