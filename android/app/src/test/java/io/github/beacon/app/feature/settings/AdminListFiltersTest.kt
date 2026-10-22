package io.github.beacon.app.feature.settings

import org.junit.Assert.*
import org.junit.Test

class AdminListFiltersTest {
    @Test fun statusFiltersDoNotDependOnTheSearchText() {
        assertFalse(adminListMatches("", 1, true, "phone"))
        assertFalse(adminListMatches("phone", 2, false, "phone"))
        assertTrue(adminListMatches("", 0, true, "phone"))
    }

    @Test fun termsMatchAcrossNamesAndIdentifiersIgnoringCaseAndWhitespace() {
        assertTrue(adminListMatches("  PHONE  abC  ", 1, false, "My Phone", "ABC123"))
        assertFalse(adminListMatches("phone xyz", 0, false, "My Phone", "ABC123"))
    }
}
