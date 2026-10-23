package team.superfun.horrorpets.ui.pantry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.BOARD_PLACES
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SlabHeight
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.Amber
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
import team.superfun.horrorpets.ui.training.swallowTouches
import team.superfun.horrorpets.ui.training.swallowedWhileHeld
import team.superfun.horrorpets.ui.training.trainingKeys

/** How long a refusal ("That will not cook.") stays up. */
private const val NOTE_MS = 1_500L

/**
 * FEED: the pantry, in its horror kitchen. [pet] stands on the kitchen floor at the left; in the middle is the cutting
 * board with its two places and COOK and FEED under it, and along the bottom the shelf: everything in [shelf], a
 * carousel that slides left and right. The help strip under it says what the keys (or a finger) do right now.
 *
 * - On a TV: Left and Right move along the shelf, Down goes to COOK and FEED (and Up back), OK puts the focused item on
 *   the board or takes it off again, or presses the button. A held OK places nothing twice; a held direction keeps
 *   moving.
 * - Under a finger: a tap on an item puts it on the board (or takes it off), a tap on the board takes it off, a drag
 *   slides the shelf, and the buttons take taps.
 * - COOK acts with two items on the board, FEED with one ([Screen.Pantry.board]); either is dim otherwise, and a
 *   press on it buzzes. Back leaves at any time.
 *
 * The remote's focus is this screen's own, not Compose focus: the screen holds the one focus target and moves its
 * focus between the shelf and the buttons itself, so it can follow the shelf as it changes (a cook's new dish takes
 * the focus, see [focusAfter]). Its key sounds are its own too (see `Screen.ownsKeySounds`).
 *
 * Once [pet] has eaten, the reaction covers the screen until OK, a tap or any key carries on Home. Everything it
 * reports names [Screen.Pantry.session]. [focusEntry] is as on the other screens: input waits for it, and for the
 * moment after it that `rememberOkReady` gives every screen.
 */
@Composable
fun PantryScreen(
    screen: Screen.Pantry,
    pet: PetState,
    shelf: List<PantryItem>,
    loader: AssetLoader,
    onPick: (Long, Int) -> Unit,
    onCook: (Long) -> Unit,
    onFeed: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onBack: (Long) -> Unit,
    focusEntry: Any?,
) {
    // Each visit starts afresh (focus on the first item), even on a screen that never left: FEED, Back and FEED again
    // within a frame (as the debug puppet can) hands this screen the next session while it still shows.
    key(screen.session) {
        PantryVisit(screen, pet, shelf, loader, onPick, onCook, onFeed, onDone, onBack, focusEntry)
    }
}

