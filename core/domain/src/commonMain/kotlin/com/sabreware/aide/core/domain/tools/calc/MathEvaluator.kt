package com.sabreware.aide.core.domain.tools.calc

/**
 * Portable math-expression evaluator contract. The implementation
 * ([com.sabreware.aide.data.tools.calc.SafeMathEvaluator], exp4j-backed) is JVM-shared and bound by each
 * application — commonMain code ([com.sabreware.aide.core.domain.tools.CalculatorToolset]) depends only on
 * this interface. This is the "write once, bind the JVM impl" pattern for the one genuinely-JVM dependency
 * (exp4j) in the calculator tool.
 */
interface MathEvaluator {
    fun eval(expression: String): MathOutcome
}

sealed class MathOutcome {
    data class Ok(val value: Double, val formatted: String) : MathOutcome()
    data class Err(val code: String, val message: String) : MathOutcome()
}
