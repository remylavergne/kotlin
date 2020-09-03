/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.utils

import kotlinx.metadata.*
import kotlinx.metadata.klib.*
import org.jetbrains.kotlin.library.MetadataLibrary
import org.jetbrains.kotlin.library.ToolingSingleFileKlibResolveStrategy
import org.jetbrains.kotlin.library.resolveSingleFileKlib
import java.util.*
import kotlin.reflect.KProperty0
import org.jetbrains.kotlin.konan.file.File as KFile

class MetadataDeclarationsComparator(private val config: Config = Config.Default) {
    private val mismatches = mutableListOf<Mismatch>()

    interface Config {
        val rootPathElement: String
            get() = "<root>"

        fun shouldCheckDeclaration(declaration: Any): Boolean =
            (declaration !is KmFunction) || !declaration.name.startsWith(KNI_BRIDGE_FUNCTION_PREFIX)

        companion object Default : Config
    }

    sealed class Result {
        object Success : Result()
        class Failure(val mismatches: Collection<Mismatch>) : Result() {
            init {
                check(mismatches.isNotEmpty())
            }
        }
    }

    sealed class Mismatch {
        // an entity has different non-nullable values
        class DifferentValues(
            val kind: String,
            val name: String,
            val path: List<String>,
            val valueA: Any,
            val valueB: Any
        ) : Mismatch()

        // an entity is missing at one side and present at another side,
        // or: an entity has nullable value at one side and non-nullable value at another side
        class MissingEntity(
            val kind: String,
            val name: String,
            val path: List<String>,
            val presentValue: Any,
            val missingInA: Boolean
        ) : Mismatch() {
            val missingInB: Boolean
                get() = !missingInA
        }
    }

    private class Context(pathElement: String, parent: Context? = null) {
        val path: List<String> = parent?.path.orEmpty() + pathElement
        fun next(pathElement: String): Context = Context(pathElement, this)
    }

    private fun toResult() = if (mismatches.isEmpty()) Result.Success else Result.Failure(mismatches)

    fun compare(
        metadataA: KlibModuleMetadata,
        metadataB: KlibModuleMetadata
    ): Result {
        val rootContext = Context(config.rootPathElement)

        compareFields(rootContext, metadataA.name, metadataB.name, "ModuleName")
        if (mismatches.isNotEmpty())
            return toResult()

        val moduleContext = rootContext.next("Module ${metadataA.name}")

        compareAnnotationLists(moduleContext, metadataA.annotations, metadataB.annotations)
        compareModuleFragmentLists(moduleContext, metadataA.fragments, metadataB.fragments)

        return toResult()
    }

    private fun compareAnnotationLists(
        containerContext: Context,
        annotationListA: List<KmAnnotation>,
        annotationListB: List<KmAnnotation>,
        annotationKind: String = "Annotation"
    ) {
        compareRepetitiveEntityLists(
            entityListA = annotationListA,
            entityListB = annotationListB,
            groupingKeySelector = KmAnnotation::className,
            groupedEntityListsComparator = { annotationClassName: ClassName, annotationsA: List<KmAnnotation>, annotationsB: List<KmAnnotation> ->
                @Suppress("NAME_SHADOWING") val annotationsB: Deque<KmAnnotation> = LinkedList(annotationsB)

                for (annotationA in annotationsA) {
                    val removed = annotationsB.removeFirstOccurrence(annotationA)
                    if (!removed)
                        mismatches += Mismatch.MissingEntity(annotationKind, annotationClassName, containerContext.path, annotationA, false)
                }

                for (annotationB in annotationsB) {
                    mismatches += Mismatch.MissingEntity(annotationKind, annotationClassName, containerContext.path, annotationB, true)
                }
            }
        )
    }

