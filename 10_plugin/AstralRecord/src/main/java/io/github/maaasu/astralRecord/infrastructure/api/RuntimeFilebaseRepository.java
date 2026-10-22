package io.github.maaasu.astralRecord.infrastructure.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 実行用マスタをAPIから一括取得し、準備中と公開済みのスナップショットを分離します。 */
public final class RuntimeFilebaseRepository {
    private static final RuntimeFilebaseRepository INSTANCE = new RuntimeFilebaseRepository();
    private static final String PATH = "/api/master-data/runtime-filebase";
    private static final List<String> DIRECTORIES = List.of(
            "35.features.skilltree/nodes", "35.features.skilltree/structures",
            "41.features.mob.spawner", "42.features.gathering", "43.features.gathering.spawner",
            "45.features.shop", "51.features.quest", "52.features.quest_board",
            "65.features.dungeon", "85.shared.recipe");
    private final ThreadLocal<RuntimeFilebaseSnapshot> prepared = new ThreadLocal<>();
    private volatile RuntimeFilebaseSnapshot published;

    RuntimeFilebaseRepository() { }

    /** @return プロセス内の共有リポジトリ */
    public static RuntimeFilebaseRepository getInstance() { return INSTANCE; }

    /**
     * APIの全原稿を取得して検証します。非同期スレッド専用で、共有状態を変更しません。
     * @return 公開前の不変スナップショット
     * @throws IllegalStateException API取得または応答検証に失敗した場合
     */
    public RuntimeFilebaseSnapshot loadSnapshot() {
        try {
            var request = ApiRequestUtil.buildRequestBuilder(PATH).GET().build();
            var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for GET " + PATH);
            }
            return parseSnapshot(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runtime master request interrupted", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("Runtime master request failed", failure);
        }
    }

    /**
     * 応答の版番号、原稿型、許可カテゴリと重複を検証します。
     * @param json API応答本文
     * @return 検証済みスナップショット
     * @throws IllegalStateException 応答契約が不正な場合
     */
    public static RuntimeFilebaseSnapshot parseSnapshot(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("schemaVersion") || !root.get("schemaVersion").toString().equals("1")) {
                throw new IllegalArgumentException("Unsupported runtime master schema");
            }
            List<RuntimeFilebaseSnapshot.Entry> files = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                String path = requiredString(file, "path");
                String content = requiredString(file, "content");
                boolean allowed = DIRECTORIES.stream().anyMatch(directory -> {
                    if (!path.startsWith(directory + "/")) return false;
                    String relative = path.substring(directory.length() + 1);
                    boolean recursive = directory.equals("42.features.gathering");
                    boolean jsonCategory = directory.startsWith("35.features.skilltree/");
                    return (recursive || !relative.contains("/"))
                            && (jsonCategory ? path.toLowerCase(java.util.Locale.ROOT).endsWith(".json") : path.endsWith(".yml"));
                });
                if (!allowed) throw new IllegalArgumentException("Unexpected runtime master path: " + path);
                files.add(new RuntimeFilebaseSnapshot.Entry(path, content));
            }
            return new RuntimeFilebaseSnapshot(files);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid runtime master response", failure);
        }
    }

    /** API応答の指定キーがJSON文字列であることを検証し、文字列以外は拒否します。 */
    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return value.getAsString();
    }

    /**
     * 準備中または公開済みのマスタを取得します。通信やファイルアクセスを行いません。
     * @return 呼出しスレッドのスナップショット
     * @throws IllegalStateException 起動時の取得が完了していない場合
     */
    public RuntimeFilebaseSnapshot currentSnapshot() {
        RuntimeFilebaseSnapshot local = prepared.get();
        RuntimeFilebaseSnapshot result = local == null ? published : local;
        if (result == null) throw new IllegalStateException("Runtime masters are not loaded");
        return result;
    }

    /**
     * 検証済みスナップショットを公開します。呼出し側は全マスタの準備成功後に実行します。
     * @param snapshot 公開対象
     */
    public void replaceSnapshot(RuntimeFilebaseSnapshot snapshot) { published = Objects.requireNonNull(snapshot); }

    /**
     * action実行中だけ準備中マスタを公開し、正常・例外終了後に元の参照を復元します。
     * @param snapshot 準備中マスタ
     * @param action マスタを検証する処理
     * @param <T> 処理結果の型
     * @return 処理結果
     */
    public <T> T withSnapshot(RuntimeFilebaseSnapshot snapshot, Supplier<T> action) {
        RuntimeFilebaseSnapshot previous = prepared.get();
        prepared.set(Objects.requireNonNull(snapshot));
        try { return action.get(); }
        finally {
            if (previous == null) prepared.remove();
            else prepared.set(previous);
        }
    }
}
