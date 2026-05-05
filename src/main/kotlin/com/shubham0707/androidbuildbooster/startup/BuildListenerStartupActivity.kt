package com.shubham0707.androidbuildbooster.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.shubham0707.androidbuildbooster.services.BuildListenerService

/**
 * Registers [BuildListenerService] using the modern [GradleImportingUtil] API
 * on every project open. This replaces the deprecated
 * externalSystemTaskNotificationListener extension point.
 */
class BuildListenerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        BuildListenerService(project).register()
    }
}
