package com.jdbmcp;

public interface Consumer<T> {
    void accept(T t);
}
