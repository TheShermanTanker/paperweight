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

package io.papermc.paperweight.patcher

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.patcher.tasks.CheckoutRepo
import io.papermc.paperweight.patcher.tasks.DownloadSpigotMappings
import io.papermc.paperweight.patcher.tasks.PaperweightPatcherPrepareForDownstream
import io.papermc.paperweight.patcher.tasks.PaperweightPatcherUpstreamData
import io.papermc.paperweight.patcher.tasks.SimpleApplyGitPatches
import io.papermc.paperweight.patcher.tasks.SimpleRebuildGitPatches
import io.papermc.paperweight.patcher.upstream.PatchTaskConfig
import io.papermc.paperweight.patcher.upstream.PatcherUpstream
import io.papermc.paperweight.patcher.upstream.RepoPatcherUpstream
import io.papermc.paperweight.taskcontainers.DevBundleTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.registering

class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        Git.checkForGit()

        val patcher = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightPatcherExtension::class)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(DECOMPILER_CONFIG)
        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(UPSTREAM_WORK_DIR_PROPERTY).forUseAtConfigurationTime()
        val dataFileProp = target.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY).forUseAtConfigurationTime()

        val applyPatches by target.tasks.registering { group = "paperweight" }
        val rebuildPatches by target.tasks.registering { group = "paperweight" }
        val generateReobfMappings by target.tasks.registering(GenerateReobfMappings::class)

        val downloadSpigotMappings by target.tasks.registering<DownloadSpigotMappings>()
        val generateMergedMappings by target.tasks.registering<GenerateMergedMappings> {
            spigotMappingsDir.set(downloadSpigotMappings.flatMap { it.mappingsDir })
            spigotClassMappingsPatch.set(patcher.spigotClassMappingsPatch.pathOrNull)
            spigotMemberMappingsPatch.set(patcher.spigotMemberMappingsPatch.pathOrNull)
            mergedMappingsPatch.set(patcher.mergedMappingsPatch.pathOrNull)

            mergedMappings.set(layout.cache.resolve(MOJANG_YARN_SPIGOT_MAPPINGS))
            mojangToMergedMappings.set((layout.cache.resolve(MOJANG_YARN_MOJANG_YARN_SPIGOT_MAPPINGS)))
            patchedMojangToMergedMappings.set(layout.cache.resolve(PATCHED_MOJANG_YARN_MOJANG_YARN_SPIGOT_MAPPINGS))
            patchedMojangToMergedSourceMappings.set(layout.cache.resolve(PATCHED_MOJANG_YARN_MOJANG_YARN_SPIGOT_SOURCE_MAPPINGS))
            generatedMojangToMergedPatch.set(layout.cache.resolve(GENERATED_MERGED_MAPPINGS_PATCH))
            cleanedPatch.set(layout.cache.resolve(CLEANED_MERGED_MAPPINGS_PATCH))
        }

        val filterVanillaJar by target.tasks.registering(FilterJar::class)
        val filterMojangMappedJar by target.tasks.registering(FilterJar::class)

        val inspectVanillaJar by target.tasks.registering<InspectVanillaJar> {
            serverLibraries.set(layout.cache.resolve(SERVER_LIBRARIES))
        }

        val remapJar by target.tasks.registering<RemapJar> {
            mappingsFile.set(generateMergedMappings.flatMap { it.patchedMojangToMergedMappings })
            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(MERGED_NAMESPACE)
            remapper.from(project.configurations.named(REMAPPER_CONFIG))
        }

        val copyResources by target.tasks.registering<CopyResources> {
            inputJar.set(remapJar.flatMap { it.outputJar })
            includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

            outputJar.set(layout.cache.resolve(FINAL_REMAPPED_JAR))
        }

        val decompileJar by target.tasks.registering<RunForgeFlower> {
            executable.from(target.configurations.named(DECOMPILER_CONFIG))
            inputJar.set(copyResources.flatMap { it.outputJar })

            outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
        }

        val mergeReobfMappingsPatches by target.tasks.registering<PatchMappings> {
            patch.set(patcher.reobfMappingsPatch.fileExists(target))
            outputMappings.convention(defaultOutput("tiny"))

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)
        }

        val patchReobfMappings by target.tasks.registering<PatchMappings> {
            inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
            patch.set(mergeReobfMappingsPatches.flatMap { it.outputMappings })
            outputMappings.set(target.layout.cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)
        }

        val prepareForDownstream = target.tasks.register<PaperweightPatcherPrepareForDownstream>(PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dataFile.fileProvider(dataFileProp.map { File(it) })
            reobfMappingsPatch.set(mergeReobfMappingsPatches.flatMap { it.outputMappings })
        }

        val upstreamDataTaskRef = AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>(null)

        patcher.upstreams.all {
            val taskPair = target.createUpstreamTask(this, patcher, workDirProp, upstreamDataTaskRef)

            patchTasks.all {
                val createdPatchTask = target.createPatchTask(this, patcher, taskPair, applyPatches)
                prepareForDownstream {
                    dependsOn(createdPatchTask)
                }
                target.rebuildPatchTask(this, rebuildPatches)
            }
        }

        val paperclipJar by target.tasks.registering<Jar> {
            group = "paperweight"
            description = "Build a runnable paperclip jar"
        }

        val devBundleTasks = DevBundleTasks(target)

        target.afterEvaluate {
            target.repositories {
                maven(patcher.remapRepo) {
                    name = REMAPPER_REPO_NAME
                    content { onlyForConfigurations(REMAPPER_CONFIG) }
                }
                maven(patcher.decompileRepo) {
                    name = DECOMPILER_REPO_NAME
                    content { onlyForConfigurations(DECOMPILER_CONFIG) }
                }
            }

            val upstreamDataTask = upstreamDataTaskRef.get() ?: return@afterEvaluate
            val upstreamData = upstreamDataTask.map { readUpstreamData(it.dataFile) }

            val mcVersion: Property<String> = target.objects.property()
            mcVersion.set(upstreamData.map { it.mcVersion })

            filterVanillaJar {
                inputJar.pathProvider(upstreamData.map { it.vanillaJar })
                includes.set(upstreamData.map { it.vanillaIncludes })
            }

            filterMojangMappedJar {
                inputJar.pathProvider(upstreamData.map { it.remappedJar })
                includes.set(upstreamData.map { it.vanillaIncludes })
            }

            inspectVanillaJar {
                inputJar.pathProvider(upstreamData.map { it.vanillaJar })
                libraries.from(upstreamData.map { objects.directoryProperty().convention(target, it.libDir).asFileTree })
                mcLibraries.pathProvider(upstreamData.map { it.libFile })
            }

            generateMergedMappings {
                vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
                mojangMappedJar.set(filterMojangMappedJar.flatMap { it.outputJar })
                libraries.from(upstreamData.map { objects.directoryProperty().convention(target, it.libDir).asFileTree })
                syntheticMethods.set(inspectVanillaJar.flatMap { it.syntheticMethods })

                mojangYarnMappings.pathProvider(upstreamData.map { it.sourceMappings })
            }

            remapJar {
                inputJar.set(filterMojangMappedJar.flatMap { it.outputJar })
            }

            copyResources {
                vanillaJar.pathProvider(upstreamData.map { it.vanillaJar })
            }

            decompileJar {
                libraries.from(upstreamData.map { objects.directoryProperty().convention(target, it.libDir).asFileTree })
            }

            mergeReobfMappingsPatches {
                inputMappings.pathProvider(upstreamData.map { it.reobfMappingsPatch })
            }
            val mergedReobfPackagesToFix = upstreamData.zip(patcher.reobfPackagesToFix) { data, pkgs ->
                data.reobfPackagesToFix + pkgs
            }

            prepareForDownstream {
                upstreamDataFile.set(upstreamDataTask.flatMap { it.dataFile })
                reobfPackagesToFix.set(mergedReobfPackagesToFix)
            }

            for (upstream in patcher.upstreams) {
                for (patchTask in upstream.patchTasks) {
                    patchTask.patchTask {
                        vanillaJar.convention(target, upstreamData.map{ it.vanillaJar })
                        sourceMcDevJar.convention(target, decompileJar.map { it.outputJar.path })
                        mcLibrariesDir.convention(target, upstreamData.map { it.libSourceDir })
                    }
                }
            }

            val serverProj = patcher.serverProject.forUseAtConfigurationTime().orNull ?: return@afterEvaluate
            serverProj.apply(plugin = "com.github.johnrengelman.shadow")

            generateReobfMappings {
                inputMappings.pathProvider(upstreamData.map { it.mappings })
                notchToSpigotMappings.pathProvider(upstreamData.map { it.notchToSpigotMappings })
                sourceMappings.pathProvider(upstreamData.map { it.sourceMappings })
                inputJar.set(serverProj.tasks.named("shadowJar", Jar::class).flatMap { it.archiveFile })
                spigotRecompiledClasses.pathProvider(upstreamData.map { it.spigotRecompiledClasses })

                reobfMappings.set(target.layout.cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
            }

            devBundleTasks.configure(
                patcher.serverProject,
                upstreamData.map { it.mcVersion },
                upstreamData.map { it.vanillaJar },
                decompileJar.map { it.outputJar.path },
                upstreamData.map { it.libFile },
                upstreamData.map { it.accessTransform }
            ) {
                vanillaJarIncludes.set(upstreamData.map { it.vanillaIncludes })
                reobfMappingsFile.set(patchReobfMappings.flatMap { it.outputMappings })

                paramMappingsCoordinates.set(upstreamData.map { it.paramMappings.coordinates.single() })
                paramMappingsUrl.set(upstreamData.map { it.paramMappings.url })
            }

            if(!serverProj.setupPatcherProject(
                target,
                copyResources.map { it.outputJar.path },
                decompileJar.map { it.outputJar.path },
                patcher.mcDevSourceDir.path,
                upstreamData.map { it.libFile }
            )) {
                return@afterEvaluate
            }

            val generatePaperclipPatch by target.tasks.registering<GeneratePaperclipPatch> {
                originalJar.pathProvider(upstreamData.map { it.vanillaJar })
                patchedJar.set(serverProj.tasks.named("shadowJar", Jar::class).flatMap { it.archiveFile })
                mcVersion.set(upstreamData.map { it.mcVersion })
            }

            paperclipJar.configurePaperclipJar(target, generatePaperclipPatch)
        }
    }

    private fun Project.createUpstreamTask(
        upstream: PatcherUpstream,
        ext: PaperweightPatcherExtension,
        workDirProp: Provider<String>,
        upstreamDataTaskRef: AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>
    ): Pair<TaskProvider<CheckoutRepo>?, TaskProvider<PaperweightPatcherUpstreamData>> {
        val workDirFromProp = layout.dir(workDirProp.map { File(it) }).orElse(ext.upstreamsDir)

        val upstreamData = tasks.configureTask<PaperweightPatcherUpstreamData>(upstream.upstreamDataTaskName) {
            workDir.convention(workDirFromProp)
            dataFile.convention(workDirFromProp.map { it.file("upstreamData${upstream.name.capitalize()}.json") })
        }

        val cloneTask = (upstream as? RepoPatcherUpstream)?.let { repo ->
            val cloneTask = tasks.configureTask<CheckoutRepo>(repo.cloneTaskName) {
                repoName.convention(repo.name)
                url.convention(repo.url)
                ref.convention(repo.ref)

                workDir.convention(workDirFromProp)
            }

            upstreamData {
                dependsOn(cloneTask)
                projectDir.convention(cloneTask.flatMap { it.outputDir })
            }

            return@let cloneTask
        }

        if (upstream.useForUpstreamData.getOrElse(false)) {
            upstreamDataTaskRef.set(upstreamData)
        } else {
            upstreamDataTaskRef.compareAndSet(null, upstreamData)
        }

        return cloneTask to upstreamData
    }

    private fun Project.createPatchTask(
        config: PatchTaskConfig,
        ext: PaperweightPatcherExtension,
        upstreamTaskPair: Pair<TaskProvider<CheckoutRepo>?, TaskProvider<PaperweightPatcherUpstreamData>>,
        applyPatches: TaskProvider<Task>
    ): TaskProvider<SimpleApplyGitPatches> {
        val project = this
        val patchTask = tasks.configureTask<SimpleApplyGitPatches>(config.patchTaskName) {
            group = "paperweight"

            if (isBaseExecution) {
                doNotTrackState("$name should always run when requested as part of the base execution.")
            }
            printOutput.set(isBaseExecution)

            val (cloneTask, upstreamDataTask) = upstreamTaskPair
            dependsOn(upstreamDataTask)

            if (cloneTask != null) {
                upstreamDir.convention(cloneTask.flatMap { it.outputDir.dir(config.upstreamDirPath) })
                config.apiSourceDirPath.orNull?.let { apiSourceDir.convention(cloneTask.flatMap { it.outputDir.dir(config.apiSourceDirPath) }) }
                config.serverSourceDirPath.orNull?.let{ remapSourceDir.convention(cloneTask.flatMap { it.outputDir.dir(config.serverSourceDirPath) }) }
            } else {
                upstreamDir.convention(config.upstreamDir)
                // The specific Upstream Directory isn't guaranteed to be in the root directory if the
                // project is configured like this, as much as I would like to assume it is
                // config.apiSourceDirPath.orNull?.let { config.upstreamDir.path.parent.resolve(config.apiSourceDirPath.get()) }
                // config.serverSourceDirPath.orNull?.let { config.upstreamDir.path.parent.resolve(config.serverSourceDirPath.get()) }
            }

            patchDir.convention(config.patchDir.fileExists(project))
            outputDir.convention(config.outputDir)
            mcDevSources.set(ext.mcDevSourceDir)

            bareDirectory.convention(config.isBareDirectory)
            importMcDev.convention(config.importMcDev)
            devImports.convention(ext.devImports.fileExists(project))
        }

        applyPatches {
            dependsOn(patchTask)
        }

        return patchTask
    }

    private fun Project.rebuildPatchTask(
        config: PatchTaskConfig,
        rebuildPatches: TaskProvider<Task>
    ): TaskProvider<SimpleRebuildGitPatches> {
        val rebuildTask = tasks.configureTask<SimpleRebuildGitPatches>(config.rebuildTaskName) {
            group = "paperweight"

            patchDir.convention(config.patchDir)
            inputDir.convention(config.outputDir)
            baseRef.convention("base")
        }

        rebuildPatches {
            dependsOn(rebuildTask)
        }

        return rebuildTask
    }
}
