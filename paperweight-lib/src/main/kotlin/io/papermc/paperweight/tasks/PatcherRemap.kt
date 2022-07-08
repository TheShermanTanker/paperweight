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
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class PatcherRemapJar : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingsFile: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:CompileClasspath
    abstract val remapClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val remapper: ConfigurableFileCollection

    @get:Input
    abstract val remapperArgs: ListProperty<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    override fun init() {
        super.init()

        outputJar.convention(defaultOutput())
        jvmArgs.convention(listOf("-Xmx1G"))
        remapperArgs.convention(TinyRemapper.createArgsList())
    }

    @TaskAction
    fun run() {
        mappingsFile.pathOrNull?.let {
            val logFile = layout.cache.resolve(paperTaskOutput("log"))
            TinyRemapper.run(
                argsList = remapperArgs.get(),
                logFile = logFile,
                inputJar = inputJar.path,
                mappingsFile = mappingsFile.path,
                fromNamespace = fromNamespace.get(),
                toNamespace = toNamespace.get(),
                remapClasspath = remapClasspath.files.map { it.toPath() },
                remapper = remapper,
                outputJar = outputJar.path,
                launcher = launcher.get(),
                workingDir = layout.cache,
                jvmArgs = jvmArgs.get()
            )
        } ?: run {
            inputJar.path.copyRecursivelyTo(outputJar.path, true)
        }
    }
}

@CacheableTask
abstract class CleanupPatcherMappings : JavaLauncherTask() {

    @get:Classpath
    abstract val sourceJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val initialNamespace: Property<String>

    @get:Input
    abstract val targetNamespace: Property<String>

    @get:OutputFile
    abstract val unusedMappings: RegularFileProperty

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
        unusedMappings.set(defaultOutput("unused-mappings", "txt"))
    }

    @TaskAction
    fun run() {
        inputMappings.pathOrNull?.let { _ ->
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs(jvmargs.get())
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            queue.submit(CleanupPatcherMappingsAction::class) {
                inputMappings.set(this@CleanupPatcherMappings.inputMappings.path)
                libraries.from(this@CleanupPatcherMappings.libraries.files)
                sourceJar.set(this@CleanupPatcherMappings.sourceJar.path)
                initialNamespace.set(this@CleanupPatcherMappings.initialNamespace)
                targetNamespace.set(this@CleanupPatcherMappings.targetNamespace)

                unusedMappings.set(this@CleanupPatcherMappings.unusedMappings.path)

                outputMappings.set(this@CleanupPatcherMappings.outputMappings.path)
            }
        }
    }

    abstract class CleanupPatcherMappingsAction : WorkAction<CleanupPatcherMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: RegularFileProperty
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty
            val initialNamespace: Property<String>
            val targetNamespace: Property<String>

            val unusedMappings: RegularFileProperty

            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = MappingFormats.CSRG.read(
                parameters.inputMappings.path
            )

            parameters.unusedMappings.path.deleteForcefully()
            parameters.unusedMappings.path.createFile()

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
                        .addLink(RemoveAndDumpUnusedMappings(parameters.unusedMappings.path))
                        .addLink(PropagateMappingsUp.create())
                        .addLink(CopyMappingsDown.create())
                        .applyChain(mappings, MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(
                cleanedMappings,
                parameters.outputMappings.path,
                parameters.initialNamespace.get(),
                parameters.targetNamespace.get()
            )
        }
    }
}

@CacheableTask
abstract class CleanupPatcherSourceMappings : JavaLauncherTask() {

    @get:Classpath
    abstract val sourceJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val initialNamespace: Property<String>

    @get:Input
    abstract val targetNamespace: Property<String>

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
    }

    @TaskAction
    fun run() {
        inputMappings.pathOrNull?.let { _ ->
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs(jvmargs.get())
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            queue.submit(CleanupPatcherSourceMappingsAction::class) {
                inputMappings.set(this@CleanupPatcherSourceMappings.inputMappings.path)
                libraries.from(this@CleanupPatcherSourceMappings.libraries.files)
                sourceJar.set(this@CleanupPatcherSourceMappings.sourceJar.path)
                initialNamespace.set(this@CleanupPatcherSourceMappings.initialNamespace)
                targetNamespace.set(this@CleanupPatcherSourceMappings.targetNamespace)

                outputMappings.set(this@CleanupPatcherSourceMappings.outputMappings.path)
            }
        }
    }

    abstract class CleanupPatcherSourceMappingsAction : WorkAction<CleanupPatcherSourceMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: RegularFileProperty
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty
            val initialNamespace: Property<String>
            val targetNamespace: Property<String>

            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                parameters.initialNamespace.get(),
                parameters.targetNamespace.get()
            )

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
                        .addLink(CleanupSourceMappings.RemoveLambdaMappings)
                        .addLink(CleanupSourceMappings.ParamIndexesForSource)
                        .addLink(CleanupSourceMappings.RemoveAnonymousClassRenames)
                        .addLink(CleanupSourceMappings.CleanupAfterRemoveAnonymousClassRenames)
                        .applyChain(mappings, MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(
                cleanedMappings,
                parameters.outputMappings.path,
                parameters.initialNamespace.get(),
                parameters.targetNamespace.get()
            )
        }
    }
}