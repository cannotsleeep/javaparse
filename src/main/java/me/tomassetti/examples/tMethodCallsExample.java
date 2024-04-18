package me.tomassetti.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.base.Strings;
import me.tomassetti.support.DirExplorer;
import com.github.javaparser.ast.expr.NameExpr;

import java.io.File;
import java.io.IOException;

public class tMethodCallsExample {

    public static void listMethodCalls(File projectDir) {
        new DirExplorer((level, path, file) -> path.endsWith(".java"), (level, path, file) -> {
            System.out.println(path);
            System.out.println(Strings.repeat("=", path.length()));
            try {
                new VoidVisitorAdapter<Object>() {
                    @Override
                    public void visit(MethodCallExpr n, Object arg) {
                        super.visit(n, arg);
                        if(n.getScope().isPresent() && n.getScope().get().isNameExpr() ) {
                            NameExpr nameExpr = (NameExpr) n.getScope().get();
                            if (nameExpr.getNameAsString().equals("log")) {
                                System.out.println(" [L " + n.getBegin().get().line + "] " + n);
                            }
                        }
                    }
                }.visit(StaticJavaParser.parse(file), null);
                System.out.println(); // empty line
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).explore(projectDir);
    }

    public static void main(String[] args) {
        File projectDir = new File("source_to_parse/junit-master");
        listMethodCalls(projectDir);
    }
}
