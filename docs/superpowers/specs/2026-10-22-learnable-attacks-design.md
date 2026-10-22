# Learnable attacks: design

Each killer learns new attacks as it levels up, the way a Pokémon learns moves. Every killer has three
attack slots, and each slot gets three upgrades over time: 9 new attacks per killer, 63 in all. When a pet
reaches an unlock level, a screen shows the attack it has now next to the new one, and the player chooses to
learn it or keep the old one. Stronger attacks leave bigger, flashier slash, claw and chain marks.

## The three slots

The slots are the three attacking commands the fight already has. Learning an attack never changes what the
command does in the rock-paper-scissors (a Strike still interrupts a Heavy, a Block still stops a Strike);
it changes how hard it hits and what it adds.

| Slot | Command | Today's attack (tier 0) |
|---|---|---|
| Light | STRIKE | power ×1.0, 8% miss |
| Heavy | HEAVY | power ×1.8, 15% miss |
| Special | SPECIAL | power ×2.5, never misses, plus the killer's effect (bleed, drain or daze) |

## When attacks unlock

The same levels for every killer, staggered so a new attack arrives every level or two early on, and the
last ones are long-term goals. Total XP to reach level N is 50 × N × (N − 1).

| Slot | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|
| Light | **Level 2** (100 XP) | **Level 6** (1,500 XP) | **Level 10** (4,500 XP) |
| Heavy | **Level 3** (300 XP) | **Level 7** (2,100 XP) | **Level 12** (6,600 XP) |
| Special | **Level 4** (600 XP) | **Level 8** (2,800 XP) | **Level 14** (9,100 XP) |

A win pays about 40–70 XP and a loss 15, so a player who fights and trains most days gets the tier 1s in the
first day or two, the tier 2s in about a week, and the tier 3s over a few weeks.

## Power and rules

| | Light | Heavy | Special |
|---|---|---|---|
| Tier 0 (today) | ×1.0 | ×1.8 | ×2.5 |
| Tier 1 | ×1.15 | ×2.0 | ×2.75 |
| Tier 2 | ×1.30 | ×2.2 | ×3.0 |
| Tier 3 | ×1.45 | ×2.45 | ×3.4 |

These multiply with everything that already exists (strength, passives, combos, crits).

- **Tier 1** is a straight upgrade: more power, nothing else. Always worth learning.
- **Tier 2** adds a **perk** with a small **catch**.
- **Tier 3** hits hardest and has the killer's signature perk, with a bigger catch. Keeping a tier 2 can be
  the right call for some players, which is what makes the choice real.

**Perks and catches** (the whole vocabulary, so the rules stay simple):

| Rule | What it does |
|---|---|
| Crit +N% | Adds to the crit chance. |
| Accuracy ±N% | Changes the miss chance (a catch makes it wilder, a perk more precise). Specials never miss. |
| Meter: 3 hits | Each landed hit fills 34 of the special meter instead of 25, so SPECIAL comes after 3 hits. |
| Meter: 5 hits (catch) | Each landed hit fills 20, so SPECIAL takes 5 hits. |
| Bleed N | The target bleeds 6% of its max HP for N turns. |
| Drain N% | Heals the attacker by N% of the damage dealt. |
| Daze N% | N% chance to daze the target: its next Strike, Heavy or Block fails. |
| Shatter N | Knocks N off the target's special meter. |

**Specials get stronger effects as they tier up:**

| Effect | Tier 0 | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|---|
| Bleed (Jason, Ghostface, Leatherface) | 3 turns × 6% | 3 × 7% | 4 × 7% | 4 × 9% |
| Drain (Freddy) | 50% | 60% | 70% | 85% |
| Daze (Pinhead, Chucky, Michael) | daze | daze + shatter 25 | daze + shatter 50 | daze + empties their meter |

## The attacks

Names are movie references and in-jokes, easy to rename.

