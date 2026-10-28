package com.trex.server.community;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 커뮤니티 금칙어(닉네임·글·댓글). 목록은 {@code resources/community/banned-words.txt} — 한 줄에 한 단어, {@code #} 는 주석.
 *
 * <p>비교 전에 양쪽을 NFKC·소문자로 맞추고 글자·숫자만 남긴다. 그래서 띄어쓰기·문장부호·제로폭 문자·전각 문자로
 * 끊어 써도 걸린다. 부분 일치라 짧은 단어는 멀쩡한 낱말 안에서도 걸릴 수 있다 — 목록에 넣기 전에 흔한 낱말에
 * 들어가는지 본다(예: "보지 않았다" 때문에 해당 단어는 넣지 않았다).
 */
@Component
public class BannedWords {

    private static final String RESOURCE = "/community/banned-words.txt";

    private final List<String> words;

    @Autowired
    public BannedWords() {
        this(loadClasspath());
    }

    public BannedWords(List<String> words) {
        this.words = words.stream().map(BannedWords::normalize).filter(w -> !w.isEmpty()).distinct().toList();
    }

    public static BannedWords fromClasspath() {
        return new BannedWords(loadClasspath());
    }

    public boolean contains(String text) {
        if (text == null || text.isEmpty()) return false;
        String n = normalize(text);
        return words.stream().anyMatch(n::contains);
    }

    static String normalize(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(n.length());
        n.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static List<String> loadClasspath() {
        try (InputStream in = BannedWords.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("금칙어 목록이 없다: " + RESOURCE);
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
