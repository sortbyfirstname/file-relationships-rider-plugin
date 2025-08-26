package com.jetbrains.rider.plugins.filerelationships.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.EditorNotifications
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JLabel
import com.intellij.openapi.ui.ComboBox
import javax.swing.table.AbstractTableModel

class FileRelationshipsConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    private val service get() = project.getService(FileRelationshipsSettings::class.java)

    private var panel: JPanel? = null
    private var table: JBTable? = null
    private var model: RulesTableModel? = null

    private var displayModeCombo: ComboBox<FileRelationshipsSettings.DisplayMode>? = null
    private var localDisplayMode: FileRelationshipsSettings.DisplayMode = FileRelationshipsSettings.DisplayMode.Banner

    private var localRules: MutableList<FileRelationshipsSettings.RelationshipRule> = mutableListOf()

    override fun getId(): String = "settings.fileRelationships"
    override fun getDisplayName(): String = "File Relationships"

    override fun createComponent(): JComponent {
        if (panel != null) return panel as JComponent

        // Initialize from persisted settings so the page opens with current rules
        localRules = service.getRules().map { it.copy() }.toMutableList()
        localDisplayMode = service.getDisplayMode()

        val m = RulesTableModel(localRules)
        model = m
        val t = JBTable(m)
        table = t
        t.setShowGrid(true)
        t.preferredScrollableViewportSize = Dimension(600, 200)

        // Native table goodies
        t.setShowGrid(false)
        t.tableHeader.reorderingAllowed = false
        TableSpeedSearch.installOn(t)

        val decorator = ToolbarDecorator.createDecorator(t)
            .setAddAction {
                val defaultName = run {
                    val regex = Regex("^Rule (\\d+)$", RegexOption.IGNORE_CASE)
                    var max = 0
                    for (r in localRules) {
                        val m2 = regex.matchEntire(r.name.trim())
                        if (m2 != null) {
                            val n = m2.groupValues[1].toIntOrNull() ?: 0
                            if (n > max) max = n
                        }
                    }
                    "Rule ${max + 1}"
                }
                val initial = FileRelationshipsSettings.RelationshipRule(
                    id = "",
                    name = defaultName,
                    fromGlob = "",
                    toTemplate = "",
                    buttonText = "Open related file",
                    messageText = "Related file available"
                )
                val dlg = RuleEditDialog(project, initial, "Add Rule")
                if (dlg.showAndGet()) {
                    dlg.getRuleOrNull()?.let { rule ->
                        localRules.add(rule)
                        m.fireTableDataChanged()
                        val newIndex = localRules.lastIndex
                        if (newIndex >= 0) {
                            t.rowSelectionAllowed = true
                            t.changeSelection(newIndex, 0, false, false)
                        }
                    }
                }
            }
            .setRemoveAction {
                val idx = t.selectedRow
                if (idx in localRules.indices) {
                    localRules.removeAt(idx)
                    m.fireTableDataChanged()
                    val sel = (idx).coerceAtMost(localRules.lastIndex)
                    if (sel >= 0) t.changeSelection(sel, 0, false, false)
                }
            }

        // Configure toolbar actions without deprecated extra action API
        decorator.setMoveUpAction {
            val idx = t.selectedRow
            if (idx in localRules.indices && idx > 0) {
                val tmp = localRules[idx]
                localRules[idx] = localRules[idx - 1]
                localRules[idx - 1] = tmp
                m.fireTableDataChanged()
                t.changeSelection(idx - 1, 0, false, false)
            }
        }
        decorator.setMoveDownAction {
            val idx = t.selectedRow
            if (idx in localRules.indices && idx < localRules.lastIndex) {
                val tmp = localRules[idx]
                localRules[idx] = localRules[idx + 1]
                localRules[idx + 1] = tmp
                m.fireTableDataChanged()
                t.changeSelection(idx + 1, 0, false, false)
            }
        }
        decorator.setEditAction {
            val idx = t.selectedRow
            if (idx !in localRules.indices) return@setEditAction
            val current = localRules[idx]
            val dlg = RuleEditDialog(project, current, "Edit Rule")
            if (dlg.showAndGet()) {
                dlg.getRuleOrNull()?.let { updated ->
                    localRules[idx] = updated
                    m.fireTableDataChanged()
                    t.changeSelection(idx, 0, false, false)
                }
            }
        }
        decorator.addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Duplicate", "Duplicate Rule", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val idx = t.selectedRow
                if (idx !in localRules.indices) return
                val src = localRules[idx]
                val copy = src.copy(id = "", name = if (src.name.isBlank()) src.name else src.name + " (copy)")
                val dlg = RuleEditDialog(project, copy, "Duplicate Rule")
                if (dlg.showAndGet()) {
                    dlg.getRuleOrNull()?.let { newRule ->
                        val insertAt = (idx + 1).coerceAtMost(localRules.size)
                        localRules.add(insertAt, newRule)
                        m.fireTableDataChanged()
                        t.changeSelection(insertAt, 0, false, false)
                    }
                }
            }
        })

        val main = JPanel(BorderLayout())

        // Top bar for general settings
        val top = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        val modeLabel = JLabel("Display mode:")
        val modeCombo = ComboBox(FileRelationshipsSettings.DisplayMode.values())
        displayModeCombo = modeCombo
        modeCombo.selectedItem = localDisplayMode
        top.add(modeLabel)
        top.add(modeCombo)

        main.add(top, BorderLayout.NORTH)
        main.add(decorator.createPanel(), BorderLayout.CENTER)
        panel = main
        return main
    }

    override fun isModified(): Boolean {
        val currentRules = service.getRules()
        val modifiedRules = currentRules != localRules
        val selectedMode = (displayModeCombo?.selectedItem as? FileRelationshipsSettings.DisplayMode) ?: localDisplayMode
        val modeModified = service.getDisplayMode() != selectedMode
        return modifiedRules || modeModified
    }

    override fun apply() {
        validateRules(localRules)
        service.setRules(localRules)
        val selectedMode = (displayModeCombo?.selectedItem as? FileRelationshipsSettings.DisplayMode) ?: localDisplayMode
        service.setDisplayMode(selectedMode)
        localDisplayMode = selectedMode
        EditorNotifications.getInstance(project).updateAllNotifications()
    }

    override fun reset() {
        localRules = service.getRules().map { it.copy() }.toMutableList()
        localDisplayMode = service.getDisplayMode()
        displayModeCombo?.selectedItem = localDisplayMode
        model?.let { m ->
            m.items = localRules
            m.fireTableDataChanged()
        }
    }

    override fun disposeUIResources() {
        // release UI references to allow GC and avoid leaking listeners
        panel = null
        table = null
        model = null
        displayModeCombo = null
    }

    private fun validateRules(rules: List<FileRelationshipsSettings.RelationshipRule>) {
        rules.forEachIndexed { idx, r ->
            if (r.fromGlob.isBlank()) throw ConfigurationException("Row ${idx + 1}: 'fromGlob' must not be empty")
            if (r.toTemplate.isBlank()) throw ConfigurationException("Row ${idx + 1}: 'toTemplate' must not be empty")
        }
    }
}

private class RulesTableModel(var items: MutableList<FileRelationshipsSettings.RelationshipRule>) : AbstractTableModel() {
    private val columns = listOf("Name", "From glob", "To template", "Button text", "Message text")

    override fun getRowCount(): Int = items.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = items[rowIndex]
        return when (columnIndex) {
            0 -> r.name
            1 -> r.fromGlob
            2 -> r.toTemplate
            3 -> r.buttonText
            4 -> r.messageText
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val value = (aValue as? String) ?: return
        val r = items[rowIndex]
        when (columnIndex) {
            0 -> r.name = value
            1 -> r.fromGlob = value
            2 -> r.toTemplate = value
            3 -> r.buttonText = value
            4 -> r.messageText = value
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }
}
