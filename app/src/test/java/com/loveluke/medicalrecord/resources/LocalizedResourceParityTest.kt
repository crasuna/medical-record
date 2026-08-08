package com.loveluke.medicalrecord.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalizedResourceParityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun matchingResourceSetsHaveNoDifferences() {
        val difference = compareResources(
            defaultBody = """
                <string name="title">Title</string>
                <plurals name="item_count">
                    <item quantity="one">%d item</item>
                    <item quantity="other">%d items</item>
                </plurals>
                <string-array name="labels">
                    <item>First</item>
                </string-array>
            """.trimIndent(),
            localizedBody = """
                <string name="title">标题</string>
                <plurals name="item_count">
                    <item quantity="other">%d 项</item>
                </plurals>
                <string-array name="labels">
                    <item>第一项</item>
                </string-array>
            """.trimIndent(),
        )

        assertTrue(difference.describe(), difference.isEmpty)
    }

    @Test
    fun nonTranslatableDefaultResourcesDoNotRequireLocalizedCopies() {
        val difference = compareResources(
            defaultBody = """
                <string name="title">Title</string>
                <string name="product_code" translatable="false">MR</string>
            """.trimIndent(),
            localizedBody = """
                <string name="title">标题</string>
            """.trimIndent(),
        )

        assertTrue(difference.describe(), difference.isEmpty)
    }

    @Test
    fun missingExtraAndChangedResourceTypesAreReported() {
        val difference = compareResources(
            defaultBody = """
                <string name="title">Title</string>
                <plurals name="item_count">
                    <item quantity="other">%d items</item>
                </plurals>
                <string-array name="labels">
                    <item>First</item>
                </string-array>
            """.trimIndent(),
            localizedBody = """
                <string name="title">标题</string>
                <string name="item_count">%d 项</string>
                <string name="unexpected">额外资源</string>
            """.trimIndent(),
        )

        assertEquals(
            LocalizedResourceDifference(
                missing = setOf(
                    LocalizedResourceKey(type = "plurals", name = "item_count"),
                    LocalizedResourceKey(type = "string-array", name = "labels"),
                ),
                extra = setOf(
                    LocalizedResourceKey(type = "string", name = "item_count"),
                    LocalizedResourceKey(type = "string", name = "unexpected"),
                ),
            ),
            difference,
        )
    }

    @Test
    fun simplifiedChineseResourcesMatchDefaultResourcesByNameAndType() {
        val mainResources = File("src/main/res")
        assertTrue(
            "Expected Gradle test working directory to be the app module: ${File(".").absolutePath}",
            mainResources.isDirectory,
        )

        val difference = LocalizedResourceParity.compare(
            defaultValues = mainResources.resolve("values"),
            localizedValues = mainResources.resolve("values-zh-rCN"),
        )

        assertTrue(difference.describe(), difference.isEmpty)
    }

    private fun compareResources(
        defaultBody: String,
        localizedBody: String,
    ): LocalizedResourceDifference {
        val defaultValues = temporaryFolder.newFolder("values")
        val localizedValues = temporaryFolder.newFolder("values-zh-rCN")
        writeResources(defaultValues, defaultBody)
        writeResources(localizedValues, localizedBody)
        return LocalizedResourceParity.compare(defaultValues, localizedValues)
    }

    private fun writeResources(directory: File, body: String) {
        directory.resolve("strings.xml").writeText(
            """
                <resources>
                $body
                </resources>
            """.trimIndent(),
        )
    }
}
