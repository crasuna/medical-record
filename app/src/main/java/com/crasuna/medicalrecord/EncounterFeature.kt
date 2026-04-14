package com.crasuna.medicalrecord

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

data class EncounterFormState(
    val id: String? = null,
    val visitDate: LocalDate = LocalDate.now(),
    val visitTime: LocalTime? = null,
    val hospital: String = "",
    val department: String = "",
    val doctor: String = "",
    val chiefComplaint: String = "",
    val diagnosis: String = "",
    val disposition: String = "",
    val notes: String = "",
    val isLoading: Boolean = false,
) {
    fun toEntity(): EncounterEntity {
        return EncounterEntity(
            id = id ?: java.util.UUID.randomUUID().toString(),
            visitDate = visitDate,
            visitTime = visitTime,
            hospital = hospital.trim(),
            department = department.trim().ifBlank { null },
            doctor = doctor.trim().ifBlank { null },
            chiefComplaint = chiefComplaint.trim().ifBlank { null },
            diagnosis = diagnosis.trim().ifBlank { null },
            disposition = disposition.trim().ifBlank { null },
            notes = notes.trim().ifBlank { null },
        )
    }
}

@HiltViewModel
class EncountersViewModel @Inject constructor(
    encounterRepository: EncounterRepository,
) : ViewModel() {
    val encounters = encounterRepository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@HiltViewModel
class EncounterEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val encounterRepository: EncounterRepository,
) : ViewModel() {
    private val encounterId: String? = savedStateHandle["encounterId"]
    private val _formState = MutableStateFlow(EncounterFormState(isLoading = encounterId != null))
    val formState: StateFlow<EncounterFormState> = _formState.asStateFlow()

    init {
        if (encounterId != null) {
            viewModelScope.launch {
                encounterRepository.getEncounter(encounterId)?.let { encounter ->
                    _formState.value = EncounterFormState(
                        id = encounter.id,
                        visitDate = encounter.visitDate,
                        visitTime = encounter.visitTime,
                        hospital = encounter.hospital,
                        department = encounter.department.orEmpty(),
                        doctor = encounter.doctor.orEmpty(),
                        chiefComplaint = encounter.chiefComplaint.orEmpty(),
                        diagnosis = encounter.diagnosis.orEmpty(),
                        disposition = encounter.disposition.orEmpty(),
                        notes = encounter.notes.orEmpty(),
                        isLoading = false,
                    )
                } ?: _formState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun update(transform: (EncounterFormState) -> EncounterFormState) {
        _formState.update(transform)
    }

    suspend fun save(): String = encounterRepository.saveEncounter(formState.value.toEntity())
}

@HiltViewModel
class EncounterDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val encounterRepository: EncounterRepository,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {
    private val encounterId: String = checkNotNull(savedStateHandle["encounterId"])

    val detail = encounterRepository.observeEncounter(encounterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun importAttachment(uri: Uri, fallbackName: String? = null, forcedMimeType: String? = null) {
        attachmentRepository.importAttachment(encounterId, uri, fallbackName, forcedMimeType)
    }

    suspend fun deleteAttachment(attachmentId: String) {
        attachmentRepository.deleteAttachment(attachmentId)
    }

    suspend fun deleteEncounter() {
        encounterRepository.deleteEncounterCascade(encounterId)
    }

    suspend fun prepareThumbnail(path: String?): File? = attachmentRepository.prepareThumbnail(path)
}

data class AttachmentPreviewState(
    val attachment: EncounterAttachmentEntity? = null,
    val previewFile: File? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class AttachmentPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {
    private val attachmentId: String = checkNotNull(savedStateHandle["attachmentId"])
    private val _state = MutableStateFlow(AttachmentPreviewState())
    val state: StateFlow<AttachmentPreviewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val attachment = attachmentRepository.getAttachment(attachmentId)
            val preview = attachmentRepository.preparePreview(attachmentId)
            _state.value = AttachmentPreviewState(attachment = attachment, previewFile = preview, isLoading = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncounterListRoute(
    onCreateEncounter: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    viewModel: EncountersViewModel = hiltViewModel(),
) {
    val encounters by viewModel.encounters.collectAsState()
    val totalAttachments = encounters.sumOf { it.attachmentCount }

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(title = stringResource(R.string.screen_encounters_title))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateEncounter) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cd_add_encounter))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedicalRecordHeroCard(
                title = stringResource(R.string.screen_encounters_title),
                subtitle = stringResource(R.string.encounters_hero_subtitle),
                icon = Icons.Outlined.LocalHospital,
                trailing = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MedicalRecordInfoPill(text = encounters.size.toString(), accent = true)
                        MedicalRecordInfoPill(
                            text = pluralStringResource(
                                R.plurals.attachments_count,
                                totalAttachments,
                                totalAttachments,
                            ),
                            icon = Icons.Outlined.AttachFile,
                        )
                    }
                },
            )

            if (encounters.isEmpty()) {
                MedicalRecordEmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    title = stringResource(R.string.empty_no_encounters_title),
                    subtitle = stringResource(R.string.empty_no_encounters_subtitle),
                    icon = Icons.Outlined.LocalHospital,
                )
            } else {
                encounters.forEach { encounter ->
                    val visitSummary = encounter.visitDate.format(dateFormatter) +
                        (encounter.visitTime?.let { " ${it.format(timeFormatter)}" } ?: "")
                    val departmentAndDoctor = listOfNotNull(encounter.department, encounter.doctor)
                        .joinToString(" / ")
                        .ifBlank { stringResource(R.string.encounter_department_and_doctor_not_set) }
                    val diagnosis = encounter.diagnosis ?: stringResource(R.string.encounter_diagnosis_not_set)

                    MedicalRecordSurfaceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenEncounter(encounter.id) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.LocalHospital, contentDescription = null)
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            encounter.hospital,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            departmentAndDoctor,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        diagnosis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            MedicalRecordInfoPill(
                                text = visitSummary,
                                icon = Icons.Outlined.Schedule,
                                accent = true,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MedicalRecordInfoPill(
                                text = pluralStringResource(
                                    R.plurals.attachments_count,
                                    encounter.attachmentCount,
                                    encounter.attachmentCount,
                                ),
                                icon = Icons.Outlined.AttachFile,
                            )
                            AssistChip(
                                onClick = { onOpenEncounter(encounter.id) },
                                label = { Text(stringResource(R.string.action_open_record)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncounterEditorRoute(
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: EncounterEditorViewModel = hiltViewModel(),
) {
    val formState by viewModel.formState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(
                title = stringResource(
                    if (formState.id == null) R.string.new_encounter else R.string.edit_encounter,
                ),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        if (formState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MedicalRecordHeroCard(
                    title = stringResource(
                        if (formState.id == null) R.string.new_encounter else R.string.edit_encounter,
                    ),
                    subtitle = stringResource(R.string.encounter_form_hero_subtitle),
                    icon = Icons.Outlined.LocalHospital,
                )
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_visit_basics),
                    subtitle = stringResource(R.string.section_visit_basics_subtitle),
                ) {
                    DateAndTimeSection(
                        visitDate = formState.visitDate,
                        visitTime = formState.visitTime,
                        onDateClick = {
                            context.pickEncounterDate(formState.visitDate) { selected ->
                                viewModel.update { it.copy(visitDate = selected) }
                            }
                        },
                        onTimeClick = {
                            context.pickEncounterTime(formState.visitTime) { selected ->
                                viewModel.update { it.copy(visitTime = selected) }
                            }
                        },
                        onClearTime = { viewModel.update { it.copy(visitTime = null) } },
                    )
                    MedicalRecordTextField(
                        value = formState.hospital,
                        onValueChange = { value: String -> viewModel.update { it.copy(hospital = value) } },
                        label = stringResource(R.string.label_hospital_required),
                        singleLine = true,
                    )
                    MedicalRecordTextField(
                        value = formState.department,
                        onValueChange = { value: String -> viewModel.update { it.copy(department = value) } },
                        label = stringResource(R.string.label_department),
                        singleLine = true,
                    )
                    MedicalRecordTextField(
                        value = formState.doctor,
                        onValueChange = { value: String -> viewModel.update { it.copy(doctor = value) } },
                        label = stringResource(R.string.label_doctor),
                        singleLine = true,
                    )
                }
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_clinical_notes),
                    subtitle = stringResource(R.string.section_clinical_notes_subtitle),
                ) {
                    MedicalRecordTextField(
                        value = formState.chiefComplaint,
                        onValueChange = { value: String -> viewModel.update { it.copy(chiefComplaint = value) } },
                        label = stringResource(R.string.label_chief_complaint),
                        minLines = 2,
                    )
                    MedicalRecordTextField(
                        value = formState.diagnosis,
                        onValueChange = { value: String -> viewModel.update { it.copy(diagnosis = value) } },
                        label = stringResource(R.string.label_diagnosis),
                        minLines = 2,
                    )
                    MedicalRecordTextField(
                        value = formState.disposition,
                        onValueChange = { value: String -> viewModel.update { it.copy(disposition = value) } },
                        label = stringResource(R.string.label_disposition),
                        minLines = 2,
                    )
                    MedicalRecordTextField(
                        value = formState.notes,
                        onValueChange = { value: String -> viewModel.update { it.copy(notes = value) } },
                        label = stringResource(R.string.label_notes),
                        minLines = 3,
                    )
                }
                MedicalRecordPrimaryButton(
                    text = stringResource(R.string.action_save_encounter),
                    onClick = { scope.launch { onSaved(viewModel.save()) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formState.hospital.isNotBlank(),
                )
                Text(
                    stringResource(R.string.encounter_attachments_after_saving),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EncounterDetailRoute(
    onNavigateBack: () -> Unit,
    onEditEncounter: (String) -> Unit,
    onAttachmentPreview: (String) -> Unit,
    onEncounterDeleted: () -> Unit,
    viewModel: EncounterDetailViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDeleteEncounter by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var deletingAttachmentId by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                scope.launch {
                    viewModel.importAttachment(
                        uri = uri,
                        fallbackName = context.getString(R.string.camera_attachment_name, System.currentTimeMillis()),
                        forcedMimeType = "image/jpeg",
                    )
                }
            }
        }
    }
    val imageImporter = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) scope.launch { viewModel.importAttachment(uri) }
    }
    val pdfImporter = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) scope.launch { viewModel.importAttachment(uri, forcedMimeType = "application/pdf") }
    }

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(
                title = detail?.encounter?.hospital ?: stringResource(R.string.screen_encounter_detail_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    detail?.encounter?.id?.let { encounterId ->
                        IconButton(onClick = { onEditEncounter(encounterId) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                    IconButton(onClick = { confirmDeleteEncounter = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (detail == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val encounter = detail!!.encounter
            val attachments = detail!!.attachments.sortedByDescending { it.createdAt }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MedicalRecordHeroCard(
                    title = encounter.hospital,
                    subtitle = encounter.visitDate.format(dateFormatter) +
                        (encounter.visitTime?.let { " ${it.format(timeFormatter)}" } ?: ""),
                    icon = Icons.Outlined.LocalHospital,
                    trailing = {
                        MedicalRecordInfoPill(
                            text = pluralStringResource(
                                R.plurals.attachments_count,
                                attachments.size,
                                attachments.size,
                            ),
                            icon = Icons.Outlined.AttachFile,
                            accent = true,
                        )
                    },
                )
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_visit_basics),
                    subtitle = stringResource(R.string.section_visit_basics_detail_subtitle),
                ) {
                    LabeledValue(
                        stringResource(R.string.label_visit_time),
                        encounter.visitDate.format(dateFormatter) +
                            (encounter.visitTime?.let { " ${it.format(timeFormatter)}" } ?: ""),
                    )
                    LabeledValue(stringResource(R.string.label_hospital), encounter.hospital)
                    LabeledValue(
                        stringResource(R.string.label_department),
                        encounter.department ?: stringResource(R.string.value_not_set),
                    )
                    LabeledValue(
                        stringResource(R.string.label_doctor),
                        encounter.doctor ?: stringResource(R.string.value_not_set),
                    )
                }
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_clinical_notes),
                    subtitle = stringResource(R.string.section_clinical_notes_detail_subtitle),
                ) {
                    LabeledValue(
                        stringResource(R.string.label_chief_complaint),
                        encounter.chiefComplaint ?: stringResource(R.string.value_not_set),
                    )
                    LabeledValue(
                        stringResource(R.string.label_diagnosis),
                        encounter.diagnosis ?: stringResource(R.string.value_not_set),
                    )
                    LabeledValue(
                        stringResource(R.string.label_disposition),
                        encounter.disposition ?: stringResource(R.string.value_not_set),
                    )
                    LabeledValue(
                        stringResource(R.string.label_notes),
                        encounter.notes ?: stringResource(R.string.value_not_set),
                    )
                }

                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_attachments),
                    subtitle = stringResource(R.string.section_attachments_subtitle),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val uri = createCameraOutputUri(context)
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_capture))
                        }
                        OutlinedButton(onClick = { imageImporter.launch("image/*") }) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_import_image))
                        }
                        OutlinedButton(onClick = { pdfImporter.launch("application/pdf") }) {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_import_pdf))
                        }
                    }
                    if (attachments.isEmpty()) {
                        Text(
                            stringResource(R.string.empty_no_attachments),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        attachments.forEach { attachment ->
                            val thumbnail by produceState<File?>(initialValue = null, key1 = attachment.thumbnailPath) {
                                this.value = viewModel.prepareThumbnail(attachment.thumbnailPath)
                            }
                            AttachmentListItem(
                                attachment = attachment,
                                thumbnailFile = thumbnail,
                                onOpen = { onAttachmentPreview(attachment.id) },
                                onDelete = { deletingAttachmentId = attachment.id },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteEncounter) {
        AlertDialog(
            onDismissRequest = { confirmDeleteEncounter = false },
            title = { Text(stringResource(R.string.dialog_delete_encounter_title)) },
            text = { Text(stringResource(R.string.dialog_delete_encounter_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            confirmDeleteEncounter = false
                            viewModel.deleteEncounter()
                            onEncounterDeleted()
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteEncounter = false }) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            },
        )
    }

    if (deletingAttachmentId != null) {
        AlertDialog(
            onDismissRequest = { deletingAttachmentId = null },
            title = { Text(stringResource(R.string.dialog_delete_attachment_title)) },
            text = { Text(stringResource(R.string.dialog_delete_attachment_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val attachmentId = deletingAttachmentId
                        if (attachmentId != null) {
                            scope.launch {
                                viewModel.deleteAttachment(attachmentId)
                                deletingAttachmentId = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAttachmentId = null }) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPreviewRoute(
    onNavigateBack: () -> Unit,
    viewModel: AttachmentPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var currentPage by remember { mutableIntStateOf(0) }

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(
                title = state.attachment?.displayName ?: stringResource(R.string.screen_attachment_preview_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator()
                state.previewFile == null || state.attachment == null -> EmptyState(
                    title = stringResource(R.string.empty_preview_unavailable_title),
                    subtitle = stringResource(R.string.empty_preview_unavailable_subtitle),
                )
                state.attachment?.type == AttachmentType.IMAGE -> {
                    AsyncImage(
                        model = state.previewFile,
                        contentDescription = state.attachment?.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                else -> PdfPreview(
                    file = state.previewFile,
                    pageCount = state.attachment?.pageCount ?: 0,
                    currentPage = currentPage,
                    onPageChange = { currentPage = it },
                )
            }
        }
    }
}

@Composable
private fun AttachmentListItem(
    attachment: EncounterAttachmentEntity,
    thumbnailFile: File?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnailFile != null) {
                AsyncImage(
                    model = thumbnailFile,
                    contentDescription = attachment.displayName,
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White, RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (attachment.type == AttachmentType.PDF) Icons.Outlined.Description else Icons.Outlined.Image,
                        contentDescription = null,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    attachment.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MedicalRecordInfoPill(
                    text = if (attachment.type == AttachmentType.PDF) {
                        pluralStringResource(
                            R.plurals.attachment_pdf_pages,
                            attachment.pageCount ?: 0,
                            attachment.pageCount ?: 0,
                        )
                    } else {
                        stringResource(R.string.attachment_image_type)
                    },
                    icon = if (attachment.type == AttachmentType.PDF) {
                        Icons.Outlined.Description
                    } else {
                        Icons.Outlined.Image
                    },
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete_attachment))
            }
        }
    }
}

@Composable
private fun PdfPreview(
    file: File?,
    pageCount: Int,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
) {
    val pageBitmap by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath, key2 = currentPage) {
        value = null
        if (file != null && pageCount > 0) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val safePage = currentPage.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safePage).use { page ->
                        val width = 1200
                        val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        value = bitmap
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onPageChange((currentPage - 1).coerceAtLeast(0)) }, enabled = currentPage > 0) {
                    Text(stringResource(R.string.action_previous))
                }
                Text(stringResource(R.string.pdf_page_indicator, currentPage + 1, pageCount))
                OutlinedButton(
                    onClick = { onPageChange((currentPage + 1).coerceAtMost(pageCount - 1)) },
                    enabled = currentPage < pageCount - 1,
                ) {
                    Text(stringResource(R.string.action_next))
                }
            }
        }
        if (pageBitmap == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = pageBitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pdf_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun DateAndTimeSection(
    visitDate: LocalDate,
    visitTime: LocalTime?,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onClearTime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.section_visit_date_time),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDateClick, modifier = Modifier.weight(1f)) {
                Text(visitDate.format(dateFormatter))
            }
            OutlinedButton(onClick = onTimeClick, modifier = Modifier.weight(1f)) {
                Text(visitTime?.format(timeFormatter) ?: stringResource(R.string.action_select_time))
            }
        }
        if (visitTime != null) {
            TextButton(onClick = onClearTime) { Text(stringResource(R.string.action_clear_time)) }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
) {
    MedicalRecordEmptyState(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        icon = Icons.Outlined.LocalHospital,
    )
}

private fun createCameraOutputUri(context: Context): Uri {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(directory, "camera_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun Context.pickEncounterDate(initialDate: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
        this,
        { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth,
    ).show()
}

private fun Context.pickEncounterTime(initialTime: LocalTime?, onPicked: (LocalTime) -> Unit) {
    val seed = initialTime ?: LocalTime.now()
    TimePickerDialog(
        this,
        { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
        seed.hour,
        seed.minute,
        true,
    ).show()
}
