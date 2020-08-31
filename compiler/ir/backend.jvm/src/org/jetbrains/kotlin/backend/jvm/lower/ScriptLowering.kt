/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.makeIrModulePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.transformStatement
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrTypeAbbreviationImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.psi2ir.PsiSourceManager

internal val scriptToClassPhase = makeIrModulePhase(
    ::ScriptToClassLowering,
    name = "ScriptToClass",
    description = "Put script declarations into a class",
    stickyPostconditions = setOf(::checkAllFileLevelDeclarationsAreClasses)
)

private class ScriptToClassLowering(val context: JvmBackendContext) : FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.declarations.replaceAll { declaration ->
            if (declaration is IrScript) makeScriptClass(irFile, declaration)
            else declaration
        }
    }

    private fun makeScriptClass(irFile: IrFile, irScript: IrScript): IrClass {
        val fileEntry = irFile.fileEntry
        val ktFile = context.psiSourceManager.getKtFile(fileEntry as PsiSourceManager.PsiFileEntry)
            ?: throw AssertionError("Unexpected file entry: $fileEntry")
        val descriptor = WrappedClassDescriptor()
        return IrClassImpl(
            0, fileEntry.maxOffset,
            IrDeclarationOrigin.SCRIPT_CLASS,
            symbol = IrClassSymbolImpl(descriptor),
            name = irScript.name,
            kind = ClassKind.CLASS,
            visibility = DescriptorVisibilities.PUBLIC,
            modality = Modality.FINAL
        ).also { irScriptClass ->
            descriptor.bind(irScriptClass)
            irScriptClass.superTypes += context.irBuiltIns.anyType
            irScriptClass.parent = irFile
            irScriptClass.createImplicitParameterDeclarationWithWrappedDescriptor()
            val symbolRemapper = ScriptToClassSymbolRemapper(irScript.symbol, irScriptClass.symbol)
            val typeRemapper = ScriptTypeRemapper(symbolRemapper)
            val scriptTransformer = ScriptToClassTransformer(irScript, irScriptClass, symbolRemapper, typeRemapper)
            irScriptClass.thisReceiver = irScript.thisReceiver.run {
                transform(scriptTransformer, null)
            }
            irScriptClass.addConstructor {
                isPrimary = true
            }.also { irConstructor ->
                irScript.explicitCallParameters.forEach { scriptCallParameter ->
                    val callParameter = irConstructor.addValueParameter {
                        updateFrom(scriptCallParameter)
                        name = scriptCallParameter.name
                    }
                    irScriptClass.addSimplePropertyFrom(
                        callParameter,
                        IrExpressionBodyImpl(
                            IrGetValueImpl(
                                callParameter.startOffset, callParameter.endOffset,
                                callParameter.type,
                                callParameter.symbol,
                                IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER
                            )
                        )
                    )
                }

                irConstructor.body = context.createIrBuilder(irConstructor.symbol).irBlockBody {
                    +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                    +IrInstanceInitializerCallImpl(
                        irScript.startOffset, irScript.endOffset,
                        irScriptClass.symbol,
                        context.irBuiltIns.unitType
                    )
                }
            }
            irScript.statements.forEach { scriptStatement ->
                when (scriptStatement) {
                    is IrVariable -> irScriptClass.addSimplePropertyFrom(scriptStatement)
                    is IrDeclaration -> {
                        val copy = scriptStatement.transform(scriptTransformer, null) as IrDeclaration
                        irScriptClass.declarations.add(copy)
                    }
                    else -> {
                        val transformedStatement = scriptStatement.transformStatement(scriptTransformer)
                        irScriptClass.addAnonymousInitializer().also { irInitializer ->
                            irInitializer.body =
                                context.createIrBuilder(irInitializer.symbol).irBlockBody {
                                    if (transformedStatement is IrComposite) {
                                        for (statement in transformedStatement.statements)
                                            +statement
                                    } else {
                                        +transformedStatement
                                    }
                                }
                        }
                    }
                }
            }
            irScriptClass.annotations += irFile.annotations
            irScriptClass.metadata = irFile.metadata
        }
    }

    private fun IrClass.addSimplePropertyFrom(
        from: IrValueDeclaration,
        initializer: IrExpressionBodyImpl? = null
    ) {
        addProperty {
            updateFrom(from)
            name = from.name
        }.also { property ->
            property.backingField = context.irFactory.buildField {
                name = from.name
                type = from.type
                visibility = DescriptorVisibilities.PROTECTED
            }.also { field ->
                field.parent = this
                if (initializer != null) {
                    field.initializer = initializer
                }

                property.addSimpleFieldGetter(from.type, this, field)
            }
        }
    }

    private fun IrProperty.addSimpleFieldGetter(type: IrType, irScriptClass: IrClass, field: IrField) =
        addGetter {
            returnType = type
        }.apply {
            dispatchReceiverParameter = irScriptClass.thisReceiver!!.copyTo(this)
            body = IrBlockBodyImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET, listOf(
                    IrReturnImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                        context.irBuiltIns.nothingType,
                        symbol,
                        IrGetFieldImpl(
                            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                            field.symbol,
                            type,
                            IrGetValueImpl(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                                dispatchReceiverParameter!!.type,
                                dispatchReceiverParameter!!.symbol
                            )
                        )
                    )
                )
            )
        }
}

