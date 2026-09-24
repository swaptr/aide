package com.sabreware.aide.ui.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelTraitsTest {
    @Test
    fun `parameter counts are read from labels, names and ollama tags`() {
        assertEquals(31.0, parameterBillions("gemma4:31b"))
        assertEquals(1.5, parameterBillions("DeepSeek-R1-Distill-Qwen-1.5B"))
        assertEquals(7.0, parameterBillions("Qwen2.5-7B-Instruct"))
        assertEquals(2.0, parameterBillions("gemma-3n-E2B-it"))
        assertEquals(1.0, parameterBillions("1B"))
    }

    @Test
    fun `a name without a parameter count has no size`() {
        assertNull(parameterBillions("gpt-5-mini"))
        assertNull(parameterBillions("claude-sonnet-5"))
        assertNull(parameterBillions("model-4bit"))
    }
}
