package io.github.hemanthvk980.shortener.link.service;

import io.github.hemanthvk980.shortener.config.AppProperties;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Random Base62 codes from a CSPRNG. With length 7 the space is 62^7 ≈ 3.5e12, so codes are not
 * enumerable (unlike sequential ids) and collisions are rare; the service still checks and retries.
 */
@Component
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public CodeGenerator(AppProperties props) {
        this.length = props.codeLength();
    }

    public String next() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        return new String(out);
    }
}
