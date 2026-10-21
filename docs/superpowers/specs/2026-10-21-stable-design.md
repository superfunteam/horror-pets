# Start screen, one starter, and a stable you unlock

Approved 2026-10-21 from the mockups in `.superpowers/brainstorm/.../stable-flow.html` ("Caught" renamed to
"Unlocked" throughout).

## Intent

Make the game feel like a game from the first second: a title screen you have to start, one killer to begin
with, and the rest earned by beating them (like catching them in Pokémon). Once you have more than one, Home
shows the others at a glance and lets you go see them.

## Decisions (the player's)

- **Everyone starts over.** Existing saves (all seven unlocked) are reset: the next launch is a fresh game.
- **Start screen on every cold start**: the cover art and logo, blinking PRESS START, OK (or a tap) to begin.
- **One starter**: the spotlight carousel, once, any of the seven; you get only that one.
- **Unlocking**: beating a killer you don't have unlocks it ("UNLOCKED! … joins your stable"). Losing or
  running unlocks nothing. Killers you have can still be fought again for XP.
- **Stable badge**, Home top right, once you have two or more: another killer's head in four rings (Fed, Fun,
  Strength, Mood, like Apple's activity rings), their name, level and XP. With several it cycles. Tapping it
  (or OK on the remote) opens the stable.
- **Stable menu**: all seven slots; unlocked killers with rings and level (current one marked HERE), locked ones
  as dark silhouettes, "???" and "BEAT TO UNLOCK". Picking one goes to see them.
- The stats window no longer switches pets; the badge is the way.

## Design calls (mine)

- The save keeps only unlocked killers: `GameState.pets` holds the stable; `version` becomes 2 and any older
  save reads as a fresh game. Only stable killers decay and need care.
- A locked killer fights like any opponent today (the CPU is built from its id and your pet's level).
- An unlocked killer joins at level 1 with default stats.
- The UNLOCKED! moment is its own screen between the K.O. and the results, with an announcer "Unlocked!".
- Ring colors: Fed orange (100 minus Hunger, so a full ring is good), Fun lime, Strength red, Mood cyan.
- The welcome-back toast keeps the top right; the badge waits until it has gone.
- The old pet switcher (SELECT mode) goes; the starter pick and opponent pick keep the carousel.
- Head art for the badge and menus is cut from each killer's idle frame by `tools/make_heads.py`; the title art
  is exported by `tools/make_branding.py`.

## Non-goals

No changes to fight rules, care rules or decay; no new killers; no cloud saves.
