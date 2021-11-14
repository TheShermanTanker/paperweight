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
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CloneBuildData : BaseTask() {

    @get:OutputDirectory
    abstract val buildDataDir: DirectoryProperty

    override fun init() {
        buildDataDir.convention(project, defaultOutput("", "BuildData").path)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val dir = buildDataDir.path
        if (dir.resolve(".git").notExists()) {
            dir.deleteRecursively()
            dir.createDirectories()

            Git(dir)("init", "--quiet").executeSilently()
        }

        val git = Git(dir)
        git("remote", "remove", "origin").runSilently(silenceErr = true)
        git("remote", "add", "origin", "https://hub.spigotmc.org/stash/scm/spigot/builddata.git").executeSilently(silenceErr = true)
        git("fetch", "origin").executeSilently(silenceErr = true)
        git("checkout", "-f", "FETCH_HEAD").executeSilently(silenceErr = true)
        git("clean", "-fqd").executeSilently(silenceErr = true)
    }

}