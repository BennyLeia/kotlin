/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import org.jetbrains.kotlin.idea.frontend.api.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.types.KtDenotableType
import org.jetbrains.kotlin.idea.frontend.api.types.KtType

internal class HighLevelApiLookupElementFactory {
    private val classLookupElementFactory = ClassLookupElementFactory()
    private val variableLookupElementFactory = VariableLookupElementFactory()
    private val functionLookupElementFactory = FunctionLookupElementFactory()

    fun createLookupElement(symbol: KtNamedSymbol): LookupElement {
        val elementBuilder = when (symbol) {
            is KtFunctionSymbol -> functionLookupElementFactory.createLookup(symbol)
            is KtVariableLikeSymbol -> variableLookupElementFactory.createLookup(symbol)
            is KtClassLikeSymbol -> classLookupElementFactory.createLookup(symbol)
            else -> throw IllegalArgumentException("Cannot create a lookup element for $symbol")
        }

        return elementBuilder.withIcon(KotlinSymbolIconProvider.getIconFor(symbol))
    }
}

private class ClassLookupElementFactory {
    fun createLookup(symbol: KtClassLikeSymbol): LookupElementBuilder {
        return LookupElementBuilder.create(symbol.name.asString())
    }
}

private class VariableLookupElementFactory {
    fun createLookup(symbol: KtVariableLikeSymbol): LookupElementBuilder {
        return LookupElementBuilder.create(symbol.name.asString())
            .withTypeText(ShortNamesRenderer.renderType(symbol.type))
    }
}

private class FunctionLookupElementFactory {
    fun createLookup(symbol: KtFunctionSymbol): LookupElementBuilder {
        return LookupElementBuilder.create(symbol.name.asString())
            .appendTailText(ShortNamesRenderer.renderFunctionParameters(symbol), true)
            .withTypeText(ShortNamesRenderer.renderType(symbol.type))
    }
}


private object ShortNamesRenderer {
    fun renderFunctionParameters(function: KtFunctionSymbol): String =
        function.valueParameters.joinToString(", ", "(", ")") { renderFunctionParameter(it) }

    fun renderType(ktType: KtType): String = (ktType as? KtDenotableType)?.asString() ?: ""

    private fun renderFunctionParameter(param: KtFunctionParameterSymbol) = "${param.name.asString()}: ${renderType(param.type)}"
}
