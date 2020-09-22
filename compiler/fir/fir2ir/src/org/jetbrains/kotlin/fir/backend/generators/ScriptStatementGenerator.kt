/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.generators

import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.Fir2IrConversionScope
import org.jetbrains.kotlin.fir.backend.Fir2IrVisitor
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrScript

internal class ScriptStatementGenerator(
    private val components: Fir2IrComponents,
    private val visitor: Fir2IrVisitor,
    private val conversionScope: Fir2IrConversionScope,
    private val classMemberGenerator: ClassMemberGenerator
) : Fir2IrComponents by components {

    private val annotationGenerator = AnnotationGenerator(visitor)

    fun convertScriptContent(irScript: IrScript, script: FirScript) {
        declarationStorage.enterScope(irScript)
        irScript.explicitCallParameters = script.valueParameters.mapIndexed { index, firValueParameter ->
            declarationStorage.createIrParameter(firValueParameter, index)
        }
        fakeOverrideGenerator.bindOverriddenSymbols(irScript.statements.filterIsInstance<IrDeclaration>())
        script.body?.statements?.forEach { statement ->
            when {
                statement is FirTypeAlias -> {
                }
                statement is FirRegularClass -> {
                    val irNestedClass = classifierStorage.getCachedIrClass(statement)!!
                    irNestedClass.parent = irScript
                    conversionScope.withParent(irNestedClass) {
                        classMemberGenerator.convertClassContent(irNestedClass, statement)
                    }
                }
                else -> statement.accept(visitor, null)
            }
            annotationGenerator.generate(irScript, script)
        }
        declarationStorage.leaveScope(irScript)
    }
}