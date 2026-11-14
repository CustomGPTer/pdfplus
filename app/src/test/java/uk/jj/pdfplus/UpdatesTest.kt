package uk.jj.pdfplus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.jj.pdfplus.extras.Updates

class UpdatesTest {
    @Test fun newerVersionsAreDetected() {
        assertTrue(Updates.isNewer("v1.2", "1.1"))
        assertTrue(Updates.isNewer("1.10", "1.9"))
        assertTrue(Updates.isNewer("2.0.0", "1.9.9"))
        assertTrue(Updates.isNewer("1.0.1", "1.0"))
    }

    @Test fun sameOrOlderVersionsAreIgnored() {
        assertFalse(Updates.isNewer("1.0", "1.0"))
        assertFalse(Updates.isNewer("v0.9", "1.0"))
        assertFalse(Updates.isNewer("", "1.0"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
    }
}
