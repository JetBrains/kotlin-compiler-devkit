package org.jetbrains.kotlin.test.helper.findusages

import com.intellij.find.findUsages.AbstractFindUsagesDialog
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import fleet.util.runIf
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly

class FirElementFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        return (element is KtProperty || element is KtNamedFunction) && isMySpecialSymbol(element)
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        return runIf(!forHighlightUsages && canFindUsages(element)) {
            val project = element.project

            // Find the “next” factory (Kotlin’s) and ask it for its handler
            val base = EP_NAME
                .getExtensionList(project)
                .firstOrNull { it !== this && it.canFindUsages(element) }       // Kotlin’s factory
                ?.createFindUsagesHandler(element, forHighlightUsages)
                ?.takeIf { it !== FindUsagesHandler.NULL_HANDLER }
                ?: return null

            MyKotlinFindUsagesHandler(element as KtNamedDeclaration, base)
        }
    }

    private fun isMySpecialSymbol(decl: KtNamedDeclaration): Boolean {
        if (decl.name == null) return false
        if (decl is KtNamedFunction && decl.name?.startsWith("replace") != true && decl.name?.startsWith("transform") != true) return false
        val containingClass = decl.containingClass() ?: return false
        return containingClass.name.let { it != null && it.startsWith("Fir") }
    }

    class MyKotlinFindUsagesHandler(
        element: KtNamedDeclaration,
        private val delegate: FindUsagesHandler
    ) : FindUsagesHandler(element) {

        override fun getSecondaryElements(): Array<PsiElement> {
            val psiElement = psiElement as KtNamedDeclaration

            val propertyName = if (psiElement is KtProperty) {
                psiElement.name
            } else {
                val name = psiElement.name ?: return arrayOf(psiElement)
                if (name.startsWith("replace")) name.substringAfter("replace").decapitalizeAsciiOnly()
                else if (name.startsWith("transform")) name.substringAfter("transform").decapitalizeAsciiOnly()
                else null
            } ?: return arrayOf(psiElement)

            val containingClass = psiElement.containingClass() ?: return arrayOf(psiElement)
            val methodSuffix = propertyName.capitalizeAsciiOnly()

            return buildSet {
                addAll(delegate.secondaryElements)
                containingClass.declarations.filterTo(this) {
                    it is KtProperty && it.name == propertyName ||
                            it is KtNamedFunction && (it.name == "replace$methodSuffix" || it.name == "transform$methodSuffix")
                }
            }.toTypedArray()
        }

        override fun getPrimaryElements(): Array<PsiElement> {
            return delegate.primaryElements
        }

        override fun getFindUsagesOptions(dataContext: DataContext?): FindUsagesOptions =
            delegate.getFindUsagesOptions(dataContext)

        override fun processElementUsages(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            options: FindUsagesOptions
        ): Boolean =
            delegate.processElementUsages(element, processor, options)

        override fun processUsagesInText(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            searchScope: GlobalSearchScope
        ): Boolean =
            delegate.processUsagesInText(element, processor, searchScope)

        override fun getFindUsagesDialog(
            isSingleFile: Boolean,
            toShowInNewTab: Boolean,
            mustOpenInNewTab: Boolean
        ): AbstractFindUsagesDialog =
            delegate.getFindUsagesDialog(isSingleFile, toShowInNewTab, mustOpenInNewTab)

        override fun isSearchForTextOccurrencesAvailable(psiElement: PsiElement, isSingleFile: Boolean): Boolean =
            !isSingleFile && psiElement !is KtParameter

        override fun findReferencesToHighlight(
            target: PsiElement,
            searchScope: SearchScope
        ): Collection<PsiReference> =
            delegate.findReferencesToHighlight(target, searchScope)
    }
}
