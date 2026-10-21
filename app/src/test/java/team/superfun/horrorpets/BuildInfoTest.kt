package team.superfun.horrorpets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    @Test
    fun versionCodeFromName() {
        assertEquals(10203, versionCodeOf("1.2.3"))
        assertEquals(100, versionCodeOf("0.1.0"))
        assertEquals(1_000_000, versionCodeOf("100.0.0"))
        assertEquals(1, versionCodeOf("0.0.1"))
    }

    @Test
    fun devSentinelMapsToOne() {
        assertEquals(1, versionCodeOf("0.0.0-dev"))
        assertEquals(1, versionCodeOf(DEV_VERSION_NAME))
    }

    @Test
    fun zeroVersionStillGetsAPositiveCode() {
        assertEquals(1, versionCodeOf("0.0.0"))
    }

    @Test
    fun prefixedVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("v1.2.3") }
    }

    @Test
    fun incompleteVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.2") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1") }
    }

    @Test
    fun suffixedVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.2.3-rc1") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.2.3.4") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("0.0.0-dev2") }
    }

    @Test
    fun garbageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("banana") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf(" 1.2.3") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.2.3\n") }
    }

    @Test
    fun minorAndPatchMustStayBelowOneHundred() {
        assertEquals(19_999, versionCodeOf("1.99.99"))
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.100.0") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("1.0.100") }
    }

    @Test
    fun hugeMajorIsRejectedInsteadOfOverflowing() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("99999999999.0.0") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("300000.0.0") }
    }

    @Test
    fun rejectionNamesTheOffendingVersion() {
        val e = assertThrows(IllegalArgumentException::class.java) { versionCodeOf("v1.2.3") }
        assertTrue(e.message.orEmpty().contains("v1.2.3"))
    }
}
