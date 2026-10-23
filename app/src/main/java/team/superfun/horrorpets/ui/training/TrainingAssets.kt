package team.superfun.horrorpets.ui.training

/** The gravestones the shuffle game moves, as asset paths (`sprites/minigames/<id>.webp`). */
internal val TOMBSTONE_ART: List<String> = listOf("tomb-rounded", "tomb-cross", "tomb-slab")
    .map { "sprites/minigames/$it.webp" }

/** The organs the catch game drops, as asset paths. */
internal val ORGAN_ART: List<String> = listOf("heart", "brain", "eyeball", "bone", "hand", "kidney")
    .map { "sprites/minigames/$it.webp" }

/** Every sound the training games make that is their own (`audio/sfx/<name>.wav`), for the asset check. */
internal val TRAINING_SFX: List<String> = (0..7).map(::tone) +
    listOf("reel_spin", "reel_stop", "slash", "stone_slide", "squish", "splat", "game_over")

/** Slash Says' i-th tone (0 to 7), low to high on the C-major pentatonic from C5. */
internal fun tone(i: Int): String = "tone_${i + 1}"
