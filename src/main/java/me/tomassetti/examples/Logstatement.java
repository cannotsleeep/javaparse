package me.tomassetti.examples;
import com.github.javaparser.ast.Node.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.base.Strings;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
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
    public static List<String> filenamelist = List.of("testonly");
//    public static List<String> filenamelist = List.of("kafka-trunk", "cassandra-cassandra-3.11.17", "elasticsearch-7.17.20" , "flink-release-1.19.0", "hadoop-rel-release-3.4.0", "hbase-rel-2.5.8", "wicket-rel-wicket-9.17.0", "zookeeper-release-3.9.2", "tomcat-10.1.23");
//    public static List<String> filenamelist = List.of("kafka-trunk", "testonly");
    public static List<Statement> result = new ArrayList<>();
    public static List<String> source_file_list = new ArrayList<>(); // 源文件名
    public static int count = 0;
    public static int countstatement = 0;
    public static List<String> resultnologstring = new ArrayList<>(); //保存不含log的结果
    public static List<String> resultlogstring = new ArrayList<>();
    public static void listLog(File projectDir) {
        new DirExplorer((level, path, file) -> path.endsWith(".java"), (level, path, file) -> {
            System.out.println(path);
            System.out.println(Strings.repeat("=", path.length()));
            try {
                CompilationUnit Root = StaticJavaParser.parse(file);
                Node RootNode = Root.findRootNode();
                Node it = PreOrderIterator(RootNode);
//                System.out.println(RootNode);
                while(it.hasNext()){
                    System.out.println(it);
                    it = it.next();

                }
                /*for (Statement stmt : l) {
                    String stmtString = stmt.toString();
                    if(stmtString.startsWith("{") && stmtString.endsWith("}")) {
                        if (stmtString.contains("log.info") || stmtString.contains("log.debug") || stmtString.contains("log.warning")||stmtString.contains("log.error")||stmtString.contains("LOG.INFO")||stmtString.contains("LOG.DEBUG")||stmtString.contains("LOG.WARING") ||stmtString.contains("LOG.ERROR")) {
                            String stmtStringno = stmt.toString().replace("{","");
                            if(stmtString.length() - stmtStringno.length() >1 ) {
                                String[] lines = stmtString.split("\\n"); // lines为当前statement的片段
                                if(lines.length>9 && lines.length<50) { //限制代码片段的行数
                                    StringBuilder resultnolog = new StringBuilder();  //最终结果
                                    StringBuilder resultlog = new StringBuilder();//最终结果(log)
                                    int realk=0;
                                    for (int kl = 1; kl <lines.length-1;kl++) {
                                        // 如果该行不包含"log"，则追加到结果中
                                        String line = lines[kl];
                                        if (!line.contains("log.") && !line.contains("LOG.") && !line.contains("log.info") && !line.contains("log.debug") && !line.contains("log.warning") && !line.contains("log.error") && !line.contains("LOG.INFO") && !line.contains("LOG.DEBUG") && !line.contains("LOG.WARING") && !line.contains("LOG.ERROR")) {
                                            realk++;
                                            resultnolog.append("<line" + Integer.toString(realk) + ">").append(line).append("\n");
                                        } else {
                                            resultlog.append("<line" + Integer.toString(realk) + ">").append(line).append("\n");
                                        }

                                    }
                                    countstatement++;
                                    resultnologstring.add(resultnolog.toString());
                                    resultlogstring.add(resultlog.toString());
                                    source_file_list.add(path);

                                }
                                //符合要求就加入
                            }
                        }
                    }
                }*/
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).explore(projectDir);
    }

    public static void main(String[] args) {
//        List<code> resultbeforejson = new ArrayList<>();
        for(String filename : filenamelist){
            /*countstatement = 0;
            resultnologstring.clear();
            resultlogstring.clear();
            result.clear();
            source_file_list.clear();
            resultbeforejson.clear();*/
            File projectDir = new File("source_to_parse/"+ filename);
            listLog(projectDir); //提取出log的片段
            try {
                FileWriter myWriter = new FileWriter(filename+".jsonl");
                /*for(int i = 0;i<countstatement;i++){
                    code codeseg = new code(resultnologstring.get(i).toString().substring(0, resultnologstring.get(i).toString().length() - 1),resultlogstring.get(i).toString().substring(0, resultlogstring.get(i).toString().length() - 1),source_file_list.get(i), filename);
                    resultbeforejson.add(codeseg);
                    count++;
                }
                String textresult = JSON.toJSONString(resultbeforejson);
                myWriter.write(textresult);*/
                myWriter.close();
                System.out.println(count);
                System.out.println(countstatement);
            } catch (IOException e) {
                System.out.println("An error occurred.");
                e.printStackTrace();
            }
        }
    }
}