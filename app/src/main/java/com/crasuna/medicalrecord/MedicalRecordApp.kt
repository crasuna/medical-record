package com.crasuna.medicalrecord

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MedicalRecordApp : Application() {
    @Inject
    lateinit var medicationReminderScheduler: MedicationReminderScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        medicationReminderScheduler.ensureNotificationChannel()
        applicationScope.launch {
            medicationReminderScheduler.rescheduleAll()
        }
    }
}