    private fun compareModuleFragmentLists(
        moduleContext: Context,
        fragmentListA: List<KmModuleFragment>,
        fragmentListB: List<KmModuleFragment>
    ) {
        compareRepetitiveEntityLists(
            entityListA = fragmentListA,
            entityListB = fragmentListB,
            groupingKeySelector = { it.fqName.orEmpty() },
            groupedEntityListsComparator = { packageFqName: String, fragmentsA: List<KmModuleFragment>, fragmentsB: List<KmModuleFragment> ->
                val packageContext = moduleContext.next("Package ${packageFqName.ifEmpty { "<empty>" }}")

                val classesA: List<KmClass> = fragmentsA.flatMap { it.classes }
                val classesB: List<KmClass> = fragmentsB.flatMap { it.classes }
                compareClassLists(packageContext, classesA, classesB)

                val typeAliasesA: List<KmTypeAlias> = fragmentsA.flatMap { it.pkg?.typeAliases.orEmpty() }
                val typeAliasesB: List<KmTypeAlias> = fragmentsB.flatMap { it.pkg?.typeAliases.orEmpty() }
                compareTypeAliasLists(packageContext, typeAliasesA, typeAliasesB)

                val propertiesA: List<KmProperty> = fragmentsA.flatMap { it.pkg?.properties.orEmpty() }
                val propertiesB: List<KmProperty> = fragmentsB.flatMap { it.pkg?.properties.orEmpty() }
                comparePropertyLists(packageContext, propertiesA, propertiesB)

                val functionsA: List<KmFunction> = fragmentsA.flatMap { it.pkg?.functions.orEmpty() }
                val functionsB: List<KmFunction> = fragmentsB.flatMap { it.pkg?.functions.orEmpty() }
                compareFunctionLists(packageContext, functionsA, functionsB)
            }
        )
    }

    private fun compareClassLists(
        containerContext: Context,
        classListA: List<KmClass>,
        classListB: List<KmClass>
    ) {
        compareUniqueEntityLists(
            containerContext = containerContext,
            entityListA = classListA,
            entityListB = classListB,
            entityKind = "Class",
            groupingKeySelector = KmClass::name,
            entitiesComparator = ::compareClasses
        )
    }

    private fun compareTypeAliasLists(
        containerContext: Context,
        typeAliasListA: List<KmTypeAlias>,
        typeAliasListB: List<KmTypeAlias>
    ) {
        compareUniqueEntityLists(
            containerContext = containerContext,
            entityListA = typeAliasListA,
            entityListB = typeAliasListB,
            entityKind = "TypeAlias",
            groupingKeySelector = KmTypeAlias::name,
            entitiesComparator = ::compareTypeAliases
        )
    }

    private fun comparePropertyLists(
        containerContext: Context,
        propertyListA: List<KmProperty>,
        propertyListB: List<KmProperty>
    ) {
        compareUniqueEntityLists(
            containerContext = containerContext,
            entityListA = propertyListA,
            entityListB = propertyListB,
            entityKind = "Property",
            groupingKeySelector = KmProperty::name,
            entitiesComparator = ::compareProperties
        )
    }

    private fun compareFunctionLists(
        containerContext: Context,
        functionListA: List<KmFunction>,
        functionListB: List<KmFunction>
    ) {
        compareUniqueEntityLists(
            containerContext = containerContext,
            entityListA = functionListA,
            entityListB = functionListB,
            entityKind = "Function",
            groupingKeySelector = KmFunction::mangle,
            entitiesComparator = ::compareFunctions
        )
    }

    private fun compareConstructorLists(
        containerContext: Context,
        constructorListA: List<KmConstructor>,
        constructorListB: List<KmConstructor>
    ) {
        compareUniqueEntityLists(
            containerContext = containerContext,
            entityListA = constructorListA,
            entityListB = constructorListB,
            entityKind = "Constructor",
            groupingKeySelector = KmConstructor::mangle,
            entitiesComparator = ::compareConstructors
        )
    }

    private fun compareClasses(
        classContext: Context,
        classA: KmClass,
        classB: KmClass
    ) {
        compareFlags(classContext, classA.flags, classB.flags, CLASS_FLAGS)
        compareAnnotationLists(classContext, classA.annotations, classB.annotations)

        // type parameters
        // supertypes

        compareConstructorLists(classContext, classA.constructors, classB.constructors)
        compareTypeAliasLists(classContext, classA.typeAliases, classB.typeAliases)
        comparePropertyLists(classContext, classA.properties, classB.properties)
        compareFunctionLists(classContext, classA.functions, classB.functions)

        compareNullableFields(classContext, classA.companionObject, classB.companionObject, "CompanionObject")
        compareFields(classContext, classA.nestedClasses, classB.nestedClasses, "NestedClass")
        compareFields(classContext, classA.sealedSubclasses, classB.sealedSubclasses, "SealedSubclass")
        compareFields(classContext, classA.enumEntries, classB.enumEntries, "EnumEntry")

        compareUniqueEntityLists(
            containerContext = classContext,
            classA.klibEnumEntries,
            classB.klibEnumEntries,
            "KlibEnumEntry",
            KlibEnumEntry::name
        ) { context: Context, entryA: KlibEnumEntry, entryB: KlibEnumEntry ->
            compareAnnotationLists(context, entryA.annotations, entryB.annotations)
            compareNullableFields(context, entryA.ordinal, entryB.ordinal, "Ordinal")
        }
    }

