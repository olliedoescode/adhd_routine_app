package com.adhd.routineplanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.ui.plan.PlanScreen
import com.adhd.routineplanner.ui.routines.DraftTask
import com.adhd.routineplanner.ui.routines.RoutineCreateScreen
import com.adhd.routineplanner.ui.routines.RoutineListScreen
import com.adhd.routineplanner.ui.run.RunScreen
import com.adhd.routineplanner.ui.settings.SettingsScreen
import com.adhd.routineplanner.viewmodel.PlanViewModel
import com.adhd.routineplanner.viewmodel.RoutineCreateViewModel

/** Route names for the single-activity navigation graph (spec §15.1). */
object Routes {
    const val ROUTINE_LIST: String = "routines"
    const val ROUTINE_CREATE: String = "routines/new"
    const val SETTINGS: String = "settings"
    const val PLAN: String = "plan"
    const val RUN: String = "run"

    const val ARG_ROUTINE_ID: String = "routineId"

    fun plan(routineId: Long): String = "$PLAN/$routineId"
    fun run(routineId: Long): String = "$RUN/$routineId"
}

/**
 * RoutineList → RoutineCreate / Settings / Plan(routineId) → Run(routineId)
 * (spec §15.1).
 *
 * Routine creation is a full page rather than a bottom sheet, so it is a real
 * destination here. The plan screen's task editor and routine settings are
 * still sheets and are not separate destinations.
 */
