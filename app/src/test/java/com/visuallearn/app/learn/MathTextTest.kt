package com.visuallearn.app.learn

import org.junit.Assert.assertEquals
import org.junit.Test

class MathTextTest {

    @Test
    fun theQuestionFromTheEngineLessonReadsPlainly() {
        assertEquals(
            "2 revolutions; energy is delivered over 1/4 of the total rotation (180°)",
            MathText.plain("$2$ revolutions; energy is delivered over $\\frac{1}{4}$ of the total rotation ($180^\\circ$)"),
        )
    }

    @Test
    fun commonNotation() {
        assertEquals("τ = F r⊥", MathText.convert("\\tau = F r_\\perp").replace("_⊥", "⊥"))
        assertEquals("v₀ + a t", MathText.convert("v_0 + a t"))
        assertEquals("x² + y²", MathText.convert("x^2 + y^{2}"))
        assertEquals("(a+b)/2c", MathText.convert("\\frac{a+b}{2c}"))
        assertEquals("√(x+1)", MathText.convert("\\sqrt{x+1}"))
        assertEquals("ΔV ≈ 500 cm³", MathText.convert("\\Delta V \\approx 500\\,\\text{cm}^3"))
        assertEquals("θ ≤ π", MathText.convert("\\theta \\le \\pi"))
        assertEquals("10:1", MathText.convert("10:1"))
    }

    @Test
    fun displayMathGetsItsOwnLine() {
        assertEquals("Torque is\nτ = F r\nso it's zero.", MathText.plain("Torque is \\[ \\tau = F r \\] so it's zero.").replace(" \n", "\n").replace("\n ", "\n"))
    }

    @Test
    fun moneyAndPlainTextStayAsTheyAre() {
        assertEquals("It costs $5 and $10.", MathText.plain("It costs $5 and $10."))
        assertEquals("No math here.", MathText.plain("No math here."))
        // Unknown commands are left as written, not guessed.
        assertEquals("\\unknown x", MathText.convert("\\unknown x"))
    }
}
