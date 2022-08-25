package testio.utils;

import jmutation.model.MavenProject;
import jmutation.model.Project;
import jmutation.model.ProjectType;
import jmutation.model.TestCase;
import jmutation.model.ast.JdtMethodRetriever;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Given a maven or gradle project path, we parse it into a project
 *
 * @author Yun Lin
 */
public class ProjectParser {

    public static CompilationUnit parseCompilationUnit(String fileContent) {

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest()); // handles JDK 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6
        parser.setSource(fileContent.toCharArray());
        parser.setResolveBindings(true);
        // In order to parse 1.6 code, some compiler options need to be set to 1.6
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
        parser.setCompilerOptions(options);

        CompilationUnit result = (CompilationUnit) parser.createAST(null);
        return result;
    }

    public static File getFileOfClass(String classCanonicalName, File start) {
        String[] packagePartsArr = classCanonicalName.split("[.]", -1);
        Set<String> packageParts = new HashSet<>(Arrays.asList(packagePartsArr));
        String packageName = "";
        for (int i = 0; i < packagePartsArr.length - 1; i++) {
            packageName += packagePartsArr[i];
            if (i == packagePartsArr.length - 2) {
                continue;
            }
            packageName += ".";
        }
        String className = packagePartsArr[packagePartsArr.length - 1].split("[$]", 2)[0];
        return getFileOfClassHelper(packageParts, packageName, className, start);
    }

    private static File getFileOfClassHelper(Set<String> packageParts, String packageName, String className, File start) {
        File[] list = start.listFiles();
        if (list == null) {
            return null;
        }
        for (File f : list) {
            if (f.isDirectory() && (f.getName().equals("src") || f.getName().equals("main") || f.getName().equals("java") || f.getName().equals("test") || packageParts.contains(f.getName()))) {
                File file = getFileOfClassHelper(packageParts, packageName, className, f);
                if (file != null) {
                    return file;
                }
            } else {
                if (f.getName().contains(".java")) {
                    try {
                        String fileContent = Files.readString(f.toPath());
                        if (fileContent.contains("package " + packageName) && (fileContent.contains("class " + className) || fileContent.contains("enum " + className))) {
                            return f;
                        }
                    } catch (IOException e) {
                        System.out.print("Unable to open file at ");
                        System.out.println(f.getAbsolutePath());
                    }
                }
            }
        }
        return null;
    }
}
