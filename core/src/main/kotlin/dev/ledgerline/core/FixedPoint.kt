package dev.ledgerline.core

/** Decimal text to scaled integer and back, with no floating point. */
object FixedPoint {
    sealed interface Parsed {
        data class Ok(val value: Long) : Parsed

        data object TooPrecise : Parsed

        data object Invalid : Parsed
    }

    private val pattern = Regex("^(-?)(\\d+)(?:\\.(\\d+))?$")

    private fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }

    /** Parses [text] such as "-12.50" into units of 10^-[decimals]. More fractional digits than [decimals] is TooPrecise. */
    fun parse(
        text: String,
        decimals: Int,
    ): Parsed {
        val m = pattern.matchEntire(text) ?: return Parsed.Invalid
        val negative = m.groupValues[1].isNotEmpty()
        val whole = m.groupValues[2]
        val frac = m.groupValues[3]
        if (frac.length > decimals) return Parsed.TooPrecise
        val digits = (whole + frac.padEnd(decimals, '0')).trimStart('0')
        if (digits.length > 18) return Parsed.Invalid
        val magnitude = if (digits.isEmpty()) 0L else digits.toLong()
        return Parsed.Ok(if (negative) -magnitude else magnitude)
    }

    /** Formats units with [decimals] fractional digits; when [minimal], trailing fractional zeros are dropped. */
    fun format(
        units: Long,
        decimals: Int,
        minimal: Boolean,
    ): String {
        val sign = if (units < 0) "-" else ""
        val abs = if (units < 0) -units else units
        val unit = pow10(decimals)
        val whole = abs / unit
        var frac = (abs % unit).toString().padStart(decimals, '0')
        if (minimal) frac = frac.trimEnd('0')
        return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
    }
}
