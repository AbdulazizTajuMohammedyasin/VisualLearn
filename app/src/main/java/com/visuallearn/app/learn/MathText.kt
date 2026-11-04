package com.visuallearn.app.learn

/**
 * Teachers write math as LaTeX (`\( \frac{1}{4} \)`, `$180^\circ$`), which the
 * LearnHarness web app draws with KaTeX. On the phone, common LaTeX becomes
 * readable plain text instead: 1/4, 180°, x², v₀, π, ≤. Anything it doesn't
 * know is left as written rather than guessed.
 */
object MathText {

    private val SYMBOLS = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "∼", "equiv" to "≡", "propto" to "∝",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔", "iff" to "⇔", "implies" to "⇒",
        "infty" to "∞", "degree" to "°", "circ" to "°", "partial" to "∂", "nabla" to "∇", "sum" to "Σ", "prod" to "Π", "int" to "∫",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
        "ldots" to "…", "cdots" to "⋯", "dots" to "…", "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "%" to "%", "$" to "$", "{" to "{", "}" to "}", "_" to "_", "&" to "&", "#" to "#",
    )
    private val SPACES = mapOf("," to " ", ";" to " ", ":" to " ", "!" to "", "quad" to " ", "qquad" to "  ", " " to " ")
    private val UNWRAP = setOf("text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "textbf", "textit", "operatorname", "boldsymbol", "vec", "hat", "bar", "overline", "mathcal")
    private const val SUP_FROM = "0123456789+-=()niax"
    private const val SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃˣ"
    private const val SUB_FROM = "0123456789+-=()aeoxihklmnpst"
    private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓᵢₕₖₗₘₙₚₛₜ"

    /** Text with its math spans (`\( \)`, `\[ \]`, `$$ $$`, `$ $`) turned into plain text. */
    fun plain(text: String): String {
        if (!text.contains('\\') && !text.contains('$')) return text
        var s = text
        s = Regex("""\\\[(.+?)\\]""", RegexOption.DOT_MATCHES_ALL).replace(s) { "\n" + convert(it.groupValues[1]).trim() + "\n" }
        s = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL).replace(s) { "\n" + convert(it.groupValues[1]).trim() + "\n" }
        s = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL).replace(s) { convert(it.groupValues[1]).trim() }
        // Single dollars: only a span that looks like math, so "$5 and $10" stays money.
        s = Regex("""\$([^$\n]+?)\$""").replace(s) { m ->
            val inner = m.groupValues[1]
            if (inner.startsWith(" ") || inner.endsWith(" ") && !inner.contains('\\')) m.value else convert(inner).trim()
        }
        return s
    }

    /** One math expression → plain text. */
    fun convert(tex: String): String {
        var s = tex
        // \left( \right) and friends: keep just the bracket.
        s = s.replace(Regex("""\\(left|right|big|Big|bigg|Bigg)\s*"""), "")
        // Fractions and roots, innermost first.
        repeat(4) {
            s = Regex("""\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""").replace(s) { m -> "${group(m.groupValues[1])}/${group(m.groupValues[2])}" }
            s = Regex("""\\sqrt\s*\{([^{}]*)\}""").replace(s) { m -> "√" + group(m.groupValues[1]) }
            s = Regex("""\\(${UNWRAP.joinToString("|")})\s*\{([^{}]*)\}""").replace(s) { it.groupValues[2] }
        }
        s = Regex("""\\sqrt\s*(\w)""").replace(s) { "√" + it.groupValues[1] }
        // Degrees before other superscripts.
        s = Regex("""\^\s*\{?\s*\\circ\s*\}?""").replace(s, "°")
        // Commands and escaped characters.
        s = Regex("""\\([A-Za-z]+|[,;:! %$&#{}_])( (?=[A-Za-z]))?""").replace(s) { m ->
            val name = m.groupValues[1]
            val symbol = SPACES[name] ?: SYMBOLS[name] ?: return@replace if (name in UNWRAP) "" else m.value
            // A Greek letter written before a variable is one quantity: "\Delta V" reads ΔV.
            val greek = symbol.length == 1 && symbol[0] in 'Α'..'ω'
            symbol + if (greek) "" else m.groupValues[2]
        }
        // Powers and indices: small characters when every character has one.
        s = Regex("""\^\s*\{([^{}]*)\}|\^\s*(\S)""").replace(s) { m -> script(m.groupValues[1].ifEmpty { m.groupValues[2] }, SUP_FROM, SUP_TO, "^") }
        s = Regex("""_\s*\{([^{}]*)\}|_\s*(\w)""").replace(s) { m -> script(m.groupValues[1].ifEmpty { m.groupValues[2] }, SUB_FROM, SUB_TO, "_") }
        s = s.replace("{", "").replace("}", "")
        return s.replace(Regex(""" {2,}"""), " ")
    }

    /** A fraction part: bare when simple, bracketed when it's an expression. */
    private fun group(part: String): String {
        val p = convert(part).trim()
        return if (p.matches(Regex("""[\w.°√π²³]+"""))) p else "($p)"
    }

    private fun script(text: String, from: String, to: String, marker: String): String {
        val t = text.trim()
        return if (t.isNotEmpty() && t.all { it in from }) t.map { to[from.indexOf(it)] }.joinToString("")
        else if (t.length == 1) "$marker$t" else "$marker($t)"
    }
}
