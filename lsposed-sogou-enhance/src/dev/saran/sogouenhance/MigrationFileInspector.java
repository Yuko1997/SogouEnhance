package dev.saran.sogouenhance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class MigrationFileInspector {
    private MigrationFileInspector() {
    }

    static UudInfo inspectUud(File file) throws IOException {
        int records = 0;
        boolean sawHeader = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file),
                        StandardCharsets.UTF_16LE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (records == 0 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!sawHeader) {
                    sawHeader = line.startsWith("FormatVersion=");
                    continue;
                }
                String[] columns = line.split("\t", 3);
                if (columns.length >= 2
                        && !columns[0].isEmpty()
                        && !columns[1].isEmpty()) {
                    records++;
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("不是受支持的 v3new.uud 文件");
        }
        UudInfo result = new UudInfo();
        result.records = records;
        result.size = file.length();
        result.sha256 = MigrationContract.sha256(file);
        return result;
    }

    static int countVisibleShortcuts(File file) throws IOException {
        return visibleShortcuts(file).size();
    }

    static List<String> visibleShortcuts(File file)
            throws IOException {
        List<String> result = new ArrayList<>();
        JSONObject root;
        try {
            root = new JSONObject(readUtf8(file));
        } catch (Exception e) {
            throw new IOException("快捷短语 JSON 无法解析", e);
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray groups = data == null
                ? null : data.optJSONArray("list");
        if (groups == null) {
            throw new IOException("快捷短语 JSON 结构不受支持");
        }
        for (int groupIndex = 0; groupIndex < groups.length();
             groupIndex++) {
            JSONObject group = groups.optJSONObject(groupIndex);
            if (group == null || group.optInt("is_del", 0) != 0) {
                continue;
            }
            JSONArray phrases = group.optJSONArray("list");
            if (phrases == null) {
                continue;
            }
            for (int phraseIndex = 0;
                 phraseIndex < phrases.length(); phraseIndex++) {
                JSONObject phrase =
                        phrases.optJSONObject(phraseIndex);
                if (phrase == null
                        || phrase.optInt("is_del", 0) != 0) {
                    continue;
                }
                String content = phrase.optString("content", "");
                if (!content.isEmpty()) {
                    result.add(content);
                }
            }
        }
        return result;
    }

    static boolean isAllowedEnglishName(String name) {
        for (String allowed : MigrationContract.ENGLISH_FILE_NAMES) {
            if (allowed.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String readUtf8(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    static final class UudInfo {
        int records;
        long size;
        String sha256;
    }
}
