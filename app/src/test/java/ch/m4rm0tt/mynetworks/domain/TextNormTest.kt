package ch.m4rm0tt.mynetworks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormTest {
    @Test
    fun foldRemovesAccentsAndCase() {
        assertEquals("helene", TextNorm.fold("Hélène"))
        assertEquals("francois", TextNorm.fold("FRANÇOIS"))
        assertEquals("strasse", TextNorm.fold("Straße"))
        assertEquals("coeur", TextNorm.fold("Cœur"))
        assertTrue(TextNorm.fold("Zürich Genève").contains("zurich geneve"))
    }

    @Test
    fun tagKeyIgnoresCaseAndSpacesButKeepsDistinctWords() {
        assertEquals(TextNorm.tagKey("  Graphisme "), TextNorm.tagKey("graphisme"))
        assertEquals(TextNorm.tagKey("Web   design"), TextNorm.tagKey("web design"))
        // Pas de fusion sémantique : Vevey et Riviera restent distincts.
        assertNotEquals(TextNorm.tagKey("Vevey"), TextNorm.tagKey("Riviera"))
    }

    @Test
    fun cleanKeepsDisplaySpelling() {
        assertEquals("Hélène Dupré", TextNorm.clean("  Hélène   Dupré "))
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertEquals("%50\\% off%", TextNorm.likePattern("50% OFF"))
        assertEquals("%a\\_b%", TextNorm.likePattern("a_b"))
    }

    @Test
    fun initials() {
        assertEquals("HD", TextNorm.initials("Hélène Dupré"))
        assertEquals("A", TextNorm.initials("ACME"))
        assertEquals("?", TextNorm.initials("   "))
    }
}
