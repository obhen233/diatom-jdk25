package com.github.obhen233.core.code;

/**
 * 代码修改异常
 */
public class CodeModificationException extends RuntimeException {

    public CodeModificationException(String message) {
        super(message);
    }

    public CodeModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
