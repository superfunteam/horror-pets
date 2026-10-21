package team.superfun.horrorpets.ui.home

import kotlin.math.max

/**
 * Where the refusal bubble goes, kept free of Compose so it can be tested. Positions are pixels in the
 * screen's coordinates; the bubble never has a "side": its box is slid along to fit and its tail is drawn
 * wherever the head is.
 */

/**
 * The left edge of a [width]-wide bubble over a head at [headX]: hanging right of the head, with its tail
 * [preferredTail] in from its left edge, unless that would cross the right safe inset ([maxRight]), in which
 * case it slides left just enough. It never starts left of [minLeft] (the stat card's right edge plus a gap),
 * which wins if the two ever conflict.
 */
fun bubbleLeft(headX: Float, width: Float, preferredTail: Float, minLeft: Float, maxRight: Float): Float =
    (headX - preferredTail).coerceIn(minLeft, max(minLeft, maxRight - width))

/**
 * Where the tail goes inside a [width]-wide bubble whose left edge is at [left]: right under the head at
 * [headX], but never closer than [edge] to either end, so it stays on the bubble's straight bottom edge.
 */
fun bubbleTailX(headX: Float, left: Float, width: Float, edge: Float): Float =
    (headX - left).coerceIn(edge, max(edge, width - edge))
