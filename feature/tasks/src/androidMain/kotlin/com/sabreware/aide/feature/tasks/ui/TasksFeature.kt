package com.sabreware.aide.feature.tasks.ui

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.sabreware.aide.core.designsystem.feature.Feature
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

/** One-shot flag set on the task-list entry (e.g. by the IME deep link) to open the new-task page. Set via
 *  `savedStateHandle` — the Navigation-recommended way to hand a flag to an existing destination. */
internal const val OPEN_NEW_TASK_KEY = "open_new_task"

// Tasks routes — a self-contained nested graph. `@Serializable data object/data class`, androidMain-only
// (Tasks is an Android/IME feature), so they never enter the common `Route`. Outsiders navigate to
// [TasksGraph]; the list/detail/edit destinations stay encapsulated inside it.
@Serializable data object TasksGraph
@Serializable internal data object TaskListRoute
@Serializable internal data class TaskDetailRoute(val taskId: String)
@Serializable internal data class TaskEditRoute(val taskId: String? = null, val groupId: String? = null)

/**
 * The Tasks feature — Android/IME-only. Owns its whole subtree: the nested nav graph (list → detail → edit),
 * its deep-links (from the IME), and its DI including its **own** [TaskDatabase]. None of it is compiled for
 * desktop: the whole feature is its own Android-only module, and :desktopApp never depends on it.
 */
object TasksFeature : Feature {

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.navigation<TasksGraph>(startDestination = TaskListRoute) {
            composable<TaskListRoute> { entry ->
                val openNewTask by entry.savedStateHandle
                    .getStateFlow(OPEN_NEW_TASK_KEY, false)
                    .collectAsStateWithLifecycle()
                TaskListScreen(
                    onOpenTask = { nav.navigate(TaskDetailRoute(it)) },
                    onAddTask = { gid -> nav.navigate(TaskEditRoute(taskId = null, groupId = gid)) },
                    openNewTask = openNewTask,
                    onOpenNewTaskConsumed = { entry.savedStateHandle[OPEN_NEW_TASK_KEY] = false },
                )
            }
            composable<TaskDetailRoute> { entry ->
                val route = entry.toRoute<TaskDetailRoute>()
                TaskDetailScreen(
                    onEdit = { nav.navigate(TaskEditRoute(taskId = route.taskId, groupId = null)) },
                    onCloned = { newId ->
                        nav.navigate(TaskEditRoute(taskId = newId, groupId = null)) {
                            popUpTo<TaskDetailRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<TaskEditRoute> {
                TaskEditScreen()
            }
        }
    }

    override fun handleDeepLink(dest: String, nav: NavHostController): Boolean = when (dest) {
        DeepLinkDest.DEST_TASKS -> {
            nav.navigate(TasksGraph) { launchSingleTop = true }
            true
        }
        DeepLinkDest.DEST_TASK_EDIT_NEW -> {
            nav.navigate(TasksGraph) { launchSingleTop = true }
            // Signal the task-list entry to open the new-task page (works whether it's fresh or already on
            // the stack — navigate args wouldn't update on singleTop).
            nav.currentBackStackEntry?.savedStateHandle?.set(OPEN_NEW_TASK_KEY, true)
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
