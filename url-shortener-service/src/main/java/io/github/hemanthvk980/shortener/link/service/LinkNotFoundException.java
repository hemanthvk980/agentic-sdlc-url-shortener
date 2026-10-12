package io.github.hemanthvk980.shortener.link.service;

public class LinkNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LinkNotFoundException(String code) {
        super("no link with code '" + code + "'");
    }
}
