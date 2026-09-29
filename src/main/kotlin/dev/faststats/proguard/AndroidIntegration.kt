package dev.faststats.proguard

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.Callable

internal object AndroidIntegration {

    fun configure(
        project: Project,
        extension: FastStatsProguardExtension,
        uploadTask: TaskProvider<UploadProguardMappingsTask>,
    ) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all(), Action { variant ->
            if (!variant.isMinifyEnabled) return@Action

            if (!extension.mappingFiles.isEmpty) return@Action

            val mappingFile = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            val variantName = variant.name.replaceFirstChar { it.uppercase() }

            uploadTask.configure { task ->
                task.mappingFiles.from(Callable { mappingFile.orNull?.asFile })
                task.mustRunAfter(
                    project.tasks.matching {
                        it.name == "minify${variantName}WithR8" || it.name == "minify${variantName}WithProguard"
                    },
                )
            }
        })
    }
}
