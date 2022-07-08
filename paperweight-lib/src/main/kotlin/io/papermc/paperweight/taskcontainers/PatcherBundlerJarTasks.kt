/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.taskcontainers

import com.google.gson.JsonObject
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
class PatcherBundlerJarTasks(
    project: Project,
    private val bundlerJarName: Provider<String>,
    private val mainClassString: Provider<String>,
) {
    val createBundlerJar: TaskProvider<CreateBundlerJar>
    val createPaperclipJar: TaskProvider<CreatePaperclipJar>

    init {
        val (createBundlerJar, createPaperclipJar) = project.createBundlerJarTask()
        this.createBundlerJar = createBundlerJar
        this.createPaperclipJar = createPaperclipJar
    }

    fun configureBundlerTasks(
        bundlerVersionJson: Provider<RegularFile>,
        serverLibrariesList: Provider<RegularFile>,
        vanillaJar: Provider<RegularFile>,
        shadowJar: TaskProvider<out AbstractArchiveTask>,
        mcVersion: Provider<String>
    ) {
        createBundlerJar.configureWith(
            bundlerVersionJson,
            serverLibrariesList,
            vanillaJar,
            shadowJar.flatMap { it.archiveFile },
        )

        createPaperclipJar.configureWith(vanillaJar, createBundlerJar, mcVersion)
    }

    private fun Project.createBundlerJarTask(
        classifier: String = "",
    ): Pair<TaskProvider<CreateBundlerJar>, TaskProvider<CreatePaperclipJar>> {
        val bundlerTaskName = "create${classifier.capitalize()}BundlerJar"
        val paperclipTaskName = "create${classifier.capitalize()}PaperclipJar"

        val bundlerJarTask = tasks.register<CreateBundlerJar>(bundlerTaskName) {
            group = "paperweight"
            description = "Build a runnable bundler jar"

            paperclip.from(configurations.named(PAPERCLIP_CONFIG))
            mainClass.set(mainClassString)

            outputZip.set(layout.buildDirectory.file("libs/${jarName("bundler", classifier)}"))
        }
        val paperclipJarTask = tasks.register<CreatePaperclipJar>(paperclipTaskName) {
            group = "paperweight"
            description = "Build a runnable paperclip jar"

            libraryChangesJson.set(bundlerJarTask.flatMap { it.libraryChangesJson })
            outputZip.set(layout.buildDirectory.file("libs/${jarName("paperclip", classifier)}"))
        }
        return bundlerJarTask to paperclipJarTask
    }

    private fun Project.jarName(kind: String, classifier: String): String {
        return listOfNotNull(
            project.name,
            kind,
            project.version,
            classifier.takeIf { it.isNotBlank() },
        ).joinToString("-") + ".jar"
    }

    private fun TaskProvider<CreateBundlerJar>.configureWith(
        bundlerVersionJson: Provider<RegularFile>,
        serverLibrariesListFile: Provider<RegularFile>,
        vanillaJar: Provider<RegularFile>,
        serverJar: Provider<RegularFile>,
    ) = this {
        libraryArtifacts.set(project.configurations.named(SERVER_RUNTIME_CLASSPATH))
        serverLibrariesList.set(serverLibrariesListFile)
        vanillaBundlerJar.set(vanillaJar)

        versionArtifacts {
            registerVersionArtifact(
                bundlerJarName.get(),
                bundlerVersionJson,
                serverJar
            )
        }
    }

    private fun TaskProvider<CreatePaperclipJar>.configureWith(
        vanillaJar: Provider<RegularFile>,
        createBundlerJar: TaskProvider<CreateBundlerJar>,
        version: Provider<String>
    ) = this {
        originalBundlerJar.set(vanillaJar)
        bundlerJar.set(createBundlerJar.flatMap { it.outputZip })
        mcVersion.set(version)
    }

    companion object {
        fun NamedDomainObjectContainer<CreateBundlerJar.VersionArtifact>.registerVersionArtifact(
            name: String,
            versionJson: Provider<RegularFile>,
            serverJar: Provider<RegularFile>
        ) = register(name) {
            id.set(versionJson.map { gson.fromJson<JsonObject>(it)["id"].asString })
            file.set(serverJar)
        }
    }
}