fun <T : IrElement> T.patchDeclarationParentsToScriptClass(
    script: IrScript,
    scriptClass: IrClass
) = apply {
    val visitor = object : IrElementVisitorVoid {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitDeclaration(declaration: IrDeclarationBase) {
            if (declaration.parent == script) {
                declaration.parent = scriptClass
            }
            super.visitDeclaration(declaration)
        }
    }
    acceptVoid(visitor)
}

private class ScriptToClassTransformer(
    val irScript: IrScript,
    val irScriptClass: IrClass,
    val symbolRemapper: SymbolRemapper = ScriptToClassSymbolRemapper(irScript.symbol, irScriptClass.symbol),
    val typeRemapper: TypeRemapper = ScriptTypeRemapper(symbolRemapper)
) : IrElementTransformerVoid() {

    private fun IrType.remapType() = typeRemapper.remapType(this)

    private fun IrDeclaration.transformParent() {
        if (parent == irScript) {
            parent = irScriptClass
        }
    }

    private fun IrMutableAnnotationContainer.transformAnnotations() {
        annotations = annotations.transform()
    }

    private inline fun <reified T : IrElement> T.transform() =
        transform(this@ScriptToClassTransformer, null) as T

    private inline fun <reified T : IrElement> List<T>.transform() =
        map { it.transform() }

    private inline fun <reified T : IrElement> MutableList<T>.replaceTransform() =
        replaceAll { it.transform() }

    private fun <T : IrFunction> T.transformFunctionChildren(): T =
        apply {
            transformAnnotations()
            typeRemapper.withinScope(this) {
                dispatchReceiverParameter = dispatchReceiverParameter?.transform()
                extensionReceiverParameter = extensionReceiverParameter?.transform()
                returnType = typeRemapper.remapType(returnType)
                valueParameters = valueParameters.transform()
                body = body?.transform()
            }
        }

    private fun IrTypeParameter.remapSuperTypes(): IrTypeParameter = apply {
        superTypes.replaceAll { it.remapType() }
    }

    private fun unexpectedElement(element: IrElement): Nothing =
        throw IllegalArgumentException("Unsupported element type: $element")

    override fun visitElement(element: IrElement): IrElement = unexpectedElement(element)
    override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement = unexpectedElement(declaration)

    override fun visitModuleFragment(declaration: IrModuleFragment): IrModuleFragment = unexpectedElement(declaration)
    override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment) = unexpectedElement(declaration)
    override fun visitFile(declaration: IrFile): IrFile = unexpectedElement(declaration)
    override fun visitScript(declaration: IrScript): IrStatement = unexpectedElement(declaration)

    override fun visitClass(declaration: IrClass): IrClass = declaration.apply {
        transformParent()
        superTypes = superTypes.map {
            it.remapType()
        }
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction = declaration.apply {
        transformParent()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitConstructor(declaration: IrConstructor): IrConstructor = declaration.apply {
        transformParent()
        transformFunctionChildren()
    }

    override fun visitProperty(declaration: IrProperty): IrProperty = declaration.apply {
        transformParent()
        transformAnnotations()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitField(declaration: IrField): IrField = declaration.apply {
        transformParent()
        transformAnnotations()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrLocalDelegatedProperty = declaration.apply {
        transformParent()
        transformAnnotations()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitEnumEntry(declaration: IrEnumEntry): IrEnumEntry = declaration.apply {
        transformParent()
        transformAnnotations()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer): IrAnonymousInitializer = declaration.apply {
        transformParent()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitVariable(declaration: IrVariable): IrVariable {
        val type = declaration.type.remapType()
        val symbol = symbolRemapper.getDeclaredVariable(declaration.symbol)
        val remappedDeclaration =
            if (type == declaration.type && symbol == declaration.symbol) {
                declaration
            } else {
                IrVariableImpl(
                    declaration.startOffset, declaration.endOffset,
                    declaration.origin,
                    symbol,
                    declaration.name,
                    type,
                    declaration.isVar,
                    declaration.isConst,
                    declaration.isLateinit
                ).apply {
                    parent = declaration.parent
                    initializer = declaration.initializer
                }
            }
        return remappedDeclaration.apply {
            transformParent()
            transformAnnotations()
            transformChildren(this@ScriptToClassTransformer, null)
        }
    }

    override fun visitTypeParameter(declaration: IrTypeParameter): IrTypeParameter = declaration.apply {
        remapSuperTypes()
        transformParent()
        transformAnnotations()
        transformChildren(this@ScriptToClassTransformer, null)
    }

    override fun visitValueParameter(declaration: IrValueParameter): IrValueParameter {
        val type = declaration.type.remapType()
        val varargElementType = declaration.varargElementType?.remapType()
        val symbol = symbolRemapper.getDeclaredValueParameter(declaration.symbol)
        val remappedExpression =
            if (type == declaration.type && varargElementType == declaration.varargElementType && symbol == declaration.symbol) {
                declaration
            } else {
                IrValueParameterImpl(
                    declaration.startOffset, declaration.endOffset,
                    declaration.origin,
                    symbol,
                    declaration.name,
                    declaration.index,
                    type,
                    varargElementType,
                    declaration.isCrossinline,
                    declaration.isNoinline
                ).apply {
                    parent = declaration.parent
                }
            }
        return remappedExpression.apply {
            transformParent()
            transformAnnotations()
            transformChildren(this@ScriptToClassTransformer, null)
        }
    }

    override fun visitTypeAlias(declaration: IrTypeAlias): IrTypeAlias {
        val expandedType = declaration.expandedType.remapType()
        val remappedExpression = if (expandedType == declaration.expandedType) {
            declaration
        } else {
            IrTypeAliasImpl(
                declaration.startOffset, declaration.endOffset,
                declaration.symbol,
                declaration.name,
                declaration.visibility,
                expandedType,
                declaration.isActual,
                declaration.origin
            )
        }
        return remappedExpression.apply {
            transformParent()
            transformAnnotations()
            transformChildren(this@ScriptToClassTransformer, null)
        }
    }

    override fun visitVararg(expression: IrVararg): IrVararg {
        val type = expression.type.remapType()
        val elementType = expression.varargElementType.remapType()
        val elements = expression.elements.transform()
        val remappedExpression =
            if (type == expression.type && elementType == expression.varargElementType && elements == expression.elements) {
                expression
            } else {
                IrVarargImpl(expression.startOffset, expression.endOffset, type, elementType, elements).copyAttributes(expression)
            }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitSpreadElement(spread: IrSpreadElement): IrSpreadElement = spread.also {
        it.transformChildren(this, null)
    }

    override fun visitBlock(expression: IrBlock): IrBlock {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression.apply {
                statements.replaceTransform()
            }
        } else {
            if (expression is IrReturnableBlock)
                IrReturnableBlockImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.origin,
                    expression.statements.transform(),
                    expression.inlineFunctionSymbol
                ).copyAttributes(expression)
            else
                IrBlockImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.origin,
                    expression.statements.transform()
                ).copyAttributes(expression)
        }
    }

    override fun visitComposite(expression: IrComposite): IrComposite {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression.apply {
                statements.replaceTransform()
            }
        } else {
            IrCompositeImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.origin,
                expression.statements.transform()
            ).copyAttributes(expression)
        }
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation): IrStringConcatenation {
        val type = expression.type.remapType()
        val arguments = expression.arguments.transform()
        val remappedExpression = if (type == expression.type && arguments == expression.arguments) {
            expression
        } else {
            IrStringConcatenationImpl(
                expression.startOffset, expression.endOffset,
                type,
                arguments
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitGetObjectValue(expression: IrGetObjectValue): IrGetObjectValue {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression
        } else {
            IrGetObjectValueImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol
            ).copyAttributes(expression)
        }
    }

    override fun visitGetEnumValue(expression: IrGetEnumValue): IrGetEnumValue {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression
        } else {
            IrGetEnumValueImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol
            ).copyAttributes(expression)
        }
    }

    override fun visitGetValue(expression: IrGetValue): IrGetValue {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression
        } else {
            IrGetValueImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol,
                expression.origin
            ).copyAttributes(expression)
        }
    }

    override fun visitSetVariable(expression: IrSetVariable): IrSetVariable {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrSetVariableImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol,
                expression.value,
                expression.origin
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitGetField(expression: IrGetField): IrGetField {
        val type = expression.type.remapType()
        val superQualifierSymbol = symbolRemapper.getReferencedClassOrNull(expression.superQualifierSymbol)
        val remappedExpression = if (type == expression.type && superQualifierSymbol == expression.superQualifierSymbol) {
            expression
        } else {
            IrGetFieldImpl(
                expression.startOffset, expression.endOffset,
                expression.symbol,
                type,
                expression.receiver,
                expression.origin,
                superQualifierSymbol
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitSetField(expression: IrSetField): IrSetField {
        val type = expression.type.remapType()
        val superQualifierSymbol = symbolRemapper.getReferencedClassOrNull(expression.superQualifierSymbol)
        val remappedExpression = if (type == expression.type && superQualifierSymbol == expression.superQualifierSymbol) {
            expression
        } else {
            IrSetFieldImpl(
                expression.startOffset, expression.endOffset,
                expression.symbol,
                expression.receiver,
                expression.value,
                type,
                expression.origin,
                superQualifierSymbol
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitCall(expression: IrCall): IrCall =
        transformCall(expression).apply {
            transformValueArguments(expression)
        }

    override fun visitConstructorCall(expression: IrConstructorCall): IrConstructorCall {
        val type = expression.type.remapType()
        val typeRemappedCtor =
            if (type == expression.type) {
                expression
            } else {
                IrConstructorCallImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.typeArgumentsCount,
                    expression.constructorTypeArgumentsCount,
                    expression.valueArgumentsCount,
                    expression.origin
                ).copyAttributes(expression)
            }
        return typeRemappedCtor.apply {
            copyRemappedTypeArgumentsFrom(expression)
            transformValueArguments(expression)
        }
    }

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrDelegatingConstructorCall {
        val type = expression.type.remapType()
        val typeRemappedCtor =
            if (type == expression.type) {
                expression
            } else {
                IrDelegatingConstructorCallImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.typeArgumentsCount
                ).copyAttributes(expression)
            }
        return typeRemappedCtor.apply {
            copyRemappedTypeArgumentsFrom(expression)
            transformValueArguments(expression)
        }
    }

    override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrEnumConstructorCall {
        val type = expression.type.remapType()
        val typeRemappedCtor =
            if (type == expression.type) {
                expression
            } else {
                IrEnumConstructorCallImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.typeArgumentsCount
                ).copyAttributes(expression)
            }
        return typeRemappedCtor.apply {
            copyRemappedTypeArgumentsFrom(expression)
            transformValueArguments(expression)
        }
    }

    override fun visitGetClass(expression: IrGetClass): IrGetClass {
        val type = expression.type.remapType()
        val argument = expression.argument.transform()
        return if (type == expression.type && argument == expression.argument) {
            expression
        } else {
            IrGetClassImpl(
                expression.startOffset, expression.endOffset,
                type,
                argument
            ).copyAttributes(expression)
        }
    }

    override fun visitFunctionReference(expression: IrFunctionReference): IrFunctionReference {
        val type = expression.type.remapType()
        val typeRemappedFuncRef =
            if (type == expression.type) {
                expression
            } else {
                IrFunctionReferenceImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.typeArgumentsCount,
                    expression.valueArgumentsCount,
                    expression.reflectionTarget,
                    expression.origin
                ).copyAttributes(expression)
            }
        return typeRemappedFuncRef.apply {
            copyRemappedTypeArgumentsFrom(expression)
            transformValueArguments(expression)
        }
    }

    override fun visitPropertyReference(expression: IrPropertyReference): IrPropertyReference {
        val type = expression.type.remapType()
        val typeRemappedFuncRef =
            if (type == expression.type) {
                expression
            } else {
                IrPropertyReferenceImpl(
                    expression.startOffset, expression.endOffset,
                    type,
                    expression.symbol,
                    expression.typeArgumentsCount,
                    expression.field,
                    expression.getter,
                    expression.setter,
                    expression.origin
                ).copyAttributes(expression)
            }
        return typeRemappedFuncRef.apply {
            copyRemappedTypeArgumentsFrom(expression)
            transformValueArguments(expression)
        }
    }

    override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference): IrLocalDelegatedPropertyReference {
        val type = expression.type.remapType()
        return if (type == expression.type) {
            expression
        } else {
            IrLocalDelegatedPropertyReferenceImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol,
                expression.delegate,
                expression.getter,
                expression.setter,
                expression.origin
            ).copyAttributes(expression)
        }
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrFunctionExpression {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrFunctionExpressionImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.function,
                expression.origin
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitClassReference(expression: IrClassReference): IrClassReference {
        val type = expression.type.remapType()
        val classType = expression.classType.remapType()
        val symbol = symbolRemapper.getReferencedClassifier(expression.symbol)
        return if (type == expression.type && classType == expression.classType && symbol == expression.symbol) {
            expression
        } else {
            IrClassReferenceImpl(
                expression.startOffset, expression.endOffset,
                type,
                symbol,
                classType
            ).copyAttributes(expression)
        }
    }

    override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall): IrInstanceInitializerCall {
        val type = expression.type.remapType()
        val classSymbol = symbolRemapper.getReferencedClass(expression.classSymbol)
        return if (type == expression.type && classSymbol == expression.classSymbol) {
            expression
        } else {
            IrInstanceInitializerCallImpl(
                expression.startOffset, expression.endOffset,
                classSymbol,
                type
            ).copyAttributes(expression)
        }
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrTypeOperatorCall {
        val type = expression.type.remapType()
        val typeOperand = expression.typeOperand.remapType()
        val remappedExpression = if (type == expression.type && typeOperand == expression.typeOperand) {
            expression
        } else {
            IrTypeOperatorCallImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.operator,
                typeOperand,
                expression.argument
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitWhen(expression: IrWhen): IrWhen {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrWhenImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.origin,
                expression.branches
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitBreak(jump: IrBreak): IrBreak {
        val type = jump.type.remapType()
        val remappedExpression = if (type == jump.type) {
            jump
        } else {
            IrBreakImpl(
                jump.startOffset, jump.endOffset,
                type,
                jump.loop
            ).copyAttributes(jump)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitContinue(jump: IrContinue): IrContinue {
        val type = jump.type.remapType()
        val remappedExpression = if (type == jump.type) {
            jump
        } else {
            IrContinueImpl(
                jump.startOffset, jump.endOffset,
                type,
                jump.loop
            ).copyAttributes(jump)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitTry(aTry: IrTry): IrTry {
        val type = aTry.type.remapType()
        val remappedExpression = if (type == aTry.type) {
            aTry
        } else {
            IrTryImpl(
                aTry.startOffset, aTry.endOffset,
                type,
                aTry.tryResult,
                aTry.catches,
                aTry.finallyExpression
            ).copyAttributes(aTry)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitReturn(expression: IrReturn): IrReturn {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrReturnImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.returnTargetSymbol,
                expression.value
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitThrow(expression: IrThrow): IrThrow {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrThrowImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.value
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression): IrDynamicOperatorExpression {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrDynamicOperatorExpressionImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.operator
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    override fun visitDynamicMemberExpression(expression: IrDynamicMemberExpression): IrDynamicMemberExpression {
        val type = expression.type.remapType()
        val remappedExpression = if (type == expression.type) {
            expression
        } else {
            IrDynamicMemberExpressionImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.memberName,
                expression.receiver
            ).copyAttributes(expression)
        }
        return remappedExpression.also {
            it.transformChildren(this, null)
        }
    }

    private fun transformCall(expression: IrCall): IrCall {
        val type = expression.type.remapType()
        val superQualifierSymbol = symbolRemapper.getReferencedClassOrNull(expression.superQualifierSymbol)
        val remappedExpression = if (type == expression.type && superQualifierSymbol == expression.superQualifierSymbol) {
            expression
        } else {
            IrCallImpl(
                expression.startOffset, expression.endOffset,
                type,
                expression.symbol,
                expression.typeArgumentsCount,
                expression.valueArgumentsCount,
                expression.origin,
                superQualifierSymbol
            ).copyAttributes(expression)
        }
        remappedExpression.copyRemappedTypeArgumentsFrom(expression)
        return remappedExpression
    }

    private fun IrMemberAccessExpression<*>.copyRemappedTypeArgumentsFrom(other: IrMemberAccessExpression<*>) {
        assert(typeArgumentsCount == other.typeArgumentsCount) {
            "Mismatching type arguments: $typeArgumentsCount vs ${other.typeArgumentsCount} "
        }
        for (i in 0 until typeArgumentsCount) {
            putTypeArgument(i, other.getTypeArgument(i)?.remapType())
        }
    }

    private fun <T : IrMemberAccessExpression<*>> T.transformValueArguments(original: T) {
        transformReceiverArguments(original)
        for (i in 0 until original.valueArgumentsCount) {
            putValueArgument(i, original.getValueArgument(i)?.transform())
        }
    }

    private fun <T : IrMemberAccessExpression<*>> T.transformReceiverArguments(original: T): T =
        apply {
            dispatchReceiver = original.dispatchReceiver?.transform()
            extensionReceiver = original.extensionReceiver?.transform()
        }
}

private class ScriptToClassSymbolRemapper(
    val irScriptSymbol: IrScriptSymbol,
    val irScriptClassSymbol: IrClassSymbol
) : SymbolRemapper {
    override fun getDeclaredClass(symbol: IrClassSymbol): IrClassSymbol = symbol

    override fun getDeclaredScript(symbol: IrScriptSymbol): IrScriptSymbol = symbol

    override fun getDeclaredFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol = symbol

    override fun getDeclaredProperty(symbol: IrPropertySymbol): IrPropertySymbol = symbol

    override fun getDeclaredField(symbol: IrFieldSymbol): IrFieldSymbol = symbol

    override fun getDeclaredFile(symbol: IrFileSymbol): IrFileSymbol = symbol

    override fun getDeclaredConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol = symbol

    override fun getDeclaredEnumEntry(symbol: IrEnumEntrySymbol): IrEnumEntrySymbol = symbol

    override fun getDeclaredExternalPackageFragment(symbol: IrExternalPackageFragmentSymbol): IrExternalPackageFragmentSymbol = symbol

    override fun getDeclaredVariable(symbol: IrVariableSymbol): IrVariableSymbol = symbol

    override fun getDeclaredLocalDelegatedProperty(symbol: IrLocalDelegatedPropertySymbol): IrLocalDelegatedPropertySymbol = symbol

    override fun getDeclaredTypeParameter(symbol: IrTypeParameterSymbol): IrTypeParameterSymbol = symbol

    override fun getDeclaredValueParameter(symbol: IrValueParameterSymbol): IrValueParameterSymbol = symbol

    override fun getDeclaredTypeAlias(symbol: IrTypeAliasSymbol): IrTypeAliasSymbol = symbol

    override fun getReferencedClass(symbol: IrClassSymbol): IrClassSymbol = symbol

    override fun getReferencedScript(symbol: IrScriptSymbol): IrScriptSymbol = symbol

    override fun getReferencedClassOrNull(symbol: IrClassSymbol?): IrClassSymbol? = symbol

    override fun getReferencedEnumEntry(symbol: IrEnumEntrySymbol): IrEnumEntrySymbol = symbol

    override fun getReferencedVariable(symbol: IrVariableSymbol): IrVariableSymbol = symbol

    override fun getReferencedLocalDelegatedProperty(symbol: IrLocalDelegatedPropertySymbol): IrLocalDelegatedPropertySymbol = symbol

    override fun getReferencedField(symbol: IrFieldSymbol): IrFieldSymbol = symbol

    override fun getReferencedConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol = symbol

    override fun getReferencedValue(symbol: IrValueSymbol): IrValueSymbol = symbol

    override fun getReferencedFunction(symbol: IrFunctionSymbol): IrFunctionSymbol = symbol

    override fun getReferencedProperty(symbol: IrPropertySymbol): IrPropertySymbol = symbol

    override fun getReferencedSimpleFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol = symbol

    override fun getReferencedReturnableBlock(symbol: IrReturnableBlockSymbol): IrReturnableBlockSymbol = symbol

    override fun getReferencedClassifier(symbol: IrClassifierSymbol): IrClassifierSymbol =
        if (symbol != irScriptSymbol) symbol
        else irScriptClassSymbol

    override fun getReferencedTypeAlias(symbol: IrTypeAliasSymbol): IrTypeAliasSymbol = symbol
}

class ScriptTypeRemapper(
    private val symbolRemapper: SymbolRemapper
) : TypeRemapper {

    override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {
        // TODO
    }

    override fun leaveScope() {
        // TODO
    }

    override fun remapType(type: IrType): IrType =
        if (type !is IrSimpleType)
            type
        else {
            val symbol = symbolRemapper.getReferencedClassifier(type.classifier)
            val arguments = type.arguments.map { remapTypeArgument(it) }
            if (symbol == type.classifier && arguments == type.arguments)
                type
            else {
                IrSimpleTypeImpl(
                    null,
                    symbol,
                    type.hasQuestionMark,
                    arguments,
                    type.annotations,
                    type.abbreviation?.remapTypeAbbreviation()
                )
            }
        }

    private fun remapTypeArgument(typeArgument: IrTypeArgument): IrTypeArgument =
        if (typeArgument is IrTypeProjection)
            makeTypeProjection(this.remapType(typeArgument.type), typeArgument.variance)
        else
            typeArgument

    private fun IrTypeAbbreviation.remapTypeAbbreviation() =
        IrTypeAbbreviationImpl(
            symbolRemapper.getReferencedTypeAlias(typeAlias),
            hasQuestionMark,
            arguments.map { remapTypeArgument(it) },
            annotations
        )
}


// TODO: find a right place for it or inline if needed only here
private fun IrFunctionBuilder.buildAnonymousInitializer(originalDescriptor: ClassDescriptor?): IrAnonymousInitializer =
    IrAnonymousInitializerImpl(
        startOffset, endOffset, origin,
        IrAnonymousInitializerSymbolImpl(
            if (originalDescriptor != null) originalDescriptor
            else WrappedClassDescriptor()
        )
    )

private inline fun buildAnonymousInitializer(
    originalDescriptor: ClassDescriptor? = null,
    builder: IrFunctionBuilder.() -> Unit
): IrAnonymousInitializer =
    IrFunctionBuilder().run {
        builder()
        buildAnonymousInitializer(originalDescriptor)
    }

private inline fun IrClass.addAnonymousInitializer(builder: IrFunctionBuilder.() -> Unit = {}): IrAnonymousInitializer =
    buildAnonymousInitializer {
        builder()
        returnType = defaultType
    }.also { anonymousInitializer ->
        declarations.add(anonymousInitializer)
        anonymousInitializer.parent = this@addAnonymousInitializer
    }

