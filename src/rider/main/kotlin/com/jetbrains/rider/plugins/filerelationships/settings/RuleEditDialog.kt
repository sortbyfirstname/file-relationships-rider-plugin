package com.jetbrains.rider.plugins.filerelationships.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class RuleEditDialog(
    project: Project,
    private val initial: FileRelationshipsSettings.RelationshipRule? = null,
    private val titleText: String = if (initial == null) "Add Rule" else "Edit Rule"
) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val fromField = JBTextField()
    private val toField = JBTextField()
    private val buttonTextField = JBTextField()
    private val messageTextField = JBTextField()

    init {
        title = titleText
        init()
        if (initial != null) {
            nameField.text = initial.name
            fromField.text = initial.fromGlob
            toField.text = initial.toTemplate
            buttonTextField.text = initial.buttonText
            messageTextField.text = initial.messageText
        } else {
            buttonTextField.text = "Open related file"
            messageTextField.text = "Related file available"
        }
    }

    override fun createCenterPanel(): JComponent {
        val flipButton = JButton(AllIcons.Diff.ArrowLeftRight).apply {
            toolTipText = "Flip From glob and To template"
            isFocusable = false
            margin = JBUI.emptyInsets()
            val h = fromField.preferredSize.height
            val square = JBUI.size(h, h)
            preferredSize = square
            minimumSize = square
            maximumSize = square
            addActionListener {
                val (newFrom, newTo) = flipGlobAndTemplate(fromField.text.orEmpty(), toField.text.orEmpty())
                fromField.text = newFrom
                toField.text = newTo
            }
        }

        val fromToRow = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(0, 0, 0, 0)
            }
            // From field expands
            gbc.gridx = 0
            gbc.weightx = 0.5
            add(fromField, gbc)
            // Flip button fixed size
            gbc.gridx = 1
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            add(flipButton, gbc)
            // To field expands
            gbc.gridx = 2
            gbc.weightx = 0.5
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(toField, gbc)
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Name:"), nameField, 1, false)
            .addLabeledComponent(JLabel("From glob   ⇄   To template:"), fromToRow, 1, false)
            .addLabeledComponent(JLabel("Button text:"), buttonTextField, 1, false)
            .addLabeledComponent(JLabel("Message text:"), messageTextField, 1, false)
            .panel.apply {
                // Set a sensible fixed dialog size similar to External Tools dialogs
                // Use non-zero height to ensure fields are visible
                preferredSize = JBUI.size(600, 180)
                minimumSize = JBUI.size(500, 180)
            }
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent? = fromField

    override fun doValidate(): ValidationInfo? {
        if (fromField.text.isNullOrBlank()) return ValidationInfo("'From glob' must not be empty", fromField)
        if (toField.text.isNullOrBlank()) return ValidationInfo("'To template' must not be empty", toField)
        return super.doValidate()
    }

    fun getRuleOrNull(): FileRelationshipsSettings.RelationshipRule? {
        if (!isOK) return null
        val id = initial?.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        return FileRelationshipsSettings.RelationshipRule(
            id = id,
            name = nameField.text.orEmpty(),
            fromGlob = fromField.text.orEmpty(),
            toTemplate = toField.text.orEmpty(),
            buttonText = buttonTextField.text.orEmpty(),
            messageText = messageTextField.text.orEmpty()
        )
    }

    companion object {
        // Flip logic utilities
        private enum class CapKind { SINGLE, MULTI }

        private fun extractCaptureKinds(glob: String): List<CapKind> {
            val kinds = mutableListOf<CapKind>()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                if (c == '*') {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        kinds += CapKind.MULTI
                        i += 2
                        continue
                    } else {
                        kinds += CapKind.SINGLE
                        i += 1
                        continue
                    }
                }
                i++
            }
            return kinds
        }

        private fun buildTemplateFromGlob(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            var idx = 1
            while (i < glob.length) {
                val c = glob[i]
                if (c == '*') {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append("$").append(idx)
                        idx++
                        i += 2
                        continue
                    } else {
                        sb.append("$").append(idx)
                        idx++
                        i += 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun buildGlobFromTemplate(template: String, kinds: List<CapKind>): String {
            val sb = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                    var j = i + 1
                    var num = 0
                    while (j < template.length && template[j].isDigit()) {
                        num = num * 10 + (template[j] - '0')
                        j++
                    }
                    val kind = kinds.getOrNull(num - 1)
                    if (kind == CapKind.MULTI) sb.append("**") else sb.append("*")
                    i = j
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        fun flipGlobAndTemplate(fromGlob: String, toTemplate: String): Pair<String, String> {
            val kinds = extractCaptureKinds(fromGlob)
            val newFrom = buildGlobFromTemplate(toTemplate, kinds)
            val newTo = buildTemplateFromGlob(fromGlob)
            return newFrom to newTo
        }
    }
}
