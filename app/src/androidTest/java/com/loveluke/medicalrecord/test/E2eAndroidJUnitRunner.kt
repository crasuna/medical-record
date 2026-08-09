package com.loveluke.medicalrecord.test

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner

class E2eAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        check(targetContext.packageName == TARGET_APPLICATION_ID) {
            "E2E tests must target $TARGET_APPLICATION_ID, not ${targetContext.packageName}."
        }
        check(context.packageName == TEST_APPLICATION_ID) {
            "Unexpected E2E test APK identity: ${context.packageName}."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags("zh-CN")
        }
    }

    companion object {
        const val TARGET_APPLICATION_ID = "com.loveluke.medicalrecord.e2e"
        const val TEST_APPLICATION_ID = "com.loveluke.medicalrecord.e2e.test"
    }
}
