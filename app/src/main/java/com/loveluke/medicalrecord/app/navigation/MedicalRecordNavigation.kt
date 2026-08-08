package com.loveluke.medicalrecord.app.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.feature.encounter.AttachmentPreviewRoute
import com.loveluke.medicalrecord.feature.encounter.EncounterDetailRoute
import com.loveluke.medicalrecord.feature.encounter.EncounterEditorRoute
import com.loveluke.medicalrecord.feature.encounter.EncounterListRoute
import com.loveluke.medicalrecord.feature.home.HomeRoute
import com.loveluke.medicalrecord.feature.medication.MedicationDetailRoute
import com.loveluke.medicalrecord.feature.medication.MedicationEditorRoute
import com.loveluke.medicalrecord.feature.medication.MedicationListRoute

@Composable
fun MedicalRecordApp(
    patientId: String,
    pendingMedicationId: String?,
    onPendingMedicationConsumed: () -> Unit,
    onMedicationScheduleChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val verticalHinge = rememberSeparatingVerticalHinge(context)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        var topLevel by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
        val homeBackStack = rememberNavBackStack(HomeDestination)
        val encounterBackStack = rememberNavBackStack(EncounterListDestination)
        val medicationBackStack = rememberNavBackStack(MedicationListDestination)
        val currentBackStack = when (topLevel) {
            TopLevelDestination.HOME -> homeBackStack
            TopLevelDestination.ENCOUNTERS -> encounterBackStack
            TopLevelDestination.MEDICATIONS -> medicationBackStack
        }
        val sceneStrategy = rememberMedicalListDetailSceneStrategy<NavKey>(
            isWide = isWide,
            separatingVerticalHingeBounds = verticalHinge,
        )

        LaunchedEffect(pendingMedicationId) {
            val medicationId = pendingMedicationId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
            topLevel = TopLevelDestination.MEDICATIONS
            medicationBackStack.openMedicationDetail(medicationId)
            onPendingMedicationConsumed()
        }

        val navDisplay: @Composable () -> Unit = {
            NavDisplay(
                backStack = currentBackStack,
                onBack = {
                    if (currentBackStack.size > 1) {
                        currentBackStack.removeLastOrNull()
                    } else if (topLevel != TopLevelDestination.HOME) {
                        topLevel = TopLevelDestination.HOME
                    }
                },
                modifier = Modifier.fillMaxSize(),
                sceneStrategies = listOf(sceneStrategy),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<HomeDestination> {
                        HomeRoute(
                            onCreateEncounter = {
                                topLevel = TopLevelDestination.ENCOUNTERS
                                encounterBackStack.add(EncounterEditorDestination())
                            },
                            onCreateMedication = {
                                topLevel = TopLevelDestination.MEDICATIONS
                                medicationBackStack.add(MedicationEditorDestination())
                            },
                            onOpenEncounter = { encounterId ->
                                topLevel = TopLevelDestination.ENCOUNTERS
                                encounterBackStack.openEncounterDetail(encounterId)
                            },
                            onOpenMedication = { medicationId ->
                                topLevel = TopLevelDestination.MEDICATIONS
                                medicationBackStack.openMedicationDetail(medicationId)
                            },
                            onOpenEncounters = { topLevel = TopLevelDestination.ENCOUNTERS },
                            onOpenMedications = { topLevel = TopLevelDestination.MEDICATIONS },
                        )
                    }
                    entry<EncounterListDestination>(
                        metadata = MedicalListDetailScene.listPane(MedicalPaneGroup.ENCOUNTER),
                    ) {
                        EncounterListRoute(
                            onCreate = { encounterBackStack.add(EncounterEditorDestination()) },
                            onOpen = encounterBackStack::openEncounterDetail,
                        )
                    }
                    entry<EncounterDetailDestination>(
                        metadata = MedicalListDetailScene.detailPane(MedicalPaneGroup.ENCOUNTER),
                    ) { destination ->
                        EncounterDetailRoute(
                            patientId = patientId,
                            encounterId = destination.encounterId,
                            onBack = { encounterBackStack.removeLastOrNull() },
                            onEdit = { encounterBackStack.add(EncounterEditorDestination(it)) },
                            onDeleted = { encounterBackStack.removeLastOrNull() },
                            onOpenAttachment = { encounterId, attachmentId ->
                                encounterBackStack.add(AttachmentPreviewDestination(encounterId, attachmentId))
                            },
                        )
                    }
                    entry<EncounterEditorDestination> { destination ->
                        EncounterEditorRoute(
                            encounterId = destination.encounterId,
                            onBack = { encounterBackStack.removeLastOrNull() },
                            onSaved = encounterBackStack::openEncounterDetail,
                        )
                    }
                    entry<AttachmentPreviewDestination> { destination ->
                        AttachmentPreviewRoute(
                            patientId = patientId,
                            encounterId = destination.encounterId,
                            attachmentId = destination.attachmentId,
                            onBack = { encounterBackStack.removeLastOrNull() },
                            onDeleted = { encounterBackStack.removeLastOrNull() },
                        )
                    }
                    entry<MedicationListDestination>(
                        metadata = MedicalListDetailScene.listPane(MedicalPaneGroup.MEDICATION),
                    ) {
                        MedicationListRoute(
                            onCreate = { medicationBackStack.add(MedicationEditorDestination()) },
                            onOpen = medicationBackStack::openMedicationDetail,
                        )
                    }
                    entry<MedicationDetailDestination>(
                        metadata = MedicalListDetailScene.detailPane(MedicalPaneGroup.MEDICATION),
                    ) { destination ->
                        MedicationDetailRoute(
                            medicationId = destination.medicationId,
                            onBack = { medicationBackStack.removeLastOrNull() },
                            onEdit = { medicationBackStack.add(MedicationEditorDestination(it)) },
                            onDeleted = {
                                onMedicationScheduleChanged()
                                medicationBackStack.removeLastOrNull()
                            },
                        )
                    }
                    entry<MedicationEditorDestination> { destination ->
                        MedicationEditorRoute(
                            medicationId = destination.medicationId,
                            onBack = { medicationBackStack.removeLastOrNull() },
                            onSaved = { medicationId ->
                                onMedicationScheduleChanged()
                                medicationBackStack.openMedicationDetail(medicationId)
                            },
                        )
                    }
                },
            )
        }

        val attachmentPreviewVisible = currentBackStack.lastOrNull() is AttachmentPreviewDestination
        if (attachmentPreviewVisible) {
            Surface(modifier = Modifier.fillMaxSize()) { navDisplay() }
        } else {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    item(
                        selected = topLevel == TopLevelDestination.HOME,
                        onClick = { topLevel = TopLevelDestination.HOME },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home)) },
                    )
                    item(
                        selected = topLevel == TopLevelDestination.ENCOUNTERS,
                        onClick = { topLevel = TopLevelDestination.ENCOUNTERS },
                        icon = { Icon(Icons.AutoMirrored.Outlined.EventNote, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_encounters)) },
                    )
                    item(
                        selected = topLevel == TopLevelDestination.MEDICATIONS,
                        onClick = { topLevel = TopLevelDestination.MEDICATIONS },
                        icon = { Icon(Icons.Outlined.Medication, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_medications)) },
                    )
                },
                layoutType = if (isWide) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
            ) {
                navDisplay()
            }
        }
    }
}

private fun NavBackStack<NavKey>.openEncounterDetail(encounterId: String) {
    removeIf { it is EncounterDetailDestination || it is EncounterEditorDestination || it is AttachmentPreviewDestination }
    add(EncounterDetailDestination(encounterId))
}

private fun NavBackStack<NavKey>.openMedicationDetail(medicationId: String) {
    removeIf { it is MedicationDetailDestination || it is MedicationEditorDestination }
    add(MedicationDetailDestination(medicationId))
}

@Composable
private fun rememberSeparatingVerticalHinge(context: Context): Rect? {
    val activity = remember(context) { context.findActivity() } ?: return null
    val layoutInfo by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsStateWithLifecycle(initialValue = null)
    return layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { feature ->
            feature.orientation == FoldingFeature.Orientation.VERTICAL &&
                (feature.isSeparating || feature.occlusionType == FoldingFeature.OcclusionType.FULL)
        }
        ?.bounds
        ?.let(::Rect)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