    private fun compareTypeAliases(
        typeAliasContext: Context,
        typeAliasA: KmTypeAlias,
        typeAliasB: KmTypeAlias
    ) {
        compareFlags(typeAliasContext, typeAliasA.flags, typeAliasB.flags, emptyList())
        compareAnnotationLists(typeAliasContext, typeAliasA.annotations, typeAliasB.annotations)

        // TODO: type parameters
        // TODO: underlying type
        // TODO: expanded type
    }

    private fun compareProperties(
        propertyContext: Context,
        propertyA: KmProperty,
        propertyB: KmProperty
    ) {
        compareFlags(propertyContext, propertyA.flags, propertyB.flags, PROPERTY_FLAGS)
        compareFlags(propertyContext, propertyA.getterFlags, propertyB.getterFlags, PROPERTY_ACCESSOR_FLAGS, "GetterFlag")
        compareFlags(propertyContext, propertyA.setterFlags, propertyB.setterFlags, PROPERTY_ACCESSOR_FLAGS, "SetterFlag")

        compareAnnotationLists(propertyContext, propertyA.annotations, propertyB.annotations)
        compareAnnotationLists(propertyContext, propertyA.getterAnnotations, propertyB.getterAnnotations, "GetterAnnotation")
        compareAnnotationLists(propertyContext, propertyA.setterAnnotations, propertyB.setterAnnotations, "SetterAnnotation")

        compareNullableFields(propertyContext, propertyA.compileTimeValue, propertyB.compileTimeValue, "CompileTimeValue")

        // TODO: type parameters
        // TODO: receiverParameterType
        // TODO: setterParameter
        // TODO: return type
    }

    private fun compareFunctions(
        functionContext: Context,
        functionA: KmFunction,
        functionB: KmFunction
    ) {
        compareFlags(functionContext, functionA.flags, functionB.flags, FUNCTION_FLAGS)
        compareAnnotationLists(functionContext, functionA.annotations, functionB.annotations)

        // TODO: type parameters
        // TODO: receiverParameterType
        // TODO: value parameters
        // TODO: return type
    }

    private fun compareConstructors(
        constructorContext: Context,
        constructorA: KmConstructor,
        constructorB: KmConstructor
    ) {
        compareFlags(constructorContext, constructorA.flags, constructorB.flags, CONSTRUCTOR_FLAGS)
        compareAnnotationLists(constructorContext, constructorA.annotations, constructorB.annotations)

        // TODO: value parameters
    }

    private fun <E : Any> compareFields(
        containerContext: Context,
        fieldA: E,
        fieldB: E,
        fieldKind: String,
        fieldName: String? = null
    ) {
        if (fieldA != fieldB)
            mismatches += Mismatch.DifferentValues(fieldKind, fieldName.orEmpty(), containerContext.path, fieldA, fieldB)
    }

    private fun <E : Any> compareFields(
        containerContext: Context,
        listA: Collection<E>,
        listB: Collection<E>,
        fieldKind: String
    ) {
        if (listA.isEmpty() && listB.isEmpty())
            return

        for (missingInA in listB subtract listA) {
            mismatches += Mismatch.MissingEntity(fieldKind, missingInA.toString(), containerContext.path, missingInA, true)
        }

        for (missingInB in listA subtract listB) {
            mismatches += Mismatch.MissingEntity(fieldKind, missingInB.toString(), containerContext.path, missingInB, false)
        }
    }

    private fun <E : Any> compareNullableFields(
        containerContext: Context,
        fieldA: E?,
        fieldB: E?,
        fieldKind: String
    ) {
        @Suppress("NAME_SHADOWING")
        compareNullableEntities(
            containerContext = containerContext,
            entityA = fieldA,
            entityB = fieldB,
            entityKind = fieldKind,
            entityKey = null
        ) { context: Context, fieldA: E, fieldB: E -> compareFields(context, fieldA, fieldB, fieldKind) }
    }

