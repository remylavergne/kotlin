/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierList
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtModifierListOwner

abstract class FirLightModifierList<out T : FirLightElement<KtModifierListOwner>>(
    protected val owner: T
) : KtLightElementBase(owner), PsiModifierList, FirLightElement<KtModifierList> {

    override val kotlinOrigin: KtModifierList?
        get() = owner.kotlinOrigin?.modifierList

    override fun getParent() = owner

    override fun hasExplicitModifier(name: String) = hasModifierProperty(name)

    override fun setModifierProperty(name: String, value: Boolean) = TODO()
    override fun checkSetModifierProperty(name: String, value: Boolean) = TODO()
    override fun addAnnotation(qualifiedName: String) = TODO()

    override fun getApplicableAnnotations(): Array<out PsiAnnotation> = annotations

    override fun getAnnotations(): Array<out PsiAnnotation> = TODO()
    override fun findAnnotation(qualifiedName: String) = TODO()

    override fun isEquivalentTo(another: PsiElement?) =
        another is FirLightModifierList<*> && owner == another.owner

    override fun isWritable() = false

    override fun toString() = "Light modifier list of $owner"
}
