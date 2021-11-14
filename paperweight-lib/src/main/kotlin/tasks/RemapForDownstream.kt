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
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.eclipse.jdt.core.dom.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class RemapForDownstream : JavaLauncherTask() {

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
    abstract val serverDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiDir: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val ignoreGitIgnore: Property<Boolean>

    @get:Inject
    abstract val providers: ProviderFactory

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx2G"))
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
        generatedAt.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        val srcDir = serverDir.path.resolve("src/main/java")

        // Remap sources
        queue.submit(RemapForDownstreamAction::class) {
            classpath.from(mojangMappedVanillaJar.path)
            classpath.from(vanillaJar.path)
            classpath.from(apiDir.dir("src/main/java").path)
            classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })

            mappings.set(this@RemapForDownstream.mappings.path)
            remapDir.set(srcDir)
            ignoreGitIgnore.set(this@RemapForDownstream.ignoreGitIgnore)

            cacheDir.set(this@RemapForDownstream.layout.cache)

            generatedAtOutput.set(generatedAt.path)
        }

        val testSrc = serverDir.path.resolve("src/test/java")

        // Remap tests
        queue.submit(RemapForDownstreamAction::class) {
            classpath.from(mojangMappedVanillaJar.path)
            classpath.from(vanillaJar.path)
            classpath.from(apiDir.dir("src/main/java").path)
            classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
            classpath.from(srcDir)

            mappings.set(this@RemapForDownstream.mappings.path)
            remapDir.set(testSrc)

            cacheDir.set(this@RemapForDownstream.layout.cache)
        }
    }

    abstract class RemapForDownstreamAction : WorkAction<RemapForDownstreamParams> {
        override fun execute() {
            val mappingSet = MappingFormats.TINY.read(
                parameters.mappings.path,
                DEOBF_NAMESPACE,
                MERGED_NAMESPACE
            )

            val processAt = AccessTransformSet.create()
            val generatedAtOutPath = parameters.generatedAtOutput.pathOrNull

            Mercury().let { mercury ->
                mercury.classPath.addAll(parameters.classpath.map { it.toPath() })

                if (generatedAtOutPath != null) {
                    mercury.processors += AccessAnalyzerProcessor.create(processAt, mappingSet)
                } else {
                    mercury.isGracefulClasspathChecks = true
                }

                mercury.process(parameters.remapDir.path)

                val tempOut = Files.createTempDirectory(parameters.cacheDir.path, "remap")
                try {
                    mercury.processors.clear()
                    mercury.processors.addAll(
                        listOf(
                            ExplicitDownstreamThisAdder,
                            MercuryRemapper.create(mappingSet),
                            AccessTransformerRewriter.create(processAt)
                        )
                    )

                    if (generatedAtOutPath != null) {
                        mercury.processors.add(AccessTransformerRewriter.create(processAt))
                    }

                    mercury.rewrite(parameters.remapDir.path, tempOut)

                    parameters.remapDir.path.deleteRecursively()
                    parameters.remapDir.path.createDirectories()
                    tempOut.copyRecursivelyTo(parameters.remapDir.path)
                    val git = Git(parameters.remapDir.path)
                    git("init", "--quiet").executeSilently(silenceErr = true)
                    git(*Git.add(parameters.ignoreGitIgnore, ".")).executeSilently()
                    git("commit", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
                    git("tag", "-d", "base").runSilently(silenceErr = true)
                    git("tag", "base").executeSilently()
                } finally {
                    tempOut.deleteRecursively()
                }
            }

            if (generatedAtOutPath != null) {
                AccessTransformFormats.FML.write(generatedAtOutPath, processAt)
            }
        }
    }

    interface RemapForDownstreamParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val mappings: RegularFileProperty
        val remapDir: RegularFileProperty
        val ignoreGitIgnore: Property<Boolean>

        val cacheDir: RegularFileProperty
        val generatedAtOutput: RegularFileProperty
    }

    object ExplicitDownstreamThisAdder : SourceRewriter {

        override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

        override fun rewrite(context: RewriteContext) {
            context.compilationUnit.accept(ExplicitDownstreamThisAdderVisitor(context))
        }
    }

    class ExplicitDownstreamThisAdderVisitor(private val context: RewriteContext) : ASTVisitor() {

        override fun visit(node: SimpleName): Boolean {
            val binding = node.resolveBinding() ?: return false

            val name = when (val declaringNode = context.compilationUnit.findDeclaringNode(binding)) {
                is VariableDeclarationFragment -> declaringNode.name
                is MethodDeclaration -> declaringNode.name
                else -> return false
            }
            if (name === node) {
                // this is the actual declaration
                return false
            }

            visit(node, binding)
            return false
        }

        private fun visit(node: SimpleName, binding: IBinding) {
            if (binding.kind != IBinding.VARIABLE && binding.kind != IBinding.METHOD) {
                return
            }

            val referringClass = when (binding) {
                is IVariableBinding -> {
                    if (!binding.isField || binding.isEnumConstant) {
                        return
                    }
                    binding.declaringClass
                }
                is IMethodBinding -> {
                    if (binding.isConstructor || binding.isSynthetic) {
                        return
                    }
                    binding.declaringClass
                }
                else -> return
            }
            val modifiers = when (binding) {
                is IVariableBinding -> binding.modifiers
                is IMethodBinding -> binding.modifiers
                else -> return
            }

            when (val p = node.parent) {
                is FieldAccess, is SuperFieldAccess, is QualifiedName, is ThisExpression, is MethodReference, is SuperMethodInvocation -> return
                is MethodInvocation -> {
                    if (p.expression != null && p.expression !== node) {
                        return
                    }
                }
            }

            // find declaring method
            var parentNode: ASTNode? = node
            loop@ while (parentNode != null) {
                when (parentNode) {
                    is MethodDeclaration, is AnonymousClassDeclaration, is LambdaExpression, is Initializer -> break@loop
                }
                parentNode = parentNode.parent
            }

            val rewrite = context.createASTRewrite()
            val fieldAccess = rewrite.ast.newFieldAccess()

            val expr: Expression = if (!Modifier.isStatic(modifiers)) {
                rewrite.ast.newThisExpression().also { thisExpr ->
                    if (parentNode is LambdaExpression) {
                        return@also
                    }

                    if (parentNode is AnonymousClassDeclaration && referringClass.erasure != parentNode.resolveBinding().erasure) {
                        val name = getNameNode(referringClass) ?: return
                        thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                        return@also
                    }

                    val methodDec = parentNode as? MethodDeclaration ?: return@also

                    var methodClass = methodDec.resolveBinding().declaringClass
                    if (methodClass.isAnonymous) {
                        val name = getNameNode(referringClass) ?: return
                        thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                        return@also
                    }

                    if (referringClass.erasure != methodClass.erasure && methodClass.isNested && !Modifier.isStatic(methodClass.modifiers)) {
                        while (true) {
                            methodClass = methodClass.declaringClass ?: break
                        }
                        // Looks like the method is accessing an outer class's fields
                        if (referringClass.erasure == methodClass.erasure) {
                            val name = getNameNode(referringClass) ?: return
                            thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                        }
                    }
                }
            } else {
                if (parentNode is Initializer && Modifier.isStatic(parentNode.modifiers)) {
                    // Can't provide explicit static receiver here
                    return
                }
                val name = getNameNode(referringClass) ?: return
                rewrite.createCopyTarget(name) as Name
            }

            fieldAccess.expression = expr
            fieldAccess.name = rewrite.createMoveTarget(node) as SimpleName

            rewrite.replace(node, fieldAccess, null)
        }

        private fun getNameNode(dec: ITypeBinding): Name? {
            val typeDec = context.compilationUnit.findDeclaringNode(dec) as? TypeDeclaration ?: return null
            return typeDec.name
        }
    }
}
