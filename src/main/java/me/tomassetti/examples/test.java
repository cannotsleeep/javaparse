package me.tomassetti.examples;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import com.alibaba.fastjson2.JSON;
import me.tomassetti.examples.Dog;

public class test {
    public static void main(String[] args) {
        Dog a = new Dog(12, 12);
//        String text = JSON.toJSONString(a);
        int k = 11;
        String text = "<line"+Integer.toString(k)+">";
        System.out.println(text);
    }
}
