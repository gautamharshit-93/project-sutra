package com.sutra.common.util;

public final class Text {

    private Text() {
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
