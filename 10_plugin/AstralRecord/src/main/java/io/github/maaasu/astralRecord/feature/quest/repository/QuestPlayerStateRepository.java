package io.github.maaasu.astralRecord.feature.quest.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー単位のクエスト状態を AstralRecord API 経由で永続化します。
 */
public final class QuestPlayerStateRepository {
    /**
     * API からアカウントのクエスト状態を読み込みます。
     *
     * @param accountId 対象アカウント ID
     * @return クエスト状態
     * @throws RuntimeException API 通信またはレスポンス解析に失敗した場合
     */
    public @NotNull QuestPlayerState load(@NotNull UUID accountId) {
        JsonObject response = request(accountId);
        return parseApi(accountId, response);
    }

    /** player-state snapshot の questState section を構築します。通信は行いません。 */
    public @NotNull JsonObject createSnapshotSection(@NotNull QuestPlayerState state, long clientRevision) {
        JsonObject body = new JsonObject();
        body.addProperty("accountId", state.accountId().toString());
        body.addProperty("clientRevision", clientRevision);
        body.addProperty("expectedVersion", state.persistedVersion());
        JsonArray activeQuests = new JsonArray();
        for (QuestProgress progress : state.activeQuests().values()) {
            JsonObject active = new JsonObject();
            active.addProperty("questId", progress.questId());
            active.addProperty("acceptedAtEpochMillis", progress.acceptedAtEpochMillis());
            if (progress.acceptedNpcId() == null) {
                active.add("acceptedNpcId", null);
            } else {
                active.addProperty("acceptedNpcId", progress.acceptedNpcId());
            }
            active.addProperty("readyToTurnIn", progress.readyToTurnIn());
            JsonArray objectives = new JsonArray();
            progress.objectiveProgress().forEach((objectiveId, value) -> {
                JsonObject objective = new JsonObject();
                objective.addProperty("objectiveId", objectiveId);
                objective.addProperty("progress", value);
                objectives.add(objective);
            });
            active.add("objectiveProgress", objectives);
            activeQuests.add(active);
        }
        JsonArray completions = new JsonArray();
        state.completedAt().forEach((questId, completedAt) -> {
            JsonObject completion = new JsonObject();
            completion.addProperty("questId", questId);
            completion.addProperty("completedAtEpochMillis", completedAt);
            completions.add(completion);
        });
        JsonArray cooldowns = new JsonArray();
        state.cooldownUntil().forEach((questId, cooldownUntil) -> {
            JsonObject cooldown = new JsonObject();
            cooldown.addProperty("questId", questId);
            cooldown.addProperty("cooldownUntilEpochMillis", cooldownUntil);
            cooldowns.add(cooldown);
        });
        body.add("activeQuests", activeQuests);
        body.add("completions", completions);
        body.add("cooldowns", cooldowns);
        return body;
    }

    private @NotNull JsonObject request(@NotNull UUID accountId) {
        String path = "/api/account-quest/" + accountId;
        try {
            var client = ApiRequestUtil.sharedClient();
            HttpRequest request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        } catch (IOException | RuntimeException exception) {
            throw new RuntimeException(exception);
        }
    }

    private @NotNull QuestPlayerState parseApi(@NotNull UUID fallbackAccountId, @NotNull JsonObject object) {
        UUID accountId = object.has("accountId") && !object.get("accountId").isJsonNull()
            ? UUID.fromString(object.get("accountId").getAsString())
            : fallbackAccountId;
        Map<String, QuestProgress> active = new LinkedHashMap<>();
        if (object.has("activeQuests") && object.get("activeQuests").isJsonArray()) {
            for (var element : object.getAsJsonArray("activeQuests")) {
                JsonObject activeObject = element.getAsJsonObject();
                Map<String, Integer> objectives = new LinkedHashMap<>();
                if (activeObject.has("objectiveProgress")) {
                    for (var objectiveElement : activeObject.getAsJsonArray("objectiveProgress")) {
                        JsonObject objective = objectiveElement.getAsJsonObject();
                        objectives.put(objective.get("objectiveId").getAsString(), objective.get("progress").getAsInt());
                    }
                }
                active.put(activeObject.get("questId").getAsString(), new QuestProgress(
                    activeObject.get("questId").getAsString(),
                    activeObject.get("acceptedAtEpochMillis").getAsLong(),
                    nullableString(activeObject, "acceptedNpcId"),
                    objectives,
                    activeObject.get("readyToTurnIn").getAsBoolean()
                ));
            }
        }
        return new QuestPlayerState(
            accountId,
            active,
            parseLongMap(object, "completions", "questId", "completedAtEpochMillis"),
            parseLongMap(object, "cooldowns", "questId", "cooldownUntilEpochMillis"),
            object.has("version") && !object.get("version").isJsonNull()
                ? Math.max(0, object.get("version").getAsInt())
                : 0
        );
    }

    private @NotNull Map<String, Long> parseLongMap(
        @NotNull JsonObject object,
        @NotNull String arrayName,
        @NotNull String keyName,
        @NotNull String valueName
    ) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (!object.has(arrayName)) {
            return result;
        }
        for (var element : object.getAsJsonArray(arrayName)) {
            JsonObject entry = element.getAsJsonObject();
            result.put(entry.get(keyName).getAsString(), entry.get(valueName).getAsLong());
        }
        return result;
    }

    private @Nullable String nullableString(@NotNull JsonObject object, @NotNull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

}
