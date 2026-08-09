import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
}

@DisableCachingByDefault(because = "This task validates and prepares one connected Android device.")
abstract class PrepareE2eDevicePackages : DefaultTask() {
    @get:InputFile
    abstract val adbExecutable: RegularFileProperty

    @get:Input
    abstract val packageNames: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val serialOverride: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val allowedPackages = setOf(
            "com.loveluke.medicalrecord.e2e",
            "com.loveluke.medicalrecord.e2e.test",
        )
        val requestedPackages = packageNames.get()
        if (requestedPackages.toSet() != allowedPackages || requestedPackages.size != 2) {
            throw GradleException("Refusing to clean unexpected Android packages: $requestedPackages")
        }
        if (
            requestedPackages.any {
                it == "com.loveluke.medicalrecord" ||
                    it == "com.loveluke.medicalrecord.debug"
            }
        ) {
            throw GradleException("Release and debug packages must never be cleaned by E2E setup.")
        }

        val devices = runAdb(listOf("devices")).stdout
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts.first() to parts.getOrElse(1) { "" }
            }
            .filter { (_, state) -> state.startsWith("device") }
            .map(Pair<String, String>::first)
            .toList()
        val requestedSerial = serialOverride.orNull?.trim()?.takeIf(String::isNotEmpty)
        val selectedSerial = when {
            requestedSerial != null && requestedSerial in devices -> requestedSerial
            requestedSerial != null -> throw GradleException(
                "ANDROID_SERIAL=$requestedSerial is not an online device. Online devices: $devices",
            )
            devices.size == 1 -> devices.single()
            devices.isEmpty() -> throw GradleException(
                "No online Android device; device tests were not executed.",
            )
            else -> throw GradleException(
                "Multiple Android devices are online. Select one with ANDROID_SERIAL before running E2E tests: $devices",
            )
        }

        requestedPackages.forEach { packageName ->
            val installed = runAdb(
                listOf("-s", selectedSerial, "shell", "pm", "list", "packages", packageName),
            ).stdout.lineSequence().any { line -> line.trim() == "package:$packageName" }
            if (installed) {
                val uninstall = runAdb(
                    listOf("-s", selectedSerial, "uninstall", packageName),
                    ignoreExitValue = true,
                )
                if (uninstall.exitCode != 0 || !uninstall.stdout.contains("Success")) {
                    throw GradleException(
                        "Failed to uninstall isolated E2E package $packageName from $selectedSerial. " +
                            "stdout=${uninstall.stdout.trim()} stderr=${uninstall.stderr.trim()}",
                    )
                }
            }
        }
        logger.lifecycle("Prepared isolated E2E packages on $selectedSerial")
    }

    private fun runAdb(
        arguments: List<String>,
        ignoreExitValue: Boolean = false,
    ): AdbResult {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            executable = adbExecutable.get().asFile.absolutePath
            args(arguments)
            isIgnoreExitValue = ignoreExitValue
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
        }
        return AdbResult(
            exitCode = result.exitValue,
            stdout = standardOutput.toString(Charsets.UTF_8),
            stderr = errorOutput.toString(Charsets.UTF_8),
        )
    }

    private data class AdbResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

@DisableCachingByDefault(because = "This task validates and archives local connected-device evidence.")
abstract class VerifyAndArchiveJourneyResults : DefaultTask() {
    @get:InputFile
    abstract val adbExecutable: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val serialOverride: Property<String>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val additionalOutputDirectory: DirectoryProperty

    @get:Internal
    abstract val artifactRoot: DirectoryProperty

    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val expectedTestCount: Property<Int>

    @get:Input
    abstract val expectedAnnotation: Property<String>

