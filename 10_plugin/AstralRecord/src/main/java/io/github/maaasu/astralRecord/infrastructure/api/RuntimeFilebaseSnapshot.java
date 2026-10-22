package io.github.maaasu.astralRecord.infrastructure.api;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** APIから取得した実行用マスタ原稿をディスクへ保存せず保持する不変スナップショットです。 */
public final class RuntimeFilebaseSnapshot {
    private final Map<String, Entry> entries;

    /**
     * パスの一意性を検証して不変スナップショットを構築します。
     * @param files APIが返したマスタ原稿
     * @throws IllegalArgumentException パスが重複する場合
     */
    public RuntimeFilebaseSnapshot(List<Entry> files) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : files) {
            if (result.putIfAbsent(entry.path(), entry) != null) {
                throw new IllegalArgumentException("Duplicate runtime master path: " + entry.path());
            }
        }
        entries = Map.copyOf(result);
    }

    /**
     * 指定カテゴリ配下の原稿を再帰的にパス順で返します。HTTP通信は行いません。
     * @param directory スラッシュ区切りのカテゴリ相対パス
     * @return 該当する不変リスト。空カテゴリでは空リスト
     */
    public List<Entry> files(String directory) {
        String prefix = directory + "/";
        return entries.values().stream().filter(entry -> entry.path().startsWith(prefix))
                .sorted(Comparator.comparing(Entry::path)).toList();
    }

    /**
     * 指定原稿を取得します。ローカルファイルへのフォールバックはありません。
     * @param path 原稿の相対パス
     * @return マスタ原稿
     * @throws IllegalStateException 該当する原稿がない場合
     */
    public Entry require(String path) {
        Entry entry = entries.get(path);
        if (entry == null) throw new IllegalStateException("Runtime master is missing: " + path);
        return entry;
    }

    /** API上の相対パスとUTF-8原稿です。パスは診断・分類にのみ使用します。 */
    public record Entry(String path, String content) {
        /**
         * @param path 診断と分類に使う安全な相対パス
         * @param content 原稿本文
         * @throws IllegalArgumentException 相対パスが不正な場合
         */
        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
                throw new IllegalArgumentException("Invalid runtime master path: " + path);
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Invalid runtime master path: " + path);
                }
            }
        }

        /** @return 相対パスの末尾にあるファイル名 */
        public String name() { return path.substring(path.lastIndexOf('/') + 1); }

        /**
         * 原稿をメモリ上でYAMLとして解析します。解析失敗を空定義として扱いません。
         * @return 呼出しごとに独立したYAML設定
         * @throws IllegalStateException YAMLが不正な場合
         */
        public YamlConfiguration yaml() {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(content);
                return yaml;
            } catch (InvalidConfigurationException failure) {
                throw new IllegalStateException("Invalid runtime master YAML: " + path, failure);
            }
        }
    }
}
