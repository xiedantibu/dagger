/*
 * Copyright (C) 2012 Square, Inc.
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
package dagger.internal.codegen;

import dagger.MembersInjector;
import dagger.internal.Binding;
import dagger.internal.Linker;
import dagger.internal.StaticInjection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import static dagger.internal.codegen.ProcessorJavadocs.binderTypeDocs;
import static dagger.internal.plugins.loading.ClassloadingPlugin.INJECT_ADAPTER_SUFFIX;
import static dagger.internal.plugins.loading.ClassloadingPlugin.STATIC_INJECTION_SUFFIX;
import static java.lang.reflect.Modifier.FINAL;
import static java.lang.reflect.Modifier.PRIVATE;
import static java.lang.reflect.Modifier.PUBLIC;

/**
 * Generates an implementation of {@link Binding} that injects the
 * {@literal @}{@code Inject}-annotated members of a class.
 */
@SupportedAnnotationTypes("javax.inject.Inject")
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public final class InjectProcessor extends AbstractProcessor {
  private final Set<String> remainingTypeNames = new LinkedHashSet<String>();

  @Override public boolean process(Set<? extends TypeElement> types, RoundEnvironment env) {
    remainingTypeNames.addAll(getInjectedClassNames(env));
    for (Iterator<String> i = remainingTypeNames.iterator(); i.hasNext();) {
      InjectedClass injectedClass = getInjectedClass(i.next());
      // Verify that we have access to all types to be injected on this pass.
      boolean missingDependentClasses =
          !allTypesExist(injectedClass.fields)
          || (injectedClass.constructor != null && !allTypesExist(injectedClass.constructor
              .getParameters()))
          || !allTypesExist(injectedClass.staticFields);
      if (!missingDependentClasses) {
        try {
          writeInjectionsForClass(injectedClass);
        } catch (IOException e) {
          error("Code gen failed: " + e, injectedClass.type);
        }
        i.remove();
      }
    }
    if (env.processingOver() && !remainingTypeNames.isEmpty()) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Could not find injection type required by " + remainingTypeNames);
    }
    return true;
  }

  private void writeInjectionsForClass(InjectedClass injectedClass) throws IOException {
    if (injectedClass.constructor != null || !injectedClass.fields.isEmpty()) {
      writeInjectAdapter(injectedClass.type, injectedClass.constructor, injectedClass.fields);
    }
    if (!injectedClass.staticFields.isEmpty()) {
      writeStaticInjection(injectedClass.type, injectedClass.staticFields);
    }
  }

  /**
   * Return true if all element types are currently available in this code
   * generation pass. Unavailable types will be of kind {@link TypeKind#ERROR}.
   */
  private boolean allTypesExist(Collection<? extends Element> elements) {
    for (Element element : elements) {
      if (element.asType().getKind() == TypeKind.ERROR) {
        return false;
      }
    }
    return true;
  }

  private Set<String> getInjectedClassNames(RoundEnvironment env) {
    // First gather the set of classes that have @Inject-annotated members.
    Set<String> injectedTypeNames = new LinkedHashSet<String>();
    for (Element element : env.getElementsAnnotatedWith(Inject.class)) {
      TypeMirror type = element.getEnclosingElement().asType();
      injectedTypeNames.add(CodeGen.rawTypeToString(type, '.'));
    }
    return injectedTypeNames;
  }

  /**
   * @param injectedClassName the name of a class with an @Inject-annotated member.
   */
  private InjectedClass getInjectedClass(String injectedClassName) {
    TypeElement type = processingEnv.getElementUtils().getTypeElement(injectedClassName);
    boolean isAbstract = type.getModifiers().contains(Modifier.ABSTRACT);
    List<Element> staticFields = new ArrayList<Element>();
    ExecutableElement constructor = null;
    List<Element> fields = new ArrayList<Element>();
    for (Element member : type.getEnclosedElements()) {
      if (member.getAnnotation(Inject.class) == null) {
        continue;
      }

      switch (member.getKind()) {
        case FIELD:
          if (member.getModifiers().contains(Modifier.STATIC)) {
            staticFields.add(member);
          } else {
            fields.add(member);
          }
          break;
        case CONSTRUCTOR:
          if (constructor != null) {
            // TODO(tbroyer): pass annotation information
            error("Too many injectable constructors on " + type.getQualifiedName(), member);
          } else if (isAbstract) {
            // TODO(tbroyer): pass annotation information
            error("Abstract class " + type.getQualifiedName()
                + " must not have an @Inject-annotated constructor.", member);
          }
          constructor = (ExecutableElement) member;
          break;
        default:
          // TODO(tbroyer): pass annotation information
          error("Cannot inject " + member, member);
          break;
      }
    }

    if (constructor == null && !isAbstract) {
      constructor = findNoArgsConstructor(type);
    }

    return new InjectedClass(type, staticFields, constructor, fields);
  }

  /**
   * Returns the no args constructor for {@code typeElement}, or null if no such
   * constructor exists.
   */
  private ExecutableElement findNoArgsConstructor(TypeElement typeElement) {
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind() != ElementKind.CONSTRUCTOR) {
        continue;
      }
      ExecutableElement constructor = (ExecutableElement) element;
      if (constructor.getParameters().isEmpty()) {
        if (constructor.getModifiers().contains(Modifier.PRIVATE)) {
          return null;
        } else {
          return constructor;
        }
      }
    }
    return null;
  }

  private void error(String msg, Element element) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, element);
  }

  /**
   * Write a companion class for {@code type} that extends {@link Binding}.
   *
   * @param constructor the injectable constructor, or null if this binding
   *     supports members injection only.
   */
  private void writeInjectAdapter(TypeElement type, ExecutableElement constructor,
      List<Element> fields) throws IOException {
    String packageName = CodeGen.getPackage(type).getQualifiedName().toString();
    String strippedTypeName = strippedTypeName(type.getQualifiedName().toString(), packageName);
    TypeMirror supertype = CodeGen.getApplicationSupertype(type);
    String adapterName = CodeGen.adapterName(type, INJECT_ADAPTER_SUFFIX);
    JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(adapterName, type);
    JavaWriter writer = new JavaWriter(sourceFile.openWriter());
    boolean isAbstract = type.getModifiers().contains(Modifier.ABSTRACT);
    boolean injectMembers = !fields.isEmpty() || supertype != null;
    boolean disambiguateFields = !fields.isEmpty()
        && (constructor != null)
        && !constructor.getParameters().isEmpty();
    boolean dependent = injectMembers
        || ((constructor != null) && !constructor.getParameters().isEmpty());

    writer.emitEndOfLineComment(ProcessorJavadocs.GENERATED_BY_DAGGER);
    writer.emitPackage(packageName);
    writer.emitEmptyLine();
    writer.emitImports(getImports(dependent, injectMembers, constructor != null));

    writer.emitEmptyLine();
    writer.emitJavadoc(binderTypeDocs(strippedTypeName, isAbstract, injectMembers, dependent));
    writer.beginType(adapterName, "class", PUBLIC | FINAL,
        CodeGen.parameterizedType(Binding.class, strippedTypeName),
        interfaces(strippedTypeName, injectMembers, constructor != null));

    if (constructor != null) {
      for (VariableElement parameter : constructor.getParameters()) {
        writer.emitField(CodeGen.parameterizedType(Binding.class,
            CodeGen.typeToString(parameter.asType())),
            parameterName(disambiguateFields, parameter), PRIVATE);
      }
    }
    for (Element field : fields) {
      writer.emitField(CodeGen.parameterizedType(Binding.class,
          CodeGen.typeToString(field.asType())),
          fieldName(disambiguateFields, field), PRIVATE);
    }
    if (supertype != null) {
      writer.emitField(CodeGen.parameterizedType(Binding.class,
          CodeGen.rawTypeToString(supertype, '.')), "supertype", PRIVATE);
    }

    writer.emitEmptyLine();
    writer.beginMethod(null, adapterName, PUBLIC);
    String key = (constructor != null)
        ? JavaWriter.stringLiteral(GeneratorKeys.get(type.asType()))
        : null;
    String membersKey = JavaWriter.stringLiteral(GeneratorKeys.rawMembersKey(type.asType()));
    boolean singleton = type.getAnnotation(Singleton.class) != null;
    writer.emitStatement("super(%s, %s, %s, %s.class)",
        key, membersKey, (singleton ? "IS_SINGLETON" : "NOT_SINGLETON"), strippedTypeName);
    writer.endMethod();
    if (dependent) {
      writer.emitEmptyLine();
      writer.emitJavadoc(ProcessorJavadocs.ATTACH_METHOD);
      writer.emitAnnotation(Override.class);
      writer.emitAnnotation(SuppressWarnings.class, JavaWriter.stringLiteral("unchecked"));
      writer.beginMethod("void", "attach", PUBLIC, Linker.class.getName(), "linker");
      if (constructor != null) {
        for (VariableElement parameter : constructor.getParameters()) {
          writer.emitStatement("%s = (%s) linker.requestBinding(%s, %s.class)",
              parameterName(disambiguateFields, parameter),
              writer.compressType(CodeGen.parameterizedType(Binding.class,
                  CodeGen.typeToString(parameter.asType()))),
              JavaWriter.stringLiteral(GeneratorKeys.get(parameter)),
              strippedTypeName);
        }
      }
      for (Element field : fields) {
        writer.emitStatement("%s = (%s) linker.requestBinding(%s, %s.class)",
            fieldName(disambiguateFields, field),
            writer.compressType(CodeGen.parameterizedType(Binding.class,
                CodeGen.typeToString(field.asType()))),
            JavaWriter.stringLiteral(GeneratorKeys.get((VariableElement) field)),
            strippedTypeName);
      }
      if (supertype != null) {
        writer.emitStatement("%s = (%s) linker.requestBinding(%s, %s.class, false)",
            "supertype",
            writer.compressType(CodeGen.parameterizedType(Binding.class,
                CodeGen.rawTypeToString(supertype, '.'))),
            JavaWriter.stringLiteral(GeneratorKeys.rawMembersKey(supertype)),
            strippedTypeName);
      }
      writer.endMethod();

      writer.emitEmptyLine();
      writer.emitJavadoc(ProcessorJavadocs.GET_DEPENDENCIES_METHOD);
      writer.emitAnnotation(Override.class);
      String setOfBindings = CodeGen.parameterizedType(Set.class, "Binding<?>");
      writer.beginMethod("void", "getDependencies", PUBLIC, setOfBindings, "getBindings",
          setOfBindings, "injectMembersBindings");
      if (constructor != null) {
        for (Element parameter : constructor.getParameters()) {
          writer.emitStatement("getBindings.add(%s)", parameterName(disambiguateFields, parameter));
        }
      }
      for (Element field : fields) {
        writer.emitStatement("injectMembersBindings.add(%s)", fieldName(disambiguateFields, field));
      }
      if (supertype != null) {
        writer.emitStatement("injectMembersBindings.add(%s)", "supertype");
      }
      writer.endMethod();
    }

    if (constructor != null) {
      writer.emitEmptyLine();
      writer.emitJavadoc(ProcessorJavadocs.GET_METHOD, strippedTypeName);
      writer.emitAnnotation(Override.class);
      writer.beginMethod(strippedTypeName, "get", PUBLIC);
      StringBuilder newInstance = new StringBuilder();
      newInstance.append(strippedTypeName).append(" result = new ");
      newInstance.append(strippedTypeName).append('(');
      boolean first = true;
      for (VariableElement parameter : constructor.getParameters()) {
        if (!first) newInstance.append(", ");
        else first = false;
        newInstance.append(parameterName(disambiguateFields, parameter)).append(".get()");
      }
      newInstance.append(')');
      writer.emitStatement(newInstance.toString());
      if (injectMembers) {
        writer.emitStatement("injectMembers(result)");
      }
      writer.emitStatement("return result");
      writer.endMethod();
    }

    if (injectMembers) {
      writer.emitEmptyLine();
      writer.emitJavadoc(ProcessorJavadocs.MEMBERS_INJECT_METHOD, strippedTypeName);
      writer.emitAnnotation(Override.class);
      writer.beginMethod("void", "injectMembers", PUBLIC, strippedTypeName, "object");
      for (Element field : fields) {
        writer.emitStatement("object.%s = %s.get()", field.getSimpleName(),
            fieldName(disambiguateFields, field));
      }
      if (supertype != null) {
        writer.emitStatement("supertype.injectMembers(object)");
      }
      writer.endMethod();
    }

    writer.endType();
    writer.close();
  }

  private String[] interfaces(String strippedTypeName, boolean hasFields, boolean isProvider) {
    List<String> interfaces = new ArrayList<String>();
    if (isProvider) {
      interfaces.add(CodeGen.parameterizedType(Provider.class, strippedTypeName));
    }
    if (hasFields) {
      interfaces.add(CodeGen.parameterizedType(MembersInjector.class, strippedTypeName));
    }
    return interfaces.toArray(new String[0]);
  }

  private Set<String> getImports(boolean dependent, boolean injectMembers, boolean isProvider) {
    Set<String> imports = new LinkedHashSet<String>();
    imports.add(Binding.class.getName());
    if (dependent) {
      imports.add(Linker.class.getName());
      imports.add(Set.class.getName());
    }
    if (injectMembers) imports.add(MembersInjector.class.getName());
    if (isProvider) imports.add(Provider.class.getName());
    return imports;
  }

  private String strippedTypeName(String type, String packageName) {
    return type.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
  }

  /**
   * Write a companion class for {@code type} that extends {@link StaticInjection}.
   */
  private void writeStaticInjection(TypeElement type, List<Element> fields) throws IOException {
    String typeName = type.getQualifiedName().toString();
    String adapterName = CodeGen.adapterName(type, STATIC_INJECTION_SUFFIX);
    JavaFileObject sourceFile = processingEnv.getFiler()
        .createSourceFile(adapterName, type);
    JavaWriter writer = new JavaWriter(sourceFile.openWriter());

    writer.emitEndOfLineComment(ProcessorJavadocs.GENERATED_BY_DAGGER);
    writer.emitPackage(CodeGen.getPackage(type).getQualifiedName().toString());

    writer.emitEmptyLine();
    writer.emitImports(CodeGen.setOf(
        StaticInjection.class.getName(),
        Binding.class.getName(),
        Linker.class.getName()));

    writer.emitEmptyLine();

    writer.emitJavadoc(ProcessorJavadocs.STATIC_INJECTION_TYPE, type.getSimpleName());
    writer.beginType(adapterName, "class", PUBLIC | FINAL, StaticInjection.class.getSimpleName());

    for (Element field : fields) {
      writer.emitField(CodeGen.parameterizedType(Binding.class,
          CodeGen.typeToString(field.asType())),
          fieldName(false, field), PRIVATE);
    }

    writer.emitEmptyLine();
    writer.emitJavadoc(ProcessorJavadocs.ATTACH_METHOD);
    writer.emitAnnotation(Override.class);
    writer.beginMethod("void", "attach", PUBLIC, Linker.class.getName(), "linker");
    for (Element field : fields) {
      writer.emitStatement("%s = (%s) linker.requestBinding(%s, %s.class)",
          fieldName(false, field),
          writer.compressType(CodeGen.parameterizedType(Binding.class,
              CodeGen.typeToString(field.asType()))),
          JavaWriter.stringLiteral(GeneratorKeys.get((VariableElement) field)),
          typeName);
    }
    writer.endMethod();

    writer.emitEmptyLine();
    writer.emitJavadoc(ProcessorJavadocs.STATIC_INJECT_METHOD);
    writer.emitAnnotation(Override.class);
    writer.beginMethod("void", "inject", PUBLIC);
    for (Element field : fields) {
      writer.emitStatement("%s.%s = %s.get()",
          writer.compressType(typeName),
          field.getSimpleName().toString(),
          fieldName(false, field));
    }
    writer.endMethod();

    writer.endType();
    writer.close();
  }

  private String fieldName(boolean disambiguateFields, Element field) {
    return (disambiguateFields ? "field_" : "") + field.getSimpleName().toString();
  }

  private String parameterName(boolean disambiguateFields, Element parameter) {
    return (disambiguateFields ? "parameter_" : "") + parameter.getSimpleName().toString();
  }

  static class InjectedClass {
    final TypeElement type;
    final List<Element> staticFields;
    final ExecutableElement constructor;
    final List<Element> fields;

    InjectedClass(TypeElement type, List<Element> staticFields, ExecutableElement constructor,
        List<Element> fields) {
      this.type = type;
      this.staticFields = staticFields;
      this.constructor = constructor;
      this.fields = fields;
    }
  }
}
