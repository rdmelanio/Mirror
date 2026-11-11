package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
class CrewImportPolicyTest {
    @Test fun firstAndSameCrewAreAllowedButCrossAccountNeedsCurrentInteractiveConfirmation() {
        assertTrue(CrewImportPolicy.allowed(null,"a",false,null))
        assertTrue(CrewImportPolicy.allowed("a","a",false,null))
        assertFalse(CrewImportPolicy.allowed("a","b",false,null))
        assertFalse(CrewImportPolicy.allowed("a","b",true,null))
        assertFalse(CrewImportPolicy.allowed("a","b",false,"a"))
        assertTrue(CrewImportPolicy.allowed("a","b",true,"a"))
        assertFalse(CrewImportPolicy.allowed("changed","b",true,"a"))
    }
}
