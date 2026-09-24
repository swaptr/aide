package com.sabreware.aide.feature.tasks.ui

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.feature.Feature
import com.sabreware.aide.core.designsystem.navigation.Navigator
import com.sabreware.aide.core.designsystem.navigation.navigator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclassesOfSealed
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.sabreware.aide.core.domain.navigation.DeepLinkDest
import com.sabreware.aide.feature.tasks.data.TaskDatabase
import com.sabreware.aide.feature.tasks.data.TaskRepositoryImpl
import com.sabreware.aide.feature.tasks.domain.RunTaskUseCase
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * The Tasks routes — Android/IME-only, so they never enter the common `Route`. Outsiders open [Home]; the
 * detail and editor stay inside the feature. Registered for saving by [TasksFeature.routes].
 */
@Serializable
sealed interface TaskRoute : NavKey {
    @Serializable data object Home : TaskRoute
    @Serializable data class Detail(val taskId: String) : TaskRoute
    @Serializable data class Edit(val taskId: String? = null, val groupId: String? = null) : TaskRoute
}

/**
 * The Tasks feature — Android/IME-only. Owns its whole subtree: its pages (list, detail, edit),
 * its deep-links (from the IME), and its DI including its **own** [TaskDatabase]. None of it is compiled for
 * desktop: the whole feature is its own Android-only module, and :desktopApp never depends on it.
 */
object TasksFeature : Feature {

    override fun EntryProviderScope<NavKey>.entries() {
        entry<TaskRoute.Home> {
            val nav = navigator()
            TaskListScreen(
                onOpenTask = { nav.navigate(TaskRoute.Detail(it)) },
                onAddTask = { gid -> nav.navigate(TaskRoute.Edit(taskId = null, groupId = gid)) },
            )
        }
        entry<TaskRoute.Detail> { route ->
            val nav = navigator()
            TaskDetailScreen(
                onEdit = { nav.navigate(TaskRoute.Edit(taskId = route.taskId, groupId = null)) },
                // The clone's editor takes the detail's place, so back skips the page being cloned.
                onCloned = { newId -> nav.replace(TaskRoute.Edit(taskId = newId, groupId = null)) },
                viewModel = koinViewModel { parametersOf(route) },
            )
        }
        entry<TaskRoute.Edit> { route -> TaskEditScreen(viewModel = koinViewModel { parametersOf(route) }) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun PolymorphicModuleBuilder<NavKey>.routes() {
        subclassesOfSealed<TaskRoute>()
    }

    override fun handleDeepLink(dest: String, nav: Navigator): Boolean = when (dest) {
        DeepLinkDest.DEST_TASKS -> {
            nav.navigate(TaskRoute.Home)
            true
        }
        DeepLinkDest.DEST_TASK_EDIT_NEW -> {
            // The IME's "new task": the list, then a new task's editor over it (back lands on the list).
            nav.navigate(TaskRoute.Home)
            nav.navigate(TaskRoute.Edit())
            true
        }
        else -> false
    }

    override val koinModule = module {
        single { get<TaskDatabase>().taskDao() }
        single { get<TaskDatabase>().taskGroupDao() }
        singleOf(::TaskRepositoryImpl) { bind<TaskRepository>() }
        singleOf(::RunTaskUseCase)
        viewModelOf(::TaskListViewModel)
        viewModelOf(::TaskDetailViewModel)
        viewModelOf(::TaskEditViewModel)
    }
}