### Jason: Unstoppable (raw power and bleeding; catches cost accuracy)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Machete Jab | ×1.0 | | |
| | 2 | Machete Swipe | ×1.15 | | |
| | 6 | Lakeside Hack | ×1.30 | Crit +8% | Accuracy −4% |
| | 10 | Camp Blood Cleave | ×1.45 | Bleed 2 | Accuracy −6% |
| Heavy | today | Overhead Chop | ×1.8 | | |
| | 3 | Sleeping Bag Slam | ×2.0 | | |
| | 7 | Crystal Lake Crush | ×2.2 | Daze 25% | Accuracy −5% |
| | 12 | Final Chapter | ×2.45 | Bleed 3 | Meter: 5 hits |
| Special | today | Machete Frenzy | ×2.5 | Bleed 3 × 6% | |
| | 4 | Crimson Frenzy | ×2.75 | Bleed 3 × 7% | |
| | 8 | Ki Ki Ma Ma | ×3.0 | Bleed 4 × 7% | |
| | 14 | Never Dies | ×3.4 | Bleed 4 × 9% | |

### Freddy: Lucid (crits and draining; catches cost meter)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Claw Swipe | ×1.0 | | |
| | 2 | Finger Knives | ×1.15 | | |
| | 6 | Nightmare Scratch | ×1.30 | Crit +10% | Meter: 5 hits |
| | 10 | Dream Demon Rake | ×1.45 | Drain 25% | Accuracy −5% |
| Heavy | today | Glove Slash | ×1.8 | | |
| | 3 | Boiler Room Slash | ×2.0 | | |
| | 7 | Furnace Claws | ×2.2 | Bleed 2 | Accuracy −4% |
| | 12 | Freddy's Coming | ×2.45 | Crit +12% | Meter: 5 hits |
| Special | today | Dream Claws | ×2.5 | Drain 50% | |
| | 4 | Sweet Dreams | ×2.75 | Drain 60% | |
| | 8 | Never Sleep Again | ×3.0 | Drain 70% | |
| | 14 | Welcome to Prime Time | ×3.4 | Drain 85% | |

### Pinhead: Retribution (control: dazes and shatters; catches cost meter)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Hook Flick | ×1.0 | | |
| | 2 | Barbed Hook | ×1.15 | | |
| | 6 | Pin Prick | ×1.30 | Accuracy +5% | Meter: 5 hits |
| | 10 | Exquisite Agony | ×1.45 | Shatter 25 | Accuracy −4% |
| Heavy | today | Chain Lash | ×1.8 | | |
| | 3 | Hooked Chains | ×2.0 | | |
| | 7 | Puzzle Box Snare | ×2.2 | Daze 25% | Meter: 5 hits |
| | 12 | Tear Your Soul Apart | ×2.45 | Daze 35% | Accuracy −6% |
| Special | today | Chains | ×2.5 | Daze | |
| | 4 | Lament Configuration | ×2.75 | Daze + shatter 25 | |
| | 8 | Such Sights | ×3.0 | Daze + shatter 50 | |
| | 14 | Leviathan | ×3.4 | Daze + empties their meter | |

### Ghostface: Slippery (fast specials and bleeding; catches cost accuracy or meter)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Knife Poke | ×1.0 | | |
| | 2 | Quick Stab | ×1.15 | | |
| | 6 | Caller Slash | ×1.30 | Meter: 3 hits | Accuracy −3% |
| | 10 | Scream Slash | ×1.45 | Bleed 2 | Meter: 5 hits |
| Heavy | today | Knife Lunge | ×1.8 | | |
| | 3 | Pratfall Pounce | ×2.0 | | |
| | 7 | Gutting Lunge | ×2.2 | Bleed 2 | Accuracy −5% |
| | 12 | Final Act | ×2.45 | Crit +10% | Meter: 5 hits |
| Special | today | Knife Flurry | ×2.5 | Bleed 3 × 6% | |
| | 4 | Scary Movie | ×2.75 | Bleed 3 × 7% | |
| | 8 | Hello, Sidney | ×3.0 | Bleed 4 × 7% | |
| | 14 | Ultimate Scream | ×3.4 | Bleed 4 × 9% | |

