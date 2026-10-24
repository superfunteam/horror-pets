package team.superfun.horrorpets.ui.common

import team.superfun.horrorpets.model.Facing
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec

/**
 * Whether [pet]'s art is drawn mirrored to face across the screen from the left ([onLeft]) or the right: art that
 * faces left ([Facing.LEFT], Jaws) turns round on the left, where it would look away, and stays as it is on the right,
 * where it already faces in. Art that faces the camera is never turned. The fight, the VS card and the super cut-in all
 * use it (the fight leaves runs alone: they face the way they go).
 */
fun mirrorToFace(pet: PetId, onLeft: Boolean): Boolean = onLeft && pet.spec.facing == Facing.LEFT
