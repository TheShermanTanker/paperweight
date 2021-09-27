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

package io.papermc.paperweight.tasks

import com.github.salomonbrys.kotson.fromJson
import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateMergedMappings : BaseTask() {

    @get:Classpath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotClassMappingsPatch: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotMemberMappingsPatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mojangYarnMappings: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildDataDir: DirectoryProperty

    @get:OutputFile
    abstract val cleanedSpigotMemberMappings: RegularFileProperty

    @get:OutputFile
    abstract val spigotMappings: RegularFileProperty

    @get:OutputFile
    abstract val mergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val mojangToMergedMappings: RegularFileProperty

    override fun init() {
        spigotMappings.convention(defaultOutput("tiny"))
        cleanedSpigotMemberMappings.convention(defaultOutput("member.csrg"))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val mappings = MappingFormats.TINY.read(
            mojangYarnMappings.path,
            OBF_NAMESPACE,
            DEOBF_NAMESPACE
        )

        val reversedMappings = mappings.reverse()

        val buildDataInfoRaw: RegularFileProperty = project.objects.fileFrom(buildDataDir, "info.json")
        val buildDataInfo: Provider<BuildDataInfo> = project.contents(buildDataInfoRaw) {
            gson.fromJson(it)
        }

        val mappingsDir = objects.dirFrom(buildDataDir, "mappings")

        addLines(mappingsDir.file(buildDataInfo.map { it.memberMappings }).path, spigotMemberMappingsPatch.pathOrNull, cleanedSpigotMemberMappings.path)

        val classMappingSet = MappingFormats.CSRG.createReader(mappingsDir.file(buildDataInfo.map { it.classMappings }).path).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(cleanedSpigotMemberMappings.path).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        // Fix crash when no class patches are found
        if(spigotClassMappingsPatch.pathOrNull != null) {
            MappingFormats.CSRG.read(mergedMappingSet, spigotClassMappingsPatch.pathOrNull)
        }

        val libs = libraries.files.asSequence()
            .map { f -> f.toPath() }
            .filter { p -> p.isLibraryJar }
            .map { p -> ClassProviderRoot.fromJar(p) }
            .toList()

        val cleanedSpigotMappings = HypoContext.builder()
            .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(vanillaJar.path)))
            .withContextProviders(AsmClassDataProvider.of(libs))
            .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
            .build().use { hypoContext ->
                HydrationManager.createDefault()
                    .register(BridgeMethodHydrator.create())
                    .register(SuperConstructorHydrator.create())
                    .hydrate(hypoContext)

                ChangeChain.create()
                    .addLink(RemoveUnusedMappings.create())
                    .addLink(PropagateMappingsUp.create())
                    .addLink(CopyMappingsDown.create())
                    .applyChain(mergedMappingSet, MappingsCompletionManager.create(hypoContext))
            }

        MappingFormats.TINY.write(
            cleanedSpigotMappings,
            spigotMappings.path,
            OBF_NAMESPACE,
            SPIGOT_NAMESPACE
        )

        MappingFormats.TINY.read(mappings, spigotMappings.path, OBF_NAMESPACE, SPIGOT_NAMESPACE)
        MappingFormats.TINY.write(mappings, mergedMappings.path, OBF_NAMESPACE, MERGED_NAMESPACE)

        /**
         * Spigot no longer maps fields at all, and they cause issues when the mappings are
         * merged, so the easiest solution is to just discard them since we're using the Mojang
         * field names anyway
         *
         * This will change in the future when field and parameter mappings are acquired
         */
        val mojangMappings = discardFieldMappings(reversedMappings)
        val targetMappings = discardFieldMappings(mappings)
        val mojangToMergedSet = mojangMappings.merge(targetMappings)

        MappingFormats.TINY.write(mojangToMergedSet, mojangToMergedMappings.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)
    }

    private fun addLines(inFile: Path, patchFile: Path?, outputFile: Path) {
        val lines = mutableListOf<String>()
        inFile.useLines { seq -> seq.forEach { line -> lines += line } }
        patchFile?.useLines { seq -> seq.forEach { lines.add(it) } }
        outputFile.bufferedWriter().use { writer ->
            lines.forEach { writer.appendLine(it) }
        }
    }

    private fun discardFieldMappings(mappings: MappingSet): MappingSet {
        val mappingSet = MappingSet.create()

        for (topLevelClassMapping in mappings.topLevelClassMappings) {
            discardFieldMappingsInClass(topLevelClassMapping, mappingSet.createTopLevelClassMapping(topLevelClassMapping.obfuscatedName, topLevelClassMapping.deobfuscatedName))
        }

        return mappingSet
    }

    private fun discardFieldMappingsInClass(mappings: ClassMapping<*, *>, result: ClassMapping<*, *>) {
        for (innerClassMapping in mappings.innerClassMappings) {
            discardFieldMappingsInClass(innerClassMapping, result.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName))
        }

        mappings.methodMappings.forEach {
            result.createMethodMapping(it.signature, it.deobfuscatedName)
        }
    }
}