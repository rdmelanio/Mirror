package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
class EcrewLoginStoreTest {
 @Test fun credentialsAreRestrictedToExactSecureOrigin() {
  assertTrue(EcrewLoginStore.trusted("https://ecrew.cebupacificair.com"))
  for(origin in listOf("http://ecrew.cebupacificair.com","https://ecrew.cebupacificair.com.evil.test","https://evil.test","https://user@ecrew.cebupacificair.com","https://ecrew.cebupacificair.com:444",null)) assertFalse(EcrewLoginStore.trusted(origin))
 }
}
