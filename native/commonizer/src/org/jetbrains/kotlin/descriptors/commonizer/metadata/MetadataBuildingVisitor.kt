/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.metadata

import kotlinx.metadata.KmClass
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmModuleFragment
import kotlinx.metadata.KmTypeAlias
import kotlinx.metadata.klib.KlibModuleMetadata
import org.jetbrains.kotlin.descriptors.commonizer.Target
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.*
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirNode.Companion.dimension
import org.jetbrains.kotlin.utils.addToStdlib.cast

// TODO: mark expects properly
// TODO: handle lifted up declarations properly
// TODO: add logging
class MetadataBuildingVisitor(
    private val context: VisitingContext
) : CirNodeVisitor<Unit, Any> {
    override fun visitRootNode(node: CirRootNode, data: Unit): Map<Target, List<KlibModuleMetadata>> {
        val allModules: Array<MutableList<KlibModuleMetadata>> = node.createArray()
        node.modules.values.forEach { allModules += it.accept(this, Unit).cast() }

        return (node.targetDeclarations + node.commonDeclaration())
            .mapIndexed { index, cirRoot -> cirRoot!!.target to allModules[index] }
            .toMap()
    }

    override fun visitModuleNode(node: CirModuleNode, data: Unit): List<KlibModuleMetadata?> {
        val allFragments: Array<MutableList<KmModuleFragment>> = node.createArray()
        node.packages.values.forEach { allFragments += it.accept(this, Unit).cast() }

        addEmptyFragments(allFragments)

        return node.buildModules(allFragments)
    }

    override fun visitPackageNode(node: CirPackageNode, data: Unit): List<KmModuleFragment?> {
        val allClasses: Array<MutableList<KmClass>> = node.createArray()
        node.classes.values.forEach { allClasses += it.accept(this, Unit).cast() }

        val allTypeAliases: Array<MutableList<KmTypeAlias>> = node.createArray()
        node.typeAliases.values.forEach { typeAliasNode ->
            val classifiers: List<Any?> = typeAliasNode.accept(this, Unit).cast()
            classifiers.forEachIndexed { index, classifier ->
                when (classifier) {
                    null -> Unit
                    is KmClass -> allClasses[index].add(classifier)
                    is KmTypeAlias -> allTypeAliases[index].add(classifier)
                    else -> error("Unexpected classifier: ${classifier::class.java}, $classifier")
                }
            }
        }

        return node.buildFragments(allClasses, allTypeAliases)
    }

    override fun visitPropertyNode(node: CirPropertyNode, data: Unit) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionNode(node: CirFunctionNode, data: Unit): List<KmFunction?> {
        return node.buildFunctions(context)
    }

    override fun visitClassNode(node: CirClassNode, data: Unit): List<KmClass?> {
        TODO("Not yet implemented")
    }

    override fun visitClassConstructorNode(node: CirClassConstructorNode, data: Unit) {
        TODO("Not yet implemented")
    }

    override fun visitTypeAliasNode(node: CirTypeAliasNode, data: Unit): List<Any?> {
        TODO("Not yet implemented")
    }

    companion object {
        private fun <T : Any> CirNode<*, *>.createArray(): Array<MutableList<T>> {
            return Array(dimension) { mutableListOf() }
        }

        private operator fun <T : Any> Array<MutableList<T>>.plusAssign(list: List<T?>) {
            list.forEachIndexed { index, value ->
                if (value != null) this[index].add(value)
            }
        }
    }
}
