package team.superfun.horrorpets.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId

class SceneCatalogTest {
    private val catalog = parseScenes(AssetFixtures.scenes())

    @Test
    fun threeScenesPerPet() {
        assertEquals(PetId.entries.toSet(), catalog.keys)
        for (id in PetId.entries) {
            assertEquals("$id", 3, catalog.getValue(id).size)
        }
    }

    @Test
    fun entriesCarryTheFullSpec() {
        val dock = catalog.getValue(PetId.JASON).first()
        assertEquals("dock", dock.id)
        assertEquals("Crystal Lake Dock", dock.title)
        assertEquals("jason/dock.webp", dock.file)
        assertEquals(0.7531f, dock.floorY, 1e-6f)
        assertEquals("#592E2A", dock.tint)
        assertEquals(0.12f, dock.tintStrength, 1e-6f)
        assertEquals(0.55f, dock.shadowOpacity, 1e-6f)
        assertEquals("#FF7A3D", dock.accent)
    }

    @Test
    fun everySceneIsWellFormed() {
        val hex = Regex("#[0-9A-Fa-f]{6}")
        for ((id, scenes) in catalog) {
            assertEquals("$id scene ids are unique", scenes.size, scenes.map { it.id }.toSet().size)
            for (s in scenes) {
                assertTrue("$id/${s.id} file under the pet folder", s.file.startsWith("${id.name.lowercase()}/"))
                assertTrue("$id/${s.id} tint ${s.tint}", hex.matches(s.tint))
                assertTrue("$id/${s.id} accent ${s.accent}", hex.matches(s.accent))
                assertTrue("$id/${s.id} floorY ${s.floorY}", s.floorY > 0f && s.floorY < 1f)
            }
        }
    }

    @Test
    fun realFloorsStayAboveTheBottomInsetOnEveryTvAspect() {
        // Scenes are 1920x1280. 16:9 and 4:3 TV panels plus a 640x480 CRT.
        val views = listOf(1920 to 1080, 1440 to 1080, 1280 to 720, 640 to 480)
        for ((id, scenes) in catalog) for (s in scenes) for ((w, h) in views) {
            val crop = coverCrop(1920, 1280, w, h, s.floorY)
            assertTrue(
                "$id/${s.id} on ${w}x$h",
                floorYOnScreen(crop, 1280, s.floorY) <= 0.90f * h + 0.01f,
            )
        }
    }

    @Test
    fun keysAreCaseInsensitiveAndUnknownKeysAreIgnored() {
        val scene = """{"id":"a","title":"A","file":"x/a.webp","floorY":0.75,"tint":"#000000",
            "tintStrength":0.1,"shadowOpacity":0.5,"accent":"#FFFFFF"}"""
        val json = """{"Jason":[$scene],"freddy":[$scene],"PINHEAD":[$scene],"ghostface":[$scene],"Chucky":[$scene],
            "MICHAEL":[$scene],"LeatherFace":[$scene],"bigfoot":[$scene]}"""
        val parsed = parseScenes(json)
        assertEquals(PetId.entries.toSet(), parsed.keys)
        assertNotNull(parsed[PetId.JASON])
    }

    @Test
    fun aCatalogMissingAPetIsALoudError() {
        val scene = """{"id":"a","title":"A","file":"x/a.webp","floorY":0.75,"tint":"#000000",
            "tintStrength":0.1,"shadowOpacity":0.5,"accent":"#FFFFFF"}"""
        val others = """"freddy":[$scene],"pinhead":[$scene],"ghostface":[$scene],"chucky":[$scene],"michael":[$scene],
            "leatherface":[$scene]"""
        assertEquals(PetId.entries.toSet(), parseScenes("""{"jason":[$scene],$others}""").keys)
        assertThrows(IllegalArgumentException::class.java) {
            parseScenes("""{$others}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseScenes("""{"jason":[],$others}""")
        }
        // A v0.1.0 catalog (the four original killers only) is missing the new ones.
        assertThrows(IllegalArgumentException::class.java) {
            parseScenes("""{"jason":[$scene],"freddy":[$scene],"pinhead":[$scene],"ghostface":[$scene]}""")
        }
        // So is a v0.2.0 catalog (six killers, no Leatherface).
        assertThrows(IllegalArgumentException::class.java) {
            parseScenes(
                """{"jason":[$scene],"freddy":[$scene],"pinhead":[$scene],"ghostface":[$scene],"chucky":[$scene],"michael":[$scene]}""",
            )
        }
    }

    @Test
    fun leatherfacesHouseIsInDaylight() {
        // The house is a daytime scene in harsh sun: only a light touch of tint on the sprite.
        val house = catalog.getValue(PetId.LEATHERFACE).first()
        assertEquals("house", house.id)
        assertEquals("leatherface/house.webp", house.file)
        assertEquals(listOf("house", "dining", "butcher"), catalog.getValue(PetId.LEATHERFACE).map { it.id })
        assertTrue(house.tintStrength < catalog.getValue(PetId.LEATHERFACE)[1].tintStrength)
    }
}
