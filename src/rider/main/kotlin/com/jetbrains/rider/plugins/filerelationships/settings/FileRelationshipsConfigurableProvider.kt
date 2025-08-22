package com.jetbrains.rider.plugins.filerelationships.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.project.Project

class FileRelationshipsConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    override fun canCreateConfigurable(): Boolean = true
    override fun createConfigurable(): Configurable? = FileRelationshipsConfigurable(project)
}
