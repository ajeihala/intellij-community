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
package com.intellij.codeInspection.reflectiveAccess;

import com.intellij.codeInsight.daemon.JavaErrorMessages;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInspection.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.resolve.reference.impl.JavaReflectionReferenceUtil;
import com.intellij.util.PlatformIcons;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.intellij.psi.impl.source.resolve.reference.impl.JavaReflectionReferenceUtil.*;

/**
 * @author Pavel.Dolgov
 */
public class JavaLangInvokeHandleSignatureInspection extends BaseJavaBatchLocalInspectionTool {
  public static final Key<List<String>> DEFAULT_SIGNATURE = Key.create("DEFAULT_SIGNATURE");

  private static final String FIND_CONSTRUCTOR = "findConstructor";
  private static final Set<String> KNOWN_METHOD_NAMES = Collections.unmodifiableSet(
    ContainerUtil.union(Arrays.asList(HANDLE_FACTORY_METHOD_NAMES), Collections.singletonList(FIND_CONSTRUCTOR)));

  private static final List<String> NO_ARGUMENT_CONSTRUCTOR_SIGNATURE = Collections.singletonList(PsiKeyword.VOID);

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression callExpression) {
        super.visitMethodCallExpression(callExpression);

        final PsiReferenceExpression methodExpression = callExpression.getMethodExpression();
        final String methodName = methodExpression.getReferenceName();
        if (methodName != null && KNOWN_METHOD_NAMES.contains(methodName)) {
          final PsiMethod method = callExpression.resolveMethod();
          final PsiClass psiClass = method != null ? method.getContainingClass() : null;
          if (psiClass != null && JAVA_LANG_INVOKE_METHOD_HANDLES_LOOKUP.equals(psiClass.getQualifiedName())) {
            final PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
            checkHandlerFactory(methodName, methodExpression, arguments, holder);
          }
        }
      }
    };
  }

  private static void checkHandlerFactory(@NotNull String factoryMethodName,
                                          @NotNull PsiReferenceExpression factoryMethodExpression,
                                          @NotNull PsiExpression[] arguments,
                                          @NotNull ProblemsHolder holder) {
    if (arguments.length == 2) {
      if (FIND_CONSTRUCTOR.equals(factoryMethodName)) {
        final PsiClass ownerClass = getReflectiveClass(arguments[0]);
        if (ownerClass != null) {
          final PsiExpression typeExpression = ParenthesesUtils.stripParentheses(arguments[1]);
          checkConstructor(ownerClass, typeExpression, holder);
        }
      }
    }
    else if (arguments.length >= 3) {
      final PsiClass ownerClass = getReflectiveClass(arguments[0]);
      if (ownerClass != null) {
        final PsiExpression nameExpression = ParenthesesUtils.stripParentheses(arguments[1]);
        final PsiExpression nameDefinition = findDefinition(nameExpression);
        final String memberName = computeConstantExpression(nameDefinition, String.class);
        if (!StringUtil.isEmpty(memberName)) {
          final PsiExpression typeExpression = ParenthesesUtils.stripParentheses(arguments[2]);

          switch (factoryMethodName) {
            case FIND_GETTER:
            case FIND_SETTER:
            case FIND_VAR_HANDLE:
              checkField(ownerClass, memberName, nameExpression, typeExpression, false, factoryMethodExpression, holder);
              break;

            case FIND_STATIC_GETTER:
            case FIND_STATIC_SETTER:
            case FIND_STATIC_VAR_HANDLE:
              checkField(ownerClass, memberName, nameExpression, typeExpression, true, factoryMethodExpression, holder);
              break;

            case FIND_VIRTUAL:
              checkMethod(ownerClass, memberName, nameExpression, typeExpression, false, factoryMethodExpression, holder);
              break;

            case FIND_STATIC:
              checkMethod(ownerClass, memberName, nameExpression, typeExpression, true, factoryMethodExpression, holder);
              break;

            case FIND_SPECIAL:
              checkMethod(ownerClass, memberName, nameExpression, typeExpression, false, factoryMethodExpression, holder);
              break;
          }
        }
      }
    }
  }

  private static void checkConstructor(@NotNull PsiClass ownerClass,
                                       @NotNull PsiExpression constructorTypeExpression,
                                       @NotNull ProblemsHolder holder) {
    final List<String> constructorSignature = composeMethodSignature(constructorTypeExpression);
    if (constructorSignature != null) {
      final List<PsiMethod> constructors = ContainerUtil.filter(ownerClass.getMethods(), PsiMethod::isConstructor);
      List<List<String>> validSignatures = null;
      if (constructors.isEmpty()) {
        if (!constructorSignature.equals(NO_ARGUMENT_CONSTRUCTOR_SIGNATURE)) {
          validSignatures = Collections.singletonList(NO_ARGUMENT_CONSTRUCTOR_SIGNATURE);
        }
      }
      else if (!matchMethodSignature(constructors, constructorSignature)) {
        validSignatures = constructors.stream()
          .map(JavaReflectionReferenceUtil::getMethodSignature)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
      }
      if (validSignatures != null) {
        final String declarationText = getConstructorDeclarationText(ownerClass, constructorSignature);
        if (declarationText != null) {
          LocalQuickFix fix = null;
          final String ownerClassName = ownerClass.getName();
          if (ownerClassName != null) {
            fix = ReplaceSignatureQuickFix
              .createFix(constructorTypeExpression, ownerClassName, validSignatures, true, holder.isOnTheFly());
          }
          holder.registerProblem(constructorTypeExpression, JavaErrorMessages.message("cannot.resolve.constructor", declarationText), fix);
        }
      }
    }
  }

  private static void checkField(@NotNull PsiClass ownerClass,
                                 @NotNull String fieldName,
                                 @NotNull PsiExpression fieldNameExpression,
                                 @NotNull PsiExpression fieldTypeExpression,
                                 boolean isStatic,
                                 @NotNull PsiReferenceExpression factoryMethodExpression,
                                 @NotNull ProblemsHolder holder) {
    final PsiField field = ownerClass.findFieldByName(fieldName, true);
    if (field == null) {
      holder.registerProblem(fieldNameExpression, InspectionsBundle.message("inspection.handle.signature.field.cannot.resolve", fieldName));
      return;
    }

    if (field.hasModifierProperty(PsiModifier.STATIC) != isStatic) {
      final String factoryMethodName = factoryMethodExpression.getReferenceName();
      final PsiElement factoryMethodNameElement = factoryMethodExpression.getReferenceNameElement();
      if (factoryMethodName != null && factoryMethodNameElement != null) {
        final LocalQuickFix fix = SwitchStaticnessQuickFix.createFix(factoryMethodName, isStatic);
        final String message = InspectionsBundle.message(
          isStatic ? "inspection.handle.signature.field.static" : "inspection.handle.signature.field.not.static", fieldName);
        holder.registerProblem(factoryMethodNameElement, message, fix);
        return;
      }
    }

    final ReflectiveType reflectiveType = getReflectiveType(fieldTypeExpression);
    if (reflectiveType != null && !reflectiveType.isEqualTo(field.getType())) {
      final String expectedTypeText = getTypeText(field.getType(), field);
      if (expectedTypeText != null) {
        final String message = InspectionsBundle.message("inspection.handle.signature.field.type", fieldName, expectedTypeText);
        holder.registerProblem(fieldTypeExpression, message, new FieldTypeQuickFix(expectedTypeText));
      }
    }
  }

  private static void checkMethod(@NotNull PsiClass ownerClass,
                                  @NotNull String methodName,
                                  @NotNull PsiExpression methodNameExpression,
                                  @NotNull PsiExpression methodTypeExpression,
                                  boolean isStatic,
                                  @NotNull PsiReferenceExpression factoryMethodExpression,
                                  @NotNull ProblemsHolder holder) {

    final PsiMethod[] methods = ownerClass.findMethodsByName(methodName, true);
    if (methods.length == 0) {
      holder.registerProblem(methodNameExpression, JavaErrorMessages.message("cannot.resolve.method", methodName));
      return;
    }

    final List<PsiMethod> filteredMethods =
      ContainerUtil.filter(methods, method -> method.hasModifierProperty(PsiModifier.STATIC) == isStatic);
    if (filteredMethods.isEmpty()) {
      final String factoryMethodName = factoryMethodExpression.getReferenceName();
      final PsiElement factoryMethodNameElement = factoryMethodExpression.getReferenceNameElement();
      if (factoryMethodName != null && factoryMethodNameElement != null) {
        final LocalQuickFix fix = SwitchStaticnessQuickFix.createFix(factoryMethodName, isStatic);
        final String message = InspectionsBundle.message(
          isStatic ? "inspection.handle.signature.method.static" : "inspection.handle.signature.method.not.static", methodName);
        holder.registerProblem(factoryMethodNameElement, message, fix);
        return;
      }
    }

    final List<String> methodSignature = composeMethodSignature(methodTypeExpression);
    if (methodSignature != null && !matchMethodSignature(filteredMethods, methodSignature)) {
      final String declarationText = getMethodDeclarationText(methodName, methodSignature);
      final List<List<String>> validSignatures = filteredMethods.stream()
        .map(JavaReflectionReferenceUtil::getMethodSignature)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
      final LocalQuickFix fix =
        ReplaceSignatureQuickFix.createFix(methodTypeExpression, methodName, validSignatures, false, holder.isOnTheFly());
      holder.registerProblem(methodTypeExpression, JavaErrorMessages.message("cannot.resolve.method", declarationText), fix);
    }
  }

  @NotNull
  private static String getMethodDeclarationText(@NotNull String name, @NotNull List<String> methodSignature) {
    final String argumentTypes = methodSignature.stream().skip(1).collect(Collectors.joining(", "));
    final String returnType = !methodSignature.isEmpty() ? methodSignature.get(0) : "";
    return returnType + " " + name + "(" + argumentTypes + ")";
  }

  @Nullable
  private static String getConstructorDeclarationText(@NotNull PsiClass ownerClass, @NotNull List<String> methodSignature) {
    final String className = ownerClass.getName();
    if (className != null && !methodSignature.isEmpty()) {
      return getConstructorDeclarationText(className, methodSignature);
    }
    return null;
  }

  @NotNull
  private static String getConstructorDeclarationText(@NotNull String className, @NotNull List<String> methodSignature) {
    // Return type of the constructor should be 'void'. If it isn't so let's make that mistake more noticeable.
    final String returnType = methodSignature.get(0);
    final String fakeReturnType = !PsiKeyword.VOID.equals(returnType) ? returnType + " " : "";
    final String argumentTypes = methodSignature.stream().skip(1).collect(Collectors.joining(", "));
    return fakeReturnType + className + "(" + argumentTypes + ")";
  }

  private static boolean matchMethodSignature(@NotNull List<PsiMethod> methods, @NotNull List<String> expectedMethodSignature) {
    return methods.stream()
      .map(JavaReflectionReferenceUtil::getMethodSignature)
      .anyMatch(expectedMethodSignature::equals);
  }

  /**
   * Take method's return type and parameter types
   * from arguments of MethodType.methodType(Class...) and MethodType.genericMethodType(int, boolean?)
   */
  @Nullable
  private static List<String> composeMethodSignature(@Nullable PsiExpression methodTypeExpression) {
    final PsiExpression typeDefinition = findDefinition(methodTypeExpression);
    if (typeDefinition instanceof PsiMethodCallExpression) {
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)typeDefinition;
      final String referenceName = methodCallExpression.getMethodExpression().getReferenceName();

      Function<PsiExpression[], List<String>> composer = null;
      if (METHOD_TYPE.equals(referenceName)) {
        composer = JavaLangInvokeHandleSignatureInspection::composeMethodSignatureFromTypes;
      }
      else if (GENERIC_METHOD_TYPE.equals(referenceName)) {
        composer = JavaLangInvokeHandleSignatureInspection::composeGenericMethodSignature;
      }

      if (composer != null) {
        final PsiMethod method = methodCallExpression.resolveMethod();
        if (method != null) {
          final PsiClass psiClass = method.getContainingClass();
          if (psiClass != null && JAVA_LANG_INVOKE_METHOD_TYPE.equals(psiClass.getQualifiedName())) {
            final PsiExpression[] arguments = methodCallExpression.getArgumentList().getExpressions();
            return composer.apply(arguments);
          }
        }
      }
    }
    return null;
  }

  @Nullable
  private static List<String> composeMethodSignatureFromTypes(@NotNull PsiExpression[] returnAndParameterTypes) {
    final List<String> typeNames = Arrays.stream(returnAndParameterTypes)
      .map(JavaReflectionReferenceUtil::getTypeText)
      .collect(Collectors.toList());
    return !typeNames.isEmpty() && !typeNames.contains(null) ? typeNames : null;
  }

  /**
   * All the types in the method signature are either unbounded type parameters or java.lang.Object (with possible vararg)
   */
  private static List<String> composeGenericMethodSignature(@NotNull PsiExpression[] genericSignatureShape) {
    if (genericSignatureShape.length == 0 || genericSignatureShape.length > 2) {
      return null;
    }

    final Integer objectArgCount = computeConstantExpression(genericSignatureShape[0], Integer.class);
    final Boolean finalArray = // there's an additional parameter which is an ellipsis or an array
      genericSignatureShape.length > 1 ? computeConstantExpression(genericSignatureShape[1], Boolean.class) : false;

    if (objectArgCount == null || objectArgCount < 0 || objectArgCount > 255) {
      return null;
    }
    if (finalArray == null || finalArray && objectArgCount > 254) {
      return null;
    }

    final List<String> typeNames = new ArrayList<>();
    typeNames.add(CommonClassNames.JAVA_LANG_OBJECT); // return type

    for (int i = 0; i < objectArgCount; i++) {
      typeNames.add(CommonClassNames.JAVA_LANG_OBJECT);
    }
    if (finalArray) {
      typeNames.add(CommonClassNames.JAVA_LANG_OBJECT + "[]");
    }
    return typeNames;
  }

  private static class FieldTypeQuickFix implements LocalQuickFix {
    private final String myFieldTypeText;

    public FieldTypeQuickFix(String fieldTypeText) {myFieldTypeText = fieldTypeText;}

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionsBundle.message("inspection.handle.signature.change.type.fix.name", myFieldTypeText);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      final PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
      final PsiExpression typeExpression = factory.createExpressionFromText(myFieldTypeText + ".class", element);
      final JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
      styleManager.shortenClassReferences(element.replace(typeExpression));
    }
  }

  private static class SwitchStaticnessQuickFix implements LocalQuickFix {
    private static final Map<String, String> STATIC_TO_NON_STATIC = ContainerUtil.<String, String>immutableMapBuilder()
      .put(FIND_STATIC_GETTER, FIND_GETTER)
      .put(FIND_STATIC_SETTER, FIND_SETTER)
      .put(FIND_STATIC_VAR_HANDLE, FIND_VAR_HANDLE)
      .put(FIND_STATIC, FIND_VIRTUAL)
      .build();
    private static final Map<String, String> NON_STATIC_TO_STATIC = ContainerUtil.<String, String>immutableMapBuilder()
      .put(FIND_GETTER, FIND_STATIC_GETTER)
      .put(FIND_SETTER, FIND_STATIC_SETTER)
      .put(FIND_VAR_HANDLE, FIND_STATIC_VAR_HANDLE)
      .put(FIND_VIRTUAL, FIND_STATIC)
      .build();

    private final String myReplacementName;

    public SwitchStaticnessQuickFix(@NotNull String replacementName) {
      myReplacementName = replacementName;
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionsBundle.message("inspection.handle.signature.replace.with.fix.name", myReplacementName);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      final PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
      final PsiIdentifier identifier = factory.createIdentifier(myReplacementName);
      final JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
      styleManager.shortenClassReferences(element.replace(identifier));
    }

    @Nullable
    public static LocalQuickFix createFix(@NotNull String methodName, boolean isStatic) {
      final String replacementName = isStatic ? STATIC_TO_NON_STATIC.get(methodName) : NON_STATIC_TO_STATIC.get(methodName);
      return replacementName != null ? new SwitchStaticnessQuickFix(replacementName) : null;
    }
  }

  private static class ReplaceSignatureQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
    private String myName;
    private List<List<String>> mySignatures;
    private boolean myIsConstructor;

    public ReplaceSignatureQuickFix(@Nullable PsiElement element,
                                    @NotNull String name,
                                    @NotNull List<List<String>> signatures,
                                    boolean isConstructor) {
      super(element);
      myName = name;
      mySignatures = signatures;
      myIsConstructor = isConstructor;
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return getText();
    }

    @NotNull
    @Override
    public String getText() {
      if (mySignatures.size() == 1) {
        final String declarationText = getDeclarationText(mySignatures.get(0));
        return InspectionsBundle.message(myIsConstructor
                                         ? "inspection.handle.signature.use.constructor.fix.name"
                                         : "inspection.handle.signature.use.method.fix.name",
                                         declarationText);
      }
      return InspectionsBundle.message(myIsConstructor
                                       ? "inspection.handle.signature.use.constructor.fix.family.name"
                                       : "inspection.handle.signature.use.method.fix.family.name");
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @Nullable Editor editor,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        final PsiElement element = myStartElement.getElement();
        if (editor != null && element != null) {
          final List<String> signature = editor.getUserData(DEFAULT_SIGNATURE);
          if (signature != null && mySignatures.contains(signature)) {
            applyFix(project, element, signature);
          }
        }
        return;
      }

      if (mySignatures.size() == 1) {
        applyFix(project, startElement, mySignatures.get(0));
      }
      else if (editor != null) {
        showLookup(project, editor);
      }
    }

    private void showLookup(@NotNull Project project, @NotNull Editor editor) {
      final List<LookupElement> items = mySignatures.stream()
        .map(signature -> LookupElementBuilder.create(signature, "")
          .withIcon(PlatformIcons.METHOD_ICON)
          .withPresentableText(getDeclarationText(signature)))
        .collect(Collectors.toList());

      // Unfortunately, LookupManager.showLookup() doesn't invoke InsertHandler. A workaround with LookupListener is used.
      // To let the workaround work we need to make sure that noting is actually replaced by the default behavior of showLookup().
      editor.getSelectionModel().removeSelection();

      final LookupManager lookupManager = LookupManager.getInstance(project);
      final LookupEx lookup = lookupManager.showLookup(editor, items.toArray(LookupElement.EMPTY_ARRAY));
      if (lookup != null) {
        lookup.addLookupListener(new LookupAdapter() {
          @Override
          public void itemSelected(LookupEvent event) {
            final LookupElement item = event.getItem();
            if (item != null) {
              final PsiElement element = myStartElement.getElement();
              final Object object = item.getObject();
              if (element != null && object instanceof List) {
                @SuppressWarnings("unchecked") final List<String> signature = (List<String>)object;
                WriteAction.run(() -> applyFix(project, element, signature));
              }
            }
          }
        });
      }
    }

    private static void applyFix(@NotNull Project project, @NotNull PsiElement element, @NotNull List<String> signature) {
      final String replacementText = getMethodTypeExpressionText(signature);
      final PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
      final PsiExpression replacement = factory.createExpressionFromText(replacementText, element);
      final JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
      styleManager.shortenClassReferences(element.replace(replacement));
    }

    @NotNull
    private String getDeclarationText(@NotNull List<String> signature) {
      return myIsConstructor ? getConstructorDeclarationText(myName, signature) : getMethodDeclarationText(myName, signature);
    }

    @Nullable
    private static LocalQuickFix createFix(@Nullable PsiElement element,
                                           @NotNull String methodName,
                                           @NotNull List<List<String>> methodSignatures,
                                           boolean isConstructor, boolean isOnTheFly) {
      if (isOnTheFly && !methodSignatures.isEmpty() || methodSignatures.size() == 1) {
        return new ReplaceSignatureQuickFix(element, methodName, methodSignatures, isConstructor);
      }
      return null;
    }
  }
}
