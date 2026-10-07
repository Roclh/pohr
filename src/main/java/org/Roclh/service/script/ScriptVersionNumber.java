package org.Roclh.service.script;

import lombok.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Версия скрипта в формате MAJOR.MINOR.
 * Арифметическое сравнение — 1.10 > 1.9 (не строковое).
 */
public record ScriptVersionNumber(int major, int minor) implements Comparable<ScriptVersionNumber> {

    private static final Pattern FORMAT = Pattern.compile("^(\\d+)\\.(\\d+)$");

    public static ScriptVersionNumber parse(String s) {
        if (s == null || s.isBlank()) return new ScriptVersionNumber(1, 0);
        Matcher m = FORMAT.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid version: " + s);
        }
        return new ScriptVersionNumber(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)));
    }

    public static ScriptVersionNumber initial() {
        return new ScriptVersionNumber(1, 0);
    }

    public ScriptVersionNumber nextMinor() {
        return new ScriptVersionNumber(major, minor + 1);
    }

    public ScriptVersionNumber nextMajor() {
        return new ScriptVersionNumber(major + 1, 0);
    }

    public boolean isMajorChangeFrom(ScriptVersionNumber other) {
        return this.major > other.major;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }

    @Override
    public int compareTo(@NonNull ScriptVersionNumber o) {
        int c = Integer.compare(major, o.major);
        return c != 0 ? c : Integer.compare(minor, o.minor);
    }
}