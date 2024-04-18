package me.tomassetti.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.base.Strings;
import me.tomassetti.examples.tMethodCallsExample;
import me.tomassetti.support.DirExplorer;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.alibaba.fastjson2.JSON;
import me.tomassetti.examples.code;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;


public class Logstatement {
//    public static String  filename = "tomcat-10.1.23";
    public static List<String> filenamelist = List.of("kafka-trunk", "cassandra-cassandra-3.11.17", "elasticsearch-7.17.20" , "flink-release-1.19.0", "hadoop-rel-release-3.4.0", "hbase-rel-2.5.8", "wicket-rel-wicket-9.17.0", "zookeeper-release-3.9.2", "tomcat-10.1.23");
    public static List<Statement> result = new ArrayList<>();
    public static void listLog(File projectDir) {
        new DirExplorer((level, path, file) -> path.endsWith(".java"), (level, path, file) -> {
            System.out.println(path);
            System.out.println(Strings.repeat("=", path.length()));
            try {
                List<Statement> l = StaticJavaParser.parse(file)
                        .findAll(Statement.class);
                String regex = "(LOG|log)\\.(info|error|warning|debug)";
                Pattern pattern = Pattern.compile(regex);
                for (Statement stmt : l) {
                    String stmtString = stmt.toString();
                    if(stmtString.startsWith("{") && stmtString.endsWith("}")) {
                        //System.out.println(stmtString);
                        //System.out.println(Strings.repeat("-", path.length()));
                        Matcher matcher = pattern.matcher(stmtString);
                        if (matcher.find()) {
                            //System.out.println(stmtString);
                            result.add(stmt);
                            //System.out.println(Strings.repeat("0-", path.length()));
                        }
                    }
                }
                //System.out.println(); // empty line
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).explore(projectDir);
        //System.out.println(result);
    }

    public static String tr(String str){
        str = str.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f")
                .replace("\"", "\\\"")
                .replace("\'", "\\\'");
//                .replace("{", "\\{")
//                .replace("}", "\\}");
        return str;
    }

    public static void main(String[] args) {
        int count = 0;
        for(String filename : filenamelist){
            File projectDir = new File("source_to_parse/"+ filename);
            listLog(projectDir); //提取出log的片段
            //System.out.println(result);
            List<code> resultbeforejson = new ArrayList<>();
//        code resultbeforejsonseg = new code("a","a");
            List<String> resultnologstring = new ArrayList<>(); //保存不含log的结果
            List<String> resultlogstring = new ArrayList<>();
            for (Statement stmt : result) {
                String stmtString = stmt.toString(); //每个statement
                String[] lines = stmtString.split("\\n"); // lines为当前statement的片段
                StringBuilder resultnolog = new StringBuilder();  //最终结果
                StringBuilder resultlog = new StringBuilder();//最终结果(log)
                int k = 0,kl=0;
                for (String line : lines) {
                    // 如果该行不包含"log"，则追加到结果中
                    if (!line.contains("log.") && !line.contains("LOG.")) {
                        k++;
                        resultnolog.append("<line"+Integer.toString(k)+">").append(line).append("\n");
                    }
                    kl++;
                    resultlog.append("<line"+Integer.toString(kl)+">").append(line).append("\n");

                }
                resultnologstring.add(resultnolog.toString());
                resultlogstring.add(resultlog.toString());
            }
//        System.out.println(resultnologstring); //resultnologstring中是带行号的结果
            try {
                FileWriter myWriter = new FileWriter(filename+".jsonl");
                //myWriter.write("[");
                for(int i = 0;i<result.size();i++){

                    code codeseg = new code(resultnologstring.get(i).toString().substring(0, resultnologstring.get(i).toString().length() - 1),resultlogstring.get(i).toString().substring(0, resultlogstring.get(i).toString().length() - 1));
                    resultbeforejson.add(codeseg);
                    count++;
                }
                String textresult = JSON.toJSONString(resultbeforejson);
                //System.out.println(textresult);
/*                myWriter.write("{\"instruction\":\"XXXXX\",\"input\":\"");
                myWriter.write(tr(resultnologstring.get(i).toString().substring(0, resultnologstring.get(i).toString().length() - 1)));
                //上方去掉了末尾的换行
                myWriter.write("\",\"output\":\"");
                myWriter.write(tr(result.get(i).toString()));
                if(i <result.size()-1){myWriter.write("\"},");}
                    else{myWriter.write("\"}");}
            }
            myWriter.write("]");
            myWriter.close();*/
                myWriter.write(textresult);
                myWriter.close();
                System.out.println(count);
            } catch (IOException e) {
                System.out.println("An error occurred.");
                e.printStackTrace();
            }
        }
    }
}


//System.out.println(" [Lines " + statement.getBegin().get().line
//                                + " - " + statement.getEnd().get().line + " ] " + statement