    private fun <E : Any> compareNullableEntities(
        containerContext: Context,
        entityA: E?,
        entityB: E?,
        entityKind: String,
        entityKey: String?,
        entitiesComparator: (Context, E, E) -> Unit
    ) {
        when {
            entityA == null && entityB != null -> {
                mismatches += Mismatch.MissingEntity(entityKind, entityKey.orEmpty(), containerContext.path, entityB, true)
            }
            entityA != null && entityB == null -> {
                mismatches += Mismatch.MissingEntity(entityKind, entityKey.orEmpty(), containerContext.path, entityA, false)
            }
            entityA != null && entityB != null -> {
                val context = if (entityKey != null) containerContext.next("$entityKind $entityKey") else containerContext
                entitiesComparator(context, entityA, entityB)
            }
        }
    }

    private fun <E : Any> compareUniqueEntityLists(
        containerContext: Context,
        entityListA: List<E>,
        entityListB: List<E>,
        entityKind: String,
        groupingKeySelector: (E) -> String,
        entitiesComparator: (Context, E, E) -> Unit
    ) {
        compareRepetitiveEntityLists(
            entityListA = entityListA,
            entityListB = entityListB,
            groupingKeySelector = groupingKeySelector,
            groupedEntityListsComparator = { entityKey: String, entitiesA: List<E>, entitiesB: List<E> ->
                compareNullableEntities(
                    containerContext = containerContext,
                    entityA = entitiesA.singleOrNull(),
                    entityB = entitiesB.singleOrNull(),
                    entityKind = entityKind,
                    entityKey = entityKey,
                    entitiesComparator = entitiesComparator
                )
            }
        )
    }

    private fun <E : Any> compareRepetitiveEntityLists(
        entityListA: List<E>,
        entityListB: List<E>,
        groupingKeySelector: (E) -> String,
        groupedEntityListsComparator: (entityKey: String, List<E>, List<E>) -> Unit
    ) {
        if (entityListA.isEmpty() && entityListB.isEmpty())
            return

        val groupedEntitiesA: Map<String, List<E>> = entityListA.groupBy(groupingKeySelector)
        val groupedEntitiesB: Map<String, List<E>> = entityListB.groupBy(groupingKeySelector)

        val entityKeys = groupedEntitiesA.keys union groupedEntitiesB.keys

        for (entityKey in entityKeys) {
            val entitiesA: List<E> = groupedEntitiesA[entityKey].orEmpty()
            val entitiesB: List<E> = groupedEntitiesB[entityKey].orEmpty()

            groupedEntityListsComparator(entityKey, entitiesA, entitiesB)
        }
    }

    private fun compareFlags(
        containerContext: Context,
        flagsA: Flags,
        flagsB: Flags,
        customFlagsToCompare: List<KProperty0<Flag>>,
        flagKind: String = "Flag"
    ) {
        for (flag in COMMON_FLAGS + customFlagsToCompare) {
            val valueA = flag.get()(flagsA)
            val valueB = flag.get()(flagsB)

            compareFields(containerContext, valueA, valueB, flagKind, flag.name)
        }
    }

    companion object {
        private val COMMON_FLAGS: List<KProperty0<Flag>> = with(Flag.Common) {
            listOf(
                ::HAS_ANNOTATIONS, ::IS_INTERNAL, ::IS_PRIVATE, ::IS_PROTECTED, ::IS_PUBLIC, ::IS_PRIVATE_TO_THIS, ::IS_LOCAL, ::IS_FINAL,
                ::IS_OPEN, ::IS_ABSTRACT, ::IS_SEALED
            )
        }

        private val CLASS_FLAGS: List<KProperty0<Flag>> = with(Flag.Class) {
            listOf(
                ::IS_CLASS, ::IS_INTERFACE, ::IS_ENUM_CLASS, ::IS_ENUM_ENTRY, ::IS_ANNOTATION_CLASS, ::IS_OBJECT, ::IS_COMPANION_OBJECT,
                ::IS_INNER, ::IS_DATA, ::IS_EXTERNAL, ::IS_EXPECT, ::IS_INLINE, ::IS_FUN
            )
        }

        private val CONSTRUCTOR_FLAGS: List<KProperty0<Flag>> = with(Flag.Constructor) {
            listOf(::IS_PRIMARY, ::HAS_NON_STABLE_PARAMETER_NAMES)
        }

        private val FUNCTION_FLAGS: List<KProperty0<Flag>> = with(Flag.Function) {
            listOf(
                ::IS_DECLARATION, ::IS_FAKE_OVERRIDE, ::IS_DELEGATION, ::IS_SYNTHESIZED, ::IS_OPERATOR, ::IS_INFIX, ::IS_INLINE,
                ::IS_TAILREC, ::IS_EXTERNAL, ::IS_SUSPEND, ::IS_EXPECT, ::HAS_NON_STABLE_PARAMETER_NAMES
            )
        }

        private val PROPERTY_FLAGS: List<KProperty0<Flag>> = with(Flag.Property) {
            listOf(
                ::IS_DECLARATION, ::IS_FAKE_OVERRIDE, ::IS_DELEGATION, ::IS_SYNTHESIZED, ::IS_VAR, ::HAS_GETTER, ::HAS_SETTER,
                ::IS_CONST, ::IS_LATEINIT, ::HAS_CONSTANT, ::IS_EXTERNAL, ::IS_DELEGATED, ::IS_EXPECT
            )
        }

        private val PROPERTY_ACCESSOR_FLAGS: List<KProperty0<Flag>> = with(Flag.PropertyAccessor) {
            listOf(::IS_NOT_DEFAULT, ::IS_EXTERNAL, ::IS_INLINE)
        }

        private val TYPE_FLAGS: List<KProperty0<Flag>> = with(Flag.Type) {
            listOf(::IS_NULLABLE, ::IS_SUSPEND)
        }

        private val TYPE_PARAMETER_FLAGS: List<KProperty0<Flag>> = with(Flag.TypeParameter) {
            listOf(::IS_REIFIED)
        }

        private val VALUE_PARAMETER_FLAGS: List<KProperty0<Flag>> = with(Flag.ValueParameter) {
            listOf(::DECLARES_DEFAULT_VALUE, ::IS_CROSSINLINE, ::IS_NOINLINE)
        }
    }
}

