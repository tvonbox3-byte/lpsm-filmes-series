package com.lpsm.vod;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AdultContent {
    private static final Pattern MARKERS = Pattern.compile("\\b(?:adultos?|adults?|xxx|porn\\w*|erotic\\w*|hentai|onlyfans)\\b|18\\s*\\+|\\+\\s*18");
    private AdultContent() { }
    public static boolean isAdultName(String value) {
        String name = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{Mn}+", "").toLowerCase(Locale.ROOT);
        return MARKERS.matcher(name).find();
    }
}
