package io.github.hemanthvk980.shortener.link.service;

public class InvalidUrlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidUrlException(String message) {
        super(message);
    }
}
