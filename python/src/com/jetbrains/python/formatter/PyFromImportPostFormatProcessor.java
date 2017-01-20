/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.formatter;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessorHelper;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Mikhail Golubev
 */
public class PyFromImportPostFormatProcessor implements PostFormatProcessor {
  @Override
  public PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
    return new Visitor(settings).processElement(source);
  }

  @Override
  public TextRange processText(@NotNull PsiFile source, @NotNull TextRange rangeToReformat, @NotNull CodeStyleSettings settings) {
    return new Visitor(settings).processTextRange(source, rangeToReformat);
  }

  private static class Visitor extends PyRecursiveElementVisitor {
    private final PostFormatProcessorHelper myHelper;
    private final List<PyFromImportStatement> myImportStatements = new ArrayList<>();

    public Visitor(@NotNull CommonCodeStyleSettings settings) {
      myHelper = new PostFormatProcessorHelper(settings);
    }

    @Override
    public void visitPyFromImportStatement(PyFromImportStatement node) {
      if (myHelper.isElementFullyInRange(node)) {
        // If non-parenthesized "from" import ends with one or more of trailing commas, the array returned by getImportElements()
        // contains empty import elements at the end
        final List<PyImportElement> importedNames = ContainerUtil.filter(node.getImportElements(), elem -> elem.getTextLength() != 0);
        if (importedNames.size() > 1) {

          final PyCodeStyleSettings pySettings = ((CodeStyleSettings)myHelper.getSettings()).getCustomSettings(PyCodeStyleSettings.class);
          final boolean forcedParentheses = pySettings.FROM_IMPORT_PARENTHESES_FORCE == CommonCodeStyleSettings.FORCE_BRACES_ALWAYS ||
                                            pySettings.FROM_IMPORT_PARENTHESES_FORCE == CommonCodeStyleSettings.FORCE_BRACES_IF_MULTILINE &&
                                            PostFormatProcessorHelper.isMultiline(node);
          final boolean forcedComma = pySettings.FROM_IMPORT_TRAILING_COMMA_IF_MULTILINE && PostFormatProcessorHelper.isMultiline(node);
          final PyImportElement lastImportedName = importedNames.get(importedNames.size() - 1);
          final PsiElement afterLastName = PyPsiUtils.getNextNonCommentSibling(lastImportedName, true);
          final PsiElement openingParen = node.getLeftParen();
          final boolean missingComma = afterLastName == null || afterLastName.getNode().getElementType() != PyTokenTypes.COMMA;
          // Trailing comma is allowed only in "from" imports wrapped in parentheses 
          if (forcedParentheses && openingParen == null || forcedComma && missingComma && openingParen != null) {
            myImportStatements.add(node);
          }
        }
      }
    }

    @NotNull
    public PsiElement processElement(@NotNull PsiElement element) {
      findAndReplaceFromImports(element);
      return element;
    }

    @NotNull
    public TextRange processTextRange(@NotNull PsiFile file, @NotNull TextRange range) {
      myHelper.setResultTextRange(range);
      findAndReplaceFromImports(file);
      return myHelper.getResultTextRange();
    }

    private void findAndReplaceFromImports(@NotNull PsiElement element) {
      if (element.getContainingFile() instanceof PyFile) {
        element.accept(this);
        Collections.reverse(myImportStatements);
        for (PyFromImportStatement statement : myImportStatements) {
          replaceFromImport(statement);
        }
      }
    }

    @NotNull
    private PyFromImportStatement replaceFromImport(@NotNull PyFromImportStatement fromImport) {
      final PyImportElement[] allNames = fromImport.getImportElements();
      final PyImportElement firstName = allNames[0];
      final PyElementGenerator generator = PyElementGenerator.getInstance(fromImport.getProject());
      final CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(fromImport.getProject());

      if (fromImport.getLeftParen() == null) {
        // Surround with parentheses stripping obsolete continuation backslashes and added trailing comma if necessary
        final String beforeFirstName = fromImport.getText().substring(0, firstName.getStartOffsetInParent());
        final StringBuilder newStatementText = new StringBuilder(beforeFirstName);
        newStatementText.append("(");
        boolean lastElementWasComment = false;
        int lastVisibleNameCommaOffset = -1;
        for (PsiElement cur = firstName; cur != null; cur = cur.getNextSibling()) {
          if (cur instanceof PsiWhiteSpace) {
            newStatementText.append(cur.getText().replace("\\", ""));
          }
          else {
            newStatementText.append(cur.getText());
          }
          if (cur instanceof PyImportElement && cur.getTextLength() != 0) {
            lastVisibleNameCommaOffset = newStatementText.length();
          }
          else if (lastVisibleNameCommaOffset != -1 && cur.getNode().getElementType() == PyTokenTypes.COMMA) {
            lastVisibleNameCommaOffset = -1;
          }
          lastElementWasComment = cur instanceof PsiComment;
        }
        final PyCodeStyleSettings pySettings = ((CodeStyleSettings)myHelper.getSettings()).getCustomSettings(PyCodeStyleSettings.class);
        if (lastVisibleNameCommaOffset != -1 && pySettings.FROM_IMPORT_TRAILING_COMMA_IF_MULTILINE) {
          newStatementText.insert(lastVisibleNameCommaOffset, ",");
        }
        if (lastElementWasComment) {
          newStatementText.append("\n");
        }
        newStatementText.append(")");

        final LanguageLevel level = LanguageLevel.forElement(fromImport);
        PyFromImportStatement newFromImport = generator.createFromText(level, PyFromImportStatement.class, newStatementText.toString());
        newFromImport = (PyFromImportStatement)fromImport.replace(newFromImport);
        newFromImport = (PyFromImportStatement)codeStyleManager.reformat(newFromImport, true);
        myHelper.updateResultRange(fromImport.getTextLength(), newFromImport.getTextLength());
        return newFromImport;
      }
      else {
        // Add only trailing comma
        final PsiElement comma = fromImport.addAfter(generator.createComma().getPsi(), allNames[allNames.length - 1]);
        codeStyleManager.reformat(comma);
        return fromImport;
      }
    }
  }
  
}
