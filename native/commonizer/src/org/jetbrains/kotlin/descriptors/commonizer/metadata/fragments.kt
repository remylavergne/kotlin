/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.metadata

import kotlinx.metadata.KmClass
import kotlinx.metadata.KmModuleFragment
import kotlinx.metadata.KmPackage
import kotlinx.metadata.KmTypeAlias
import kotlinx.metadata.klib.className
import kotlinx.metadata.klib.fqName
import org.jetbrains.kotlin.descriptors.commonizer.cir.CirPackage
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirNode.Companion.dimension
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirNode.Companion.indexOfCommon
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirPackageNode
import org.jetbrains.kotlin.descriptors.commonizer.utils.CommonizedGroup

internal fun CirPackageNode.buildFragments(
    allClasses: Array<MutableList<KmClass>>,
    allTypeAliases: Array<MutableList<KmTypeAlias>>
): List<KmModuleFragment?> {
    val fragments = CommonizedGroup<KmModuleFragment>(dimension)

    targetDeclarations.forEachIndexed { index, cirPackage ->
        cirPackage?.buildFragment(fragments, index, allClasses, allTypeAliases)
    }
    commonDeclaration()?.buildFragment(fragments, indexOfCommon, allClasses, allTypeAliases)

    return fragments
}

private fun CirPackage.buildFragment(
    output: CommonizedGroup<KmModuleFragment>,
    index: Int,
    allClasses: Array<MutableList<KmClass>>,
    allTypeAliases: Array<MutableList<KmTypeAlias>>
) {
    output[index] = KmModuleFragment().also { fragment ->
        fragment.fqName = fqName.asString()
        allClasses[index].forEach {
            fragment.classes += it
            fragment.className += it.name
        }
        fragment.pkg = KmPackage().also { pkg ->
            pkg.typeAliases += allTypeAliases[index]
        }
    }
}

internal fun addEmptyFragments(allFragments: Array<MutableList<KmModuleFragment>>) {
    allFragments.forEach { fragments ->
        val existingPackageFqNames: Set<String> = fragments.mapTo(HashSet()) { it.fqName!! }

        val missingPackageFqNames: Set<String> = existingPackageFqNames.flatMapTo(HashSet()) { fqName ->
            fqName.mapIndexedNotNull { index, ch ->
                if (ch == '.') index else null
            }.mapNotNull { dotIndex ->
                val parentFqName = fqName.substring(0, dotIndex)
                if (parentFqName !in existingPackageFqNames) parentFqName else null
            }
        }

        missingPackageFqNames.forEach { fqName ->
            fragments += KmModuleFragment().also { fragment ->
                fragment.fqName = fqName
            }
        }
    }
}
