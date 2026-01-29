package com.jdbmcp;

public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("HelloWorld starting...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        String greeting = "Hello, JDI!";
        int a = 10;
        int b = 20;
        int sum = a + b;
        System.out.println(greeting);
        System.out.println("Sum: " + sum);
    }
}
