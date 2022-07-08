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

package io.papermc.paperweight.util

import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.LorenzUtil
import dev.denwav.hypo.mappings.changes.MemberReference
import dev.denwav.hypo.mappings.changes.RemoveClassMappingChange
import dev.denwav.hypo.mappings.changes.RemoveMappingChange
import dev.denwav.hypo.mappings.changes.RemoveParameterMappingChange
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.model.data.ClassData
import dev.denwav.hypo.model.data.MethodData
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

abstract class RemapUpstreamAction : WorkAction<RemapUpstreamParams> {
    override fun execute() {
        val mappingSet = MappingFormats.TINY.read(
            parameters.mappings.path,
            parameters.initialNamespace.get(),
            parameters.targetNamespace.get()
        )

        val processAt = AccessTransformSet.create()
        val generatedAtOutPath = parameters.generatedAtOutput.pathOrNull

        Mercury().let { mercury ->
            mercury.sourceCompatibility = JavaCore.VERSION_17
            mercury.isGracefulClasspathChecks = true
            mercury.classPath.addAll(parameters.classpath.map { it.toPath() })

            if (generatedAtOutPath != null) {
                mercury.processors += AccessAnalyzerProcessor.create(processAt, mappingSet)
            }

            mercury.process(parameters.remapDir.path)

            val tempOut = Files.createTempDirectory(parameters.cacheDir.path, "remap")
            try {
                mercury.processors.clear()
                mercury.processors.addAll(
                    listOf(
                        ExplicitUpstreamThisAdder,
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
            } finally {
                tempOut.deleteRecursively()
            }
        }

        if (generatedAtOutPath != null) {
            AccessTransformFormats.FML.write(generatedAtOutPath, processAt)
        }
    }
}

interface RemapUpstreamParams : WorkParameters {
    val classpath: ConfigurableFileCollection
    val mappings: RegularFileProperty
    val remapDir: RegularFileProperty
    val initialNamespace: Property<String>
    val targetNamespace: Property<String>

    val cacheDir: RegularFileProperty
    val generatedAtOutput: RegularFileProperty
}

object ExplicitUpstreamThisAdder : SourceRewriter {

    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(ExplicitUpstreamThisAdderVisitor(context))
    }
}

class ExplicitUpstreamThisAdderVisitor(private val context: RewriteContext) : ASTVisitor() {

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

// Variation of RemoveUnusedMappings from Hypo that dumps unused mappings to make fast
// tracking Mojang name changes easier
class RemoveAndDumpUnusedMappings(private val unusedMappings: Path) : ChangeContributor {

    override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
        val classCollection = mutableListOf<String>()
        val methodCollection = mutableListOf<String>()
        val fieldCollection = mutableListOf<String>()
        val paramsCollection = mutableListOf<String>()

        if (classMapping == null) {
            return
        }
        if (currentClass == null) {
            classCollection += (classMapping.fullObfuscatedName + " -> " + classMapping.fullDeobfuscatedName)
            registry.submitChange(RemoveClassMappingChange.of(classMapping.fullObfuscatedName))
            return
        }

        for (methodMapping in classMapping.methodMappings) {
            val method: MethodData? = LorenzUtil.findMethod(currentClass, methodMapping)
            if (method == null) {
                methodCollection += (methodMapping.fullObfuscatedName + " -> " + methodMapping.fullDeobfuscatedName)
                registry.submitChange(RemoveMappingChange.of(MemberReference.of(methodMapping)))
                continue
            }
            if (method.isConstructor && methodMapping.parameterMappings.isEmpty()) {
                // Constructor mappings without parameters are useless
                registry.submitChange(RemoveMappingChange.of(MemberReference.of(methodMapping)))
                continue
            }
            var methodRef: MemberReference? = null
            for (paramMapping in methodMapping.parameterMappings) {
                if (method.paramLvt(paramMapping.index) == null) {
                    if (methodRef == null) {
                        methodRef = MemberReference.of(method)
                    }
                    paramsCollection += (paramMapping.index.toString() + " " + paramMapping.fullObfuscatedName + " -> " + paramMapping.fullDeobfuscatedName)
                    registry.submitChange(RemoveParameterMappingChange.of(methodRef, paramMapping.index))
                }
            }
        }

        for (fieldMapping in classMapping.fieldMappings) {
            if (LorenzUtil.findField(currentClass, fieldMapping).isEmpty()) {
                fieldCollection += (fieldMapping.fullObfuscatedName + " -> " + fieldMapping.fullDeobfuscatedName)
                registry.submitChange(RemoveMappingChange.of(MemberReference.of(fieldMapping)))
            }
        }

        classCollection.sort()
        methodCollection.sort()
        fieldCollection.sort()
        paramsCollection.sort()

        if (classCollection.isNotEmpty()) {
            unusedMappings.bufferedWriter(options = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)).use { writer ->
                writer.appendLine("Classes:")
                writer.appendLine("")
                classCollection.forEach { writer.appendLine(it) }
                writer.appendLine("")
            }
        }

        if (methodCollection.isNotEmpty()) {
            unusedMappings.bufferedWriter(options = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)).use { writer ->
                writer.appendLine("Methods:")
                writer.appendLine("")
                methodCollection.forEach { writer.appendLine(it) }
                writer.appendLine("")
            }
        }

        if (fieldCollection.isNotEmpty()) {
            unusedMappings.bufferedWriter(options = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)).use { writer ->
                writer.appendLine("Fields:")
                writer.appendLine("")
                fieldCollection.forEach { writer.appendLine(it) }
                writer.appendLine("")
            }
        }

        if (paramsCollection.isNotEmpty()) {
            unusedMappings.bufferedWriter(options = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)).use { writer ->
                writer.appendLine("Parameters:")
                writer.appendLine("")
                paramsCollection.forEach { writer.appendLine(it) }
            }
        }
    }

    override fun name(): String {
        return "RemoveAndDumpUnusedMappings"
    }

}

fun Path.copyRecursivelyTo(target: Path, overwrite: Boolean = false) {
    target.createDirectories()
    if (!exists()) {
        return
    }
    Files.walk(this).use { stream ->
        for (f in stream) {
            val targetPath = target.resolve(f.relativeTo(this).invariantSeparatorsPathString)
            if (f.isDirectory()) {
                targetPath.createDirectories()
            } else {
                f.copyTo(target = targetPath, overwrite = overwrite)
            }
        }
    }
}