package team.superfun.horrorpets.audio

/**
 * Whether switching the music from [from] to [to] picks the new track up where the old one is, rather than from its
 * start: only the fight music hardening into `fight_intense`, which keeps `fight`'s tempo, bars and length, so the
 * beat carries on through the crossfade. Every other switch starts the new track over.
 */
fun keepsPlace(from: String?, to: String?): Boolean = from == "fight" && to == "fight_intense"
