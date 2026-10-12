package io.github.hemanthvk980.shortener.link.service;

/** The link existed but has expired or been disabled (HTTP 410, distinct from never-existed 404). */
public class LinkGoneException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LinkGoneException(String code, String why) {
        super("link '" + code + "' is no longer available: " + why);
    }
}
