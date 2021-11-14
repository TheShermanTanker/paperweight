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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext
) : SpigotTasks(project) {

    val mergeAdditionalAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(mergeGeneratedAts.flatMap { it.outputFile })
        secondFile.set(extension.paper.additionalAts.fileExists(project))
    }

    val applyMergedAt by tasks.registering<ApplyAccessTransform> {
        inputJar.set(fixJar.flatMap { it.outputJar })
        atFile.set(mergeAdditionalAts.flatMap { it.outputFile })
    }

    val copyResources by tasks.registering<CopyResources> {
        inputJar.set(applyMergedAt.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

        outputJar.set(cache.resolve(FINAL_REMAPPED_JAR))
    }

    val decompileJar by tasks.registering<RunForgeFlower> {
        executable.from(project.configurations.named(DECOMPILER_CONFIG))

        inputJar.set(copyResources.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })

        outputJar.set(cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val applyApiPatches by tasks.registering<ApplyGitPatches> {
        group = "paper"
        description = "Setup the Paper-API project"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(patchSpigotApi.flatMap { it.outputDir })
        patchDir.set(extension.paper.spigotApiPatchDir)
        unneededFiles.value(listOf("README.md"))

        outputDir.set(extension.paper.paperApiDir)
    }

    @Suppress("DuplicatedCode")
    val applyServerPatches by tasks.registering<ApplyPaperPatches> {
        group = "paper"
        description = "Setup the Paper-Server project"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        patchDir.set(extension.paper.spigotServerPatchDir)
        remappedSource.set(remapSpigotSources.flatMap { it.sourcesOutputZip })
        remappedTests.set(remapSpigotSources.flatMap { it.testsOutputZip })
        caseOnlyClassNameChanges.set(cleanupSourceMappings.flatMap { it.caseOnlyNameChanges })
        upstreamDir.set(patchSpigotServer.flatMap { it.outputDir })
        sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
        mcLibrariesDir.set(downloadMcLibrariesSources.flatMap { it.outputDir })
        devImports.set(extension.paper.devImports.fileExists(project))
        unneededFiles.value(listOf("nms-patches", "applyPatches.sh", "CONTRIBUTING.md", "makePatches.sh", "README.md"))

        outputDir.set(extension.paper.paperServerDir)
        mcDevSources.set(extension.mcDevSourceDir)
    }

    val applyPatches by tasks.registering<Task> {
        group = "paper"
        description = "Set up the Paper development environment"
        dependsOn(applyApiPatches, applyServerPatches)
    }

    val rebuildApiPatches by tasks.registering<RebuildPaperPatches> {
        group = "paper"
        description = "Rebuilds patches to api"
        inputDir.set(extension.paper.paperApiDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotApiPatchDir)
    }

    val rebuildServerPatches by tasks.registering<RebuildPaperPatches> {
        group = "paper"
        description = "Rebuilds patches to server"
        inputDir.set(extension.paper.paperServerDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotServerPatchDir)
    }

    @Suppress("unused")
    val rebuildPatches by tasks.registering<Task> {
        group = "paper"
        description = "Rebuilds patches to api and server"
        dependsOn(rebuildApiPatches, rebuildServerPatches)
    }

    val generateReobfMappings by tasks.registering<GenerateReobfMappings> {
        inputMappings.set(patchMappings.flatMap { it.outputMappings })
        notchToSpigotMappings.set(generateSpigotMappings.flatMap { it.notchToSpigotMappings })
        sourceMappings.set(generateMappings.flatMap { it.outputMappings })
        spigotRecompiledClasses.set(remapSpigotSources.flatMap { it.spigotRecompiledClasses })

        reobfMappings.set(cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val patchReobfMappings by tasks.registering<PatchMappings> {
        inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
        patch.set(extension.paper.reobfMappingsPatch.fileExists(project))

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val cloneBuildData by tasks.registering<CloneBuildData>()

    val generateMergedMappings by tasks.registering<GenerateMergedMappings> {
        vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
        mojangMappedJar.set(fixJar.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })
        syntheticMethods.set(inspectVanillaJar.flatMap { it.syntheticMethods })

        buildDataDir.set(cloneBuildData.flatMap { it.buildDataDir })
        mojangYarnMappings.set(generateMappings.flatMap { it.outputMappings })
        spigotClassMappingsPatch.set(project.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_SPIGOT_CLASS_MAPPINGS_PATCH).map { File(it) }.orNull.convertToPathOrNull())
        spigotMemberMappingsPatch.set(project.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_SPIGOT_MEMBER_MAPPINGS_PATCH).map { File(it) }.orNull.convertToPathOrNull())
        mergedMappingsPatch.set(project.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_MERGED_MAPPINGS_PATCH).map { File(it) }.orNull.convertToPathOrNull())

        mergedMappings.set(cache.resolve(MOJANG_YARN_SPIGOT_MAPPINGS))
        mojangToMergedMappings.set((cache.resolve(MOJANG_YARN_MOJANG_YARN_SPIGOT_MAPPINGS)))
        patchedMojangToMergedMappings.set(cache.resolve(PATCHED_MOJANG_YARN_MOJANG_YARN_SPIGOT_MAPPINGS))
        patchedMojangToMergedSourceMappings.set(cache.resolve(PATCHED_MOJANG_YARN_MOJANG_YARN_SPIGOT_SOURCE_MAPPINGS))
        generatedMojangToMergedPatch.set(cache.resolve(GENERATED_MERGED_MAPPINGS_PATCH))
    }

    val remapForDownstream by tasks.registering<RemapForDownstream> {
        serverDir.set(applyServerPatches.flatMap { it.outputDir })
        apiDir.set(applyApiPatches.flatMap { it.outputDir })
        mappings.set(generateMergedMappings.flatMap { it.patchedMojangToMergedSourceMappings })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        mojangMappedVanillaJar.set(fixJar.flatMap { it.outputJar })
        spigotDeps.from(downloadSpigotDependencies.map { it.outputDir.asFileTree })
    }

    val remapJarMerged by tasks.registering<RemapJar> {
        inputJar.set(applyMergedAt.flatMap { it.outputJar })
        mappingsFile.set(generateMergedMappings.flatMap { it.patchedMojangToMergedMappings })
        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(MERGED_NAMESPACE)
        remapper.from(project.configurations.named(REMAPPER_CONFIG))
    }

    val exportMergedVanillaJar by tasks.registering<CopyResources> {
        inputJar.set(remapJarMerged.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

        outputJar.set(cache.resolve(FINAL_REMAPPED_MERGED_JAR))
    }

    val decompileJarMerged by tasks.registering<RunForgeFlower> {
        executable.from(project.configurations.named(DECOMPILER_CONFIG))

        inputJar.set(exportMergedVanillaJar.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })

        outputJar.set(cache.resolve(FINAL_DECOMPILE_MERGED_JAR))
    }
}
