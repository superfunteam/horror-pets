package team.superfun.horrorpets.ui.common

import team.superfun.horrorpets.model.Facing
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec

/**
 * Whether [pet]'s art is drawn mirrored to face across the screen from the left ([onLeft]) or the right: art that
 * faces left ([Facing.LEFT], Jaws) turns round on the left, where it would look away, and stays as it is on the right,
 * where it already faces in. Art that faces the camera is never turned. The fight, the VS card, the super cut-in and
 * the training games with two killers (Quick Draw, Hide and Eek!) all use it (the fight leaves runs alone: they face
 * the way they go).
 */
fun mirrorToFace(pet: PetId, onLeft: Boolean): Boolean = onLeft && pet.spec.facing == Facing.LEFT

/**
 * How far side-on art ([Facing.LEFT]) reaches out either side of its anchor, nose or tail, as a share of its cell's
 * height: 180 px of Jaws's 416 px cell. Art that faces the camera reaches far less (a weapon held out). Where he stands
 * goes by it, so his nose or tail stays clear of what is beside him: the fight's command window, the VS card's edges.
 */
const val SIDE_ON_REACH_OF_CELL = 180f / 416f
