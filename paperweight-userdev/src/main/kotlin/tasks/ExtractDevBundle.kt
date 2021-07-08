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

package io.papermc.paperweight.userdev.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ExtractDevBundle : BaseTask() {
    @get:InputFiles
    abstract val devBundleZip: RegularFileProperty

    @get:OutputDirectory
    abstract val outputFolder: DirectoryProperty

    @TaskAction
    private fun run() {
        extractDevBundle(outputFolder.path, devBundleZip.path)
    }
}

fun extractDevBundle(destinationDirectory: Path, devBundle: Path) {
    if (destinationDirectory.exists()) {
        destinationDirectory.deleteRecursively()
    }
    destinationDirectory.createDirectories()
    devBundle.openZip().use { fs ->
        fs.getPath("/").copyRecursivelyTo(destinationDirectory)
    }
}
