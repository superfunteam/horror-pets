package team.superfun.horrorpets

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun versionCodeFromName() {
        assertEquals(10203, versionCodeOf("1.2.3"))
        assertEquals(1, versionCodeOf("0.0.0-dev"))
    }
}
