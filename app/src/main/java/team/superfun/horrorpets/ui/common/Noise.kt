package team.superfun.horrorpets.ui.common

/**
 * A repeatable pseudo-random number in 0..1 for the pair ([a], [b]): the same pair always gives the same
 * value. Effects drawn every frame (dust motes, glitch slices) use it instead of a `Random`, so a draw
 * allocates nothing and a value holds still for as long as its inputs do.
 */
fun hash01(a: Int, b: Int): Float {
    var h = a * 374_761_393 + b * 668_265_263
    h = (h xor (h ushr 13)) * 1_274_126_177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16_777_216f
}