@Composable
fun AppNavHost(openRunRoutineId: Long? = null) {
    val navController = rememberNavController()

    // When launched from the alarm screen, start on the Run destination for
    // that routine (spec section 15.9); otherwise start on the routine list.
    //
    // `remember` so this is computed once per composition: the start
    // destination must not change under the nav host, or it would rebuild the
    // graph and reset the back stack.
    val startDestination = remember {
        if (openRunRoutineId != null) {
            Routes.run(openRunRoutineId)
        } else {
            Routes.ROUTINE_LIST
        }
    }

    // The activity can update [openRunRoutineId] after the nav host is already
    // composed (e.g. the phone was unlocked during a run and the app was
    // brought to the front). When that happens, jump to the run screen so the
    // app "takes over" in run mode rather than staying on the list.
    //
    // The first composition is skipped: [startDestination] already handles the
    // cold-start case, and navigating here as well would push a duplicate Run
    // entry onto the back stack. The skip is keyed on the id the host started
    // with, so a *different* id arriving later still navigates.
    //
    // The id is read through `rememberUpdatedState` so the effect always sees
    // the latest value rather than the one captured when it was first launched.
    val initialRunId = remember { openRunRoutineId }
    val currentRunId by rememberUpdatedState(openRunRoutineId)

    // The routine the user has just ended from the run screen. Ending a run
    // clears the session asynchronously, so the activity's session re-check can
    // still report the old routine a moment later and drag the user straight
    // back into run mode — the "End routine does nothing" bug. Remembering the
    // id we exited from lets the effect below ignore that stale re-entry.
    var exitedRunId by remember { mutableStateOf<Long?>(null) }

    // The last id this effect has already acted on. Without it, a recomposition
    // (or the activity being recreated) re-runs the effect for the same id and
    // pushes a second Run entry — which is what made the back gesture on the
    // home screen appear to "start a routine" instead of closing the app.
    var handledRunId by remember { mutableStateOf<Long?>(initialRunId) }

    LaunchedEffect(openRunRoutineId) {
        val target = currentRunId ?: return@LaunchedEffect
        // Already acted on this id: nothing new to do.
        if (target == handledRunId) return@LaunchedEffect
        handledRunId = target
        // A re-entry for the routine we just ended is the stale session, not a
        // new run: ignore it so the pop back to the list sticks.
        if (target == exitedRunId) return@LaunchedEffect
        val route = Routes.run(target)
        if (navController.currentDestination?.route != route) {
            navController.navigate(route) {
                popUpTo(Routes.ROUTINE_LIST) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ROUTINE_LIST) {
            // Back on the app home closes the app rather than popping an empty
            // nav stack. Without this, the system back gesture popped the last
            // entry, the activity finished, and the launcher re-delivered a
            // bare intent — which the old session re-check turned into "open
            // the run screen", so back looked like it started a routine.
            //
            // The list is the start destination, so there is never anything
            // below it: finish the activity directly. `popBackStack()` would
            // return `false` here and leave the empty stack in place.
            BackHandler(enabled = true) {
                (navController.context as? android.app.Activity)?.finish()
            }
            RoutineListScreen(
                onOpenRoutine = { routineId ->
                    navController.navigate(Routes.plan(routineId))
                },
                onCreateRoutine = { navController.navigate(Routes.ROUTINE_CREATE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                // The "in progress" bar jumps straight to the run page.
                onOpenRun = { routineId ->
                    navController.navigate(Routes.run(routineId))
                },
            )
        }

        composable(Routes.ROUTINE_CREATE) {
            val viewModel: RoutineCreateViewModel = hiltViewModel()
            val stockTasks by viewModel.stockTasks.collectAsStateWithLifecycle()
            // Existing names let the create screen make its suggested name
            // unique, so a second morning routine becomes "Morning Routine #2".
            val existingNames by viewModel.existingNames.collectAsStateWithLifecycle()
            // Drives the create screen's progress overlay while the routine and
            // its tasks are written.
            val saving by viewModel.saving.collectAsStateWithLifecycle()
            RoutineCreateScreen(
                stockTasks = stockTasks,
                existingNames = existingNames,
                saving = saving,
                onSave = { routine, drafts ->
                    viewModel.create(routine, drafts.toPlacedTasks()) { newId ->
                        // Replace the create screen with the new routine's plan so
                        // Back from the plan returns to the list, not the form.
                        navController.navigate(Routes.plan(newId)) {
                            popUpTo(Routes.ROUTINE_CREATE) { inclusive = true }
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // Alarm setup is no longer a destination: it is a section of settings.
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "${Routes.PLAN}/{${Routes.ARG_ROUTINE_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_ROUTINE_ID) { type = NavType.LongType },
            ),
        ) {
            PlanScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Routes.RUN}/{${Routes.ARG_ROUTINE_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_ROUTINE_ID) { type = NavType.LongType },
            ),
        ) {
            // RunViewModel reads the routine id from the nav arguments itself
            // via SavedStateHandle, so nothing needs to be passed in here.
            RunScreen(
                onBack = {
                    // Remember which run was ended so a late session re-check
                    // cannot bounce the user back into it.
                    exitedRunId = navController.currentBackStackEntry
                        ?.arguments?.getLong(Routes.ARG_ROUTINE_ID)
                    // Always land on the routine list (the app home) after
                    // ending a run. A plain popBackStack() left the user on the
                    // run screen when it was the start destination (launched
                    // from an alarm), and returned to the plan screen rather
                    // than home when the run was opened from there. Navigating
                    // to the list and clearing everything above it fixes both.
                    navController.navigate(Routes.ROUTINE_LIST) {
                        popUpTo(Routes.ROUTINE_LIST) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/**
 * Converts the creation screen's staged drafts into domain tasks.
 *
 * Positions follow list order because the repository renumbers them on insert,
 * and every task starts with no alarm offset beyond the flag the user chose.
 */
private fun List<DraftTask>.toPlacedTasks(): List<PlacedTask> = mapIndexed { index, draft ->
    PlacedTask(
        routineId = 0L,
        name = draft.name,
        minutes = draft.minutes,
        color = draft.color,
        position = index,
        alarmEnabled = draft.alarmEnabled,
        icon = draft.icon,
    )
}

/** Keeps the argument name in one place for [PlanViewModel]. */
internal val planRoutineIdArg: String = PlanViewModel.ARG_ROUTINE_ID
