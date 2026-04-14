package com.crasuna.medicalrecord

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint

private const val ENCOUNTERS_ROUTE = "encounters"
private const val ENCOUNTER_FORM_ROUTE = "encounterForm"
private const val ENCOUNTER_DETAIL_ROUTE = "encounterDetail"
private const val ATTACHMENT_PREVIEW_ROUTE = "attachmentPreview"
private const val MEDICATIONS_ROUTE = "medications"
private const val MEDICATION_FORM_ROUTE = "medicationForm"

data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var openMedicationId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMedicationId = intent.extractMedicationId()
        enableEdgeToEdge()
        setContent {
            MedicalRecordTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MedicalRecordAppRoot(
                        openMedicationId = openMedicationId,
                        onOpenMedicationHandled = { openMedicationId = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openMedicationId = intent.extractMedicationId()
    }
}

@Composable
private fun MedicalRecordTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}

@Composable
private fun MedicalRecordAppRoot(
    openMedicationId: String?,
    onOpenMedicationHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val destinations = listOf(
        TopLevelDestination(ENCOUNTERS_ROUTE, R.string.nav_encounters, Icons.Outlined.EventNote),
        TopLevelDestination(MEDICATIONS_ROUTE, R.string.nav_medications, Icons.Outlined.Medication),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = destinations.any { top ->
        currentDestination?.hierarchy?.any { it.route == top.route } == true
    }

    LaunchedEffect(openMedicationId) {
        if (!openMedicationId.isNullOrBlank()) {
            navController.navigate(MEDICATIONS_ROUTE) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
            navController.navigate("$MEDICATION_FORM_ROUTE?medicationId=$openMedicationId")
            onOpenMedicationHandled()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ENCOUNTERS_ROUTE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ENCOUNTERS_ROUTE) {
                EncounterListRoute(
                    onCreateEncounter = { navController.navigate(ENCOUNTER_FORM_ROUTE) },
                    onOpenEncounter = { id -> navController.navigate("$ENCOUNTER_DETAIL_ROUTE/$id") },
                )
            }
            composable(
                route = "$ENCOUNTER_FORM_ROUTE?encounterId={encounterId}",
                arguments = listOf(
                    navArgument("encounterId") {
                        nullable = true
                        type = NavType.StringType
                        defaultValue = null
                    },
                ),
            ) {
                EncounterEditorRoute(
                    onNavigateBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.navigate("$ENCOUNTER_DETAIL_ROUTE/$id") {
                            popUpTo(ENCOUNTERS_ROUTE)
                        }
                    },
                )
            }
            composable(
                route = "$ENCOUNTER_DETAIL_ROUTE/{encounterId}",
                arguments = listOf(navArgument("encounterId") { type = NavType.StringType }),
            ) {
                EncounterDetailRoute(
                    onNavigateBack = { navController.popBackStack() },
                    onEditEncounter = { id -> navController.navigate("$ENCOUNTER_FORM_ROUTE?encounterId=$id") },
                    onAttachmentPreview = { id -> navController.navigate("$ATTACHMENT_PREVIEW_ROUTE/$id") },
                    onEncounterDeleted = {
                        navController.popBackStack(ENCOUNTERS_ROUTE, false)
                    },
                )
            }
            composable(
                route = "$ATTACHMENT_PREVIEW_ROUTE/{attachmentId}",
                arguments = listOf(navArgument("attachmentId") { type = NavType.StringType }),
            ) {
                AttachmentPreviewRoute(onNavigateBack = { navController.popBackStack() })
            }
            composable(MEDICATIONS_ROUTE) {
                MedicationListRoute(
                    onCreateMedication = { navController.navigate(MEDICATION_FORM_ROUTE) },
                    onEditMedication = { id -> navController.navigate("$MEDICATION_FORM_ROUTE?medicationId=$id") },
                )
            }
            composable(
                route = "$MEDICATION_FORM_ROUTE?medicationId={medicationId}",
                arguments = listOf(
                    navArgument("medicationId") {
                        nullable = true
                        type = NavType.StringType
                        defaultValue = null
                    },
                ),
            ) {
                MedicationEditorRoute(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

private fun Intent?.extractMedicationId(): String? {
    return this?.getStringExtra(EXTRA_OPEN_MEDICATION_ID)?.takeIf { it.isNotBlank() }
}
