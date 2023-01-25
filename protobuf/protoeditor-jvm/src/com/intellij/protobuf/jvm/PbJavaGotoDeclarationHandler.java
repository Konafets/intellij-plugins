/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.protobuf.jvm;

import com.google.common.collect.ImmutableList;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.protobuf.jvm.names.NameGeneratorSelector;
import com.intellij.protobuf.jvm.names.NameMatcher;
import com.intellij.protobuf.lang.psi.*;
import com.intellij.protobuf.lang.psi.util.PbPsiUtil;
import com.intellij.protobuf.shared.gencode.ProtoFromSourceComments;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Handles goto declaration from java generated code -> .proto files.
 */
public class PbJavaGotoDeclarationHandler implements GotoDeclarationHandler {

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(
    @Nullable PsiElement sourceElement, int i, Editor editor) {
    return findProtoDeclarationForJavaElement(sourceElement);
  }

  public static PsiElement @Nullable [] findProtoDeclarationForJavaElement(@Nullable PsiElement sourceElement) {
    if (!(sourceElement instanceof PsiIdentifier)) {
      return null;
    }
    PsiJavaCodeReferenceElement javaRef =
      PsiTreeUtil.getParentOfType(sourceElement, PsiJavaCodeReferenceElement.class);
    if (javaRef == null) {
      return null;
    }
    PsiElement resolved = javaRef.resolve();
    if (resolved == null) {
      return null;
    }
    return findProtoDeclarationForResolvedJavaElement(resolved);
  }

  public static PsiElement @Nullable [] findProtoDeclarationForResolvedJavaElement(@NotNull PsiElement resolved) {
    PbJavaGotoDeclarationContext context = PbJavaGotoReferenceMatch.isFromProto(resolved);
    if (context == null) {
      return null;
    }
    Project project = resolved.getProject();
    Collection<PbFile> matchedFiles =
      PbJavaOuterClassIndex.getFilesWithOuterClass(
        project, context.outerClass.getQualifiedName(), GlobalSearchScope.allScope(project));
    if (matchedFiles.isEmpty()) {
      // Try looking for the .proto file through source comments (ideally an annotation) if we
      // didn't index the .proto file so PbJavaOuterClassIndex doesn't know about it.
      PbFile matchingFile = matchingProtoFileFromSource(resolved);
      if (matchingFile == null) {
        return null;
      }
      matchedFiles = ImmutableList.of(matchingFile);
    }
    List<PsiElement> results = new ArrayList<>();
    for (PbFile file : matchedFiles) {
      results.addAll(findMatchingElements(file, context));
    }
    // Don't include the original resolved element. If a user really wanted to see the
    // generated bytecode, they could use "Go To Implementation" instead of "Declaration".
    return results.toArray(PsiElement.EMPTY_ARRAY);
  }

  @Nullable
  private static PbFile matchingProtoFileFromSource(PsiElement resolvedReference) {
    if (!(resolvedReference.getContainingFile() instanceof PsiCompiledFile)) {
      return null;
    }
    PsiElement possibleSourceElement = resolvedReference.getNavigationElement();
    PsiFile possibleSourceFile = possibleSourceElement.getContainingFile();
    if (possibleSourceFile == null) {
      return null;
    }
    return ProtoFromSourceComments.findProtoOfGeneratedCode("//", possibleSourceFile);
  }

  private static List<PsiElement> findMatchingElements(PbFile file, PbJavaGotoDeclarationContext context) {
    // At this point we know that file has an outer class matching the caret.
    // - We know that contextClass represents a message and is nested within the outer class.
    //   Thus, find the matching message in the file.
    // - Within that message, iterate through the fields. Look for a match that against the
    //   resolved element.
    PsiClass contextClass = context.javaClass;
    String classContextName = contextClass.getQualifiedName();
    String elementName = context.resolvedElement.getName();
    if (classContextName == null || elementName == null) {
      return Collections.emptyList();
    }
    List<PsiElement> results = new ArrayList<>();

    // Track the matched type definitions, in case we don't match a field or other more
    // refined statement (might be a java member that is specific to a message/enum definition).
    List<PsiElement> matchedTypeElements = new ArrayList<>();
    List<NameMatcher> nameMatchers =
      ContainerUtil.map(NameGeneratorSelector.selectForFile(file),
                        generator -> generator.toNameMatcher(context));
    if (context.javaClass.isEnum()) {
      findMatchingEnumElement(file, context, nameMatchers, results, matchedTypeElements);
    }
    else {
      findMatchingClassElement(file, context, nameMatchers, results, matchedTypeElements);
    }
    if (results.isEmpty()) {
      return matchedTypeElements;
    }
    return results;
  }

  private static void findMatchingEnumElement(
    PbFile file,
    PbJavaGotoDeclarationContext context,
    List<NameMatcher> nameMatchers,
    List<PsiElement> results,
    List<PsiElement> matchedTypeElements) {
    boolean searchEnumValues = context.resolvedElement instanceof PsiEnumConstant;
    for (PbSymbol symbol : file.getLocalQualifiedSymbolMap().values()) {
      if (PbPsiUtil.isEnumElement(symbol)) {
        PbEnumDefinition enumDefinition = (PbEnumDefinition)symbol;
        for (NameMatcher matcher : nameMatchers) {
          if (matcher.matchesEnum(enumDefinition)) {
            if (!searchEnumValues) {
              results.add(enumDefinition);
            }
            else {
              matchedTypeElements.add(enumDefinition);
              for (PbEnumValue enumValue : enumDefinition.getEnumValues()) {
                if (matcher.matchesEnumValue(enumValue)) {
                  results.add(enumValue);
                }
              }
            }
          }
        }
      }
      else if (PbPsiUtil.isOneofElement(symbol)) {
        PbOneofDefinition oneof = (PbOneofDefinition)symbol;
        for (NameMatcher matcher : nameMatchers) {
          if (matcher.matchesOneofEnum(oneof)) {
            if (!searchEnumValues) {
              results.add(oneof);
            }
            else {
              matchedTypeElements.add(oneof);
              if (matcher.matchesOneofNotSetEnumValue(oneof)) {
                results.add(oneof);
              }
              else {
                for (PbStatement statement : oneof.getStatements()) {
                  if (statement instanceof PbField) {
                    PbField oneofField = (PbField)statement;
                    if (matcher.matchesOneofEnumValue(oneofField)) {
                      results.add(oneofField);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static void findMatchingClassElement(
    PbFile file,
    PbJavaGotoDeclarationContext context,
    List<NameMatcher> nameMatchers,
    List<PsiElement> results,
    List<PsiElement> matchedTypeElements) {
    boolean searchFields = context.resolvedElement instanceof PsiMember;
    for (PbSymbol symbol : file.getLocalQualifiedSymbolMap().values()) {
      if (PbPsiUtil.isMessageElement(symbol)) {
        PbMessageType message = (PbMessageType)symbol;
        for (NameMatcher matcher : nameMatchers) {
          if (matcher.matchesMessage(message)) {
            if (!searchFields) {
              results.add(message);
            }
            else {
              matchedTypeElements.add(message);
              for (PbField field : message.getSymbols(PbField.class)) {
                if (matcher.matchesField(field)) {
                  results.add(field);
                }
              }
              for (PbOneofDefinition oneof : message.getSymbols(PbOneofDefinition.class)) {
                if (matcher.matchesOneofMember(oneof)) {
                  results.add(oneof);
                }
              }
            }
          }
        }
      }
    }
  }
}
