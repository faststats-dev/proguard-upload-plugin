package dev.faststats.proguard

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

class FastStatsProguardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "mappingsUpload",
            FastStatsProguardExtension::class.java,
        )

        extension.authToken.convention(
            project.providers.environmentVariable("FASTSTATS_AUTH_TOKEN"),
        )

        extension.buildId.convention(
            project.provider { project.version.toString() },
        )

        val uploadTask = project.tasks.register(
            "uploadProguardMappings",
            UploadProguardMappingsTask::class.java,
        ) { task ->
            task.group = "faststats"
            task.description = "Uploads ProGuard/R8 mapping files to the Faststats sourcemaps API"

            task.authToken.set(extension.authToken)
            task.endpoint.set(extension.endpoint)
            task.buildId.set(extension.buildId)
            task.mappingFiles.from(extension.mappingFiles)
        }

        project.tasks.withType(Jar::class.java).configureEach { jar ->
            jar.inputs.property("faststats.buildId", extension.buildId)
            jar.filesMatching("META-INF/faststats.properties") { file ->
                file.filter(mapOf("buildId" to extension.buildId.get()), BuildIdFilter::class.java)
            }
        }

        project.afterEvaluate {
            if (extension.proguardTask.isPresent) {
                uploadTask.configure { task ->
                    task.dependsOn(extension.proguardTask.get())
                }
            }
        }

        project.pluginManager.withPlugin("com.android.application") {
            AndroidIntegration.configure(project, extension, uploadTask)
        }
    }
}
