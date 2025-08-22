package com.jetbrains.rider.plugins.filerelationships

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ProtocolCaller(private val project: Project) {
    // Converted to pure Kotlin: implement locally without Rider protocol/backend
    suspend fun doCall(input: String): Int {
        // Keep behavior consistent with previous backend implementation: return input length
        return input.length
    }
}
