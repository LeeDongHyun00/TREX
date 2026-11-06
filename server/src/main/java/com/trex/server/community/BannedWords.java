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
import java.util.regex.Pattern;

/**
 * 커뮤니티 금칙어(닉네임·글·댓글). 목록은 {@code resources/community/banned-words.txt} — 한 줄에 한 단어, {@code #} 는 주석.
 *
 * <p>비교 전에 NFKC·소문자로 맞추고 문장부호·제로폭 문자를 지운다(전각·"씨.발"·"바​보" 로 피하지 못하게). 띄어쓰기는 단어 길이로 가른다:
 * 세 글자 이상은 띄어쓰기도 지우고 맞추지만("개 새 끼"), 두 글자 단어와 영어 단어는 띄어쓰기를 넘어 맞추지 않는다 —
 * "스쿼트 시 팔은"·"보존 나중에"·"push it" 이 막히면 안 된다(리뷰 2026-10-29: 자세가 이 앱의 중심이라 "~ 시 팔"은 흔한 문장).
 * 영어 단어는 낱말 단위로만 맞춘다. 부분 일치라 붙여 쓴 낱말 안에서는 여전히 걸릴 수 있다 — 목록에 넣기 전에 흔한 낱말에 들어가는지 본다.
 */
@Component
public class BannedWords {

    private static final String RESOURCE = "/community/banned-words.txt";

    private final List<String> words;
    private final List<Pattern> asciiWords;

    @Autowired
    public BannedWords() {
        this(loadClasspath());
    }

    public BannedWords(List<String> words) {
        List<String> clean = words.stream().map(BannedWords::compact).filter(w -> !w.isEmpty()).distinct().toList();
        this.words = clean.stream().filter(w -> !isAscii(w)).toList();
        this.asciiWords = clean.stream().filter(BannedWords::isAscii)
                .map(w -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])"))
                .toList();
    }

    public static BannedWords fromClasspath() {
        return new BannedWords(loadClasspath());
    }

    public boolean contains(String text) {
        if (text == null || text.isEmpty()) return false;
        String spaced = spaced(text);
        String compact = spaced.replace(" ", "");
        for (String word : words) {
            boolean longWord = word.codePointCount(0, word.length()) >= 3;
            if ((longWord ? compact : spaced).contains(word)) return true;
        }
        return asciiWords.stream().anyMatch(p -> p.matcher(spaced).find());
    }

    /** NFKC·소문자, 글자·숫자만 남기고 공백 줄은 한 칸으로. */
    static String spaced(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(n.length());
        boolean space = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (space && out.length() > 0) out.append(' ');
                space = false;
                out.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = true;
            }
        }
        return out.toString();
    }

    static String compact(String text) {
        return spaced(text).replace(" ", "");
    }

    private static boolean isAscii(String word) {
        return word.chars().allMatch(ch -> ch < 128);
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
