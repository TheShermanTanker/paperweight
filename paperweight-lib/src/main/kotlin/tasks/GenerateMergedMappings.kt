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
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.ClassMappingsChange
import dev.denwav.hypo.mappings.LorenzUtil
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.MergeableMappingsChange
import dev.denwav.hypo.mappings.changes.AbstractMappingsChange
import dev.denwav.hypo.mappings.changes.MemberReference
import dev.denwav.hypo.mappings.changes.RemoveMappingChange
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import dev.denwav.hypo.model.data.ClassData
import dev.denwav.hypo.model.data.types.PrimitiveType
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import javax.inject.Inject
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
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class GenerateMergedMappings : JavaLauncherTask() {

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Classpath
    abstract val vanillaJar: RegularFileProperty

    @get:Classpath
    abstract val mojangMappedJar: RegularFileProperty

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
    abstract val cleanedPatch: RegularFileProperty

    @get:OutputFile
    abstract val mergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val mojangToMergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val patchedMojangToMergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val patchedMojangToMergedSourceMappings: RegularFileProperty

    @get:OutputFile
    abstract val generatedMojangToMergedPatch: RegularFileProperty

    override fun init() {
        super.init()
        jvmargs.convention(listOf("-Xmx2G"))
        cleanedPatch.set(defaultOutput("cleaned-merged-mappings-patch", "tiny"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

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

        appendLines(mappingsDir.file(buildDataInfo.map { it.memberMappings }).path, spigotMemberMappingsPatch.pathOrNull)

        val classMappingSet = MappingFormats.CSRG.createReader(mappingsDir.file(buildDataInfo.map { it.classMappings }).path).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(mappingsDir.file(buildDataInfo.map { it.memberMappings }).path).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        if(spigotClassMappingsPatch.pathOrNull != null) {
            MappingFormats.CSRG.read(mergedMappingSet, spigotClassMappingsPatch.path)
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

        queue.submit(CleanMappingsAction::class) {
            sourceJar.set(this@GenerateMergedMappings.vanillaJar.path)
            libraries.from(this@GenerateMergedMappings.libraries.files)
            inputMappings.set(fixedSpigotMappings)
            fromNamespace.set(OBF_NAMESPACE)
            toNamespace.set(MERGED_NAMESPACE)
            outputMappings.set(this@GenerateMergedMappings.mergedMappings.path)
        }

        queue.await()

        /**
         * Spigot no longer maps fields at all, and they cause issues when the mappings are
         * merged, so the easiest solution is to just discard them since we're using the Mojang
         * field names anyway
         *
         * This may change in the future when Spigot field and parameter mappings are acquired
         */
        val mojangMappings = filterClassAndMethodMappings(mappings.reverse())
        val targetMappings = filterClassAndMethodMappings(MappingFormats.TINY.read(mergedMappings.path, OBF_NAMESPACE, MERGED_NAMESPACE))
        val mojangToMergedSet = mojangMappings.merge(targetMappings)

        MappingFormats.TINY.write(mojangToMergedSet, mojangToMergedMappings.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)

        if(mergedMappingsPatch.pathOrNull != null) {
            queue.submit(CleanMappingsAction::class) {
                sourceJar.set(this@GenerateMergedMappings.mojangMappedJar.path)
                libraries.from(this@GenerateMergedMappings.libraries.files)
                inputMappings.set(MappingFormats.CSRG.read(mergedMappingsPatch.path))
                fromNamespace.set(DEOBF_NAMESPACE)
                toNamespace.set(MERGED_NAMESPACE)
                outputMappings.set(this@GenerateMergedMappings.cleanedPatch.path)
            }
            queue.await()
        }

        if(cleanedPatch.asFile.isPresent) {
            MappingFormats.TINY.read(mojangToMergedSet, cleanedPatch.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)
        }

        MappingFormats.TINY.write(mojangToMergedSet, patchedMojangToMergedMappings.path, DEOBF_NAMESPACE, MERGED_NAMESPACE)

        queue.submit(CleanSourceMappingsAction::class) {
            sourceJar.set(this@GenerateMergedMappings.mojangMappedJar.path)
            libraries.from(this@GenerateMergedMappings.libraries.files)
            inputMappings.set(mojangToMergedSet)
            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(MERGED_NAMESPACE)
            outputMappings.set(this@GenerateMergedMappings.patchedMojangToMergedSourceMappings.path)
        }

        queue.await()

        queue.submit(GenerateMergedMappingsPatchAction::class) {
            sourceJar.set(this@GenerateMergedMappings.mojangMappedJar.path)
            libraries.from(this@GenerateMergedMappings.libraries.files)
            inputMappings.set(mojangToMergedSet)
            outputMappings.set(this@GenerateMergedMappings.generatedMojangToMergedPatch.path)
        }
    }

    object RemoveSourceLambdaMappings : ChangeContributor {

        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (currentClass == null || classMapping == null) {
                return
            }

            for (methodMapping in classMapping.methodMappings) {
                if (methodMapping.deobfuscatedName.startsWith("lambda$")) {
                    registry.submitChange(RemoveMappingChange.of(MemberReference.of(methodMapping)))
                }
            }
        }

        override fun name(): String = "RemoveSourceLambdaMappings"
    }

    object ParamIndexBytecodeToSource : ChangeContributor {

        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (currentClass == null || classMapping == null) {
                return
            }

            for (methodMapping in classMapping.methodMappings) {
                val method = LorenzUtil.findMethod(currentClass, methodMapping) ?: continue

                var methodRef: MemberReference? = null

                var lvtIndex = if (method.isStatic) 0 else 1
                if (method.isConstructor && currentClass.outerClass() != null && !currentClass.isStaticInnerClass) {
                    lvtIndex += 1
                }
                for ((sourceIndex, param) in method.params().withIndex()) {
                    if (methodMapping.hasParameterMapping(lvtIndex)) {
                        if (methodRef == null) {
                            methodRef = MemberReference.of(methodMapping)
                        }
                        registry.submitChange(ParamIndexBytecodeToSourceChange(methodRef, lvtIndex, sourceIndex))
                    }
                    lvtIndex++
                    if (param === PrimitiveType.LONG || param === PrimitiveType.DOUBLE) {
                        lvtIndex++
                    }
                }
            }
        }

        override fun name(): String = "ParamIndexesBytecodeToSource"

        class ParamIndexBytecodeToSourceChange(
            target: MemberReference,
            fromIndex: Int,
            toIndex: Int
        ) : AbstractMappingsChange(target), MergeableMappingsChange<ParamIndexBytecodeToSourceChange> {

            private val indexMap: MutableMap<Int, Int> = HashMap()

            init {
                indexMap[fromIndex] = toIndex
            }

            override fun applyChange(input: MappingSet, ref: MemberReference) {
                val classMapping = input.getOrCreateClassMapping(ref.className())
                val methodMapping = classMapping.getOrCreateMethodMapping(ref.name(), ref.desc())

                val paramsMap = LorenzUtil.getParamsMap(methodMapping)
                val params = paramsMap.values.toList()
                paramsMap.clear()

                for (param in params) {
                    methodMapping.createParameterMapping(indexMap[param.index] ?: param.index, param.deobfuscatedName)
                }
            }

            override fun mergeWith(that: ParamIndexBytecodeToSourceChange): dev.denwav.hypo.mappings.MergeResult<ParamIndexBytecodeToSourceChange> {
                for (fromIndex in this.indexMap.keys) {
                    if (that.indexMap.containsKey(fromIndex)) {
                        return dev.denwav.hypo.mappings.MergeResult.failure("Cannot merge 2 param mappings changes with matching fromIndexes")
                    }
                }
                for (toIndex in this.indexMap.values) {
                    if (that.indexMap.containsValue(toIndex)) {
                        return dev.denwav.hypo.mappings.MergeResult.failure("Cannot merge 2 param mappings changes with matching toIndex")
                    }
                }

                this.indexMap += that.indexMap
                return dev.denwav.hypo.mappings.MergeResult.success(this)
            }

            override fun toString(): String {
                return "Move param mappings for ${target()} for index pairs [${indexMap.entries.joinToString(", ") { "${it.key}:${it.value}" }}]"
            }
        }
    }

    companion object {
        const val TEMP_SUFFIX = "paperweight-remove-anon-renames-temp-suffix"
    }

    object DeleteAnonymousClassRenames : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) return

            val obf = classMapping.obfuscatedName.toIntOrNull()
            val deobf = classMapping.deobfuscatedName.toIntOrNull()

            if (obf != null && deobf != null && obf != deobf) {
                val newName = classMapping.fullObfuscatedName.substringBeforeLast('$') + '$' + classMapping.deobfuscatedName + TEMP_SUFFIX
                registry.submitChange(ModifyObfClassName(classMapping.fullObfuscatedName, newName))
            }
        }

        override fun name(): String = "DeleteAnonymousClassRenames"
    }

    object PostRemoveAnonymousClassRenameCleanup : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) return

            if (classMapping.fullObfuscatedName.endsWith(TEMP_SUFFIX)) {
                val newName = classMapping.fullObfuscatedName.substringBefore(TEMP_SUFFIX)
                registry.submitChange(ModifyObfClassName(classMapping.fullObfuscatedName, newName))
            }
        }

        override fun name(): String = "PostRemoveAnonymousClassRenameCleanup"
    }

    abstract class CleanMappingsAction : WorkAction<CleanMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: Property<MappingSet>
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty

            val fromNamespace: Property<String>
            val toNamespace: Property<String>
            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = parameters.inputMappings

            val cleanedMappings = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.sourceJar.path)))
                .withContextProvider(AsmClassDataProvider.of(parameters.libraries.toJarClassProviderRoots()))
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
                        .applyChain(mappings.get(), MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(cleanedMappings, parameters.outputMappings.path, parameters.fromNamespace.get(), parameters.toNamespace.get())
        }
    }

    abstract class CleanSourceMappingsAction : WorkAction<CleanSourceMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: Property<MappingSet>
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty

            val fromNamespace: Property<String>
            val toNamespace: Property<String>
            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = parameters.inputMappings

            val cleanedMappings = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.sourceJar.path)))
                .withContextProvider(AsmClassDataProvider.of(parameters.libraries.toJarClassProviderRoots()))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveSourceLambdaMappings)
                        .addLink(ParamIndexBytecodeToSource)
                        .addLink(DeleteAnonymousClassRenames)
                        .addLink(PostRemoveAnonymousClassRenameCleanup)
                        .applyChain(mappings.get(), MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(cleanedMappings, parameters.outputMappings.path, parameters.fromNamespace.get(), parameters.toNamespace.get())
        }
    }

    abstract class GenerateMergedMappingsPatchAction : WorkAction<GenerateMergedMappingsPatchAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: Property<MappingSet>
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty

            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = parameters.inputMappings

            val cleanedMappings = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.sourceJar.path)))
                .withContextProvider(AsmClassDataProvider.of(parameters.libraries.toJarClassProviderRoots()))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveUnusedMappings.create())
                        .addLink(PropagateMappingsUp.create())
                        .applyChain(mappings.get(), MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.CSRG.write(cleanedMappings, parameters.outputMappings.path)
        }
    }
}

