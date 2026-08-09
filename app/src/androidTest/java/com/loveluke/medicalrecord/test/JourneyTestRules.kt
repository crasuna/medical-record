package com.loveluke.medicalrecord.test

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement

abstract class CoreJourneyTest {
    // JUnit 4.13 makes higher-order rules inner. Keep evidence capture inside any
    // Compose rule so screenshots and hierarchy dumps run before Activity disposal.
    @get:Rule(order = 1)
    val journeyRules: TestRule = RuleChain
        .outerRule(E2eEnvironmentRule())
        .around(E2eArtifactRule())
        .around(Timeout(CORE_TIMEOUT_MINUTES, TimeUnit.MINUTES))
}

abstract class SystemInteractionTest {
    @get:Rule(order = 1)
    val journeyRules: TestRule = RuleChain
        .outerRule(E2eEnvironmentRule())
        .around(E2eArtifactRule())
        .around(Timeout(SYSTEM_TIMEOUT_MINUTES, TimeUnit.MINUTES))
}

private class E2eEnvironmentRule : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == E2eAndroidJUnitRunner.TARGET_APPLICATION_ID)
        check(instrumentation.context.packageName == E2eAndroidJUnitRunner.TEST_APPLICATION_ID)
    }
}

private class E2eArtifactRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val recorder = ArtifactRecorder(description)
            recorder.start()
            var testFailure: Throwable? = null
            try {
                base.evaluate()
            } catch (failure: Throwable) {
                testFailure = failure
            }

            val diagnosticFailure = recorder.finish(testFailure)
            when {
                testFailure != null -> throw testFailure
                diagnosticFailure != null -> throw diagnosticFailure
            }
        }
    }
}

private class ArtifactRecorder(description: Description) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val storage = PlatformTestStorageRegistry.getInstance()
    private val artifactPrefix = listOf(
        "journeys",
        description.className.substringAfterLast('.').safePathSegment(),
        description.methodName.safePathSegment(),
    ).joinToString("/")
    private val marker = "MEDICAL_RECORD_E2E_${UUID.randomUUID()}"

    fun start() {
        android.util.Log.i(LOG_TAG, marker)
        writeText("device-metadata.txt", deviceMetadata())
    }

    fun finish(testFailure: Throwable?): AssertionError? {
        captureScreenshot(if (testFailure == null) "terminal.png" else "failure.png")
        val logcat = relevantLogcat()
        val diagnostic = crashOrAnrEvidence(logcat)
        if (testFailure != null || diagnostic != null) {
            writeText("logcat.txt", logcat)
            dumpHierarchy()
            testFailure?.let { writeText("exception.txt", it.stackTraceToString()) }
        }
        return diagnostic?.let { evidence ->
            AssertionError("New target-app crash or ANR detected after $marker:\n$evidence")
        }
    }

    private fun captureScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            storage.openOutputFile("$artifactPrefix/$name").use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun dumpHierarchy() {
        val temporary = File.createTempFile("medical-record-e2e-", ".xml", targetContext.cacheDir)
        try {
            device.dumpWindowHierarchy(temporary)
            storage.openOutputFile("$artifactPrefix/ui-hierarchy.xml").use { output ->
                temporary.inputStream().use { input -> input.copyTo(output) }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun relevantLogcat(): String {
        val all = shell("logcat -b all -d -v threadtime")
        val markerIndex = all.lastIndexOf(marker)
        return if (markerIndex >= 0) all.substring(markerIndex) else all
    }

    private fun crashOrAnrEvidence(logcat: String): String? {
        val target = E2eAndroidJUnitRunner.TARGET_APPLICATION_ID
        val fatal = logcat.split("FATAL EXCEPTION").drop(1).firstOrNull { block ->
            block.take(FATAL_BLOCK_CHARS).contains("Process: $target")
        }?.take(FATAL_BLOCK_CHARS)
        if (fatal != null) return "FATAL EXCEPTION$fatal"

        return logcat.lineSequence().firstOrNull { line ->
            (line.contains("am_crash") || line.contains("am_anr")) && line.contains(target)
        }
    }

    private fun deviceMetadata(): String = buildString {
        appendLine("recordedAt=${Instant.now()}")
        appendLine("targetPackage=${targetContext.packageName}")
        appendLine("testPackage=${instrumentation.context.packageName}")
        appendLine("deviceSerial=${shell("getprop ro.serialno").trim()}")
        appendLine("avd=${shell("getprop ro.boot.qemu.avd_name").trim()}")
        appendLine("api=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}")
        appendLine("resolution=${device.displayWidth}x${device.displayHeight}")
        appendLine("densityDpi=${targetContext.resources.displayMetrics.densityDpi}")
        appendLine("locale=${Locale.getDefault().toLanguageTag()}")
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun writeText(name: String, value: String) {
        storage.openOutputFile("$artifactPrefix/$name").bufferedWriter().use { writer ->
            writer.write(value)
        }
    }

    private fun String.safePathSegment(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val LOG_TAG = "MedicalRecordE2E"
        private const val FATAL_BLOCK_CHARS = 8_192
    }
}

private const val CORE_TIMEOUT_MINUTES = 5L
private const val SYSTEM_TIMEOUT_MINUTES = 15L
