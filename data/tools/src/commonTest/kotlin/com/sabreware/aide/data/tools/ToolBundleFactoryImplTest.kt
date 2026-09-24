package com.sabreware.aide.data.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerStatus
import com.sabreware.aide.core.domain.mcp.McpToolDescriptor
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.tools.ToolsetScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adding a tool should be its files plus one binding. What that means concretely — and what is pinned here —
 * is that the factory names no toolset: it takes whatever was contributed, stamps each tool with its owner's
 * category, and applies only the general filters.
 */
class ToolBundleFactoryImplTest {

    private class StubToolset(
        override val category: ToolCategory,
        private val toolNames: List<String>,
        override val onDemand: Boolean = false,
        private val surfaces: Set<Surface> = setOf(Surface.CHAT, Surface.IME),
        private val note: String? = null,
    ) : Toolset {
        override val displayName = category.id
        override val blurb = "does ${category.id} things"
        override fun tools(scope: ToolsetScope): List<AideTool> = toolNames.map { name ->
            AideTool.Function(
                name = name,
                description = name,
                parametersSchema = JsonObject(emptyMap()),
                handler = { JsonObject(emptyMap()) },
                surfaces = surfaces,
            )
        }

        override fun promptNotes(scope: ToolsetScope): String? = note
    }

    private object NoMcp : McpConnections {
        override val status: StateFlow<List<McpServerStatus>> = MutableStateFlow(emptyList())
        override suspend fun connect(config: McpServerConfig) = Result.success(emptyList<McpToolDescriptor>())
        override suspend fun disconnect(url: String) = Unit
        override suspend fun setEnabled(url: String, enabled: Boolean) = Unit
        override fun currentTools(): List<AideTool> = emptyList()
    }

    private val clock = StubToolset(ToolCategory.Clock, listOf("SetAlarm"), onDemand = true)
    private val math = StubToolset(ToolCategory.Math, listOf("Calculator"))

    private fun factory(vararg toolsets: Toolset) =
        ToolBundleFactoryImpl(ToolsetRegistry(toolsets.toList()), NoMcp)

    private fun names(bundle: com.sabreware.aide.core.domain.tools.ToolBundle) = bundle.tools.map { it.name }

    @Test
    fun `a contributed toolset's tools appear, stamped with its category`() {
        val bundle = factory(math).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = true,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Math),
        )

        val calculator = bundle.tools.filterIsInstance<AideTool.Function>().first { it.name == "Calculator" }
        assertEquals(ToolCategory.Math.id, calculator.category)
        assertFalse(calculator.requiresActivation)
    }

    @Test
    fun `a category the user did not enable contributes nothing`() {
        val bundle = factory(math, clock).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = true,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Math),
        )

        assertContains(names(bundle), "Calculator")
        assertFalse("SetAlarm" in names(bundle))
    }

    // The meta-tool is how an on-demand category gets loaded, so it is always present and always visible.
    @Test
    fun `the RequestToolset meta-tool offers exactly the enabled on-demand categories`() {
        val bundle = factory(math, clock).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = true,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Math, ToolCategory.Clock),
        )

        assertContains(names(bundle), "RequestToolset")
        val alarm = bundle.tools.filterIsInstance<AideTool.Function>().first { it.name == "SetAlarm" }
        assertTrue(alarm.requiresActivation)
        assertTrue(bundle.systemPrompt!!.contains("Categories: Clock"))
    }

    @Test
    fun `a tool the surface cannot reach is dropped, and the prompt says why`() {
        val chatOnly = StubToolset(
            ToolCategory.Filesystem,
            listOf("DeleteFile"),
            surfaces = setOf(Surface.CHAT),
        )
        val bundle = factory(math, chatOnly).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = true,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Math, ToolCategory.Filesystem),
            surface = Surface.IME,
        )

        assertFalse("DeleteFile" in names(bundle))
        assertTrue(bundle.systemPrompt!!.contains("DeleteFile"), "the keyboard note should name it")
    }

    @Test
    fun `a toolset's own prompt note reaches the prompt`() {
        val noted = StubToolset(ToolCategory.Filesystem, listOf("ListFiles"), note = "Valid rootKey values: docs.")
        val bundle = factory(noted).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = true,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Filesystem),
        )

        assertTrue(bundle.systemPrompt!!.contains("Valid rootKey values: docs."))
    }

    @Test
    fun `a model that cannot call tools gets none`() {
        val bundle = factory(math).build(
            providerId = ProviderId("openai-test01"),
            supportsTools = false,
            enabledGated = emptySet(),
            enabledCategories = setOf(ToolCategory.Math),
        )

        assertTrue(bundle.tools.isEmpty())
        assertEquals(null, bundle.systemPrompt)
    }
}
