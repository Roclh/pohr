package org.Roclh.service.edge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Минимальный unified diff без внешних зависимостей: LCS + контекст 3 строки.
 * Используется только для показа сгенерированного конфига рядом с файлом на
 * диске, поэтому производительность не критична.
 */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    private static final long MAX_DP_CELLS = 4_000_000L;

    private enum Type { EQUAL, DELETE, INSERT }

    private record Edit(Type type, String line) {
    }

    private UnifiedDiff() {
    }

    public static String diff(String oldText, String newText, String oldLabel, String newLabel) {
        if (Objects.equals(oldText, newText)) {
            return "";
        }
        List<String> oldLines = split(oldText);
        List<String> newLines = split(newText);
        List<Edit> edits = edits(oldLines, newLines);
        if (edits.stream().allMatch(e -> e.type() == Type.EQUAL)) {
            return "";
        }

        // Префиксные счётчики: сколько старых/новых строк прошло до позиции i.
        int[] oldBefore = new int[edits.size() + 1];
        int[] newBefore = new int[edits.size() + 1];
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            oldBefore[i + 1] = oldBefore[i] + (edit.type() == Type.INSERT ? 0 : 1);
            newBefore[i + 1] = newBefore[i] + (edit.type() == Type.DELETE ? 0 : 1);
        }

        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            if (edits.get(i).type() != Type.EQUAL) {
                changes.add(i);
            }
        }
        if (changes.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        out.append("--- ").append(oldLabel).append('\n');
        out.append("+++ ").append(newLabel).append('\n');

        int changeIndex = 0;
        while (changeIndex < changes.size()) {
            int start = Math.max(0, changes.get(changeIndex) - CONTEXT);
            int end = Math.min(edits.size(), changes.get(changeIndex) + 1 + CONTEXT);
            int cursor = changeIndex + 1;
            while (cursor < changes.size() && changes.get(cursor) - CONTEXT <= end) {
                end = Math.min(edits.size(), changes.get(cursor) + 1 + CONTEXT);
                cursor++;
            }

            int oldStart = oldBefore[start] + 1;
            int newStart = newBefore[start] + 1;
            int oldCount = oldBefore[end] - oldBefore[start];
            int newCount = newBefore[end] - newBefore[start];
            if (oldCount == 0) {
                oldStart = oldBefore[start];
            }
            if (newCount == 0) {
                newStart = newBefore[start];
            }
            out.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");

            for (int i = start; i < end; i++) {
                Edit edit = edits.get(i);
                char prefix = switch (edit.type()) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                };
                out.append(prefix).append(edit.line()).append('\n');
            }
            changeIndex = cursor;
        }
        return out.toString();
    }

    private static List<Edit> edits(List<String> oldLines, List<String> newLines) {
        long cells = (long) oldLines.size() * newLines.size();
        if (cells > MAX_DP_CELLS) {
            // Огромные файлы не встречаются в edge, но не даём процессору уйти в разнос.
            List<Edit> fallback = new ArrayList<>();
            oldLines.forEach(line -> fallback.add(new Edit(Type.DELETE, line)));
            newLines.forEach(line -> fallback.add(new Edit(Type.INSERT, line)));
            return fallback;
        }

        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) {
            for (int j = newLines.size() - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Edit> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldLines.size() && j < newLines.size()) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new Edit(Type.EQUAL, oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Edit(Type.DELETE, oldLines.get(i)));
                i++;
            } else {
                result.add(new Edit(Type.INSERT, newLines.get(j)));
                j++;
            }
        }
        while (i < oldLines.size()) {
            result.add(new Edit(Type.DELETE, oldLines.get(i++)));
        }
        while (j < newLines.size()) {
            result.add(new Edit(Type.INSERT, newLines.get(j++)));
        }
        return result;
    }

    private static List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Arrays.asList(normalized.split("\n", -1));
    }
}
