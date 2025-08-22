package com.jetbrains.rider.plugins.filerelationships.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Stores user-configurable file relationship rules.
 * A rule maps a source file path (fromGlob) to a related file path using a template (toTemplate).
 * Pattern syntax:
 *  - Use forward slashes '/' in patterns regardless of OS; paths are normalized before matching.
 *  - fromGlob supports wildcards: '*' (single path segment), '**' (multi-segment, can include '/'). Each wildcard becomes a capturing group.
 *  - toTemplate may reference captured groups using $1, $2, ...
 */
@Service(Service.Level.PROJECT)
@State(name = "FileRelationshipsSettings", storages = [Storage("fileRelationships.xml")])
class FileRelationshipsSettings(private val project: Project) : PersistentStateComponent<FileRelationshipsSettings.State> {
    class State {
        // Start with no default rules; users configure rules explicitly in Settings.
        var rules: MutableList<RelationshipRule> = mutableListOf()
    }

    data class RelationshipRule(
        var id: String = "",
        var name: String = "",
        var fromGlob: String = "",
        var toTemplate: String = "",
        var buttonText: String = "Open related file",
        var messageText: String = "" // if blank, default banner message will be used
    )

    private var state = State()

    // cache of compiled rules to avoid recompiling regexes repeatedly
    @Transient
    private var compiledCache: List<RelationshipMatcher.CompiledRule> = emptyList()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        // invalidate cache when state is loaded
        compiledCache = emptyList()
    }

    // Utility API for other components
    fun getRules(): List<RelationshipRule> = state.rules

    fun setRules(newRules: List<RelationshipRule>) {
        state.rules = newRules.toMutableList()
        // invalidate cache on change
        compiledCache = emptyList()
    }

    fun getCompiledRules(): List<RelationshipMatcher.CompiledRule> {
        if (compiledCache.isEmpty() && state.rules.isNotEmpty()) {
            compiledCache = RelationshipMatcher.compileRules(state.rules)
        }
        return compiledCache
    }
}

object RelationshipMatcher {
    internal fun normalize(path: String): String = path.replace('\\', '/').trimStart('/')

    data class CompiledRule(
        val rule: FileRelationshipsSettings.RelationshipRule,
        val regex: Regex,
        val groupCount: Int,
        val templateParts: List<TemplatePart>
    )

    data class TemplatePart(val literal: String?, val groupIndex: Int?)

    private fun parseTemplate(t: String): List<TemplatePart> {
        val parts = mutableListOf<TemplatePart>()
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '$' && i + 1 < t.length && t[i + 1].isDigit()) {
                if (sb.isNotEmpty()) {
                    parts += TemplatePart(sb.toString(), null)
                    sb.setLength(0)
                }
                var j = i + 1
                var num = 0
                while (j < t.length && t[j].isDigit()) {
                    num = num * 10 + (t[j] - '0')
                    j++
                }
                parts += TemplatePart(null, num)
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        if (sb.isNotEmpty()) parts += TemplatePart(sb.toString(), null)
        return parts
    }

    private fun expand(parts: List<TemplatePart>, groups: List<String>): String {
        val out = StringBuilder()
        for (p in parts) {
            if (p.literal != null) out.append(p.literal) else {
                val idx = (p.groupIndex ?: 0) - 1
                if (idx >= 0 && idx < groups.size) out.append(groups[idx])
            }
        }
        return out.toString()
    }

    private fun compileGlob(glob: String): Pair<Regex, Int> {
        val norm = normalize(glob)
        val sb = StringBuilder()
        var i = 0
        var groupCount = 0
        while (i < norm.length) {
            val c = norm[i]
            if (c == '*') {
                // check for **
                if (i + 1 < norm.length && norm[i + 1] == '*') {
                    // consume both *
                    i += 2
                    // '(.*)' multi-segment
                    sb.append("(.*)")
                    groupCount += 1
                    continue
                } else {
                    i += 1
                    // '([^/]+)' single segment
                    sb.append("([^/]+)")
                    groupCount += 1
                    continue
                }
            }
            when (c) {
                '.', '(', ')', '+', '?', '^', '$', '{', '}', '[', ']', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i += 1
        }
        val regex = Regex("^$sb$")
        return regex to groupCount
    }

    fun compile(rule: FileRelationshipsSettings.RelationshipRule): CompiledRule {
        val (regex, count) = compileGlob(rule.fromGlob)
        val parts = parseTemplate(rule.toTemplate)
        return CompiledRule(rule, regex, count, parts)
    }

    fun compileRules(rules: List<FileRelationshipsSettings.RelationshipRule>): List<CompiledRule> =
        rules.map { compile(it) }

    fun mapToRelatedPath(path: String, rules: List<FileRelationshipsSettings.RelationshipRule>): MappedResult? {
        return mapToRelatedPathCompiled(path, compileRules(rules))
    }

    fun mapToRelatedPathCompiled(path: String, compiledRules: List<CompiledRule>): MappedResult? {
        val all = mapAllToRelatedPathsCompiled(path, compiledRules)
        return all.firstOrNull()
    }

    fun mapAllToRelatedPaths(path: String, rules: List<FileRelationshipsSettings.RelationshipRule>): List<MappedResult> =
        mapAllToRelatedPathsCompiled(path, compileRules(rules))

    fun mapAllToRelatedPathsCompiled(path: String, compiledRules: List<CompiledRule>): List<MappedResult> {
        val normPath = normalize(path)
        val dedup = LinkedHashMap<String, MappedResult>()
        for (compiled in compiledRules) {
            val match = compiled.regex.matchEntire(normPath) ?: continue
            val groups = match.groupValues.drop(1) // group 0 is the full match
            val result = expand(compiled.templateParts, groups)
            val normalized = normalize(result)
            dedup.computeIfAbsent(normalized) { MappedResult(compiled.rule, normalized) }
        }
        return dedup.values.toList()
    }

    data class MappedResult(val rule: FileRelationshipsSettings.RelationshipRule, val relatedPath: String)
}
