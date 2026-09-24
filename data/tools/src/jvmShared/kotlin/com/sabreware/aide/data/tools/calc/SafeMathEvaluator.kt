package com.sabreware.aide.data.tools.calc

import com.sabreware.aide.core.domain.tools.calc.MathEvaluator
import com.sabreware.aide.core.domain.tools.calc.MathOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.log2
import net.objecthunter.exp4j.ExpressionBuilder
import net.objecthunter.exp4j.function.Function

/**
 * exp4j-backed [MathEvaluator]. Kept out of `commonMain` because exp4j and `java.math.BigDecimal` are
 * JVM-only — but it is the same class on both JVMs, so it lives in `src/jvmShared` and each application
 * binds it. A non-JVM target would supply its own [MathEvaluator]; that is what the port is for.
 */
class SafeMathEvaluator : MathEvaluator {

    override fun eval(expression: String): MathOutcome {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) {
            return MathOutcome.Err("INVALID_EXPRESSION", "empty expression")
        }
        // Reject identifiers not in WHITELIST before exp4j sees them.
        IDENTIFIER_RE.findAll(trimmed).forEach { match ->
            val ident = match.value
            if (ident !in WHITELIST) {
                return MathOutcome.Err(
                    "INVALID_EXPRESSION",
                    "unknown identifier '$ident'",
                )
            }
        }

        val value = runCatching {
            ExpressionBuilder(trimmed)
                .functions(LN_FUNCTION, LOG2_FUNCTION)
                .build()
                .evaluate()
        }.getOrElse { return parseError(it) }

        return classify(trimmed, value)
    }

    private fun classify(expr: String, value: Double): MathOutcome {
        if (value.isNaN()) {
            return MathOutcome.Err("OUT_OF_DOMAIN", "result is not a number (e.g. sqrt of negative)")
        }
        if (value.isInfinite()) {
            val code = if ('/' in expr) "DIVIDE_BY_ZERO" else "OVERFLOW"
            return MathOutcome.Err(code, "result is infinite")
        }
        return MathOutcome.Ok(value = value, formatted = formatDouble(value))
    }

    private fun parseError(t: Throwable): MathOutcome = MathOutcome.Err(
        "INVALID_EXPRESSION",
        t.message ?: t::class.simpleName ?: "error",
    )

    private fun formatDouble(value: Double): String {
        val asLong = value.toLong()
        if (asLong.toDouble() == value && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            return asLong.toString()
        }
        return BigDecimal(value)
            .setScale(8, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    companion object {
        private val WHITELIST: Set<String> = setOf(
            "pi", "e",
            "sqrt", "cbrt", "abs", "ceil", "floor", "round",
            "sin", "cos", "tan", "asin", "acos", "atan",
            "sinh", "cosh", "tanh",
            "log", "log10", "exp", "pow",
            "ln", "log2",
        )

        private val IDENTIFIER_RE = Regex("[A-Za-z_]+")

        // exp4j's `log` is natural log, but `ln` reads more commonly in LLM output.
        private val LN_FUNCTION = object : Function("ln", 1) {
            override fun apply(vararg args: Double): Double = ln(args[0])
        }

        private val LOG2_FUNCTION = object : Function("log2", 1) {
            override fun apply(vararg args: Double): Double = log2(args[0])
        }
    }
}