### Chucky: Scrappy (quick lights and voodoo; catches cost accuracy)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Knife Jab | ×1.0 | | |
| | 2 | Ankle Slash | ×1.15 | | |
| | 6 | Good Guy Jab | ×1.30 | Meter: 3 hits | Accuracy −3% |
| | 10 | Wanna Play? | ×1.45 | Crit +10% | Accuracy −5% |
| Heavy | today | Cleaver Chop | ×1.8 | | |
| | 3 | Toy Hammer Bonk | ×2.0 | | |
| | 7 | Doll's Revenge | ×2.2 | Daze 25% | Meter: 5 hits |
| | 12 | Charles Lee Ray | ×2.45 | Bleed 2 | Accuracy −6% |
| Special | today | Voodoo Stab | ×2.5 | Daze | |
| | 4 | Damballa | ×2.75 | Daze + shatter 25 | |
| | 8 | Soul Transfer | ×3.0 | Daze + shatter 50 | |
| | 14 | Friends to the End | ×3.4 | Daze + empties their meter | |

### Michael: Relentless (slow, crushing, dazing; catches cost meter)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Knife Stab | ×1.0 | | |
| | 2 | Head Tilt Stab | ×1.15 | | |
| | 6 | Kitchen Knife | ×1.30 | Crit +8% | Meter: 5 hits |
| | 10 | Haddonfield Slash | ×1.45 | Daze 15% | Accuracy −4% |
| Heavy | today | Overhand Stab | ×1.8 | | |
| | 3 | Closet Ambush | ×2.0 | | |
| | 7 | Pinned to the Wall | ×2.2 | Bleed 2 | Meter: 5 hits |
| | 12 | The Boogeyman | ×2.45 | Daze 30% | Accuracy −6% |
| Special | today | The Shape | ×2.5 | Daze | |
| | 4 | Halloween Night | ×2.75 | Daze + shatter 25 | |
| | 8 | Pure Evil | ×3.0 | Daze + shatter 50 | |
| | 14 | He Came Home | ×3.4 | Daze + empties their meter | |

### Leatherface: Chainsaw (enormous heavies and bleeding; catches cost accuracy)

| Slot | Level | Attack | Power | Perk | Catch |
|---|---|---|---|---|---|
| Light | today | Hammer Tap | ×1.0 | | |
| | 2 | Sledgehammer Bonk | ×1.15 | | |
| | 6 | Meat Hook Jab | ×1.30 | Bleed 2 | Accuracy −4% |
| | 10 | Butcher's Cut | ×1.45 | Crit +8% | Meter: 5 hits |
| Heavy | today | Chainsaw Swing | ×1.8 | | |
| | 3 | Revving Rush | ×2.0 | | |
| | 7 | Grinding Saw | ×2.2 | Bleed 2 | Accuracy −5% |
| | 12 | Texas Chainsaw | ×2.45 | Daze 30% | Accuracy −7% |
| Special | today | Saw Dance | ×2.5 | Bleed 3 × 6% | |
| | 4 | Saw Twirl | ×2.75 | Bleed 3 × 7% | |
| | 8 | Family Dinner | ×3.0 | Bleed 4 × 7% | |
| | 14 | Chainsaw Massacre | ×3.4 | Bleed 4 × 9% | |

Leatherface's passive (+20% on heavies) stacks with these, so Texas Chainsaw (×2.94) is the biggest hit
short of a special.

## Learning: the new-attack screen

It comes up whenever a pet reaches an unlock level: after the fight's results (or the unlock screen), or on
Home after a Train or Play levels it up. A pet that skips past several unlock levels at once gets one screen
per attack, in order. Pets that are already past unlock levels when this update installs get their screens
the next time they're on Home, one after another.

```
                     JASON CAN LEARN A NEW HEAVY!
                              LEVEL 7

  ┌─ NOW ──────────────────────┐        ┌─ NEW ──────────────────────┐
  │ SLEEPING BAG SLAM          │        │ CRYSTAL LAKE CRUSH         │
  │                            │        │                            │
  │   [ its marks, looping ]   │   ➜    │  [ its marks, looping ]    │
  │                            │        │                            │
  │ POWER     ██████░░  ×2.0   │        │ POWER     ███████░  ×2.2 ▲ │
  │ ACCURACY  85%              │        │ ACCURACY  80%           ▼  │
  │ METER     4 hits           │        │ METER     4 hits           │
  │ PERK      —                │        │ PERK      25% daze      ▲  │
  └────────────────────────────┘        └────────────────────────────┘

                [ LEARN IT ]     [ KEEP SLEEPING BAG SLAM ]
```