///**
// * Merges two fragments of a single module into one.
// */
//private fun joinFragments(fragmentA: KmModuleFragment, fragmentB: KmModuleFragment) = KmModuleFragment().apply {
//    check(fragmentA.fqName == fragmentB.fqName)
//    pkg = when {
//        fragmentA.pkg != null && fragmentB.pkg != null -> joinAndSortPackages(fragmentA.pkg!!, fragmentB.pkg!!)
//        fragmentA.pkg != null -> joinAndSortPackages(fragmentA.pkg!!, KmPackage())
//        fragmentB.pkg != null -> joinAndSortPackages(KmPackage(), fragmentB.pkg!!)
//        else -> null
//    }
//    fqName = fragmentA.fqName
//    classes += (fragmentA.classes + fragmentB.classes).sortedBy(KmClass::name)
//}

//private fun joinAndSortPackages(pkgA: KmPackage, pkgB: KmPackage) = KmPackage().apply {
//    functions += (pkgA.functions + pkgB.functions).sortedBy(KmFunction::mangle)
//    properties += (pkgA.properties + pkgB.properties).sortedBy(KmProperty::name)
//    typeAliases += (pkgA.typeAliases + pkgB.typeAliases).sortedBy(KmTypeAlias::name)
//}

/**
 * We need a stable order for overloaded functions.
 */
internal fun KmFunction.mangle(): String {
    return buildString {
        receiverParameterType?.classifier?.let { receiver ->
            append(receiver)
        }
        append('.')
        append(name)
        append('.')
        typeParameters.joinTo(this, prefix = "<", postfix = ">", transform = KmTypeParameter::name)
        append('.')
        valueParameters.joinTo(this, prefix = "(", postfix = ")", transform = KmValueParameter::name)
    }
}

internal fun KmConstructor.mangle(): String {
    return valueParameters.joinToString(prefix = "(", postfix = ")", transform = KmValueParameter::name)
}


fun main() {
    val libraryPath =
        "/Users/dmitriy.dolovov/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-iosarm64/1.3.9/cabb410dc423afad65b27a1c09731d60047ade56/kotlinx-coroutines-core.klib"

    val klib = resolveSingleFileKlib(KFile(libraryPath), strategy = ToolingSingleFileKlibResolveStrategy)
    val metadata = KlibModuleMetadata.read(TrivialLibraryProvider(klib))

    val result = MetadataDeclarationsComparator().compare(metadata, metadata)
    println(result)
}

/**
 * Provides access to metadata using default compiler's routine.
 */
private class TrivialLibraryProvider(
    private val library: MetadataLibrary
) : KlibModuleMetadata.MetadataLibraryProvider {

    override val moduleHeaderData: ByteArray
        get() = library.moduleHeaderData

    override fun packageMetadata(fqName: String, partName: String): ByteArray =
        library.packageMetadata(fqName, partName)

    override fun packageMetadataParts(fqName: String): Set<String> =
        library.packageMetadataParts(fqName)
}