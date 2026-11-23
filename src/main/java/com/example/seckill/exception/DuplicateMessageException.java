package com.example.seckill.exception;

public class DuplicateMessageException extends RuntimeException {
    public DuplicateMessageException(String message) {
        super(message);
    }
}