- Both cards play their attack's marks on a loop, so the player sees how much flashier the new one is.
- Changes are marked: better in bright green ▲, worse in Amber ▼.
- LEARN IT is focused first. Back means "decide later": the screen comes back next time.
- **Either way the pet now knows the attack.** MOVES, on Home's action bar after FIGHT, shows each slot's
  four attacks (the locked ones too, with the level they unlock at) and equips any unlocked one, so skipping
  one is never permanent.

## In the fight

- The callout over a fighter names its attack ("CRYSTAL LAKE CRUSH!") instead of "HEAVY".
- The command window keeps STRIKE / HEAVY / BLOCK / SPECIAL, since that's what decides the
  rock-paper-scissors. The help line under it names the attack and its perk: "CRYSTAL LAKE CRUSH · breaks a
  Block, a Strike cuts it off · 25% daze".
- **CPU opponents use the best attacks unlocked at their level**, so a level 12 Leatherface comes at you
  with Texas Chainsaw.
- **PvP:** each player's equipped attacks travel with their fighter through the relay, and both apps resolve
  them the same way. The release that adds attacks only pairs with the same release (the version check
  already handles that).

## Escalating marks

Today, only specials draw marks (Jason's four red slashes, Freddy's claw rakes, Pinhead's chains and so on);
Strikes and Heavies just flash and shake. With learned attacks, every attack leaves marks in its killer's
style, and they grow with the tier:

| | Light | Heavy | Special |
|---|---|---|---|
| Tier 0 | none (as today) | none (as today) | today's effect |
| Tier 1 | 1 mark | 1 big mark and an impact ring | today's effect with half again as many marks, and a glow in the killer's color |
| Tier 2 | 2 crossing marks | 2 big marks, ring and a screen shake | darker arena, longer (1.2s), particles: blood drops, embers or sparks |
| Tier 3 | 3 marks and sparks | an X of big marks, ring, red flash and shake | **Super**: the screen cuts to black and red, the killer's head slides across with the attack's name in huge type, the effect plays doubled with lingering mark trails, then a white flash |

Marks linger and fade: about 0.6s for tier 1, up to 1.2s for tier 3.

Each killer's mark style:

| Killer | Marks |
|---|---|
| Jason | thick curved machete arcs, blood red |
| Freddy | four-line claw rakes with green dream glow |
| Pinhead | hooked chain links and barbs, steel blue |
| Ghostface | thin white knife slashes and stab flecks |
| Chucky | short stab punctures and voodoo pins, purple and green |
| Michael | one long clean diagonal slash, white-hot, in a dark vignette |
| Leatherface | jagged chainsaw tears throwing orange sparks |

These reuse the drawing code the specials already have (FightVfx.kt), with how many, how big, how bright and
how long all scaled by the attack's tier.

## How it gets built

- **Catalog:** every attack in one table in code (killer, slot, tier, name, level, power, perk, catch), with
  helpers for "what unlocks at this level" and "the best attack at this level".
- **Pets:** each pet remembers its equipped attack per slot and which offered attacks it has answered. These
  are new fields with defaults, so existing saves load with today's attacks equipped.
- **Engine:** power, accuracy, crit and meter come from the equipped attack, and perks apply when it lands. A
  daze-chance perk rolls one extra die only for attacks that have one.
- **Screens:** the new-attack screen, MOVES on Home's action bar, attack names in callouts and the help
  line, and the mark layer with tier scaling.
- **Relay:** passes each fighter's equipped attacks.
- **Tests:**
  - every killer has 3 slots × 4 tiers at the levels above, with names unique and short enough for callouts
  - each perk and catch in the engine, and the dice order
  - the learn queue, including several levels at once and pets past unlock levels on update
  - saves load and save the attacks
  - PvP fighters carry their attacks
