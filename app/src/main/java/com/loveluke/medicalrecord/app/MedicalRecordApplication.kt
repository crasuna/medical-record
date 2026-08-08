package com.loveluke.medicalrecord.app

import android.app.Application
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessController
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessState
import com.loveluke.medicalrecord.app.di.ApplicationCoroutineScope
import com.loveluke.medicalrecord.app.reminder.ReminderRuntimeCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MedicalRecordApplication : Application() {
    @Inject
    lateinit var accessController: MedicalRecordAccessController

    @Inject
    lateinit var reminderRuntimeCoordinator: ReminderRuntimeCoordinator

    @Inject
    @ApplicationCoroutineScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        reminderRuntimeCoordinator.installColdStartBridge(accessController)
        applicationScope.launch {
            when (accessController.initialize()) {
                is MedicalRecordAccessState.Ready -> {
                    reminderRuntimeCoordinator.startAndReconcile()
                }

                MedicalRecordAccessState.Initializing,
                MedicalRecordAccessState.Clearing,
                is MedicalRecordAccessState.Locked,
                is MedicalRecordAccessState.RestartRequired,
                -> Unit
            }
        }
    }
}