    @get:Input
    abstract val requestedAnnotation: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verifyAndArchive() {
        val expectedRunnerAnnotation = expectedAnnotation.get()
        val actualRunnerAnnotation = requestedAnnotation.orNull.orEmpty().trim()
        if (actualRunnerAnnotation != expectedRunnerAnnotation) {
            throw GradleException(
                "Refusing to verify ${groupId.get()} results without " +
                    "-Pandroid.testInstrumentationRunnerArguments.annotation=" +
                    expectedRunnerAnnotation,
            )
        }
        val selectedSerial = selectDevice()

        val resultRoot = resultDirectory.get().asFile
        if (!resultRoot.isDirectory) {
            throw GradleException(
                "Device tests were not executed: missing instrumentation results at $resultRoot",
            )
        }
        val xmlFiles = resultRoot.listFiles()
            ?.filter { file -> file.isFile && file.name.startsWith("TEST-") && file.extension == "xml" }
            .orEmpty()
        if (xmlFiles.size != 1) {
            throw GradleException(
                "Expected exactly one selected-device JUnit XML in $resultRoot, found ${xmlFiles.size}: " +
                    xmlFiles.map { it.name },
            )
        }
        val xmlFile = xmlFiles.single()
        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = documentFactory.newDocumentBuilder().parse(xmlFile)
        val root = document.documentElement
        fun count(name: String): Int = root.getAttribute(name).toIntOrNull()
            ?: throw GradleException("JUnit XML is missing a numeric $name attribute: $xmlFile")

        val tests = count("tests")
        val failures = count("failures")
        val errors = count("errors")
        val skipped = count("skipped")
        val expected = expectedTestCount.get()
        if (tests != expected || failures != 0 || errors != 0 || skipped != 0) {
            throw GradleException(
                "${groupId.get()} device verdict failed: expected tests=$expected, " +
                    "actual tests=$tests failures=$failures errors=$errors skipped=$skipped. " +
                    "JUnit XML: $xmlFile",
            )
        }

        val testCaseNodes = root.getElementsByTagName("testcase")
        if (testCaseNodes.length != expected) {
            throw GradleException(
                "JUnit XML reports tests=$tests but contains ${testCaseNodes.length} testcase nodes: $xmlFile",
            )
        }
        val testCases = (0 until testCaseNodes.length).map { index ->
            val node = testCaseNodes.item(index)
            val attributes = node.attributes
            JourneyTestCase(
                className = attributes.getNamedItem("classname")?.nodeValue.orEmpty(),
                methodName = attributes.getNamedItem("name")?.nodeValue.orEmpty(),
            )
        }
        if (testCases.any { it.className.isBlank() || it.methodName.isBlank() }) {
            throw GradleException("JUnit XML contains a testcase without classname/name: $xmlFile")
        }

        val additionalRoot = additionalOutputDirectory.get().asFile
        val deviceOutputDirectories = additionalRoot.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
        if (deviceOutputDirectories.size != 1) {
            throw GradleException(
                "Expected additional output for exactly one selected device in $additionalRoot, " +
                    "found ${deviceOutputDirectories.size}: ${deviceOutputDirectories.map { it.name }}",
            )
        }
        val deviceOutput = deviceOutputDirectories.single()
        val evidenceDirectories = testCases.map { testCase ->
            deviceOutput.resolve(
                "journeys/${testCase.className.substringAfterLast('.').safeSegment()}/" +
                    testCase.methodName.safeSegment(),
            )
        }
        evidenceDirectories.forEach { evidence ->
            if (!evidence.isDirectory) {
                throw GradleException("Missing per-test device evidence directory: $evidence")
            }
            listOf("device-metadata.txt", "terminal.png").forEach { required ->
                if (!evidence.resolve(required).isFile) {
                    throw GradleException("Missing required passing-test evidence: ${evidence.resolve(required)}")
                }
            }
            listOf("failure.png", "exception.txt").forEach { failureArtifact ->
                if (evidence.resolve(failureArtifact).exists()) {
                    throw GradleException(
                        "Passing JUnit XML conflicts with failure evidence: ${evidence.resolve(failureArtifact)}",
                    )
                }
            }
        }

        val metadata = evidenceDirectories.map { directory ->
            parseMetadata(directory.resolve("device-metadata.txt"))
        }
        val requiredMetadata = listOf(
            "deviceSerial",
            "avd",
            "api",
            "abi",
            "pageSize",
            "resolution",
            "densityDpi",
        )
        requiredMetadata.forEach { key ->
            val values = metadata.map { it[key].orEmpty() }.distinct()
            if (values.size != 1 || values.single().isBlank()) {
                throw GradleException("Inconsistent or missing device metadata '$key': $values")
            }
        }
        val deviceMetadata = metadata.first()
        val artifactBase = artifactRoot.get().asFile.canonicalFile
        val destination = artifactBase
            .resolve(selectedSerial.safeSegment())
            .resolve(groupId.get().safeSegment())
            .canonicalFile
        if (!destination.toPath().startsWith(artifactBase.toPath())) {
            throw GradleException("Refusing to archive outside $artifactBase: $destination")
        }
        if (destination.exists() && !destination.deleteRecursively()) {
            throw GradleException("Could not replace prior journey artifact directory: $destination")
        }
        if (!destination.mkdirs()) {
            throw GradleException("Could not create journey artifact directory: $destination")
        }
        xmlFile.copyTo(destination.resolve("junit.xml"), overwrite = true)
        if (!deviceOutput.copyRecursively(destination.resolve("evidence"), overwrite = true)) {
            throw GradleException("Could not archive device evidence from $deviceOutput")
        }

        val reportLines = buildList {
            add("verdict=passed")
            add("group=${groupId.get()}")
            add("tests=$tests")
            add("failures=$failures")
            add("errors=$errors")
            add("skipped=$skipped")
            add("serial=$selectedSerial")
            requiredMetadata.forEach { key -> add("$key=${deviceMetadata.getValue(key)}") }
            add("instrumentationXml=${xmlFile.absolutePath}")
            add("archivedInstrumentationXml=${destination.resolve("junit.xml").absolutePath}")
            testCases.forEachIndexed { index, testCase ->
                val evidence = "evidence/journeys/" +
                    testCase.className.substringAfterLast('.').safeSegment() + "/" +
                    testCase.methodName.safeSegment()
                add("test.${index + 1}=${testCase.className}#${testCase.methodName}")
                add("evidence.${index + 1}=$evidence")
            }
        }
        destination.resolve("report.txt").writeText(reportLines.joinToString("\n", postfix = "\n"))
        logger.lifecycle(
            "Verified and archived ${groupId.get()} device results: tests=$tests " +
                "failures=0 errors=0 skipped=0 -> $destination",
        )
    }

