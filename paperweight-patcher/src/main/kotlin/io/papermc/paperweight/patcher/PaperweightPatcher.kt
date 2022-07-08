/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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
import io.papermc.paperweight.extension.RelocationExtension
import io.papermc.paperweight.patcher.tasks.*
import io.papermc.paperweight.patcher.upstream.PatchTaskConfig
import io.papermc.paperweight.patcher.upstream.PatcherUpstream
import io.papermc.paperweight.patcher.upstream.RepoPatcherUpstream
import io.papermc.paperweight.taskcontainers.PatcherBundlerJarTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.registering
import org.gradle.plugins.ide.idea.model.IdeaModel

class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        checkJavaVersion()
        Git.checkForGit()

        val patcher = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightPatcherExtension::class, target)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(DECOMPILER_CONFIG)
        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(UPSTREAM_WORK_DIR_PROPERTY)
        val dataFileProp = target.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY)

        val applyPatches by target.tasks.registering { group = "paperweight" }
        val rebuildPatches by target.tasks.registering { group = "paperweight" }

        val generateReobfMappings by target.tasks.registering(GenerateReobfMappings::class)

        val extractFromBundler by target.tasks.registering<ExtractFromBundler> {
            versionJson.set(layout.cache.resolve(SERVER_VERSION_JSON))
            serverLibrariesTxt.set(layout.cache.resolve(SERVER_LIBRARIES_TXT))
            serverLibrariesList.set(layout.cache.resolve(SERVER_LIBRARIES_LIST))
            serverVersionsList.set(layout.cache.resolve(SERVER_VERSIONS_LIST))
            serverLibraryJars.set(layout.cache.resolve(MINECRAFT_JARS_PATH))
        }

        val filterRemappedJar by target.tasks.registering(FilterJar::class)

        val cleanupMappings by target.tasks.registering<CleanupPatcherMappings> {
            sourceJar.set(filterRemappedJar.flatMap { it.outputJar })
            libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
            inputMappings.set(patcher.mappings.fileExists(target))
            initialNamespace.set(DEOBF_NAMESPACE)
            targetNamespace.set("mojang+yarn+spigot")

            outputMappings.set(layout.cache.resolve("paperweight/mappings/mojang+yarn-mojang+yarn+spigot-cleaned.tiny"))
        }

        val cleanupSourceMappings by target.tasks.registering<CleanupPatcherSourceMappings> {
            dependsOn(cleanupMappings)
            sourceJar.set(filterRemappedJar.flatMap { it.outputJar })
            libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
            initialNamespace.set(DEOBF_NAMESPACE)
            targetNamespace.set("mojang+yarn+spigot")

            outputMappings.set(layout.cache.resolve("paperweight/mappings/mojang+yarn-mojang+yarn+spigot-source.tiny"))
        }

        val remapJar by target.tasks.registering<RemapJar> {
            dependsOn(cleanupMappings)
            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set("mojang+yarn+spigot")
            remapper.from(target.configurations.named(REMAPPER_CONFIG))
            remapperArgs.set(TinyRemapper.minecraftRemapArgs)
        }

        /*
        val fixJar by target.tasks.registering<FixJarTask> {
            inputJar.set(remapJar.flatMap { it.outputJar })
            vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
        }
         */

        val copyResources by target.tasks.registering<CopyResources> {
            inputJar.set(remapJar.flatMap { it.outputJar })
            vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
            includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta", "flightrecorder-config.jfc"))

            outputJar.set(layout.cache.resolve(FINAL_REMAPPED_JAR))
        }

        val decompileJar by target.tasks.registering<RunForgeFlower> {
            executable.from(target.configurations.named(DECOMPILER_CONFIG))
            inputJar.set(copyResources.flatMap { it.outputJar })
            libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })

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

        // val devBundleTasks = DevBundleTasks(target)

        val bundlerJarTasks = PatcherBundlerJarTasks(
            target,
            patcher.bundlerJarName,
            patcher.mainClass
        )

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

            extractFromBundler {
                bundlerJar.pathProvider(upstreamData.map { it.vanillaJar })
            }

            filterRemappedJar {
                inputJar.pathProvider(upstreamData.map { it.remappedJar })
                includes.set(upstreamData.map { it.vanillaIncludes })
            }

            cleanupSourceMappings {
                inputMappings.set(cleanupMappings.flatMap { it.outputMappings.fileExists(target) })
            }

            remapJar {
                inputJar.pathProvider(filterRemappedJar.map { it.outputJar.path })
                mappingsFile.set(cleanupMappings.flatMap { it.outputMappings.fileExists(target) })
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
                remappedJar.set(copyResources.flatMap { it.outputJar })
            }

            for (upstream in patcher.upstreams) {
                for (patchTask in upstream.patchTasks) {
                    patchTask.patchTask {
                        dependsOn(cleanupSourceMappings)
                        vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
                        remappedJar.set(filterRemappedJar.flatMap { it.outputJar })
                        minecraftLibs.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
                        mappings.set(cleanupSourceMappings.flatMap { it.outputMappings.fileExists(target) })
                        extraLibs.from(patcher.extraRemapLibs.asFileTree)
                        sourceMcDevJar.convention(target, decompileJar.map { it.outputJar.path })
                        mcLibrariesDir.convention(target, upstreamData.map { it.libSourceDir })
                        spigotLibrariesSourceDir.convention(target, upstreamData.map { it.spigotLibSourcesDir })
                    }
                }
            }

            val serverProj = patcher.serverProject.orNull ?: return@afterEvaluate
            serverProj.apply(plugin = "com.github.johnrengelman.shadow")
            val shadowJar = serverProj.tasks.named("shadowJar", Jar::class)

            generateReobfMappings {
                inputMappings.pathProvider(upstreamData.map { it.mappings })
                notchToSpigotMappings.pathProvider(upstreamData.map { it.notchToSpigotMappings })
                sourceMappings.pathProvider(upstreamData.map { it.sourceMappings })
                inputJar.set(shadowJar.flatMap { it.archiveFile })
                spigotRecompiledClasses.pathProvider(upstreamData.map { it.spigotRecompiledClasses })

                reobfMappings.set(target.layout.cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
            }

            if(!serverProj.setupPatcherProject(
                target,
                copyResources.map { it.outputJar.path },
                decompileJar.map { it.outputJar.path },
                patcher.mcDevSourceDir.path,
                upstreamData.map { it.libFile }
            )) return@afterEvaluate

            /*
            devBundleTasks.configure(
                patcher.serverProject.get(),
                patcher.bundlerJarName.get(),
                patcher.mainClass,
                upstreamData.map { it.mcVersion },
                decompileJar.map { it.outputJar.path },
                upstreamData.map { it.serverLibrariesTxt },
                upstreamData.map { it.serverLibrariesList },
                upstreamData.map { it.vanillaJar },
                upstreamData.map { it.accessTransform },
                upstreamData.map { it.bundlerVersionJson }.convertToFileProvider(layout, providers)
            ) {
                vanillaJarIncludes.set(upstreamData.map { it.vanillaIncludes })
                reobfMappingsFile.set(patchReobfMappings.flatMap { it.outputMappings })

                paramMappingsCoordinates.set(upstreamData.map { it.paramMappings.coordinates.single() })
                paramMappingsUrl.set(upstreamData.map { it.paramMappings.url })
            }
            devBundleTasks.configureAfterEvaluate()
            */

            bundlerJarTasks.configureBundlerTasks(
                upstreamData.map { it.bundlerVersionJson }.convertToFileProvider(target.layout, target.providers),
                upstreamData.map { it.serverLibrariesList }.convertToFileProvider(target.layout, target.providers),
                upstreamData.map { it.vanillaJar }.convertToFileProvider(target.layout, target.providers),
                shadowJar,
                upstreamData.map { it.mcVersion }
            )
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
    ): TaskProvider<PatcherApplyGitPatches> {
        val project = this
        val patchTask = tasks.configureTask<PatcherApplyGitPatches>(config.patchTaskName) {
            group = "paperweight"

            if (isBaseExecution) {
                doNotTrackState("$name should always run when requested as part of the base execution.")
            }
            printOutput.set(isBaseExecution)

            val (cloneTask, upstreamDataTask) = upstreamTaskPair
            dependsOn(upstreamDataTask)

            if (cloneTask != null) {
                upstreamDir.convention(cloneTask.flatMap { it.outputDir.dir(config.upstreamDirPath) })
                config.apiSourceDirPath.orNull?.let {
                    apiSourceDir.convention(cloneTask.flatMap { it.outputDir.dir(config.apiSourceDirPath) })
                }
            } else {
                upstreamDir.convention(config.upstreamDir)
                config.apiSourceDir.pathOrNull?.let {
                    apiSourceDir.convention(config.apiSourceDir)
                }
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
    ): TaskProvider<RebuildGitPatches> {
        val rebuildTask = tasks.configureTask<RebuildGitPatches>(config.rebuildTaskName) {
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

private fun Project.addMcDevSourcesRoot(mcDevSourceDir: Path) {
    plugins.apply("idea")

    val dir = mcDevSourceDir.toFile()

    the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
        java {
            srcDirs(dir)
            val pathString = dir.invariantSeparatorsPath
            exclude {
                it.file.absoluteFile.invariantSeparatorsPath.contains(pathString)
            }
        }
    }

    extensions.configure<IdeaModel> {
        module {
            generatedSourceDirs.add(dir)
        }
    }
}

private fun Project.exportRuntimeClasspathTo(parent: Project) {
    configurations.create(CONSUMABLE_RUNTIME_CLASSPATH) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        extendsFrom(configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
    }
    parent.configurations.create(SERVER_RUNTIME_CLASSPATH) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
    parent.dependencies {
        add(SERVER_RUNTIME_CLASSPATH, parent.dependencies.project(path, configuration = CONSUMABLE_RUNTIME_CLASSPATH))
    }
    afterEvaluate {
        val old = parent.repositories.toList()
        parent.repositories.clear()
        repositories.filterIsInstance<MavenArtifactRepository>().forEach {
            parent.repositories.maven(it.url) {
                name = "serverRuntimeClasspath repo ${it.url}"
                content { onlyForConfigurations(SERVER_RUNTIME_CLASSPATH) }
            }
        }
        parent.repositories.addAll(old)
    }
}

private fun Project.setupPatcherProject(
    parent: Project,
    remappedJar: Any,
    remappedJarSources: Any,
    mcDevSourceDir: Path,
    libsFile: Any
): Boolean {
    if (!projectDir.exists()) {
        return false
    }

    plugins.apply("java")

    extensions.create<RelocationExtension>(RELOCATION_EXTENSION, objects)

    exportRuntimeClasspathTo(parent)

    val vanillaServer: Configuration by configurations.creating {
        withDependencies {
            dependencies {
                // update mc-dev sources on dependency resolution
                makeMcDevSrc(
                    parent.layout.cache,
                    remappedJarSources.convertToPath(),
                    mcDevSourceDir,
                    layout.projectDirectory.path
                )

                add(create(parent.files(remappedJar)))
            }
        }
    }

    configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
        extendsFrom(vanillaServer)
        withDependencies {
            dependencies {
                val libs = libsFile.convertToPathOrNull()
                if (libs != null && libs.exists()) {
                    libs.forEachLine { line ->
                        add(create(line))
                    }
                }
            }
        }
    }

    addMcDevSourcesRoot(mcDevSourceDir)

    plugins.apply("com.github.johnrengelman.shadow")
    return true
}
