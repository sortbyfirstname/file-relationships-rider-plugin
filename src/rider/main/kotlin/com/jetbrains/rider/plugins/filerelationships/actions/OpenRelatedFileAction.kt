package com.jetbrains.rider.plugins.filerelationships.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.rider.plugins.filerelationships.settings.FileRelationshipsSettings
import com.jetbrains.rider.plugins.filerelationships.settings.RelationshipMatcher

class OpenRelatedFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    private val logger = Logger.getInstance(OpenRelatedFileAction::class.java)

    override fun update(e: AnActionEvent) {
        val project = e.project
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || vFile == null || !project.isInitialized) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        if (DumbService.isDumb(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val items = findAllRelated(project, vFile.path)
        if (items.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = if (items.size == 1) {
            items[0].rule.buttonText.ifBlank { DEFAULT_LABEL }
        } else {
            "Open related files (${items.size})"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val items = findAllRelated(project, vFile.path)
        if (items.isEmpty()) return
        val fem = FileEditorManager.getInstance(project)
        val lfs = LocalFileSystem.getInstance()
        for (it in items) {
            val vf = lfs.findFileByPath(it.absoluteRelatedPath)
            if (vf != null) fem.openFile(vf, true) else logger.warn("Related file not found: ${it.absoluteRelatedPath}")
        }
    }

    private fun findAllRelated(project: Project, absoluteSourcePath: String): List<RelatedContext> {
        val basePath = project.basePath ?: return emptyList()
        val lfs = LocalFileSystem.getInstance()
        val baseVf = lfs.findFileByPath(basePath) ?: return emptyList()
        val srcVf = lfs.findFileByPath(absoluteSourcePath) ?: return emptyList()
        val rel = VfsUtilCore.getRelativePath(srcVf, baseVf, '/') ?: return emptyList()
        val compiled = project.getService(FileRelationshipsSettings::class.java).getCompiledRules()
        val all = RelationshipMatcher.mapAllToRelatedPathsCompiled(rel, compiled)
        if (all.isEmpty()) return emptyList()
        return all.mapNotNull { m ->
            val abs = (basePath.trimEnd('\\', '/') + "/" + m.relatedPath).replace('/', '\\')
            val vf = lfs.findFileByPath(abs) ?: return@mapNotNull null
            RelatedContext(m.rule, abs)
        }
    }

    data class RelatedContext(val rule: FileRelationshipsSettings.RelationshipRule, val absoluteRelatedPath: String)

    companion object {
        private const val DEFAULT_LABEL = "Open related file"
    }
}