    private fun selectDevice(): String {
        val devices = runAdb(listOf("devices")).stdout
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts.first() to parts.getOrElse(1) { "" }
            }
            .filter { (_, state) -> state.startsWith("device") }
            .map(Pair<String, String>::first)
            .toList()
        val requestedSerial = serialOverride.orNull?.trim()?.takeIf(String::isNotEmpty)
        return when {
            requestedSerial != null && requestedSerial in devices -> requestedSerial
            requestedSerial != null -> throw GradleException(
                "ANDROID_SERIAL=$requestedSerial is not an online device. Online devices: $devices",
            )
            devices.size == 1 -> devices.single()
            devices.isEmpty() -> throw GradleException(
                "No online Android device; device tests were not executed.",
            )
            else -> throw GradleException(
                "Multiple Android devices are online. Select one with ANDROID_SERIAL before verification: $devices",
            )
        }
    }

    private fun runAdb(arguments: List<String>): AdbResult {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            executable = adbExecutable.get().asFile.absolutePath
            args(arguments)
            isIgnoreExitValue = true
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "ADB command failed (${arguments.joinToString(" ")}): " +
                    "${errorOutput.toString(Charsets.UTF_8).trim()}",
            )
        }
        return AdbResult(
            stdout = standardOutput.toString(Charsets.UTF_8),
            stderr = errorOutput.toString(Charsets.UTF_8),
        )
    }

    private fun parseMetadata(file: java.io.File): Map<String, String> =
        file.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()

    private fun String.safeSegment(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")

    private data class JourneyTestCase(
        val className: String,
        val methodName: String,
    )

    private data class AdbResult(
        val stdout: String,
        val stderr: String,
    )
}

