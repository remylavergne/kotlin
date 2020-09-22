/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.psi.*
import com.intellij.psi.impl.PsiVariableEx
import org.jetbrains.kotlin.asJava.KtLightClassMarker
import org.jetbrains.kotlin.asJava.elements.KtLightAbstractAnnotation
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtParameter

interface FirLightElement<out T : KtElement> : PsiElement {
    val kotlinOrigin: T?
}
interface FirLightAnnotatedElement<out T : KtElement> : FirLightElement<T> {
    val annotations: List<KtLightAbstractAnnotation>
}
interface FirLightDeclaration<out T : KtDeclaration> : FirLightElement<T>, PsiNamedElement
interface FirLightMember : PsiMember, FirLightDeclaration<KtDeclaration>, PsiNameIdentifierOwner, PsiDocCommentOwner
interface FirLightField : PsiField, FirLightMember, PsiVariableEx
interface FirLightParameter : PsiParameter, FirLightDeclaration<KtParameter>
interface FirLightMethod : PsiAnnotationMethod, FirLightMember

interface FirLightClass : PsiClass, FirLightDeclaration<KtClassOrObject>, KtLightClassMarker