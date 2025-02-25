/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8306112
 * @summary Test basic processing of unnamed classes.
 * @library /tools/javac/lib
 * @modules java.compiler
 *          jdk.compiler
 * @build   JavacTestingAbstractProcessor TestUnnamedClass
 * @compile -processor TestUnnamedClass -proc:only --enable-preview --release ${jdk.version} Anonymous.java
 */


import java.lang.annotation.*;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import static javax.lang.model.util.ElementFilter.*;
import javax.tools.JavaFileObject;

/*
 * Ideally, this processor would test both the compile-time
 * representation of an unnamed class starting from a source file as
 * well as the representation starting from a class file. Currently,
 * only the source file based view will be tested.
 *
 * For future work to test the class file based view, an additional jtreg directive like the following could
 * be used:
 *
 * @compile/process -processor TestUnnamedClass -proc:only Anonymous Nameless
 */
@SuppressWarnings("preview")
public class TestUnnamedClass  extends JavacTestingAbstractProcessor {

    private static int round  = 0;

    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {
        if (round == 0) { // Check file from comamnd line
            checkRoots(roundEnv);
            generateUnnamed();
        }

        if (!roundEnv.processingOver()) { // Test generated file(s)
            checkRoots(roundEnv);
        }

        round++;
        return true;
    }

    private void checkRoots(RoundEnvironment roundEnv) {
        int checks = 0;
        for (TypeElement type : typesIn(roundEnv.getRootElements())) {
            System.out.println("Checking " + type.getQualifiedName());
            checks++;
            checkUnnamedClassProperties(type);
        }
        if (checks == 0) {
            messager.printError("No checking done of any candidate unnamed classes.");
        }
    }

    private void generateUnnamed() {
        try {
            String unnamedSource = """
            void main() {
                System.out.println("Nameless, but not voiceless.");
            }
            """;

            JavaFileObject outputFile = processingEnv.getFiler().createSourceFile("Nameless");
            try(Writer w = outputFile.openWriter()) {
                w.append(unnamedSource);
            }
        } catch (java.io.IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    /*
     * From JEP 445 JLS changes:
     *
     * "An unnamed class compilation unit implicitly declares a class that satisfies the following
     * properties:
     * It is always a top level class.
     * It is always an unnamed class (it has no canonical or fully qualified name (6.7)).
     * It is never abstract (8.1.1.1).
     * It is always final (8.1.1.2).
     * It is always a member of an unnamed package (7.4.2) and has package access.
     * Its direct superclass type is always Object (8.1.4).
     * It never has any direct superinterface types (8.1.5).
     *
     * The body of the class contains every ClassMemberDeclaration
     * from the unnamed class compilation unit. It is not possible for
     * an unnamed class compilation unit to declare an instance
     * initializer, static initializer, or constructor.
     *
     * It has an implicitly declared default constructor (8.8.9).
     * All members of this class, including any implicitly declared
     * members, are subject to the usual rules for member declarations
     * in a class.
     *
     * It is a compile-time error if this class does not declare a candidate main method (12.1.4).
     */
    void checkUnnamedClassProperties(TypeElement unnamedClass) {
        if (unnamedClass.getNestingKind() != NestingKind.TOP_LEVEL) {
            messager.printError("Unnamed class is not top-level.", unnamedClass);
        }

        if (!unnamedClass.isUnnamed()) {
            messager.printError("Unnamed class is _not_ indicated as such.", unnamedClass);
        }

        if (unnamedClass.getSimpleName().isEmpty()) {
            messager.printError("Unnamed class does have an empty simple name.", unnamedClass);
        }

        if (!unnamedClass.getQualifiedName().isEmpty()) {
            messager.printError("Unnamed class does _not_ have an empty qualified name.", unnamedClass);
        }

        if (unnamedClass.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printError("Unnamed class is abstract.", unnamedClass);
        }

        if (!unnamedClass.getModifiers().contains(Modifier.FINAL)) {
            messager.printError("Unnamed class is _not_ final.", unnamedClass);
        }

        if (!elements.getPackageOf(unnamedClass).isUnnamed()) {
            messager.printError("Unnamed class is _not_ in an unnamed package.", unnamedClass);
        }

        if (unnamedClass.getModifiers().contains(Modifier.PUBLIC)  ||
            unnamedClass.getModifiers().contains(Modifier.PRIVATE) ||
            unnamedClass.getModifiers().contains(Modifier.PROTECTED)) {
            messager.printError("Unnamed class does _not_ have package access.", unnamedClass);
        }

        if ( !types.isSameType(unnamedClass.getSuperclass(),
                               elements.getTypeElement("java.lang.Object").asType())) {
            messager.printError("Unnamed class does _not_ have java.lang.Object as a superclass.", unnamedClass);
        }

        if (!unnamedClass.getInterfaces().isEmpty()) {
            messager.printError("Unnamed class has superinterfaces.", unnamedClass);
        }

        List<ExecutableElement> ctors = constructorsIn(unnamedClass.getEnclosedElements());
        if (ctors.size() != 1 ) {
            messager.printError("Did not find exactly one constructor", unnamedClass);
        }

        ExecutableElement ctor = ctors.getFirst();
        if (elements.getOrigin(ctor) != Elements.Origin.MANDATED) {
            messager.printError("Constructor was not marked as mandated", ctor);
        }

        List<ExecutableElement> methods = methodsIn(unnamedClass.getEnclosedElements());
        // Just look for a method named "main"; don't check the other details.
        boolean mainFound = false;
        Name mainName = elements.getName("main");
        for (var method : methods) {
            if (method.getSimpleName().equals(mainName)) {
                mainFound = true;
                break;
            }
        }

        if (!mainFound) {
            messager.printError("No main mehtod found", unnamedClass);
        }
    }
}