val releaseStoreFile = providers.environmentVariable("MEDICAL_RECORD_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MEDICAL_RECORD_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MEDICAL_RECORD_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MEDICAL_RECORD_KEY_PASSWORD").orNull
val releaseSigningInputs = linkedMapOf(
    "MEDICAL_RECORD_STORE_FILE" to releaseStoreFile,
    "MEDICAL_RECORD_STORE_PASSWORD" to releaseStorePassword,
    "MEDICAL_RECORD_KEY_ALIAS" to releaseKeyAlias,
    "MEDICAL_RECORD_KEY_PASSWORD" to releaseKeyPassword,
)
val configuredReleaseSigningInputs = releaseSigningInputs.filterValues { !it.isNullOrBlank() }
if (configuredReleaseSigningInputs.isNotEmpty() &&
    configuredReleaseSigningInputs.size != releaseSigningInputs.size
) {
    val missingNames = releaseSigningInputs.keys - configuredReleaseSigningInputs.keys
    throw GradleException(
        "Incomplete external release signing configuration. Missing: ${missingNames.joinToString()}.",
    )
}
val hasReleaseSigning = configuredReleaseSigningInputs.size == releaseSigningInputs.size

android {
    namespace = "com.loveluke.medicalrecord"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.loveluke.medicalrecord"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testApplicationId = "com.loveluke.medicalrecord.e2e.test"
        testInstrumentationRunner = "com.loveluke.medicalrecord.test.E2eAndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("externalRelease") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            versionNameSuffix = "-e2e"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
        }
    }

    testBuildType = "e2e"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("--enable-native-access=ALL-UNNAMED")
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.navigation.suite)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("e2eImplementation", libs.androidx.compose.ui.tooling)
    add("e2eImplementation", libs.androidx.compose.ui.test.manifest)
}

val prepareE2eDevicePackages = tasks.register<PrepareE2eDevicePackages>(
    "prepareE2eDevicePackages",
) {
    group = "verification"
    description = "Validates one selected device and removes only isolated E2E packages."
    adbExecutable.set(androidComponents.sdkComponents.adb)
    packageNames.set(
        listOf(
            "com.loveluke.medicalrecord.e2e.test",
            "com.loveluke.medicalrecord.e2e",
        ),
    )
    serialOverride.set(providers.environmentVariable("ANDROID_SERIAL"))
}

val requestedJourneyAnnotation = providers.gradleProperty(
    "android.testInstrumentationRunnerArguments.annotation",
).orElse("")

val verifyAndArchiveCoreJourney = tasks.register<VerifyAndArchiveJourneyResults>(
    "verifyAndArchiveCoreJourney",
) {
    group = "verification"
    description = "Requires exactly 12 passing CoreJourney tests and archives selected-device evidence."
    resultDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected/e2e"))
    additionalOutputDirectory.set(
        layout.buildDirectory.dir(
            "outputs/connected_android_test_additional_output/e2eAndroidTest/connected",
        ),
    )
    artifactRoot.set(layout.buildDirectory.dir("outputs/androidTest-artifacts"))
    adbExecutable.set(androidComponents.sdkComponents.adb)
    serialOverride.set(providers.environmentVariable("ANDROID_SERIAL"))
    groupId.set("core")
    expectedTestCount.set(12)
    expectedAnnotation.set("com.loveluke.medicalrecord.test.CoreJourney")
    requestedAnnotation.set(requestedJourneyAnnotation)
    mustRunAfter("connectedE2eAndroidTest")
}

val verifyAndArchiveSystemInteraction = tasks.register<VerifyAndArchiveJourneyResults>(
    "verifyAndArchiveSystemInteraction",
) {
    group = "verification"
    description = "Requires exactly 2 passing SystemInteraction tests and archives selected-device evidence."
    resultDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected/e2e"))
    additionalOutputDirectory.set(
        layout.buildDirectory.dir(
            "outputs/connected_android_test_additional_output/e2eAndroidTest/connected",
        ),
    )
    artifactRoot.set(layout.buildDirectory.dir("outputs/androidTest-artifacts"))
    adbExecutable.set(androidComponents.sdkComponents.adb)
    serialOverride.set(providers.environmentVariable("ANDROID_SERIAL"))
    groupId.set("system-interaction")
    expectedTestCount.set(2)
    expectedAnnotation.set("com.loveluke.medicalrecord.test.SystemInteraction")
    requestedAnnotation.set(requestedJourneyAnnotation)
    mustRunAfter("connectedE2eAndroidTest")
}

tasks.configureEach {
    if (name == "connectedE2eAndroidTest") {
        dependsOn(prepareE2eDevicePackages)
    }
}