/** One visit ([Screen.Pantry.session]) of [PantryScreen]. */
@Composable
private fun PantryVisit(
    screen: Screen.Pantry,
    pet: PetState,
    shelf: List<PantryItem>,
    loader: AssetLoader,
    onPick: (Long, Int) -> Unit,
    onCook: (Long) -> Unit,
    onFeed: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onBack: (Long) -> Unit,
    focusEntry: Any?,
) {
    val session = screen.session
    val sounds = LocalSoundSink.current
    GameBackHandler(enabled = focusEntry != null) { onBack(session) }
    val rootFocus = remember { FocusRequester() }
    RequestFocusOnEntry(rootFocus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val fed = screen.fed
    val kitchen = remember(loader) { loader.kitchenScene() }
    val actor = remember { TrainingActor() }
    val heart = favoriteHeart(pet)

    // The remote's place, kept on the shelf as it changes; and where each board item sits.
    val cursor = remember { KitchenCursor() }
    SideEffect { cursor.follow(shelf, screen) }
    val lastPlaces = remember { arrayOf<List<Int?>>(emptyList()) }
    val places = remember(screen.board) { boardPlaces(lastPlaces[0], screen.board).also { lastPlaces[0] = it } }

    // Read when a key or a tap comes in, so it acts on the shelf and board as they are drawn now.
    val latestShelf by rememberUpdatedState(shelf)
    val latestScreen by rememberUpdatedState(screen)

    // Input waits while the screen is coming in or going out, and once the killer has eaten (its reaction has the keys).
    val held = focusEntry == null || fed != null

    // A refusal says so for a moment; the screen's tokens change on each one.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.refusedCook) {
        if (screen.refusedCook == 0L) return@LaunchedEffect
        note = "That will not cook."
        delay(NOTE_MS)
        note = null
    }
    LaunchedEffect(screen.tooFull) {
        if (screen.tooFull == 0L) return@LaunchedEffect
        note = "${pet.id.displayName} is too full to eat."
        delay(NOTE_MS)
        note = null
    }

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

    /** OK or a tap on [button]: it acts, or buzzes while it can't. */
    fun press(button: KitchenButton) {
        val board = latestScreen.board
        when (button) {
            KitchenButton.COOK -> if (board.size == BOARD_PLACES) onCook(session) else sounds.sfx("refuse")
            KitchenButton.FEED -> if (board.size == 1) {
                sounds.sfx("ui_select")
                onFeed(session)
            } else {
                sounds.sfx("refuse")
            }
        }
    }

    fun onKey(key: TrainingKey) {
        val size = latestShelf.size
        val moved = when (cursor.row) {
            KitchenRow.SHELF -> when (key) {
                TrainingKey.LEFT, TrainingKey.RIGHT -> {
                    val next = (cursor.focus + if (key == TrainingKey.LEFT) -1 else 1).coerceIn(0, max(0, size - 1))
                    (next != cursor.focus).also { cursor.focus = next }
                }
                TrainingKey.DOWN -> {
                    cursor.row = KitchenRow.BUTTONS
                    cursor.button = when (latestScreen.board.size) {
                        BOARD_PLACES -> KitchenButton.COOK
                        1 -> KitchenButton.FEED
                        else -> cursor.button
                    }
                    true
                }
                TrainingKey.UP -> false
                TrainingKey.OK -> {
                    pick(cursor.focus)
                    false
                }
            }
            KitchenRow.BUTTONS -> when (key) {
                TrainingKey.UP -> {
                    cursor.row = KitchenRow.SHELF
                    true
                }
                TrainingKey.LEFT -> (cursor.button == KitchenButton.FEED).also { cursor.button = KitchenButton.COOK }
                TrainingKey.RIGHT -> (cursor.button == KitchenButton.COOK).also { cursor.button = KitchenButton.FEED }
                TrainingKey.DOWN -> false
                TrainingKey.OK -> {
                    press(cursor.button)
                    false
                }
            }
        }
        if (moved) sounds.sfx("ui_move")
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .ignoreOkUnless(okReady)
            // Once the killer has eaten, any key carries on (Back is the back handler's own).
            .onPreviewKeyEvent { event ->
                val carryOn = fed != null && event.type == KeyEventType.KeyDown && event.key != Key.Back
                if (carryOn) onDone(session)
                carryOn
            }
            .onPreviewKeyEvent { swallowedWhileHeld(it.key, it.type, held) }
            .trainingKeys { key, down ->
                if (down) onKey(key)
                true
            }
            .focusable(),
    ) {
        val safe = safeInset()
        val density = LocalDensity.current
        val manifest = remember(loader, pet.id) { loader.manifest(pet.id) }
        val killerHeight = maxHeight.value * HOME_PET_HEIGHT
        val layout = KitchenLayout.of(
            width = maxWidth.value,
            height = maxHeight.value,
            insetX = safe.horizontal.value,
            insetY = safe.vertical.value,
            killerHalf = killerHeight * manifest.cell[0] / manifest.cell[1] * KILLER_BODY_HALF,
        )
        val touch = isTouchInput()
        val window = shelfWindow(cursor.focus, shelf.size, layout.slots, cursor.first)
        SideEffect { cursor.first = window.first }

        // The kitchen, and the killer standing on its floor at the left, in its light.
        SceneBackdrop(loader, kitchen) { floorY ->
            val tint = remember(kitchen) { hexColor(kitchen.tint) }
            SpriteView(
                loader = loader,
                pet = pet.id,
                anim = actor.anim,
                heightPx = with(density) { killerHeight.dp.toPx() },
                tint = tint,
                tintStrength = kitchen.tintStrength,
                shadowOpacity = kitchen.shadowOpacity,
                playKey = actor.playKey,
                onFinished = actor.onFinished,
                modifier = Modifier.placeAt(x = { with(density) { layout.killerX.dp.toPx() } }, y = { floorY }),
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
        }

        Board(
            loader = loader,
            shelf = shelf,
            places = places,
            heart = heart,
            width = layout.boardWidth.dp,
            onTap = ::pick,
            modifier = Modifier.offset(x = (layout.columnCenter - layout.boardWidth / 2f).dp, y = layout.boardTop.dp),
        )
        KitchenButtons(
            canCook = screen.board.size == BOARD_PLACES,
            canFeed = screen.board.size == 1,
            focused = cursor.button.takeIf { cursor.row == KitchenRow.BUTTONS && !touch },
            onPress = ::press,
            modifier = Modifier.offset(x = (layout.columnCenter - KitchenButtonsWidth / 2f).dp, y = layout.buttonsTop.dp),
        )
        Shelf(
            loader = loader,
            shelf = shelf,
            board = screen.board,
            heart = heart,
            window = window,
            focus = cursor.focus.takeIf { cursor.row == KitchenRow.SHELF && !touch && shelf.isNotEmpty() },
            onTap = ::pick,
            onScrolled = { first ->
                cursor.first = first
                cursor.focus = ShelfWindow(first, layout.slots).focusWithin(cursor.focus, latestShelf.size)
            },
            modifier = Modifier
                .offset(x = (layout.columnCenter - layout.columnHalf).dp, y = layout.shelfTop.dp)
                .size((layout.columnHalf * 2f).dp, ShelfHeight),
        )
        if (shelf.isEmpty()) {
            BasicText(
                "THE SHELF IS EMPTY",
                style = displayStyle(16.sp, FontWeight.Bold, color = TextLo, tracking = 0.1f, halo = true),
                modifier = Modifier.placeAt(
                    x = { with(density) { layout.columnCenter.dp.toPx() } },
                    y = { with(density) { (layout.shelfTop.dp + ShelfStandY).toPx() } },
                ),
            )
        }
        HelpStrip(
            help = kitchenHelp(cursor.row, cursor.button, screen.board, cursor.focus, shelf.size),
            touchHelp = kitchenTouchHelp(screen.board.size),
            modifier = Modifier.placeAt(
                x = { with(density) { layout.columnCenter.dp.toPx() } },
                y = { with(density) { layout.helpBottom.dp.toPx() } },
            ),
        )
        note?.let {
            BasicText(
                it.uppercase(),
                style = displayStyle(16.sp, FontWeight.Bold, color = TextHi, tracking = 0.06f),
                maxLines = 1,
                modifier = Modifier
                    .placeAt(
                        x = { with(density) { layout.columnCenter.dp.toPx() } },
                        y = { with(density) { (layout.boardTop + 6f).dp.toPx() } },
                        pivotY = 0f,
                    )
                    .gameFrame(topEnd = 7.dp, bottomStart = 7.dp, inner = false)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }

        // While held, no touch reaches the kitchen (the reaction over it takes its own).
        if (held) Spacer(Modifier.fillMaxSize().swallowTouches())

        if (fed != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onDone(session) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(fed.item.title.uppercase(), style = displayStyle(30.sp, tracking = 0.06f))
                Spacer(Modifier.height(8.dp))
                BasicText("\"${fed.line}\"", style = displayStyle(22.sp, color = Amber))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "+${fed.xp} XP" + if (fed.levelsGained > 0) "  ·  LEVEL UP!" else "",
                    style = displayStyle(18.sp, color = TextHi),
                )
                Spacer(Modifier.height(16.dp))
                BasicText(
                    if (isTouchInput()) "TAP TO CARRY ON" else "PRESS ANY KEY TO CARRY ON",
                    style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                )
            }
        }
    }
}

