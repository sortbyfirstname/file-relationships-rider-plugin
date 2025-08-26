package com.jetbrains.rider.plugins.filerelationships.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import com.jetbrains.rider.plugins.filerelationships.settings.FileRelationshipsSettings
import com.jetbrains.rider.plugins.filerelationships.settings.RelationshipMatcher

/**
 * Injects a small toolbar button next to the Editor Inspections toolbar (EditorInspectionsToolbar)
 * that opens all related files.
 *
 * We cannot use add-to-group because the inspections toolbar isn't exposed as a public action group.
 * Instead, we locate the existing toolbar (ActionToolbar with place == "EditorInspectionsToolbar")
 * and add our own ActionToolbar to its parent NonOpaquePanel.
 */
class EditorInspectionsToolbarInjector : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project: Project = editor.project ?: return

        // Avoid multiple installations per editor
        if (editor.getUserData(INSTALLED_KEY) == true) return

        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

        var tries = 0
        val maxTries = 40 // wait up to ~10s
        val request = object : Runnable {
            override fun run() {
                if (editor.isDisposed) return
                if (installIfPossible(project, editor.component, editor)) {
                    editor.putUserData(INSTALLED_KEY, true)
                    return
                }
                tries++
                if (tries < maxTries) {
                    alarm.addRequest(this, 250)
                }
            }
        }

        // Try immediately and then with a few retries to allow inspections toolbar to be created
        alarm.addRequest(request, 250)

        // No explicit disposal necessary; components are under the editor hierarchy and Alarm is tied to project.
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        // Nothing to clean explicitly; components live under editor hierarchy and will be disposed.
    }

    private fun installIfPossible(project: Project, root: JComponent, editor: Editor): Boolean {
        // Only show the toolbar button when display mode is Icon
        val settings = project.getService(FileRelationshipsSettings::class.java)
        if (settings.getDisplayMode() != FileRelationshipsSettings.DisplayMode.Icon) {
            return false
        }
        // Find the existing inspections toolbar by its place
        val inspectionsToolbar = findComponent(root) {
            it is JComponent && it is ActionToolbar && (it.place == INSPECTIONS_PLACE)
        } as? ActionToolbar ?: return false

        val parent = (inspectionsToolbar as JComponent).parent ?: return false

        // If already installed, skip by checking for our component name
        if (parent.components.any { it.name == COMPONENT_NAME }) return true

        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction(OPEN_ACTION_ID) ?: return false
        val group = DefaultActionGroup().apply { add(action) }

        val toolbar = actionManager.createActionToolbar(INSPECTIONS_PLACE, group, true)
        toolbar.targetComponent = root

        // Name our component to prevent duplicates and copy styling to match inspections toolbar
        val comp = toolbar.component
        comp.name = COMPONENT_NAME
        comp.isOpaque = inspectionsToolbar.isOpaque
        comp.background = inspectionsToolbar.background
        comp.border = JBUI.Borders.empty(2, 0, 2, 2)

        // Attach right-click popup for related files. Install on the toolbar and any children, including future ones.
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
            private fun maybeShow(e: MouseEvent) {
                if (!(e.isPopupTrigger || SwingUtilities.isRightMouseButton(e))) return
                e.consume()
                showRelatedFilesPopup(project, editor, e, comp)
            }
        }
        attachPopupListenerRecursively(comp, listener)
        comp.addContainerListener(object : java.awt.event.ContainerAdapter() {
            override fun componentAdded(e: java.awt.event.ContainerEvent) {
                attachPopupListenerRecursively(e.child, listener)
            }
        })

        // Add after the inspections toolbar so it appears alongside
        try {
            parent.add(comp)
            parent.revalidate()
            parent.repaint()
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun findComponent(root: Component, predicate: (Component) -> Boolean): Component? {
        if (predicate(root)) return root
        if (root is Container) {
            for (i in 0 until root.componentCount) {
                val child = root.getComponent(i)
                val found = findComponent(child, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun attachPopupListenerRecursively(root: Component, listener: MouseAdapter) {
        try {
            root.addMouseListener(listener)
        } catch (_: Throwable) {}
        if (root is Container) {
            for (i in 0 until root.componentCount) {
                attachPopupListenerRecursively(root.getComponent(i), listener)
            }
        }
    }

    private fun showRelatedFilesPopup(project: Project, editor: Editor, e: MouseEvent, fallbackInvoker: JComponent) {
        if (!project.isInitialized) return
        if (DumbService.isDumb(project)) return

        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val basePath = project.basePath ?: return
        val lfs = LocalFileSystem.getInstance()
        val baseVf = lfs.findFileByPath(basePath) ?: return
        val rel = VfsUtilCore.getRelativePath(vFile, baseVf, '/') ?: return
        val settings = project.getService(FileRelationshipsSettings::class.java)
        val compiled = settings.getCompiledRules()
        val all = RelationshipMatcher.mapAllToRelatedPathsCompiled(rel, compiled)
        if (all.isEmpty()) return
        val items = all.mapNotNull { m ->
            val abs = (basePath.trimEnd('\\', '/') + "/" + m.relatedPath).replace('/', '\\')
            val vf = lfs.findFileByPath(abs) ?: return@mapNotNull null
            Pair(m, vf)
        }
        if (items.isEmpty()) return

        val group = DefaultActionGroup()
        for ((mapped, vf) in items) {
            val label = mapped.rule.buttonText.ifBlank { "Open related file" }
            group.add(object : AnAction(label) {
                override fun actionPerformed(event: AnActionEvent) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            })
        }
        if (items.size > 1) {
            group.add(Separator.create())
            group.add(object : AnAction("Open all related files (${items.size})") {
                override fun actionPerformed(event: AnActionEvent) {
                    val fem = FileEditorManager.getInstance(project)
                    for ((_, vf) in items) fem.openFile(vf, true)
                }
            })
        }

        val popupMenu = ActionManager.getInstance().createActionPopupMenu(INSPECTIONS_PLACE, group).component
        val invoker = (e.component as? JComponent) ?: fallbackInvoker
        val pt: Point = SwingUtilities.convertPoint(e.component, e.point, invoker)
        popupMenu.show(invoker, pt.x, pt.y)
    }

    private fun removeOurToolbar(root: JComponent): Boolean {
        val comp = findComponent(root) { it is JComponent && it.name == COMPONENT_NAME } as? JComponent ?: return false
        val parent = comp.parent ?: return false
        return try {
            parent.remove(comp)
            parent.revalidate()
            parent.repaint()
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private val INSTALLED_KEY = Key.create<Boolean>("FileRelationships.ToolbarInstalled")
        private const val COMPONENT_NAME = "FileRelationships.ToolbarButton"
        private const val OPEN_ACTION_ID = "com.aaronseabrook.plugins.filerelationships.OpenRelatedFileAction"
        private const val INSPECTIONS_PLACE = "EditorInspectionsToolbar"

        fun refreshForProject(project: Project) {
            val settings = project.getService(FileRelationshipsSettings::class.java)
            val editors = EditorFactory.getInstance().allEditors
            val instance = EditorInspectionsToolbarInjector()
            for (editor in editors) {
                if (editor.project != project) continue
                val root = editor.component
                if (settings.getDisplayMode() == FileRelationshipsSettings.DisplayMode.Icon) {
                    instance.installIfPossible(project, root, editor)
                } else {
                    instance.removeOurToolbar(root)
                }
            }
        }
    }
}
