"""Generate the pantry's food (cooking on FEED) with OpenAI's image model, on transparent backgrounds, in the same
glossy toy style as the food that drops today: the five ways to cook, the foods, the snacks, the meals and the botches
(see docs/superpowers/specs/2026-10-23-pantry-cooking-design.md). The ids are the items' ids in the game.

Every candidate is written to art-work/pantry/<id>-<k>.png (gitignored); the picked ones are trimmed and encoded into
sprites-final/pantry/ by tools/finalize_pantry_art.py. The API key is read from the OPENAI_API_KEY environment variable
only.

Usage: OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_pantry_art.py [--n 1] [--start 1] [--only ID ...]
       OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_pantry_art.py --extras --n 3   # the board and the kitchen
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.openai.com/v1"
MODEL = "gpt-image-2.5-sunburst"
OUT = ROOT / "art-work/pantry"

STYLE = (
    "A single {kind} for a cute horror video game, rendered in a glossy, high-end 3D collectible-toy style: soft studio "
    "key light, a subtle colored rim light, rich saturated color, smooth stylized shapes and slightly exaggerated, chunky "
    "cartoon proportions, appealing and funny. Anything gross is cartoonish, chunky and more funny than gory: no "
    "realistic flesh, no gore. Three-quarter view from slightly above. The whole item is fully in frame, centered, with "
    "a little margin, isolated on a fully transparent background with no ground shadow. No text, letters, numbers or "
    "logos anywhere, no people, no characters, no hands holding anything. The item: {item}"
)

# The five ways to cook: kitchen props, used up like any ingredient.
METHODS = {
    "grill": "a small chunky black barbecue grill on short stubby legs, glowing orange coals under a silver grate, a wisp "
             "of smoke.",
    "campfire": "a little campfire: a tidy ring of grey stones around crossed logs with big, friendly cartoon flames.",
    "pot": "a chunky dented steel stock pot of water at a rolling boil, bubbles at the surface and steam curling up, its "
           "lid tipped on the rim.",
    "cauldron": "a squat black iron witch's cauldron on three stubby legs, bubbling with bright green goo, glowing bubbles "
                "and wisps of green vapor.",
    "sun": "a round, smiling cartoon sun with chunky triangular golden rays and cool little black sunglasses, glowing "
           "warmly.",
}

FOODS = {
    "raw_steak": "a thick raw red T-bone steak with white marbling, glossy and uncooked.",
    "egg": "a single large white egg, smooth and glossy, standing upright.",
    "flour": "a small plump paper flour sack tied at the top with string, a puff of white flour spilling out; the sack "
             "is plain with no writing.",
    "cheese": "a big wedge of yellow Swiss cheese with round holes.",
    "tomato": "a big shiny red tomato with a green leafy stem.",
    "potato": "a lumpy brown russet potato with little eyes and a couple of sprouts.",
    "sardines": "an opened oval tin of sardines, the peeled-back lid curled up, three silver sardines packed inside with "
                "their heads poking out and goofy little eyes.",
    "corn": "an ear of yellow corn with its green husk peeled half back.",
    "sugar": "a glass sugar bowl heaped with sparkling white sugar cubes and loose sugar, a silver spoon in it.",
    "milk": "an old-fashioned glass milk bottle full of milk with a foil cap and a few drops of condensation.",
    "orange": "a round bright orange with a green leaf on top, one wedge cut out to show juicy segments.",
    "pumpkin": "a squat round orange pumpkin with deep ribs and a curly green stem.",
    "chocolate": "a chunky bar of milk chocolate in a half-peeled red wrapper with gold foil, one square snapped off.",
    "marshmallow": "a little pile of three plump, puffy white marshmallows, soft and squishy.",
    "severed_foot": "a chunky cartoon severed foot, pale greenish-grey with stubby toes and a band-aid, ending cleanly at "
                    "the ankle with a clean white bone stub.",
    "eyeball": "a single big glossy eyeball with a bright green iris, a few red veins and a short pink nerve tail.",
    "brain": "a squishy pink brain with exaggerated chunky folds, glossy.",
    "finger": "a single cartoon severed finger, greenish-grey with knobbly knuckles and a long chipped purple nail, "
              "ending cleanly with a white bone stub.",
    "worms": "a wriggling heap of fat pink earthworms on a little mound of dark soil, some curling up.",
    "bat_wing": "a single leathery black bat wing with thin finger bones and scalloped edges, slightly translucent.",
    "blood": "a corked glass apothecary jar of thick bright red blood with a blank paper label and a drip down the side.",
}

SNACKS = {
    # Grill
    "steak": "a perfectly grilled steak with dark crosshatch grill marks, juicy and sizzling, on a small wooden board.",
    "fried_egg": "a sunny-side-up fried egg in a small black cast-iron skillet, glossy golden yolk.",
    "melted_cheese": "a gooey wedge of cheese melting on a skewer, dripping long stretchy strings.",
    "grilled_sardines": "three grilled sardines on a skewer with charred grill marks and goofy little eyes, a lemon wedge.",
    "grilled_foot": "a cartoon foot grilled like barbecue with dark crosshatch grill marks and glossy barbecue sauce, "
                    "stubby toes, on a small plate.",
    "buffalo_bat_wings": "a small paper-lined basket of saucy orange-red buffalo-style bat wings, little leathery wings "
                         "glazed in sauce, with celery sticks and a cup of dip.",
    # Campfire
    "toasted_marshmallow": "a big, plump golden-brown toasted marshmallow on the end of a short, chunky roasting stick, "
                           "the marshmallow filling most of the frame, slightly melting, a tiny wisp of smoke.",
    "popcorn": "an overflowing red-and-white striped movie-theater popcorn bucket heaped high with fluffy buttery "
               "popcorn, a few kernels tumbling off the top.",
    "baked_potato": "a baked potato split open on crinkled foil with a pat of melting butter, sour cream and chives.",
    "campfire_bread": "a rustic round loaf of campfire bread with a golden scored crust and a few charred spots.",
    "caramel": "a little glass dish brimming with thick, glossy golden caramel, a fat drip over the rim, two wrapped "
               "caramel candies beside it.",
    "roasted_pumpkin": "half a roasted pumpkin, cut side up, caramelized orange flesh, roasted seeds scattered around.",
    "finger_dog": "a fat cartoon finger roasted like a hot dog, charred and blistered with a chipped purple nail, on a "
                  "short, chunky stick, the finger filling most of the frame.",
    # Pot
    "tomato_soup": "a steaming bowl of bright red tomato soup with a swirl of cream.",
    "boiled_egg": "a soft-boiled egg in a little egg cup, its top cracked off, a runny yolk.",
    "hot_cocoa": "a big mug of hot cocoa with rising steam and a dollop of whipped cream.",
    "worm_spaghetti": "a bowl of spaghetti in tomato sauce where the noodles are clearly long pink worms, a few with tiny "
                      "happy faces.",
    "poached_eyeball": "one big eyeball floating in a small bowl of clear broth, its green iris looking up, a sprig of "
                       "parsley.",
    "blood_pudding": "a wobbly, glossy dark red jelly pudding in a bowl with a spoon.",
    # Cauldron
    "bubbling_brain_broth": "a little black cauldron-shaped bowl of bubbling green broth with a pink brain bobbing in it.",
    "bat_brew": "a round glass potion flask of fizzing purple brew with a little bat wing sticking out of the top.",
    "eye_of_newt_potion": "a tall corked potion bottle of glowing green liquid with several eyeballs floating inside, "
                          "looking in different directions.",
    "pumpkin_spice_potion": "a round potion bottle of orange pumpkin-spice brew topped with whipped cream and a cinnamon "
                            "stick, a little jack-o'-lantern face on the glass.",
    # Sun
    "jerky": "a little pile of dark red-brown beef jerky strips.",
    "sun_dried_tomatoes": "a small glass jar of shriveled red sun-dried tomatoes in oil with green herbs.",
    "dried_orange_slices": "a few translucent dried orange wheels strung on twine, glowing like stained glass.",
    "foot_jerky": "a cartoon foot dried into wrinkly, leathery brown jerky, shriveled stubby toes, tied with twine.",
    "rotten_egg": "a cracked, greenish rotten egg with wavy green stink lines and a buzzing cartoon fly.",
    # Mixed
    "batter": "a mixing bowl of smooth pale yellow cake batter with a whisk in it and a drip on the rim.",
    "milkshake": "a tall vanilla milkshake in a classic soda-fountain glass with whipped cream, a cherry and a striped "
                 "straw.",
    "chocolate_orange": "a chocolate orange broken into segments, fanned out, glossy milk chocolate.",
    "gummy_worms": "a pile of real worms coated in sparkly colored sugar like gummy worms, clearly worms, a few with tiny "
                   "faces.",
    "dirt_cup": "a clear cup of chocolate pudding topped with crumbled-cookie dirt, live pink worms poking out.",
    "jello_brain": "a wobbly, glossy, translucent lime-green gelatin dessert molded in the shape of a brain, sitting on a "
                   "small white dessert plate, jiggly and shiny with a few bubbles inside.",
    "ladyfingers": "a little plate of cartoon fingers dusted with powdered sugar like ladyfinger cookies, chipped pink "
                   "nails.",
    "toe_jam": "a small glass jam jar of purple jam with little cartoon toes floating in it, a gingham cloth lid and a "
               "blank label.",
    "blood_orange_juice": "a glass of thick red blood-orange juice with an orange slice on the rim, a bendy straw and a "
                          "drip down the side.",
    "not_strawberry_milk": "a glass of suspicious dark red-pink milk with a striped straw and a red smudge on the glass.",
    "eyeball_caviar": "a small round open tin of caviar where the caviar is dozens of tiny glossy eyeballs, a little "
                      "mother-of-pearl spoon beside it.",
    "deviled_eggs": "three deviled eggs on a small plate, each with an eyeball in its yolk, looking different ways.",
    "eye_scream": "an ice cream cone with two scoops of vanilla, an eyeball on top in place of a cherry, a drip running "
                  "down the cone.",
}

MEALS = {
    "steak_dinner": "a dinner plate with a grilled steak, a baked potato with butter and a few green beans.",
    "steak_and_eggs": "a plate with a grilled steak and two sunny-side-up fried eggs.",
    "grilled_cheese": "a golden grilled cheese sandwich cut diagonally, melted cheese stretching between the halves.",
    "pizza": "a whole round pizza with bubbling cheese and tomato sauce, one slice pulled out with stringy cheese.",
    "cowboy_trail_mix": "a little tin cup overflowing with trail mix: beef jerky pieces, dried orange slices and nuts.",
    "camp_crystal_fish_fry": "a red-and-white checkered paper boat of golden fried fish and fried potato wedges with a "
                             "lemon wedge and a tiny white hockey-mask decoration on a toothpick.",
    "movie_night_combo": "a little tray holding a red-and-white striped popcorn bucket and a tall vanilla milkshake with "
                         "a straw.",
    "caramel_corn": "a striped paper bag overflowing with glossy golden caramel popcorn.",
    "birthday_cake": "a round birthday cake with thick white frosting, glossy cherry-red icing dripping down the sides, "
                     "one lit candle on top, and a big kitchen knife stuck into it at a jaunty angle.",
    "pumpkin_pie": "a whole pumpkin pie with a crimped crust, a jack-o'-lantern face cut into the top, a dollop of "
                   "whipped cream.",
    "pancake_stack": "a tall stack of fluffy pancakes with a pat of melting butter and syrup dripping down the sides.",
    "campfire_smores": "a big s'more: a toasted marshmallow and a square of chocolate squished between two graham "
                       "crackers, oozing.",
    "cocoa_and_marshmallows": "a big mug of hot cocoa overflowing with toasted marshmallows.",
    "texas_bbq_platter": "a metal barbecue tray on butcher paper with a dried cartoon foot jerky, a pile of saucy buffalo "
                         "bat wings, pickles and a slice of white bread.",
    "foot_and_potatoes": "a dinner plate with a grilled cartoon foot with grill marks and barbecue sauce next to a baked "
                         "potato with butter.",
    "spaghetti_and_eyeballs": "a big plate of worm spaghetti in tomato sauce topped with eyeballs in place of meatballs.",
    "caviar_canapes": "an elegant little silver tray of toast canapés, each topped with a mound of tiny glossy eyeballs "
                      "like caviar.",
    "brain_trifle": "a tall glass trifle bowl layered with green brain-shaped jello, finger-shaped ladyfingers and cream.",
    "finger_dog_in_a_bun": "a hot dog bun holding a roasted cartoon finger in place of a sausage, a squiggle of mustard, "
                           "a chipped fingernail poking out.",
    "vampire_breakfast": "a little breakfast tray with a fried egg, a glass of red blood-orange juice and a set of plastic "
                         "vampire fangs.",
    "eye_scream_sundae": "a glass sundae dish of vanilla ice cream with caramel sauce, three eyeballs on top and a long "
                         "spoon.",
    "graveyard_pudding": "a dish of chocolate pudding with cookie-crumb dirt, a little tombstone-shaped cookie and "
                         "sugared worms poking out.",
    "witchs_supper": "a witch's dinner on a rustic plate: a small black cauldron of green brain broth beside a fizzing "
                     "purple bat-brew flask.",
}

BOTCHED = {
    "pile_of_slop": "a gross, lumpy pile of grey-green slop on a plate with buzzing flies and wavy stink lines.",
    "burnt_to_a_crisp": "a smoking black lump of charcoal on a plate, completely burnt, a wisp of smoke.",
    "kitchen_fire": "a small frying pan on fire with big cartoon flames and puffs of black smoke.",
    "sardine_smoothie": "a tall smoothie glass of murky orange smoothie with a sardine tail sticking out of the top next "
                        "to the straw.",
    "fish_fudge": "a square of brown chocolate fudge with a sardine's head and tail sticking out of it.",
    "fishmallow": "a white marshmallow on a stick with a sardine's head and tail sticking out of either end.",
    "stinky_fish": "a sun-baked rotten sardine with X eyes, green stink lines and buzzing flies.",
    "curdled_mess": "a glass of lumpy curdled milk with orange chunks floating in it.",
    "spoiled_milk": "a bloated, bulging plain milk carton with green stink lines and lumpy curds oozing from the spout.",
    "melted_mess": "a sad puddle of melted chocolate with a limp, drooping red chocolate wrapper in it.",
    "cursed_cake": "a lopsided purple-and-green cake with a screaming face in the frosting and wisps of ghostly smoke.",
    "soggy_popcorn": "a bowl of limp, soggy popcorn drowning in milk.",
}

# Not items: the pantry's own props (--extras).
EXTRAS = {
    "board": "a big, chunky rectangular wooden cutting board seen from slightly above, warm honey-colored end grain "
             "with knife scratches, a few dark stains and a little hole for hanging in one corner, empty on top.",
}

# The pantry's kitchen (--kitchen), in the scenes' own style (tools/scene_prompts.json) and size.
KITCHEN = (
    "A horror kitchen at night: the grimy old kitchen of a haunted farmhouse. A black cast-iron stove glowing orange on "
    "one side, cleavers, ladles and a meat hook hanging from a rack, shelves along the side walls crowded with jars of "
    "mysterious pickled things, a cracked window with moonlight and fog at the back, cobwebs and dripping candles. "
    "A checkered black-and-cream tile floor with a few old stains forms the open floor in the foreground. Warm stove "
    "glow mixed with a sickly green light."
)

ITEMS = {**{k: ("kitchen prop", v) for k, v in METHODS.items()},
         **{k: ("food item", v) for d in (FOODS, SNACKS, MEALS) for k, v in d.items()},
         **{k: ("disgusting failed dish", v) for k, v in BOTCHED.items()}}


def redact(text: str, key: str) -> str:
    return text.replace(key, "[redacted]") if key else text


def generate(key: str, item: str, k: int) -> Path:
    if item == "kitchen":
        style = json.loads((ROOT / "tools/scene_prompts.json").read_text())["style"]
        body = {"prompt": f"{style}\n\nScene: {KITCHEN}", "size": "1536x1024"}
    else:
        kind, what = ("kitchen prop", EXTRAS[item]) if item in EXTRAS else ITEMS[item]
        body = {"prompt": STYLE.format(kind=kind, item=what), "size": "1024x1024", "background": "transparent"}
    r = requests.post(f"{API}/images/generations", headers={"Authorization": f"Bearer {key}"},
                      json={"model": MODEL, "quality": "high", "output_format": "png", "n": 1, **body}, timeout=600)
    if r.status_code != 200:
        raise RuntimeError(f"{r.status_code}: {r.text[:400]}")
    dest = OUT / f"{item}-{k}.png"
    dest.write_bytes(base64.b64decode(r.json()["data"][0]["b64_json"]))
    return dest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=1, help="candidates per item")
    ap.add_argument("--start", type=int, default=1, help="first candidate number (to add more without overwriting)")
    ap.add_argument("--only", action="append", choices=sorted(ITEMS))
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--skip-existing", action="store_true", help="leave out candidates already on disk")
    ap.add_argument("--extras", action="store_true", help="the pantry's props and kitchen instead of its items")
    args = ap.parse_args()
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        print("OPENAI_API_KEY is not set", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    names = [*EXTRAS, "kitchen"] if args.extras else list(ITEMS)
    jobs = [(i, k) for i in names if not args.only or i in args.only for k in range(args.start, args.start + args.n)
            if not (args.skip_existing and (OUT / f"{i}-{k}.png").exists())]
    failed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(generate, key, i, k): (i, k) for i, k in jobs}
        for fut, (i, k) in futures.items():
            try:
                print(f"wrote {fut.result().relative_to(ROOT)}", flush=True)
            except Exception as e:  # noqa: BLE001 - report and keep going
                print(f"FAIL {i}-{k}: {redact(str(e), key)}", flush=True)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
