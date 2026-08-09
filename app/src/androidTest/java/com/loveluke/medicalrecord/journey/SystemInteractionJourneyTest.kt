package com.loveluke.medicalrecord.journey

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.loveluke.medicalrecord.app.MainActivity
import com.loveluke.medicalrecord.app.testing.MedicalRecordTestTags
import com.loveluke.medicalrecord.core.reminder.ReminderAlarmReceiver
import com.loveluke.medicalrecord.core.reminder.ReminderNotificationPublisher
import com.loveluke.medicalrecord.core.reminder.ReminderSchedulingState
import com.loveluke.medicalrecord.core.reminder.SystemReminderScheduler
import com.loveluke.medicalrecord.e2e.E2eFixture
import com.loveluke.medicalrecord.test.SystemInteraction
import com.loveluke.medicalrecord.test.SystemInteractionTest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SystemInteraction
class SystemInteractionJourneyTest : SystemInteractionTest() {
    @get:Rule(order = 0)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val fixture: E2eFixture by lazy { E2eFixture.from(context) }

    @Test
    fun photoPickerImportsSelectedMediaIntoEncryptedStorage() {
        val marker = marker("photo-picker")
        val encounter = runBlocking {
            fixture.awaitReady()
            fixture.seedEncounter(marker)
        }
        var insertedUri: android.net.Uri? = null
        try {
            insertedUri = insertPickerJpeg(marker)
            assertNotNull(insertedUri)

            composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
            waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
            waitForText(encounter.hospital)
            composeRule.onNodeWithText(encounter.hospital).performClick()
            waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
            composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DETAIL_CONTENT)
                .performScrollToNode(hasTestTag(MedicalRecordTestTags.ATTACHMENT_PHOTO_PICKER))
            composeRule.onNodeWithTag(MedicalRecordTestTags.ATTACHMENT_PHOTO_PICKER).performClick()

            selectPhotoPickerItem()
            waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
            val attachment = runBlocking { fixture.awaitSingleAttachment(encounter.id) }
            waitForText(attachment.displayName)
            val plaintext = runBlocking { fixture.readDecryptedAttachment(attachment) }
            val decoded = checkNotNull(
                BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size),
            ) { "The Photo Picker attachment did not decrypt as a JPEG." }
            try {
                assertEquals(PICKER_FIXTURE_SIZE, decoded.width)
                assertEquals(PICKER_FIXTURE_SIZE, decoded.height)
                val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                assertTrue(kotlin.math.abs(Color.red(center) - PICKER_FIXTURE_RED) <= 12)
                assertTrue(kotlin.math.abs(Color.green(center) - PICKER_FIXTURE_GREEN) <= 12)
                assertTrue(kotlin.math.abs(Color.blue(center) - PICKER_FIXTURE_BLUE) <= 12)
            } finally {
                decoded.recycle()
            }
            val ciphertext = fixture.encryptedAttachmentFile(attachment)
            assertTrue(ciphertext.isFile)
            val header = ciphertext.inputStream().use { input -> input.readNBytes(2) }
            assertFalse(
                header.size == 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte(),
            )
        } finally {
            insertedUri?.let(::deletePickerFixture)
        }
    }

    @Test
    fun reminderReceiverPublishesPrivateNotificationAndNotificationOpensMedication() {
        val marker = marker("notification")
        val hospitalMarker = "医院隐私-$marker"
        val diagnosisMarker = "诊断隐私-$marker"
        val doctorMarker = "医生隐私-$marker"
        val notesMarker = "备注隐私-$marker"
        val medicationName = "提醒药品 $marker"
        val dose = "25 mg $marker"
        val currentInstant = Instant.now()
        val currentZone = ZoneId.systemDefault()

        grantNotificationPermission()
        fixture.freezeTime(currentInstant, currentZone)
        runBlocking {
            fixture.awaitReady()
            fixture.seedEncounter(
                marker = marker,
                hospital = hospitalMarker,
                diagnosis = diagnosisMarker,
                notes = doctorMarker,
            )
        }

        val nextMinute = currentInstant.atZone(currentZone).toLocalTime()
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)
        val reminderText = nextMinute.format(DateTimeFormatter.ofPattern("HH:mm"))

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_MEDICATIONS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_NEW).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_EDITOR)
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_NAME, medicationName)
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_DOSE, dose)
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_FREQUENCY, "每日一次")
        replaceMedicationField(
            MedicalRecordTestTags.MEDICATION_START_DATE,
            fixture.today.toString(),
        )
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_NOTES, notesMarker)
        scrollMedicationFormTo(MedicalRecordTestTags.REMINDER_ADD)
        composeRule.onNodeWithTag(MedicalRecordTestTags.REMINDER_ADD).performClick()
        val reminderTag = MedicalRecordTestTags.reminderTime(0)
        scrollMedicationFormTo(reminderTag)
        composeRule.onNodeWithTag(reminderTag)
            .performTextReplacement(reminderText)
        scrollMedicationFormTo(MedicalRecordTestTags.MEDICATION_SAVE)
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_SAVE).performClick()

        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)
        val medication = runBlocking { fixture.awaitMedication(marker) }
        assertEquals(1, medication.reminders.size)
        val scheduled = runBlocking {
            fixture.reconcileReminders()
            fixture.persistedReminderState()
        } as? ReminderSchedulingState.Scheduled
        checkNotNull(scheduled) { "Reminder did not reach a scheduled state." }

        val receiverIntent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction("${context.packageName}.action.DELIVER_MEDICATION_REMINDER")
            .putExtra(SystemReminderScheduler.EXTRA_REMINDER_ID, scheduled.reminderId)
            .putExtra(
                SystemReminderScheduler.EXTRA_SCHEDULED_AT,
                scheduled.triggerAt.toEpochMilli(),
            )
        context.sendBroadcast(receiverIntent)

        val statusBarNotification = waitForNotification(medicationName)
        val notification = statusBarNotification.notification
        val channelId = "${context.packageName}.medication_reminders"
        assertEquals(channelId, notification.channelId)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(NotificationCompat.CATEGORY_REMINDER, notification.category)
        assertNotNull(notification.contentIntent)
        assertEquals(medicationName, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(dose))

        val publicVersion = checkNotNull(notification.publicVersion)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, publicVersion.visibility)
        assertEquals("用药提醒", publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "打开医疗记录查看提醒。",
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channelId)
        assertNotNull(channel)

        val allRenderedNotificationText = buildString {
            append(notification.extras.toString())
            append(publicVersion.extras.toString())
        }
        listOf(hospitalMarker, diagnosisMarker, doctorMarker, notesMarker).forEach { sensitive ->
            assertFalse("Notification leaked $sensitive", allRenderedNotificationText.contains(sensitive))
        }

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        lateinit var originalScenarioIntent: Intent
        composeRule.activityRule.scenario.onActivity { activity ->
            originalScenarioIntent = Intent(activity.intent)
        }

        device.openNotification()
        check(device.wait(Until.hasObject(By.text(medicationName)), SYSTEM_UI_TIMEOUT_MILLIS)) {
            "Notification shade did not expose the generated medication notification."
        }
        val notificationText = checkNotNull(device.findObject(By.text(medicationName)))
        val notificationBounds = notificationText.visibleBounds
        check(device.click(notificationBounds.centerX(), notificationBounds.centerY())) {
            "UiAutomator could not click the generated medication notification."
        }
        val resumedFromNotification = waitForTargetActivityResumed(SYSTEM_UI_TIMEOUT_MILLIS)
        if (!resumedFromNotification) {
            device.pressBack()
            waitForTargetActivityResumed(SHORT_RETURN_TIMEOUT_MILLIS)
        }
        check(resumedFromNotification) {
            "Notification PendingIntent did not return to the E2E application."
        }
        assertEquals(medication.medication.id, runBlocking { fixture.findMedication(marker) }?.medication?.id)
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals("${context.packageName}.action.OPEN_MEDICATION", activity.intent.action)
            assertEquals(
                medication.medication.id,
                activity.intent.getStringExtra(ReminderNotificationPublisher.EXTRA_MEDICATION_ID),
            )
        }
        waitForText(medicationName)
        composeRule.onNodeWithText(medicationName).assertIsDisplayed()

        // ActivityScenario identifies its activity through data on the original launch Intent.
        // Restore that Intent before recreation/teardown after the real notification onNewIntent.
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.intent = Intent(originalScenarioIntent)
        }

        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun insertPickerJpeg(marker: String): android.net.Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "The deterministic MediaStore fixture requires API 29 or newer."
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$marker.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_FAVORITE, 1)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/MedicalRecordE2E",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = checkNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore rejected the Photo Picker fixture." }
        try {
            context.contentResolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output)
                val bitmap = Bitmap.createBitmap(
                    PICKER_FIXTURE_SIZE,
                    PICKER_FIXTURE_SIZE,
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    bitmap.eraseColor(
                        Color.rgb(
                            PICKER_FIXTURE_RED,
                            PICKER_FIXTURE_GREEN,
                            PICKER_FIXTURE_BLUE,
                        ),
                    )
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                } finally {
                    bitmap.recycle()
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            values.put(MediaStore.Images.Media.IS_FAVORITE, 1)
            check(context.contentResolver.update(uri, values, null, null) == 1)
            check(isFavorite(uri)) { "MediaStore did not persist the Photo Picker favorite fixture." }
            clearPickerFixtureOwner(uri)
            waitForMediaStoreIdle()
            return uri
        } catch (failure: Throwable) {
            runCatching { deletePickerFixture(uri) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun selectPhotoPickerItem() {
        check(device.wait(Until.gone(By.pkg(context.packageName)), SYSTEM_UI_TIMEOUT_MILLIS)) {
            "Photo Picker did not leave the E2E application foreground."
        }
        val pickerPackage = checkNotNull(device.currentPackageName)
        check(pickerPackage != context.packageName) { "Photo Picker was not launched." }
        check(device.wait(Until.hasObject(By.pkg(pickerPackage)), SYSTEM_UI_TIMEOUT_MILLIS)) {
            "Photo Picker package $pickerPackage did not become interactive."
        }

        openPickerFavoritesAlbum(pickerPackage)
        val mediaItem = waitForPickerObject(pickerPackage)
        mediaItem.clickClickableAncestor()

        if (waitForTargetActivityResumed(SHORT_RETURN_TIMEOUT_MILLIS)) {
            return
        }
        val addButton = waitForPickerConfirmationButton(pickerPackage)
        checkNotNull(addButton) {
            "Photo Picker did not expose a language-independent confirmation action."
        }
        addButton.click()
        check(waitForTargetActivityResumed(SYSTEM_UI_TIMEOUT_MILLIS)) {
            "Photo Picker selection did not return to the application."
        }
    }

    private fun openPickerFavoritesAlbum(pickerPackage: String) {
        val tabs = waitForPickerNavigationTabs(pickerPackage)
        tabs.last().click()
        waitForPickerFirstAlbum(pickerPackage).click()
    }

    private fun waitForPickerFirstAlbum(pickerPackage: String): UiObject2 {
        val minimumAlbumSize = (context.resources.displayMetrics.density * 120).toInt()
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObjects(By.pkg(pickerPackage))
                .filter { candidate ->
                    val bounds = candidate.visibleBounds
                    candidate.isEnabled &&
                        candidate.isClickable &&
                        bounds.top < device.displayHeight * 3 / 4 &&
                        bounds.width() >= minimumAlbumSize &&
                        bounds.height() >= minimumAlbumSize
                }
                .minWithOrNull(
                    compareBy<UiObject2>({ it.visibleBounds.top }, { it.visibleBounds.left }),
                )
                ?.let { return it }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        error("Photo Picker did not expose its first system album for the favorite fixture.")
    }

    private fun waitForPickerNavigationTabs(pickerPackage: String): List<UiObject2> {
        val density = context.resources.displayMetrics.density
        val minimumWidth = (density * 48).toInt()
        val maximumWidth = (density * 240).toInt()
        val minimumHeight = (density * 40).toInt()
        val maximumHeight = (density * 80).toInt()
        val coordinateTolerance = (density * 8).toInt()
        val maximumGap = (density * 32).toInt()
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = device.findObjects(By.pkg(pickerPackage))
                .filter { candidate ->
                    val bounds = candidate.visibleBounds
                    candidate.isEnabled &&
                        candidate.isClickable &&
                        bounds.top < device.displayHeight / 2 &&
                        bounds.width() in minimumWidth..maximumWidth &&
                        bounds.height() in minimumHeight..maximumHeight
                }
                .sortedWith(compareBy<UiObject2>({ it.visibleBounds.top }, { it.visibleBounds.left }))
            candidates.forEachIndexed { index, leftTab ->
                candidates.drop(index + 1).forEach { rightTab ->
                    val leftBounds = leftTab.visibleBounds
                    val rightBounds = rightTab.visibleBounds
                    val sameRow = kotlin.math.abs(leftBounds.top - rightBounds.top) <= coordinateTolerance &&
                        kotlin.math.abs(leftBounds.bottom - rightBounds.bottom) <= coordinateTolerance
                    val sameSize = kotlin.math.abs(leftBounds.width() - rightBounds.width()) <=
                        coordinateTolerance * 3
                    val gap = rightBounds.left - leftBounds.right
                    if (sameRow && sameSize && gap in 0..maximumGap) {
                        return listOf(leftTab, rightTab)
                    }
                }
            }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        error("Photo Picker did not expose its language-independent photo/album tab pair.")
    }

    private fun waitForPickerObject(pickerPackage: String): UiObject2 {
        val minimumMediaSize = (context.resources.displayMetrics.density * 96).toInt()
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val mediaItems = device.findObjects(By.pkg(pickerPackage))
                .mapNotNull { candidate ->
                    runCatching {
                        val resource = candidate.resourceName.orEmpty().lowercase()
                        val bounds = candidate.visibleBounds
                        val isLegacyThumbnail =
                            resource.contains("icon_thumbnail") || resource.endsWith("/thumbnail")
                        val isComposeMediaSemantics =
                            candidate.contentDescription.orEmpty().isNotBlank() &&
                                bounds.width() >= minimumMediaSize &&
                                bounds.height() >= minimumMediaSize &&
                                kotlin.math.abs(bounds.width() - bounds.height()) <=
                                maxOf(bounds.width(), bounds.height()) / 4
                        candidate.takeIf { isLegacyThumbnail || isComposeMediaSemantics }
                    }.getOrNull()
                }
                .sortedWith(
                    compareBy<UiObject2>({ it.visibleBounds.top }, { it.visibleBounds.left }),
                )
            mediaItems.firstOrNull()?.let { return it }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        error("Photo Picker did not expose a media-grid item for the generated JPEG fixture.")
    }

    private fun isFavorite(uri: android.net.Uri): Boolean =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.IS_FAVORITE),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        } == true

    private fun waitForPickerConfirmationButton(pickerPackage: String): UiObject2? {
        val minimumActionSize = (context.resources.displayMetrics.density * 48).toInt()
        val actionAreaTop = device.displayHeight * 3 / 4
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObjects(By.pkg(pickerPackage))
                .filter { candidate ->
                    val bounds = candidate.visibleBounds
                    candidate.isEnabled &&
                        candidate.isClickable &&
                        bounds.top >= actionAreaTop &&
                        bounds.width() >= minimumActionSize &&
                        bounds.height() >= minimumActionSize
                }
                .maxByOrNull { candidate -> candidate.visibleBounds.right }
                ?.let { return it }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        return null
    }

    private fun clearPickerFixtureOwner(uri: android.net.Uri) {
        shell("content update --uri $uri --bind owner_package_name:n:")
    }

    private fun waitForMediaStoreIdle() {
        val output = shell("content call --uri content://media --method wait_for_idle")
        check(!output.contains("Error", ignoreCase = true)) {
            "MediaStore did not finish syncing the Photo Picker fixture: ${output.trim()}"
        }
    }

    private fun deletePickerFixture(uri: android.net.Uri) {
        shell("content delete --uri $uri")
        waitForMediaStoreIdle()
    }

    private fun waitForObject(
        packageName: String,
        predicate: (UiObject2) -> Boolean,
    ): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObjects(By.pkg(packageName)).firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        return null
    }

    private fun waitForTargetActivityResumed(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { activity -> activity.packageName == context.packageName }
            }
            if (resumed) return true
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private fun waitForNotification(title: String): android.service.notification.StatusBarNotification {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.uptimeMillis() + SYSTEM_UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            notificationManager.activeNotifications.firstOrNull { status ->
                status.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            }?.let { return it }
            SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
        }
        error("Receiver did not publish the expected notification: $title")
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            check(
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            ) { "POST_NOTIFICATIONS could not be granted to the isolated E2E package." }
        }
    }

    private fun UiObject2.clickClickableAncestor() {
        var candidate: UiObject2? = this
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent
        checkNotNull(candidate) { "System UI node has no clickable ancestor: $this" }.click()
    }

    private fun replaceMedicationField(tag: String, value: String) {
        scrollMedicationFormTo(tag)
        composeRule.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun scrollMedicationFormTo(tag: String) {
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_EDITOR_FORM)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun waitForScreen(tag: String) {
        composeRule.waitUntil(COMPOSE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(COMPOSE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun marker(prefix: String): String =
        "SI-$prefix-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private const val PICKER_FIXTURE_SIZE = 96
        private const val PICKER_FIXTURE_RED = 70
        private const val PICKER_FIXTURE_GREEN = 130
        private const val PICKER_FIXTURE_BLUE = 180
        private const val COMPOSE_TIMEOUT_MILLIS = 20_000L
        private const val SYSTEM_UI_TIMEOUT_MILLIS = 30_000L
        private const val SHORT_RETURN_TIMEOUT_MILLIS = 2_000L
        private const val UI_POLL_INTERVAL_MILLIS = 250L
    }
}