/** Which row of the kitchen the remote is on. */
internal enum class KitchenRow { SHELF, BUTTONS }

/**
 * Where the remote is in the kitchen: its [row], the [focus] on the shelf (kept while it is on the buttons) and the
 * [button] it is on, and the [first] slot the shelf's window shows. [follow] keeps the focus on the shelf as the shelf
 * changes. Main thread only.
 */
@Stable
internal class KitchenCursor {
    var row by mutableStateOf(KitchenRow.SHELF)
    var button by mutableStateOf(KitchenButton.COOK)
    var focus by mutableIntStateOf(0)
    var first by mutableIntStateOf(0)

    private var seenShelf: List<PantryItem>? = null
    private var seenBoard: List<Int> = emptyList()
    private var seenCook: Long? = null
    private var seenFed = false

    /**
     * Brings the focus along to [shelf] from the shelf seen last time: a cook's new dish takes it (and the remote goes
     * back to the shelf, the board being empty now), an item eaten off the board shifts it as [focusAfter] says, and
     * items stocked onto the end leave it be.
     */
    fun follow(shelf: List<PantryItem>, screen: Screen.Pantry) {
        val old = seenShelf
        val cook = screen.cook
        val cooked = cook != null && cook.token != seenCook
        val eaten = screen.fed != null && !seenFed
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
 * [board] on the board: what the arrows and OK do now.
 */
internal fun kitchenHelp(row: KitchenRow, button: KitchenButton, board: List<Int>, focus: Int, shelfSize: Int): List<HelpItem> =
    when (row) {
        KitchenRow.SHELF -> buildList {
            if (shelfSize > 1) add(HelpItem(listOf(TrainingKey.LEFT, TrainingKey.RIGHT), "Shelf"))
            when {
                shelfSize == 0 -> Unit
                focus in board -> add(HelpItem(listOf(TrainingKey.OK), "Off the board"))
                board.size < BOARD_PLACES -> add(HelpItem(listOf(TrainingKey.OK), "On the board"))
            }
            when (board.size) {
                BOARD_PLACES -> add(HelpItem(listOf(TrainingKey.DOWN), "Cook"))
                1 -> add(HelpItem(listOf(TrainingKey.DOWN), "Feed"))
            }
        }
        KitchenRow.BUTTONS -> buildList {
            add(HelpItem(listOf(TrainingKey.UP), "Shelf"))
            when {
                button == KitchenButton.COOK && board.size == BOARD_PLACES -> add(HelpItem(listOf(TrainingKey.OK), "Cook"))
                button == KitchenButton.FEED && board.size == 1 -> add(HelpItem(listOf(TrainingKey.OK), "Feed"))
            }
        }
    }

/** The help strip under a finger, with [boardSize] items on the board. */
internal fun kitchenTouchHelp(boardSize: Int): String = when (boardSize) {
    0 -> "Tap something on the shelf"
    1 -> "Feed it, or add one more to cook"
    else -> "Cook them, or tap one to take it off"
}
