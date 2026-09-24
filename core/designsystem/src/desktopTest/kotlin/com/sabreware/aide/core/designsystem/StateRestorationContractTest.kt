package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.sabreware.aide.core.designsystem.navigation.InModal
import com.sabreware.aide.core.designsystem.navigation.LocalNavigator
import com.sabreware.aide.core.designsystem.navigation.ModalSceneStrategy
import com.sabreware.aide.core.designsystem.navigation.Navigator
import com.sabreware.aide.core.designsystem.navigation.modalAware
import com.sabreware.aide.core.designsystem.navigation.navigator
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.test.Test

// Top-level and non-private: kotlinx-serialization reads an object's INSTANCE reflectively on the JVM.
@Serializable internal data object RestoreHome : NavKey

@Serializable internal data object RestoreDetail : NavKey

@Serializable internal data object FlowHome : NavKey

@Serializable internal data object FlowDetail : NavKey

private val RestoreConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(RestoreHome::class, RestoreHome.serializer())
            subclass(RestoreDetail::class, RestoreDetail.serializer())
            subclass(FlowHome::class, FlowHome.serializer())
            subclass(FlowDetail::class, FlowDetail.serializer())
            subclass(InModal::class, InModal.serializer())
        }
    }
}

/**
 * Pins state across a layout switch — a rotation across a breakpoint, in process or through a recreation.
 *
 * `rememberSaveable` keys by COMPOSITION POSITION, so a subtree that one layout composes under a different
 * parent than the other both loses its state in process and, after a recreation, restores whatever that
 * layout saved on its LAST visit — the "rotated back one or two steps" bug. The cure is one identity across
 * both parents: the shell's NavDisplay and a modal container's body are movable content. Each test switches layout
 * twice, in process and through a simulated recreation (save → dispose → recompose from the saved map, as
 * an Activity does), and checks the page, its inner page stack and a saveable field all come back.
 */
@OptIn(ExperimentalTestApi::class)
class StateRestorationContractTest {

    /**
     * A compose root whose "activity" can be recreated: save the registry, drop the whole tree for a frame,
     * compose it again from the saved map at the SAME position (not under a new `key`, whose value would
     * enter every composite key hash below it and restore nothing).
     */
    private class Recreatable {
        private var alive by mutableStateOf(true)
        private var registry = SaveableStateRegistry(emptyMap()) { true }

        fun ComposeUiTest.recreate(beforeRestore: () -> Unit) {
            val saved = registry.performSave()
            alive = false
            waitForIdle()
            registry = SaveableStateRegistry(saved) { true }
            beforeRestore()
            alive = true
            waitForIdle()
        }

        @Composable
        fun Root(content: @Composable () -> Unit) {
            if (alive) CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
        }
    }

    @Composable
    private fun Counter(label: String) {
        var n by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { n++ }) { Text("$label=$n") }
    }

    private fun ComposeUiTest.tap(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    /** The shell's shape: ONE back stack, NavDisplay with the modal scene, the app navigator over it. */
    @Composable
    private fun Nav(stack: NavBackStack<NavKey>) {
        val app = remember(stack) {
            object : Navigator {
                override val canGoBack get() = stack.size > 1
                override fun navigate(route: Any) { stack.add(route as NavKey) }
                override fun goBack() = stack.removeLastOrNull() != null
                override fun replace(route: Any) { stack[stack.lastIndex] = route as NavKey }
            }
        }
        CompositionLocalProvider(LocalNavigator provides app) {
            NavDisplay(
                backStack = stack,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                sceneStrategies = listOf(remember(stack) { ModalSceneStrategy(stack) }, SinglePaneSceneStrategy()),
                entryProvider = modalAware(
                    entryProvider {
                        entry<RestoreHome> {
                            val nav = navigator()
                            Button(onClick = { nav.navigate(RestoreDetail) }) { Text("open detail") }
                        }
                        entry<RestoreDetail> { Counter("detail") }
                        entry<FlowHome> {
                            PageScaffold(title = "page home") {
                                Column(it) {
                                    val nav = navigator()
                                    Counter("home")
                                    Button(onClick = { nav.navigate(FlowDetail) }) { Text("push detail") }
                                }
                            }
                        }
                        entry<FlowDetail> {
                            PageScaffold(title = "page detail") {
                                Column(it) {
                                    val nav = navigator()
                                    Counter("field")
                                    Button(onClick = { nav.goBack() }) { Text("pop") }
                                }
                            }
                        }
                    },
                ),
            )
        }
    }

    @Test
    fun shellNavDisplaySurvivesALayoutSwitchInProcessAndThroughRecreation() = runDesktopComposeUiTest {
        val root = Recreatable()
        var wide by mutableStateOf(false)
        setModalContent {
            root.Root {
                val stack = rememberNavBackStack(RestoreConfig, RestoreHome)
                // AppShell's shape: the stack above the switch, the NavDisplay as movable content, and the two
                // layouts giving it different parents (the compact drawer vs the wide sidebar Row).
                val pane = remember(stack) { movableContentOf { Nav(stack) } }
                if (wide) Row { Box { pane() } } else Column { pane() }
            }
        }
        tap("open detail")
        tap("detail=0")
        wide = true
        waitForIdle()
        tap("detail=1") // in process: same page, same field
        with(root) { recreate { wide = false } }
        onNodeWithText("detail=2").assertExists() // recreated into the OTHER layout: nothing rolled back
    }

    @Test
    fun modalFlowSurvivesASheetDialogSwitchInProcessAndThroughRecreation() = runDesktopComposeUiTest(
        width = 800,
        height = 600,
    ) {
        val root = Recreatable()
        var presentation by mutableStateOf(ModalPresentation.Sheet)
        setModalContent {
            root.Root {
                CompositionLocalProvider(LocalModalPresentation provides presentation) {
                    Nav(rememberNavBackStack(RestoreConfig, RestoreHome, InModal("flow", FlowHome)))
                }
            }
        }
        tap("push detail")
        tap("field=0")
        presentation = ModalPresentation.Dialog
        waitForIdle()
        tap("field=1") // in process: the inner page and its field moved with the body
        with(root) { recreate { presentation = ModalPresentation.Sheet } }
        onNodeWithText("page detail").assertExists()
        onNodeWithText("field=2").assertExists()
    }

    // A page below the top keeps its state while it is covered: going forward and back returns to the same
    // tab, scroll and search, not a page rebuilt from scratch. A popped page forgets, so re-opening it is fresh.
    @Test
    fun aPageUnderneathKeepsItsStateAcrossAPushAndPop() = runDesktopComposeUiTest(width = 800, height = 600) {
        setModalContent { Nav(rememberNavBackStack(RestoreConfig, RestoreHome, InModal("flow", FlowHome))) }
        tap("home=0")
        tap("home=1")
        tap("push detail")
        tap("field=0")
        tap("pop")
        onNodeWithText("home=2").assertExists()
        tap("push detail")
        onNodeWithText("field=0").assertExists()
    }
}
