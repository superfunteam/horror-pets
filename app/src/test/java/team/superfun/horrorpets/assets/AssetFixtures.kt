package team.superfun.horrorpets.assets

/** Reads the real `sprites-final` / `scenes-final` JSON copied into `src/test/resources/fixtures/`. */
object AssetFixtures {
    fun text(name: String): String {
        val stream = AssetFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("missing test fixture fixtures/$name")
        return stream.bufferedReader().use { it.readText() }
    }

    fun manifest(pet: String): String = text("$pet-manifest.json")

    fun scenes(): String = text("scenes.json")
}
