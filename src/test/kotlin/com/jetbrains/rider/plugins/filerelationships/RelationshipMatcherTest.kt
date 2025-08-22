package com.jetbrains.rider.plugins.filerelationships

import com.jetbrains.rider.plugins.filerelationships.settings.FileRelationshipsSettings
import com.jetbrains.rider.plugins.filerelationships.settings.RelationshipMatcher
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.annotations.Test

class RelationshipMatcherTest {
    @Test
    fun mapsPagesCsToXml() {
        val rule = FileRelationshipsSettings.RelationshipRule(
            id = "test",
            name = "Pages to XML",
            fromGlob = "src/main/Example.Pages/**/*.cs",
            toTemplate = "src/main/Example.Site/Example/App_Data/Common/Xml/$1/$2.xml",
            buttonText = "Open page XML"
        )
        val result = RelationshipMatcher.mapToRelatedPath(
            "src/main/Example.Pages/Area/Sub/Page.cs",
            listOf(rule)
        )
        assertNotNull(result, "Expected a mapping result")
        assertEquals(
            result!!.relatedPath,
            "src/main/Example.Site/Example/App_Data/Common/Xml/Area/Sub/Page.xml"
        )
    }

    @Test
    fun doesNotSupportParenAlternation() {
        val rule = FileRelationshipsSettings.RelationshipRule(
            id = "paren",
            name = "Paren alternation",
            fromGlob = "src/pages/**/*.(js|ts)",
            toTemplate = "out/$1.$2"
        )
        val noMatchJs = RelationshipMatcher.mapToRelatedPath(
            "src/pages/a/b/file.js",
            listOf(rule)
        )
        val noMatchTs = RelationshipMatcher.mapToRelatedPath(
            "src/pages/a/b/file.ts",
            listOf(rule)
        )
        assertNull(noMatchJs, "(js|ts) must be treated as literal text, not alternation")
        assertNull(noMatchTs, "(js|ts) must be treated as literal text, not alternation")
    }

    @Test
    fun doesNotSupportBraceAlternation() {
        val rule = FileRelationshipsSettings.RelationshipRule(
            id = "brace",
            name = "Brace alternation",
            fromGlob = "src/pages/**/*.{js,ts}",
            toTemplate = "out/$1.$2"
        )
        val noMatchJs = RelationshipMatcher.mapToRelatedPath(
            "src/pages/a/b/file.js",
            listOf(rule)
        )
        val noMatchTs = RelationshipMatcher.mapToRelatedPath(
            "src/pages/a/b/file.ts",
            listOf(rule)
        )
        assertNull(noMatchJs, "{js,ts} must be treated as literal text, not alternation")
        assertNull(noMatchTs, "{js,ts} must be treated as literal text, not alternation")
    }
}
