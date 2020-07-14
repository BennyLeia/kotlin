/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.generators

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.backend.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClassType
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.parentAsClass

internal class ClassMemberGenerator(
    private val components: Fir2IrComponents,
    private val visitor: Fir2IrVisitor,
    private val conversionScope: Fir2IrConversionScope
) : Fir2IrComponents by components {

    private val annotationGenerator = AnnotationGenerator(visitor)

    private fun FirTypeRef.toIrType(): IrType = with(typeConverter) { toIrType() }

    private fun ConeKotlinType.toIrType(): IrType = with(typeConverter) { toIrType() }

    private fun <T : IrDeclaration> applyParentFromStackTo(declaration: T): T = conversionScope.applyParentFromStackTo(declaration)

    fun convertClassContent(irClass: IrClass, klass: FirClass<*>) {
        declarationStorage.enterScope(irClass)
        conversionScope.withClass(irClass) {
            val primaryConstructor = klass.getPrimaryConstructorIfAny()
            val irPrimaryConstructor = primaryConstructor?.let { declarationStorage.getCachedIrConstructor(it)!! }
            if (irPrimaryConstructor != null) {
                with(declarationStorage) {
                    enterScope(irPrimaryConstructor)
                    irPrimaryConstructor.valueParameters.forEach { symbolTable.introduceValueParameter(it) }
                    irPrimaryConstructor.putParametersInScope(primaryConstructor)
                    convertFunctionContent(irPrimaryConstructor, primaryConstructor, containingClass = klass)
                }
            }
            val processedCallableNames = klass.declarations.mapNotNullTo(mutableSetOf()) {
                when (it) {
                    is FirSimpleFunction -> it.name
                    is FirProperty -> it.name
                    else -> null
                }
            }
            // Add delegated members *before* fake override generations.
            // Otherwise, fake overrides for delegated members, which are redundant, will be added.
            irClass.declarations.filter {
                it.origin == IrDeclarationOrigin.DELEGATED_MEMBER
            }.forEach {
                when (it) {
                    is IrSimpleFunction -> processedCallableNames += it.name
                    is IrProperty -> processedCallableNames += it.name
                }
            }
            // Add synthetic members *before* fake override generations.
            // Otherwise, redundant members, e.g., synthetic toString _and_ fake override toString, will be added.
            if (irClass.isInline && klass.getPrimaryConstructorIfAny() != null) {
                processedCallableNames += DataClassMembersGenerator(components).generateInlineClassMembers(klass, irClass)
            }
            if (irClass.isData && klass.getPrimaryConstructorIfAny() != null) {
                processedCallableNames += DataClassMembersGenerator(components).generateDataClassMembers(klass, irClass)
            }
            with(fakeOverrideGenerator) { irClass.addFakeOverrides(klass, processedCallableNames) }
            klass.declarations.forEach { declaration ->
                when {
                    declaration is FirTypeAlias -> {
                    }
                    declaration is FirConstructor && declaration.isPrimary -> {
                    }
                    declaration is FirRegularClass && declaration.visibility == Visibilities.LOCAL -> {
                        val irNestedClass = classifierStorage.getCachedIrClass(declaration)!!
                        irNestedClass.parent = irClass
                        conversionScope.withParent(irNestedClass) {
                            convertClassContent(irNestedClass, declaration)
                        }
                    }
                    else -> declaration.accept(visitor, null)
                }
            }
            annotationGenerator.generate(irClass, klass)
            if (irPrimaryConstructor != null) {
                declarationStorage.leaveScope(irPrimaryConstructor)
            }
        }
        declarationStorage.leaveScope(irClass)
    }

    fun <T : IrFunction> convertFunctionContent(irFunction: T, firFunction: FirFunction<*>?, containingClass: FirClass<*>?): T {
        conversionScope.withParent(irFunction) {
            if (firFunction != null) {
                if (irFunction !is IrConstructor || !irFunction.isPrimary) {
                    // Scope for primary constructor should be entered before class declaration processing
                    with(declarationStorage) {
                        enterScope(irFunction)
                        irFunction.valueParameters.forEach { symbolTable.introduceValueParameter(it) }
                        irFunction.putParametersInScope(firFunction)
                    }
                }
                for ((valueParameter, firValueParameter) in valueParameters.zip(firFunction.valueParameters)) {
                    valueParameter.setDefaultValue(firValueParameter)
                    annotationGenerator.generate(valueParameter, firValueParameter, irFunction is IrConstructor)
                }
                annotationGenerator.generate(irFunction, firFunction)
            }
            if (firFunction is FirConstructor && irFunction is IrConstructor && !parentAsClass.isAnnotationClass && !firFunction.isExpect) {
                val body = IrBlockBodyImpl(startOffset, endOffset)
                val delegatedConstructor = firFunction.delegatedConstructor
                if (delegatedConstructor != null) {
                    val irDelegatingConstructorCall = delegatedConstructor.toIrDelegatingConstructorCall()
                    body.statements += irDelegatingConstructorCall
                }
                if (delegatedConstructor?.isThis == false) {
                    val instanceInitializerCall = IrInstanceInitializerCallImpl(
                        startOffset, endOffset, (parent as IrClass).symbol, irFunction.constructedClassType
                    )
                    body.statements += instanceInitializerCall
                }
                val regularBody = firFunction.body?.let { visitor.convertToIrBlockBody(it) }
                if (regularBody != null) {
                    body.statements += regularBody.statements
                }
                if (body.statements.isNotEmpty()) {
                    irFunction.body = body
                }
            } else if (irFunction !is IrConstructor && !irFunction.isExpect) {
                when {
                    irFunction.origin == IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER -> {
                        val kind = Fir2IrDeclarationStorage.ENUM_SYNTHETIC_NAMES.getValue(irFunction.name)
                        irFunction.body = IrSyntheticBodyImpl(startOffset, endOffset, kind)
                    }
                    irFunction.parent is IrClass && irFunction.parentAsClass.isData -> {
                        val classId = firFunction?.symbol?.callableId?.classId
                        when {
                            DataClassMembersGenerator.isComponentN(irFunction) ->
                                firFunction?.body?.let { irFunction.body = visitor.convertToIrBlockBody(it) }
                                    ?: DataClassMembersGenerator(components).generateDataClassComponentBody(irFunction, classId!!)
                            DataClassMembersGenerator.isCopy(irFunction) ->
                                firFunction?.body?.let { irFunction.body = visitor.convertToIrBlockBody(it) }
                                    ?: DataClassMembersGenerator(components).generateDataClassCopyBody(irFunction, classId!!)
                            else ->
                                irFunction.body = firFunction?.body?.let { visitor.convertToIrBlockBody(it) }
                        }
                    }
                    else -> {
                        irFunction.body = firFunction?.body?.let { visitor.convertToIrBlockBody(it) }
                    }
                }
            }
            if (irFunction !is IrConstructor || !irFunction.isPrimary) {
                // Scope for primary constructor should be left after class declaration
                declarationStorage.leaveScope(irFunction)
            }
            if (irFunction is IrSimpleFunction && firFunction != null && containingClass != null) {
                val scope = containingClass.unsubstitutedScope(session, scopeSession)
                scope.processFunctionsByName(name) {}
                val overriddenSet = mutableSetOf<IrSimpleFunctionSymbol>()
                scope.processDirectlyOverriddenFunctions(firFunction.symbol) {
                    if ((it.fir as FirSimpleFunction).visibility == Visibilities.PRIVATE) {
                        return@processDirectlyOverriddenFunctions ProcessorAction.NEXT
                    }
                    val overridden = declarationStorage.getIrFunctionSymbol(it)
                    overriddenSet += overridden as IrSimpleFunctionSymbol
                    ProcessorAction.NEXT
                }
                irFunction.overriddenSymbols = overriddenSet.toList()
            }
        }
        return irFunction
    }

    fun convertPropertyContent(irProperty: IrProperty, property: FirProperty, containingClass: FirClass<*>?): IrProperty {
        val initializer = property.initializer
        val delegate = property.delegate
        val propertyType = property.returnTypeRef.toIrType()
        irProperty.initializeBackingField(property, initializerExpression = initializer ?: delegate)
        irProperty.getter?.setPropertyAccessorContent(
            property, property.getter, irProperty, propertyType,
            property.getter is FirDefaultPropertyGetter,
            isGetter = true,
            containingClass = containingClass
        )
        if (property.isVar) {
            irProperty.setter?.setPropertyAccessorContent(
                property, property.setter, irProperty, propertyType,
                property.setter is FirDefaultPropertySetter,
                isGetter = false,
                containingClass = containingClass
            )
        }
        annotationGenerator.generate(irProperty, property)
        return irProperty
    }

    private fun IrProperty.initializeBackingField(
        property: FirProperty,
        initializerExpression: FirExpression?
    ) {
        val irField = backingField ?: return
        conversionScope.withParent(irField) {
            declarationStorage.enterScope(this@initializeBackingField)
            // NB: initializer can be already converted
            if (initializer == null && initializerExpression != null) {
                initializer = irFactory.createExpressionBody(visitor.convertToIrExpression(initializerExpression))
            }
            declarationStorage.leaveScope(this@initializeBackingField)
        }
        annotationGenerator.generate(irField, property)
    }

    private fun IrSimpleFunction.setPropertyAccessorContent(
        property: FirProperty,
        propertyAccessor: FirPropertyAccessor?,
        correspondingProperty: IrProperty,
        propertyType: IrType,
        isDefault: Boolean,
        isGetter: Boolean,
        containingClass: FirClass<*>?
    ) {
        conversionScope.withFunction(this) {
            applyParentFromStackTo(this)
            convertFunctionContent(this, propertyAccessor, containingClass = null)
            if (isDefault) {
                conversionScope.withParent(this) {
                    declarationStorage.enterScope(this)
                    val backingField = correspondingProperty.backingField
                    val fieldSymbol = backingField?.symbol
                    val declaration = this
                    if (fieldSymbol != null) {
                        body = IrBlockBodyImpl(
                            startOffset, endOffset,
                            listOf(
                                if (isSetter) {
                                    IrSetFieldImpl(startOffset, endOffset, fieldSymbol, irBuiltIns.unitType).apply {
                                        setReceiver(declaration)
                                        value = IrGetValueImpl(startOffset, endOffset, propertyType, valueParameters.first().symbol)
                                    }
                                } else {
                                    IrReturnImpl(
                                        startOffset, endOffset, irBuiltIns.nothingType, symbol,
                                        IrGetFieldImpl(startOffset, endOffset, fieldSymbol, propertyType).setReceiver(declaration)
                                    )
                                }
                            )
                        )
                    }
                    declarationStorage.leaveScope(this)
                }
            }
            if (containingClass != null) {
                val scope = containingClass.unsubstitutedScope(session, scopeSession)
                scope.processPropertiesByName(property.name) {}
                val overriddenSet = mutableSetOf<IrSimpleFunctionSymbol>()
                scope.processDirectlyOverriddenProperties(property.symbol) {
                    if (it.fir.visibility == Visibilities.PRIVATE) {
                        return@processDirectlyOverriddenProperties ProcessorAction.NEXT
                    }
                    val overridden = declarationStorage.getIrPropertyOrFieldSymbol(it)
                    if (overridden is IrPropertySymbol) {
                        val accessorSymbol =
                            if (isGetter) overridden.owner.getter?.symbol
                            else overridden.owner.setter?.symbol
                        if (accessorSymbol != null) {
                            overriddenSet += accessorSymbol
                        }
                    }
                    ProcessorAction.NEXT
                }
                this.overriddenSymbols = overriddenSet.toList()
            }

        }
        annotationGenerator.generate(this, property)
    }

    private fun IrFieldAccessExpression.setReceiver(declaration: IrDeclaration): IrFieldAccessExpression {
        if (declaration is IrFunction) {
            val dispatchReceiver = declaration.dispatchReceiverParameter
            if (dispatchReceiver != null) {
                receiver = IrGetValueImpl(startOffset, endOffset, dispatchReceiver.symbol)
            }
        }
        return this
    }

    internal fun FirDelegatedConstructorCall.toIrDelegatingConstructorCall(): IrExpression {
        val constructedIrType = constructedTypeRef.toIrType()
        val referencedSymbol = (this.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirConstructorSymbol
            ?: return convertWithOffsets { startOffset, endOffset ->
                IrErrorCallExpressionImpl(
                    startOffset, endOffset, constructedIrType, "Cannot find delegated constructor call"
                )
            }
        val constructorSymbol = referencedSymbol.deepestMatchingOverriddenSymbol() as FirConstructorSymbol
        val firDispatchReceiver = dispatchReceiver
        return convertWithOffsets { startOffset, endOffset ->
            val irConstructorSymbol = declarationStorage.getIrFunctionSymbol(constructorSymbol) as IrConstructorSymbol
            val typeArguments = constructedTypeRef.coneType.fullyExpandedType(session).typeArguments
            val constructor = constructorSymbol.fir
            if (constructor.isFromEnumClass || constructor.returnTypeRef.isEnum) {
                IrEnumConstructorCallImpl(
                    startOffset, endOffset,
                    constructedIrType,
                    irConstructorSymbol,
                    typeArgumentsCount = constructor.typeParameters.size,
                    valueArgumentsCount = constructor.valueParameters.size
                )
            } else {
                IrDelegatingConstructorCallImpl(
                    startOffset, endOffset,
                    constructedIrType,
                    irConstructorSymbol,
                    typeArgumentsCount = constructor.typeParameters.size,
                    valueArgumentsCount = irConstructorSymbol.owner.valueParameters.size
                )
            }.let {
                if (constructor.typeParameters.isNotEmpty()) {
                    if (typeArguments.isNotEmpty()) {
                        for ((index, typeArgument) in typeArguments.withIndex()) {
                            if (index >= constructor.typeParameters.size) break
                            val irType = (typeArgument as ConeKotlinTypeProjection).type.toIrType()
                            it.putTypeArgument(index, irType)
                        }
                    }
                }
                if (firDispatchReceiver !is FirNoReceiverExpression) {
                    it.dispatchReceiver = visitor.convertToIrExpression(firDispatchReceiver)
                }
                with(callGenerator) {
                    it.applyCallArguments(this@toIrDelegatingConstructorCall, annotationMode = false)
                }
            }
        }
    }

    private fun IrValueParameter.setDefaultValue(firValueParameter: FirValueParameter) {
        val firDefaultValue = firValueParameter.defaultValue
        if (firDefaultValue != null) {
            this.defaultValue = factory.createExpressionBody(visitor.convertToIrExpression(firDefaultValue))
        }
    }
}
