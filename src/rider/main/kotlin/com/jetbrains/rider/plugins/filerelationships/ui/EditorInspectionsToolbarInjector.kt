package com.jetbrains.rider.plugins.filerelationships.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import com.jetbrains.rider.plugins.filerelationships.settings.FileRelationshipsSettings

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
                if (installIfPossible(project, editor.component)) {
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

    private fun installIfPossible(project: Project, root: JComponent): Boolean {
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

    companion object {
        private val INSTALLED_KEY = Key.create<Boolean>("FileRelationships.ToolbarInstalled")
        private const val COMPONENT_NAME = "FileRelationships.ToolbarButton"
        private const val OPEN_ACTION_ID = "com.aaronseabrook.plugins.filerelationships.OpenRelatedFileAction"
        private const val INSPECTIONS_PLACE = "EditorInspectionsToolbar"
    }
}
