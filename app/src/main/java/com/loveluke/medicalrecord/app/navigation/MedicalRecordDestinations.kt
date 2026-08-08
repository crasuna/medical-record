package com.loveluke.medicalrecord.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination : NavKey

@Serializable
data object EncounterListDestination : NavKey

@Serializable
data class EncounterDetailDestination(val encounterId: String) : NavKey

@Serializable
data class EncounterEditorDestination(val encounterId: String? = null) : NavKey

@Serializable
data class AttachmentPreviewDestination(
    val encounterId: String,
    val attachmentId: String,
) : NavKey

@Serializable
data object MedicationListDestination : NavKey

@Serializable
data class MedicationDetailDestination(val medicationId: String) : NavKey

@Serializable
data class MedicationEditorDestination(val medicationId: String? = null) : NavKey

enum class TopLevelDestination {
    HOME,
    ENCOUNTERS,
    MEDICATIONS,
}

fun TopLevelDestination.startKey(): NavKey = when (this) {
    TopLevelDestination.HOME -> HomeDestination
    TopLevelDestination.ENCOUNTERS -> EncounterListDestination
    TopLevelDestination.MEDICATIONS -> MedicationListDestination
}
