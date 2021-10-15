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

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class RemapPaperSources : JavaLauncherTask() {

    @get:CompileClasspath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val mojangMappedVanillaJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:CompileClasspath
    abstract val spigotDeps: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val remapDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val paperApiDir: DirectoryProperty

    @get:OutputFile
    abstract val sourcesOutputZip: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx2G"))
        sourcesOutputZip.convention(defaultOutput("$name-sources", "zip"))
    }

    @TaskAction
    fun run() {
        val srcOut = findOutputDir(sourcesOutputZip.path).apply { createDirectories() }

        try {
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs(jvmargs.get())
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            val srcDir = remapDir.path.resolve("src/main/java")

            // Remap sources
            queue.submit(RemapAction::class) {
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(paperApiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })

                mappings.set(this@RemapPaperSources.mappings.path)
                inputDir.set(srcDir)

                cacheDir.set(this@RemapPaperSources.layout.cache)

                outputDir.set(srcOut)
            }

            queue.await()

            zip(srcOut, sourcesOutputZip)
        } finally {
            srcOut.deleteRecursively()
        }
    }

    abstract class RemapAction : WorkAction<RemapMojangParams> {
        override fun execute() {
            val mappingSet = MappingFormats.TINY.read(
                parameters.mappings.path,
                DEOBF_NAMESPACE,
                MERGED_NAMESPACE
            )

            Mercury().let { mercury ->
                mercury.classPath.addAll(parameters.classpath.map { it.toPath() })

                mercury.isGracefulClasspathChecks = true

                mercury.process(parameters.inputDir.path)

                val tempOut = Files.createTempDirectory(parameters.cacheDir.path, "remap")
                try {
                    mercury.processors.clear()
                    mercury.processors.addAll(
                        listOf(
                            RemapSources.ExplicitThisAdder,
                            MercuryRemapper.create(mappingSet)
                        )
                    )

                    mercury.rewrite(parameters.inputDir.path, tempOut)

                    tempOut.copyRecursivelyTo(parameters.outputDir.path)
                } finally {
                    tempOut.deleteRecursively()
                }
            }
        }
    }

    interface RemapMojangParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val mappings: RegularFileProperty
        val inputDir: RegularFileProperty

        val cacheDir: RegularFileProperty
        val outputDir: RegularFileProperty
    }
}
