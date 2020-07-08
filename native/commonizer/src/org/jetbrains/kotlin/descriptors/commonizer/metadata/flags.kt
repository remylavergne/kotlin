/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.metadata

import kotlinx.metadata.Flag
import kotlinx.metadata.Flags
import kotlinx.metadata.flagsOf
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.commonizer.cir.*

internal fun CirFunction.functionFlags(isExpect: Boolean): Flags =
    flagsOfNotNull(
        hasAnnotationsFlag,
        visibilityFlag,
        modalityFlag,
        memberKindFlag,
        expectFlag(isExpect)
    ) or modifiers.modifiersFlags

internal fun CirType.typeFlags(): Flags = flagsOfNotNull(nullableFlag)

internal fun CirTypeParameter.typeParameterFlags(): Flags = flagsOfNotNull(reifiedFlag)

internal fun CirValueParameter.valueParameterFlags(): Flags =
    flagsOfNotNull(
        declaresDefaultValueFlag,
        crossinlineFlag,
        noinlineFlag
    )

private inline val CirHasAnnotations.hasAnnotationsFlag: Flag?
    get() = if (annotations.isNotEmpty()) Flag.HAS_ANNOTATIONS else null

private inline val CirHasVisibility.visibilityFlag: Flag
    get() = when (visibility) {
        Visibilities.PUBLIC -> Flag.IS_PUBLIC
        Visibilities.PROTECTED -> Flag.IS_PROTECTED
        Visibilities.INTERNAL -> Flag.IS_INTERNAL
        Visibilities.PRIVATE -> Flag.IS_PRIVATE
        else -> error("Unexpected visibility: $this")
    }

private inline val CirHasModality.modalityFlag: Flag
    get() = when (modality) {
        Modality.FINAL -> Flag.IS_FINAL
        Modality.ABSTRACT -> Flag.IS_ABSTRACT
        Modality.OPEN -> Flag.IS_OPEN
        Modality.SEALED -> Flag.IS_SEALED
    }

private inline val CirFunction.memberKindFlag: Flag
    get() = when (kind) {
        CallableMemberDescriptor.Kind.DECLARATION -> Flag.Function.IS_DECLARATION
        CallableMemberDescriptor.Kind.FAKE_OVERRIDE -> Flag.Function.IS_FAKE_OVERRIDE
        CallableMemberDescriptor.Kind.DELEGATION -> Flag.Function.IS_DELEGATION
        CallableMemberDescriptor.Kind.SYNTHESIZED -> Flag.Function.IS_SYNTHESIZED
    }

private inline val CirFunctionModifiers.modifiersFlags: Flags
    get() = flagsOfNotNull(
        Flag.Function.IS_OPERATOR.takeIf { isOperator },
        Flag.Function.IS_INFIX.takeIf { isInfix },
        Flag.Function.IS_INLINE.takeIf { isInline },
        Flag.Function.IS_TAILREC.takeIf { isTailrec },
        Flag.Function.IS_SUSPEND.takeIf { isSuspend },
        Flag.Function.IS_EXTERNAL.takeIf { isExternal }
    )

private inline val CirType.nullableFlag: Flag?
    get() {
        val isNullable = when (this) {
            is CirSimpleType -> isMarkedNullable
            is CirFlexibleType -> lowerBound.isMarkedNullable
        }

        return if (isNullable) Flag.Type.IS_NULLABLE else null
    }

private inline val CirTypeParameter.reifiedFlag: Flag?
    get() = if (isReified) Flag.TypeParameter.IS_REIFIED else null

private inline val CirValueParameter.declaresDefaultValueFlag: Flag?
    get() = if (declaresDefaultValue) Flag.ValueParameter.DECLARES_DEFAULT_VALUE else null

private inline val CirValueParameter.crossinlineFlag: Flag?
    get() = if (isCrossinline) Flag.ValueParameter.IS_CROSSINLINE else null

private inline val CirValueParameter.noinlineFlag: Flag?
    get() = if (isNoinline) Flag.ValueParameter.IS_NOINLINE else null

@Suppress("NOTHING_TO_INLINE")
private inline fun CirFunction.expectFlag(isExpect: Boolean): Flag? = if (isExpect) Flag.Function.IS_EXPECT else null

@Suppress("NOTHING_TO_INLINE")
private inline fun CirProperty.expectFlag(isExpect: Boolean): Flag? = if (isExpect) Flag.Property.IS_EXPECT else null

@Suppress("NOTHING_TO_INLINE")
private inline fun CirClass.expectFlag(isExpect: Boolean): Flag? = if (isExpect) Flag.Class.IS_EXPECT else null

private fun flagsOfNotNull(vararg flags: Flag?): Flags = flagsOf(*listOfNotNull(*flags).toTypedArray())

