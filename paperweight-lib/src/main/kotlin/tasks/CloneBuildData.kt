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