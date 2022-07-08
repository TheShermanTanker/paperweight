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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor
abstract class PatcherApplyGitPatches : ControllableOutputTask(), JavaLauncherTaskBase {

    @get:InputDirectory
    abstract val upstreamDir: DirectoryProperty

    @get:Input
    abstract val upstreamBranch: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Input
    abstract val bareDirectory: Property<Boolean>

    @get:Input
    abstract val importMcDev: Property<Boolean>

    @get:Optional
    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val remappedJar: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val extraLibs: ConfigurableFileCollection

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftLibs: ConfigurableFileCollection

    @get:Optional
    @get:InputDirectory
    abstract val apiSourceDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val mappings: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val spigotLibrariesSourceDir: DirectoryProperty

    @get:Input
    abstract val ignoreGitIgnore: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val providers: ProviderFactory

    @get:OutputDirectory
    abstract val mcDevSources: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        launcher.convention(defaultJavaLauncher(project))
        jvmargs.convention(listOf("-Xmx2G"))
        generatedAt.convention(defaultOutput("at"))
        upstreamBranch.convention("master")
        importMcDev.convention(false)
        printOutput.convention(true).finalizeValueOnRead()
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
    }

    @TaskAction
    fun runLocking() {
        val lockFile = layout.cache.resolve(applyPatchesLock(outputDir.path))
        acquireProcessLockWaiting(lockFile)
        try {
            run()
        } finally {
            lockFile.deleteForcefully()
        }
    }

    private fun run() {
        Git.checkForGit()

        val output = outputDir.path
        recreateCloneDirectory(output)

        val target = output.name

        if (printOutput.get()) {
            println("   Creating $target from patch source...")
        }

        if (bareDirectory.get()) {
            val up = upstreamDir.path
            up.resolve(".git").deleteRecursively()
            Git(up).let { upstreamGit ->
                upstreamGit("init", "--quiet").executeSilently(silenceErr = true)
                upstreamGit("checkout", "-b", upstreamBranch.get()).executeSilently(silenceErr = true)
                upstreamGit.disableAutoGpgSigningInRepo()
                upstreamGit("add", ".").executeSilently(silenceErr = true)
                upstreamGit("commit", "-m", "Initial Source", "--author=Initial <auto@mated.null>").executeSilently(silenceErr = true)
            }
        }

        val git = Git(output)
        checkoutRepoFromUpstream(git, upstreamDir.path, upstreamBranch.get())

        git.disableAutoGpgSigningInRepo()

        val srcDir = output.resolve("src/main/java")
        val testDir = output.resolve("src/test/java")

        apiSourceDir.pathOrNull?.let {
            mappings.pathOrNull?.let {
                val queue = workerExecutor.processIsolation {
                    forkOptions.jvmArgs(jvmargs.get())
                    forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
                }

                queue.submit(RemapUpstreamAction::class) {
                    classpath.from(remappedJar.path)
                    classpath.from(vanillaJar.path)
                    classpath.from(apiSourceDir.dir("src/main/java").path)
                    classpath.from(minecraftLibs.files.filter { it.toPath().isLibraryJar })
                    if(!extraLibs.isEmpty) classpath.from(extraLibs.files.filter { it.toPath().isLibraryJar })

                    remapDir.set(srcDir)
                    mappings.set(this@PatcherApplyGitPatches.mappings.path)

                    initialNamespace.set(DEOBF_NAMESPACE)
                    targetNamespace.set("mojang+yarn+spigot")

                    cacheDir.set(this@PatcherApplyGitPatches.layout.cache)
                    generatedAtOutput.set(generatedAt.path)
                }

                queue.submit(RemapUpstreamAction::class) {
                    classpath.from(remappedJar.path)
                    classpath.from(vanillaJar.path)
                    classpath.from(apiSourceDir.dir("src/main/java").path)
                    classpath.from(minecraftLibs.files.filter { it.toPath().isLibraryJar })
                    if(!extraLibs.isEmpty) classpath.from(extraLibs.files.filter { it.toPath().isLibraryJar })
                    classpath.from(srcDir)

                    remapDir.set(testDir)
                    mappings.set(this@PatcherApplyGitPatches.mappings.path)

                    initialNamespace.set(DEOBF_NAMESPACE)
                    targetNamespace.set("mojang+yarn+spigot")

                    cacheDir.set(this@PatcherApplyGitPatches.layout.cache)
                }

                queue.await()
            }
        }

        val patches = patchDir.pathOrNull?.listDirectoryEntries("*.patch") ?: listOf()
        val librarySources = ArrayList<Path>()
        spigotLibrariesSourceDir.pathOrNull?.let { librarySources.add(it) }
        mcLibrariesDir.pathOrNull?.let { librarySources.add(it) }

        if (sourceMcDevJar.isPresent && importMcDev.get()) {
            McDev.importMcDev(
                patches = patches,
                decompJar = sourceMcDevJar.path,
                importsFile = devImports.pathOrNull,
                targetDir = srcDir,
                librariesDirs = librarySources,
                printOutput = printOutput.get()
            )
        }

        git(*Git.add(ignoreGitIgnore, ".")).executeSilently()
        git("commit", "--allow-empty", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
        git("tag", "-d", "base").runSilently(silenceErr = true)
        git("tag", "base").executeSilently()

        applyGitPatches(git, target, output, patchDir.pathOrNull, printOutput.get())

        makeMcDevSrc(layout.cache, sourceMcDevJar.path, mcDevSources.path, outputDir.path, srcDir)
    }
}

private fun PatcherApplyGitPatches.defaultJavaLauncher(project: Project): Provider<JavaLauncher> =
    javaToolchainService.defaultJavaLauncher(project)