private fun appendLines(file: Path, patch: Path?) {
    val lines = mutableListOf<String>()
    file.useLines { sequence -> sequence.forEach { line -> lines += line } }
    patch?.useLines { sequence -> sequence.forEach { lines.add(it) } }
    file.bufferedWriter().use { writer ->
        lines.forEach { writer.appendLine(it) }
    }
}

typealias Synthetics = Map<String, Map<String, Map<String, String>>>
class SpigotMojangMappingsMergerHandler(private val synths: Synthetics) : MappingSetMergerHandler {

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
            MergeResult(newMapping)
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

class ModifyObfClassName(
    private val targetClass: String,
    private val newFullObfuscatedName: String
) : ClassMappingsChange {
    override fun targetClass(): String = targetClass

    override fun applyChange(input: MappingSet) {
        val classMapping = LorenzUtil.getClassMapping(input, targetClass) ?: return
        LorenzUtil.removeClassMapping(classMapping)

        val newMap = input.getOrCreateClassMapping(newFullObfuscatedName)
        copyMapping(classMapping, newMap)
    }

    private fun copyMapping(from: ClassMapping<*, *>, to: ClassMapping<*, *>) {
        to.deobfuscatedName = from.deobfuscatedName

        for (methodMapping in from.methodMappings) {
            methodMapping.copy(to)
        }
        for (fieldMapping in from.fieldMappings) {
            fieldMapping.copy(to)
        }
        for (innerClassMapping in from.innerClassMappings) {
            innerClassMapping.copy(to)
        }
    }
}

private fun filterClassAndMethodMappings(mappings: MappingSet): MappingSet {
    val mappingSet = MappingSet.create()

    for (topLevelClassMapping in mappings.topLevelClassMappings) {
        filterInnerClassAndMethodMappings(topLevelClassMapping, mappingSet.createTopLevelClassMapping(topLevelClassMapping.obfuscatedName, topLevelClassMapping.deobfuscatedName))
    }

    return mappingSet
}

private fun filterInnerClassAndMethodMappings(mappings: ClassMapping<*, *>, result: ClassMapping<*, *>) {
    for (innerClassMapping in mappings.innerClassMappings) {
        filterInnerClassAndMethodMappings(innerClassMapping, result.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName))
    }

    mappings.methodMappings.forEach {
        result.createMethodMapping(it.signature, it.deobfuscatedName)
    }
}