# The pantry: cooking on FEED

## Why

FEED is a button press today: a random snack whistles down from the sky (one of six) and the killer munches it. The
player wants feeding to be a little game, the way TRAIN became one: a kitchen where you combine things into better
food, push your luck, and find out what each killer likes. It's modelled on the "Elements" mixing games (Little
Alchemy, Doodle God: water + seed = plant), with a horror pet twist: some of the best food is gross on purpose, and
every killer has a secret favorite.

The full cookbook, as approved by the player, was drafted as a page (Pantry Cookbook artifact); this document carries
all of it. Layout mockups: `art-work/cooking/mockups/` (the player picked A, the stovetop, "but make it look a little
better", and dropped the craving box).

## Goals

- FEED opens **the pantry**: its own full screen with its own art (a horror kitchen), like training has the film reel.
- Cook by combining two things at a time, Elements-style, through three tiers: ingredients → snacks → meals.
- Ways to cook (Grill, Campfire, Pot, Cauldron, Sun) are ingredients like any other, used up when used.
- Wrong combinations fail: a pile of slop, or a specific named disaster. A failed dish is a dead end.
- Some successful dishes are gross on purpose (a grilled foot). Successful doesn't always mean delicious.
- Each killer has a taste and one secret favorite, learned only by feeding. Better food pays more; the favorite pays
  the most.
- The killer reacts to what it's fed: a happy jump or the FAIL pose, with a line of text.
- The pantry keeps what you don't feed, between visits.
- Plays with a TV remote (D-pad + OK) and with touch, like the rest of the game.
- Quirky toy-style art for every item (the style of today's snacks).

## Non-goals

- No recipe book, discovery list or hint system in this version (the Elements games have one; not asked for).
- No craving or favorite hint before feeding: favorites are never telegraphed.
- No change to PLAY, TRAIN or fights. Earning food from fights and training is the **next** project (see Later); this
  one only makes the pantry ready to receive it.
- No online or shared pantry.

## Anti-goals (a failure even if it works)

- Favorites telegraphed in any way before the killer has been fed it.
- A player who just wants to feed can't do it quickly (one item, FEED, done must always work).
- Gore. The food is chunky, glossy, cute-gross toys, never realistic.
- A TV remote can't drive it comfortably from the couch.
- A botched dish that can still be cooked with (the player's rule: nothing cooks with a botch).

## What's decided

### The visit

- FEED on Home opens the pantry (a new full-screen `Screen`). Back leaves it, keeping everything on the shelf.
- **The shelf** is a carousel of everything in the pantry, slid left and right. One pantry for the whole stable
  (shared by all killers), saved with the game. *(My call: one pantry, not one per killer — food is the player's.)*
- **When it opens**, an empty pantry starts with a random handful. Every visit also tops the shelf up so it holds at
  least four raw ingredients, at least one of them a way to cook and at least two of them foods. *(My call, so there's
  always something to cook until fights and training pay out food.)* Ways to cook come up often in the draw. The shelf
  holds 40; when it's full and short of those minimums, the top-up first throws out the oldest botched dishes (never
  good food) to make room. *(My call, from the final review: otherwise a run of slop could jam the shelf for days.)*
- **The board** (the stage) has two places. Tap or OK an item on the shelf to put it on the board; tap or OK an item
  on the board to put it back. Under the board: **COOK** (needs two items) and **FEED** (needs exactly one).
- **COOK**: the two items slide together, poof, the result appears with its name, then it flies back onto the shelf.
  Both inputs are used up — a way to cook included. Every cook is saved at once.
- **No cook limit**: cook as much as the shelf allows. The risk is botching into slop, which feeds badly and cooks
  into nothing (the mockup's "cooks left" meter was a guess and is dropped).
- **FEED**: the killer eats the item (its feed animation, the dish drops in front of it and gets munched, as today),
  then reacts: a happy jump for something it likes, the FAIL pose for something it doesn't, a big celebration for its
  favorite, each with a speech-bubble line ("Ugh, slop.", "Not bad!", "MY FAVORITE!!"). The payout is applied and
  saved when it's fed. Then the visit ends and Home comes back. *(My call: feeding ends the visit.)*
- A pet too full to eat (hunger below `FEED_REFUSE_BELOW`) can still open the pantry and cook; FEED is refused there
  with the same "Too full" bubble as today. *(My call.)*
- The layout is mockup A, the stovetop, polished: the killer stands to one side of the kitchen, the board in the
  middle with COOK and FEED under it, the shelf carousel along the bottom, a help strip (key caps on TV, words on touch).

### The rules of cooking

- **A food + a way to cook** → a cooked snack (Steak = Raw Steak + Grill). The same food comes out differently each way
  (an egg fries on the grill, boils in the pot, rots in the sun).
- **Two foods** with no way to cook are **mixed** (Milkshake = Milk + Sugar).
- **Two snacks** → a meal (Steak Dinner = Steak + Baked Potato). A few exceptions, listed in the cookbook: a snack and
  a way to cook (Pancake Stack = Batter + Grill), a snack and a food (Campfire S'mores = Toasted Marshmallow + Chocolate).
- Order on the board doesn't matter (A + B = B + A).
- **Anything with no recipe** → **Pile of Slop**, except:
  - a few named disasters for particular wrong pairs (below);
  - a finished dish (snack or meal) + a way to cook, when that's not a recipe → **Burnt to a Crisp**;
  - two ways to cook together → **Kitchen Fire**.
- **Botched dishes are dead ends**: COOK refuses any board holding one (a squelch, a shake, nothing used up). They can
  still be fed (badly).
- Two of the same item on the board follow the same rules (no recipe → slop).

### Flavors and tastes

Every successful dish is one flavor: **Savory**, **Sweet** or **Gross**. Botched dishes and ways to cook have none.
Every killer loves one flavor, is fine with another and hates the third, and has one favorite dish:

| Killer | Loves | Fine with | Hates | Favorite |
|---|---|---|---|---|
| Jason | Savory | Sweet | Gross | Camp Crystal Fish Fry |
| Freddy | Gross | Savory | Sweet | Spaghetti & Eyeballs |
| Pinhead | Gross | Sweet | Savory | Caviar Canapés |
| Ghostface | Savory | Sweet | Gross | Movie Night Combo |
| Chucky | Sweet | Gross | Savory | Birthday Cake |
| Michael | Sweet | Savory | Gross | Pumpkin Pie |
| Leatherface | Gross | Savory | Sweet | Texas BBQ Platter |

Each favorite is a meal made from exactly four ingredients (its path is in the cookbook below), so the pantry won't
always allow it. Tastes and favorites are never shown before feeding.

An item's flavor isn't labeled on the shelf, the board or the reveal: the art carries it (gross looks gross). The
killer's reaction names it, so tastes can be learned by feeding: "Mmm, savory!", "Too sweet. Yuck.", "Gross! I love
it!". *(My call: hiding the flavor everywhere would leave nothing to learn from; labeling every item would clutter the
shelf.)* Raw foods ignore taste (a shrug). Once a killer has been fed its favorite, the game
remembers: that dish shows a small heart on the shelf while that killer is the one in the kitchen. *(My call.)*

### What feeding pays

Starting numbers, to tune by playing (today a feed is hunger −35, nothing else; lower hunger is fuller). The flavor
stat is Strength for savory, Fun for sweet, Mood (the mood bonus) for gross.

| Fed | Hunger | XP | Flavor stat | Reaction |
|---|---|---|---|---|
| A raw food | −20 | 0 | +1 | shrug: "It's... raw." |
| A snack | −35 | +5 | +3 | by taste |
| A meal | −55 | +15 | +6 | by taste |
| … in the flavor it loves | as above | ×2 | ×2, and mood +10 | happy jump, naming it: "Mmm, savory!" |
| … in the flavor it's fine with | as above | as above | as above | content: "Not bad!" |
| … in the flavor it hates | half | 0 | 0, and mood −10 | FAIL, naming it: "Too sweet. Yuck." |
| A botch | −10 | 0 | mood −15 | FAIL: "Ugh, slop." (each named botch may have its own line) |
| A way to cook, raw | 0 | 0 | mood −10 | FAIL, its own line ("HOT! HOT!" for the campfire) |
| Its favorite | −70 | +40 | +10, and mood +25 | big celebration: "MY FAVORITE!!" |

A level-up from feeding is handled as any other (Home offers the new attack). XP and stats clamp as everywhere else.

### The cookbook

**Ways to cook** (5; ingredients, used up): Grill ("That's a grill."), Campfire ("HOT! HOT!"), Pot ("...It's a pot."),
Cauldron ("Bubbly. Ugh."), Sun ("Too bright!") — the quotes are what each says when fed raw.

**Foods** (21): Savory — Raw Steak, Egg, Flour, Cheese, Tomato, Potato, Sardines, Corn. Sweet — Sugar, Milk, Orange,
Pumpkin, Chocolate, Marshmallow. Gross — Severed Foot, Eyeball, Brain, Finger, Worms, Bat Wing, Blood.

**Snacks** (41):

| Snack | Made from | Flavor |
|---|---|---|
| Steak | Raw Steak + Grill | Savory |
| Fried Egg | Egg + Grill | Savory |
| Melted Cheese | Cheese + Grill | Savory |
| Grilled Sardines | Sardines + Grill | Savory |
| Grilled Foot | Severed Foot + Grill | Gross |
| Buffalo Bat Wings | Bat Wing + Grill | Gross |
| Toasted Marshmallow | Marshmallow + Campfire | Sweet |
| Popcorn | Corn + Campfire | Savory |
| Baked Potato | Potato + Campfire | Savory |
| Campfire Bread | Flour + Campfire | Savory |
| Caramel | Sugar + Campfire | Sweet |
| Roasted Pumpkin | Pumpkin + Campfire | Sweet |
| Finger Dog | Finger + Campfire | Gross |
| Tomato Soup | Tomato + Pot | Savory |
| Boiled Egg | Egg + Pot | Savory |
| Hot Cocoa | Chocolate + Pot | Sweet |
| Worm Spaghetti | Worms + Pot | Gross |
| Poached Eyeball | Eyeball + Pot | Gross |
| Blood Pudding | Blood + Pot | Gross |
| Bubbling Brain Broth | Brain + Cauldron | Gross |
| Bat Brew | Bat Wing + Cauldron | Gross |
| Eye of Newt Potion | Eyeball + Cauldron | Gross |
| Pumpkin Spice Potion | Pumpkin + Cauldron | Sweet |
| Jerky | Raw Steak + Sun | Savory |
| Sun-Dried Tomatoes | Tomato + Sun | Savory |
| Dried Orange Slices | Orange + Sun | Sweet |
| Foot Jerky | Severed Foot + Sun | Gross |
| Rotten Egg | Egg + Sun | Gross |
| Batter | Flour + Egg | Sweet |
| Milkshake | Milk + Sugar | Sweet |
| Chocolate Orange | Chocolate + Orange | Sweet |
| Gummy Worms | Worms + Sugar | Gross |
| Dirt Cup | Worms + Chocolate | Gross |
| Jello Brain | Brain + Sugar | Gross |
| Ladyfingers | Finger + Sugar | Gross |
| Toe Jam | Severed Foot + Sugar | Gross |
| Blood Orange Juice | Blood + Orange | Gross |
| Not-Strawberry Milk | Blood + Milk | Gross |
| Eyeball Caviar | Eyeball + Sardines | Gross |
| Deviled Eggs | Eyeball + Egg | Gross |
| Eye Scream | Eyeball + Milk | Gross |

**Meals** (23):

| Meal | Made from | Flavor | Favorite of |
|---|---|---|---|
| Steak Dinner | Steak + Baked Potato | Savory | |
| Steak & Eggs | Steak + Fried Egg | Savory | |
| Grilled Cheese | Campfire Bread + Melted Cheese | Savory | |
| Pizza | Campfire Bread + Tomato Soup | Savory | |
| Cowboy Trail Mix | Jerky + Dried Orange Slices | Savory | |
| Camp Crystal Fish Fry | Grilled Sardines + Baked Potato | Savory | Jason |
| Movie Night Combo | Popcorn + Milkshake | Savory | Ghostface |
| Caramel Corn | Popcorn + Caramel | Sweet | |
| Birthday Cake | Batter + Caramel | Sweet | Chucky |
| Pumpkin Pie | Roasted Pumpkin + Batter | Sweet | Michael |
| Pancake Stack | Batter + Grill | Sweet | |
| Campfire S'mores | Toasted Marshmallow + Chocolate | Sweet | |
| Cocoa & Marshmallows | Hot Cocoa + Toasted Marshmallow | Sweet | |
| Texas BBQ Platter | Foot Jerky + Buffalo Bat Wings | Gross | Leatherface |
| Foot & Potatoes | Grilled Foot + Baked Potato | Gross | |
| Spaghetti & Eyeballs | Worm Spaghetti + Deviled Eggs | Gross | Freddy |
| Caviar Canapés | Eyeball Caviar + Campfire Bread | Gross | Pinhead |
| Brain Trifle | Jello Brain + Ladyfingers | Gross | |
| Finger Dog in a Bun | Finger Dog + Campfire Bread | Gross | |
| Vampire Breakfast | Blood Orange Juice + Fried Egg | Gross | |
| Eye Scream Sundae | Eye Scream + Caramel | Gross | |
| Graveyard Pudding | Dirt Cup + Gummy Worms | Gross | |
| Witch's Supper | Bubbling Brain Broth + Bat Brew | Gross | |

**Botched** (12): Pile of Slop (any pair with no recipe), Burnt to a Crisp (a finished dish + a way to cook, not a
recipe), Kitchen Fire (two ways to cook), Sardine Smoothie (Sardines + Orange), Fish Fudge (Sardines + Chocolate),
Fishmallow (Sardines + Marshmallow), Stinky Fish (Sardines + Sun), Curdled Mess (Milk + Orange), Spoiled Milk (Milk +
Sun), Melted Mess (Chocolate + Sun), Cursed Cake (Batter + Cauldron), Soggy Popcorn (Popcorn + Milk).

The favorites' paths, four ingredients each:

- Jason: Sardines + Grill → Grilled Sardines; Potato + Campfire → Baked Potato; together → Camp Crystal Fish Fry.
- Freddy: Worms + Pot → Worm Spaghetti; Eyeball + Egg → Deviled Eggs; together → Spaghetti & Eyeballs.
- Pinhead: Eyeball + Sardines → Eyeball Caviar; Flour + Campfire → Campfire Bread; together → Caviar Canapés.
- Ghostface: Corn + Campfire → Popcorn; Milk + Sugar → Milkshake; together → Movie Night Combo.
- Chucky: Flour + Egg → Batter; Sugar + Campfire → Caramel; together → Birthday Cake.
- Michael: Pumpkin + Campfire → Roasted Pumpkin; Flour + Egg → Batter; together → Pumpkin Pie.
- Leatherface: Severed Foot + Sun → Foot Jerky; Bat Wing + Grill → Buffalo Bat Wings; together → Texas BBQ Platter.

### Art and sound

- **Food** (102 pictures: 5 ways to cook, 21 foods, 41 snacks, 23 meals, 12 botches) in the toy style of today's
  snacks (`tools/generate_food.py`'s style: glossy 3D collectible toy, three-quarter view, transparent, no text),
  cute-gross for the gross ones, never realistic. A new generate/finalize pair like the food and mini-game tools,
  picks trimmed into `sprites-final/pantry/`. Today's art may stand in where it matches exactly (the cake with a knife
  is the Birthday Cake, the popcorn bucket is Popcorn, the jello brain is the Jello Brain). The ways to cook are drawn
  as little objects (a grill, a campfire, a pot, a cauldron, a smiling sun), not effects.
- **The kitchen**: one new horror-kitchen backdrop, shared by every killer, in the style of the game's scenes
  (`tools/generate_scenes.py`), with room for the board and the shelf.
- **The board and the shelf**: a cutting board and a wooden shelf, drawn or generated to match.
- **The poof**: a puff of smoke when a cook lands (code-drawn or a small sprite sheet).
- **Sounds** (new ones made like the training sounds, ElevenLabs into `audio-final/sfx/`; existing sounds must not
  change): the poof, a squelch for slop and a refused cook, a short sound per way to cook (sizzle, crackle, boil,
  cauldron bubble, a sunny shimmer) played on the cook, a happy-eat and a gross-eat beat, a favorite fanfare. Munching
  reuses today's feed sounds; the refuse buzz and the level-up are reused where they fit.
- **Music**: the pet's own track, as on Home.

### Later (the next project, not this one)

Winning a fight or finishing a training game pays out ingredients, snacks or meals into the pantry. The pantry's save
format and its add-items entry point should make that a small change.

## Constraints (from the codebase)

- Kotlin + Jetpack Compose for TV and phones; screens lay out in dp on a 540 dp-tall design (see `ScreenDensity`).
- A new `Screen` must be handled in the exhaustive `when`s: `HorrorPetsApp`, `MainActivity.musicTrackFor`, and the
  debug puppet's `cueOf`, `goHome` and `describe`. `MainActivity.dispatchKeyEvent`'s key ticks get the same exemption
  training has (`ownsKeySounds`).
- Don't bump `GameState.version` (it would read every save as a fresh game). New save fields get defaults and are
  sanitized in `SaveCodec.sanitize`: unknown item ids dropped, the pantry capped (a few dozen items), favorite flags
  per pet.
- Unit tests are pure JVM (no Robolectric, no Compose test rule): the cookbook, the cooking rules, the top-up, the
  payouts and the reactions are plain Kotlin and tested there; the view model is tested with the `GameViewModelTest`
  rig. The cookbook needs a test that every favorite is reachable from four ingredients, that every recipe's inputs
  exist, that no pair has two results, and that every item has art.
- Today's FEED tests change: FEED now opens the pantry. `FoodDrop` and the `Food` enum are reused or retired as the
  builder sees fit; the feed animation and munch sounds stay.
- The debug puppet's `feed` keeps working (opens the pantry); add commands to stock the pantry, cook a pair and serve
  an item, for footage and tests (README's puppet table).
- Input follows the training games: D-pad and OK on TV (no key repeats placing items twice), touch on phones, Back
  leaves; game input held while the screen is paused.

## Left to the builder

- Exact layout, sizes and motion of the board, shelf, poof, reveal and reaction, within mockup A's shape.
- The weights of the random top-up and the starting handful (ways to cook come up often).
- Reaction lines beyond the ones given, per-botch lines, and whether a killer has a few lines of its own.
- How items are named in code (ids are save keys: keep them stable once shipped).
- The image prompts, how many candidates per item, and the picks (the first good one unless it reads badly).
- The ElevenLabs prompts and which existing sounds to reuse.
