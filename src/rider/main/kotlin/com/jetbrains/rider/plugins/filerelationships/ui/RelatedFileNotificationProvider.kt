package com.jetbrains.rider.plugins.filerelationships.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.jetbrains.rider.plugins.filerelationships.settings.FileRelationshipsSettings
import com.jetbrains.rider.plugins.filerelationships.settings.RelationshipMatcher
import java.util.function.Function as JFunction
import javax.swing.JComponent

class RelatedFileNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: com.intellij.openapi.vfs.VirtualFile): JFunction<in FileEditor, out JComponent?>? {
        if (!project.isInitialized) return null
        if (DumbService.isDumb(project)) return null
        val settings = project.getService(FileRelationshipsSettings::class.java)
        if (settings.getDisplayMode() == FileRelationshipsSettings.DisplayMode.Icon) return null
        val basePath = project.basePath ?: return null
        val lfs = LocalFileSystem.getInstance()
        val baseVf = lfs.findFileByPath(basePath) ?: return null
        val rel = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, baseVf, '/') ?: return null
        val compiled = settings.getCompiledRules()
        val all = RelationshipMatcher.mapAllToRelatedPathsCompiled(rel, compiled)
        if (all.isEmpty()) return null
        val items = all.mapNotNull { m ->
            val abs = (basePath.trimEnd('\\', '/') + "/" + m.relatedPath).replace('/', '\\')
            val vf = lfs.findFileByPath(abs) ?: return@mapNotNull null
            m to vf
        }
        if (items.isEmpty()) return null
        return JFunction { _: FileEditor ->
            val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            panel.text = if (items.size == 1) {
                items[0].first.rule.messageText.ifBlank { "Related file available" }
            } else {
                "Related files found"
            }
            for ((mapped, vf) in items) {
                val label = mapped.rule.buttonText.ifBlank { "Open related file" }
                panel.createActionLabel(label) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
            if (items.size > 1) {
                panel.createActionLabel("Open all related files (${items.size})") {
                    val fem = FileEditorManager.getInstance(project)
                    for ((_, vf) in items) fem.openFile(vf, true)
                }
            }
            panel
        }
    }
}
