package com.visuallearn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OrdinalTest {
    @Test
    fun englishOrdinals() {
        val expected = mapOf(1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 11 to "11th", 12 to "12th", 13 to "13th", 21 to "21st", 22 to "22nd", 101 to "101st", 111 to "111th")
        expected.forEach { (n, text) -> assertEquals(text, ordinal(n)) }
    }
}
