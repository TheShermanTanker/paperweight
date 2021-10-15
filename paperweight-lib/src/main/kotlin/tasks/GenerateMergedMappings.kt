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
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.merge.MappingSetMergerHandler
import org.cadixdev.lorenz.merge.MergeConfig
import org.cadixdev.lorenz.merge.MergeContext
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateMergedMappings : BaseTask() {

    @get:Classpath
    abstract val vanillaJar: RegularFileProperty

    @get:Optional // This is not actually optional but is needed to allow delayed initialization
    @get:Classpath
    abstract val paperMappedJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val syntheticMethods: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotClassMappingsPatch: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotMemberMappingsPatch: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedMappingsPatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mojangYarnMappings: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildDataDir: DirectoryProperty

    @get:OutputFile
    abstract val patchedSpigotMemberMappings: RegularFileProperty

    @get:OutputFile
    abstract val mergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val mojangToMergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val patchedMojangToMergedMappings: RegularFileProperty

    override fun init() {
        patchedSpigotMemberMappings.convention(defaultOutput("members.csrg"))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val mappings = MappingFormats.TINY.read(
            mojangYarnMappings.path,
            OBF_NAMESPACE,
            DEOBF_NAMESPACE
        )

        val buildDataInfoRaw: RegularFileProperty = project.objects.fileFrom(buildDataDir, "info.json")
        val buildDataInfo: Provider<BuildDataInfo> = project.contents(buildDataInfoRaw) {
            gson.fromJson(it)
        }

        val mappingsDir = objects.dirFrom(buildDataDir, "mappings")

        addLines(mappingsDir.file(buildDataInfo.map { it.memberMappings }).path, spigotMemberMappingsPatch.pathOrNull, patchedSpigotMemberMappings.path)

        val classMappingSet = MappingFormats.CSRG.createReader(mappingsDir.file(buildDataInfo.map { it.classMappings }).path).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(patchedSpigotMemberMappings.path).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        if(spigotClassMappingsPatch.pathOrNull != null) {
            MappingFormats.CSRG.read(mergedMappingSet, spigotClassMappingsPatch.pathOrNull)
        }

        val synths = hashMapOf<String, MutableMap<String, MutableMap<String, String>>>()
        syntheticMethods.path.useLines { lines ->
            for (line in lines) {
                val (className, desc, synthName, baseName) = line.split(" ")
                synths.computeIfAbsent(className) { hashMapOf() }
                    .computeIfAbsent(desc) { hashMapOf() }[baseName] = synthName
            }
        }

        val fixedSpigotMappings = MappingSetMerger.create(
            mergedMappingSet,
            mappings,
            MergeConfig.builder()
                .withMergeHandler(SpigotMojangMappingsMergerHandler(synths))
                .build()
        ).merge()

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
                    .applyChain(fixedSpigotMappings, MappingsCompletionManager.create(hypoContext))
            }

        MappingFormats.TINY.write(cleanedSpigotMappings, mergedMappings.path, OBF_NAMESPACE, MERGED_NAMESPACE)

        /**
         * Spigot no longer maps fields at all, and they cause issues when the mappings are
         * merged, so the easiest solution is to just discard them since we're using the Mojang
         * field names anyway
         *
         * This will change in the future when field and parameter mappings are acquired
         */
        val mojangMappings = discardFieldMappings(mappings.reverse())
        val targetMappings = discardFieldMappings(cleanedSpigotMappings)
        val mojangToMergedSet = mojangMappings.merge(targetMappings)

        MappingFormats.TINY.write(mojangToMergedSet, mojangToMergedMappings.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)
        applyTinyPatch(mojangToMergedMappings, patchedMojangToMergedMappings)
    }

    private fun addLines(file: Path, patchFile: Path?, outputFile: Path) {
        val lines = mutableListOf<String>()
        file.useLines { seq -> seq.forEach { line -> lines += line } }
        patchFile?.useLines { seq -> seq.forEach { lines.add(it) } }
        outputFile.bufferedWriter().use { writer ->
            lines.forEach { writer.appendLine(it) }
        }
    }

    private fun applyTinyPatch(input: RegularFileProperty, output: RegularFileProperty) {
        val mappings = MappingFormats.TINY.read(
            input.path,
            DEOBF_NAMESPACE,
            MERGED_NAMESPACE
        )
        mergedMappingsPatch.pathOrNull?.let { patchFile ->
            val temp = createTempFile("patch", "tiny")
            try {
                val comment = commentRegex()
                // tiny format doesn't allow comments, so we manually remove them
                // The tiny mappings reader also doesn't have a InputStream or Reader input
                patchFile.useLines { lines ->
                    temp.bufferedWriter().use { writer ->
                        for (line in lines) {
                            val newLine = comment.replace(line, "")
                            if (newLine.isNotBlank()) {
                                writer.appendLine(newLine)
                            }
                        }
                    }
                }
                MappingFormats.TINY.read(mappings, temp, DEOBF_NAMESPACE, MERGED_NAMESPACE)
            } finally {
                temp.deleteForcefully()
            }
        }

        MappingFormats.TINY.write(mappings, output.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)
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

class SpigotMojangMappingsMergerHandler(private val synths: Synths) : MappingSetMergerHandler {

    override fun mergeTopLevelClassMappings(
        left: TopLevelClassMapping,
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException("Unexpectedly merged class: ${left.fullObfuscatedName}")
    }

    override fun mergeDuplicateTopLevelClassMappings(
        left: TopLevelClassMapping,
        right: TopLevelClassMapping,
        rightContinuation: TopLevelClassMapping?,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // If both are provided, keep spigot
        return MergeResult(
            target.createTopLevelClassMapping(left.obfuscatedName, left.deobfuscatedName),
            right
        )
    }

    override fun addLeftTopLevelClassMapping(
        left: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        return MergeResult(
            target.createTopLevelClassMapping(right.obfuscatedName, right.deobfuscatedName),
            right
        )
    }

    override fun mergeInnerClassMappings(
        left: InnerClassMapping,
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        throw IllegalStateException("Unexpectedly merged class: ${left.fullObfuscatedName}")
    }

    override fun mergeDuplicateInnerClassMappings(
        left: InnerClassMapping,
        right: InnerClassMapping,
        rightContinuation: InnerClassMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        return MergeResult(
            target.createInnerClassMapping(left.obfuscatedName, left.deobfuscatedName),
            right
        )
    }

    override fun addLeftInnerClassMapping(
        left: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping> {
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    override fun addRightInnerClassMapping(
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        return MergeResult(target.createInnerClassMapping(right.obfuscatedName, right.deobfuscatedName), right)
    }

    override fun mergeFieldMappings(
        left: FieldMapping,
        strictRight: FieldMapping?,
        looseRight: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
        throw IllegalStateException("Unexpectedly merged field: ${left.fullObfuscatedName}")
    }

    override fun mergeDuplicateFieldMappings(
        left: FieldMapping,
        strictRightDuplicate: FieldMapping?,
        looseRightDuplicate: FieldMapping?,
        strictRightContinuation: FieldMapping?,
        looseRightContinuation: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
        val right = strictRightDuplicate ?: looseRightDuplicate ?: strictRightContinuation ?: looseRightContinuation ?: left
        return target.createFieldMapping(right.signature, left.deobfuscatedName)
    }

    override fun addLeftFieldMapping(left: FieldMapping, target: ClassMapping<*, *>, context: MergeContext): FieldMapping? {
        // We don't want mappings Spigot thinks exist but don't
        return null
    }

    override fun mergeMethodMappings(
        left: MethodMapping,
        standardRight: MethodMapping?,
        wiggledRight: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        throw IllegalStateException("Unexpectedly merged method: $left")
    }

    override fun mergeDuplicateMethodMappings(
        left: MethodMapping,
        standardRightDuplicate: MethodMapping?,
        wiggledRightDuplicate: MethodMapping?,
        standardRightContinuation: MethodMapping?,
        wiggledRightContinuation: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        // Check if Spigot calls this mapping something else
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        val newName = synthMethods?.get(left.obfuscatedName)
        return if (newName != null) {
            val newLeftMapping = left.parentClass.getMethodMapping(MethodSignature(newName, left.descriptor)).orNull
            val newMapping = if (newLeftMapping != null) {
                target.getOrCreateMethodMapping(newLeftMapping.signature).also {
                    it.deobfuscatedName = left.deobfuscatedName
                }
            } else {
                target.getOrCreateMethodMapping(left.signature).also {
                    it.deobfuscatedName = newName
                }
            }
            MergeResult(newMapping)
        } else {
            val newMapping = target.getOrCreateMethodMapping(left.signature).also {
                it.deobfuscatedName = left.deobfuscatedName
            }
            return MergeResult(newMapping)
        }
    }

    override fun addLeftMethodMapping(
        left: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        /*
         * Check if Spigot maps this from a synthetic method name
         * What this means is:
         * Spigot has a mapping for
         *     a b()V -> something
         * But in Mojang's mapping there's only
         *     a a()V -> somethingElse
         * The original method is named a, but spigot calls it b, because
         * b is a synthetic method for a. In this case we should create the mapping as
         *     a a()V -> something
         */

        var obfName: String? = null
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        if (synthMethods != null) {
            // This is a reverse lookup
            for ((base, synth) in synthMethods) {
                if (left.obfuscatedName == synth) {
                    obfName = base
                    break
                }
            }
        }

        if (obfName == null) {
            return emptyMergeResult()
        }

        val newMapping = target.getOrCreateMethodMapping(obfName, left.descriptor)
        newMapping.deobfuscatedName = left.deobfuscatedName
        return MergeResult(newMapping)
    }

    override fun addRightMethodMapping(
        right: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        val newMapping = target.getOrCreateMethodMapping(right.signature)
        newMapping.deobfuscatedName = right.deobfuscatedName
        return MergeResult(newMapping)
    }
}