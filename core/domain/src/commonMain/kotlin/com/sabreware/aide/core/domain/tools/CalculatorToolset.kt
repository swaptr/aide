package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.tools.calc.MathEvaluator
import com.sabreware.aide.core.domain.tools.calc.MathOutcome
import com.sabreware.aide.core.domain.tools.results.CalculatorResult
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AideTools"
private val CALCULATOR_ERROR_CODES = setOf(
    "INVALID_EXPRESSION", "DIVIDE_BY_ZERO", "OUT_OF_DOMAIN", "OVERFLOW",
)

class CalculatorToolset(
    private val evaluator: MathEvaluator,
) : Toolset {

    override val category = ToolCategory.Math
    override val displayName = "Math"
    override val blurb = "Run arithmetic and math expressions."

    override fun tools(scope: ToolsetScope): List<AideTool> = listOf(asAideTool())

    fun asAideTool(): AideTool = AideTool.Function(
        name = "Calculator",
        readOnly = true,
        description = "Evaluate a mathematical expression. Supports the common scalar " +
            "functions (sqrt, cbrt, abs, ceil, floor, round, sin, cos, tan, asin, acos, " +
            "atan, sinh, cosh, tanh, log = log10, ln = natural log, log2, exp, pow), the " +
            "operators + - * / ^, parentheses, and decimals. Constants: pi, e. Returns " +
            "{result, formatted}. Identifiers outside the whitelist (including bare " +
            "variables like 'x') are rejected with INVALID_EXPRESSION.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "expression" to stringProp(
                    "Math expression, e.g. 'sqrt(2)' or 'sin(pi/4) + log2(8)'",
                ),
            ),
        ),
        handler = { args ->
            val expression = args["expression"]?.jsonPrimitive?.content.orEmpty()
            AideLog.i(TAG, "Calculator called: expression='$expression'")
            when (val outcome = evaluator.eval(expression)) {
                is MathOutcome.Ok ->
                    CalculatorResult.Ok(outcome.value, outcome.formatted).toEnvelope()
                is MathOutcome.Err ->
                    CalculatorResult.Err(outcome.code, outcome.message).toEnvelope()
            }
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CALCULATOR_ERROR_CODES,
    )
}
