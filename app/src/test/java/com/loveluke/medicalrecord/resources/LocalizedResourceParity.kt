package com.loveluke.medicalrecord.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class LocalizedResourceKey(
    val type: String,
    val name: String,
) : Comparable<LocalizedResourceKey> {
    override fun compareTo(other: LocalizedResourceKey): Int =
        compareValuesBy(this, other, LocalizedResourceKey::type, LocalizedResourceKey::name)

    override fun toString(): String = "$type/$name"
}

internal data class LocalizedResourceDifference(
    val missing: Set<LocalizedResourceKey>,
    val extra: Set<LocalizedResourceKey>,
) {
    val isEmpty: Boolean = missing.isEmpty() && extra.isEmpty()

    fun describe(): String = buildString {
        appendLine("Localized resource key/type mismatch.")
        if (missing.isNotEmpty()) {
            appendLine("Missing localized resources: ${missing.sorted().joinToString()}")
        }
        if (extra.isNotEmpty()) {
            appendLine("Unexpected localized resources: ${extra.sorted().joinToString()}")
        }
    }.trimEnd()
}

internal object LocalizedResourceParity {
    private val supportedTypes = setOf("string", "plurals", "string-array")
    private const val accessExternalDtd = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val accessExternalSchema =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun compare(defaultValues: File, localizedValues: File): LocalizedResourceDifference {
        val defaultResources = readResources(defaultValues, excludeNonTranslatable = true)
        val localizedResources = readResources(localizedValues, excludeNonTranslatable = false)
        return LocalizedResourceDifference(
            missing = defaultResources - localizedResources,
            extra = localizedResources - defaultResources,
        )
    }

    private fun readResources(
        directory: File,
        excludeNonTranslatable: Boolean,
    ): Set<LocalizedResourceKey> {
        require(directory.isDirectory) { "Resource directory does not exist: $directory" }
        return directory.listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .sortedBy(File::getName)
            .flatMap { file -> readResourcesFile(file, excludeNonTranslatable) }
            .toSet()
    }

    private fun readResourcesFile(
        file: File,
        excludeNonTranslatable: Boolean,
    ): List<LocalizedResourceKey> {
        val factory = DocumentBuilderFactory.newDefaultInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(accessExternalDtd, "")
            setAttribute(accessExternalSchema, "")
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val root = file.inputStream().use { input ->
            factory.newDocumentBuilder().parse(input).documentElement
        }
        return buildList {
            for (index in 0 until root.childNodes.length) {
                val element = root.childNodes.item(index) as? Element ?: continue
                if (excludeNonTranslatable &&
                    element.getAttribute("translatable").equals("false", ignoreCase = true)
                ) {
                    continue
                }
                val type = when {
                    element.tagName in supportedTypes -> element.tagName
                    element.tagName == "item" && element.getAttribute("type") in supportedTypes ->
                        element.getAttribute("type")
                    else -> continue
                }
                val name = element.getAttribute("name")
                if (name.isNotBlank()) {
                    add(LocalizedResourceKey(type = type, name = name))
                }
            }
        }
    }
}
