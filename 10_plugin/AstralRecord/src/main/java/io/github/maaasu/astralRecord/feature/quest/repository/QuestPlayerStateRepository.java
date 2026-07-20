package io.github.maaasu.astralRecord.feature.quest.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー単位のクエスト状態を AstralRecord API 経由で永続化します。
 * 既存の quest-states YAML は初回 API 保存時だけ移行用に読み込みます。
 */
public final class QuestPlayerStateRepository {
    private final File legacyDirectory;

    public QuestPlayerStateRepository(@NotNull Plugin plugin) {
        this.legacyDirectory = new File(plugin.getDataFolder(), "quest-states");
    }

    /**
     * API からアカウントのクエスト状態を読み込み、未保存状態の場合だけ旧 YAML を移行します。
     *
     * @param accountId 対象アカウント ID
     * @return クエスト状態
     * @throws RuntimeException API 通信またはレスポンス解析に失敗した場合
     */
    public @NotNull QuestPlayerState load(@NotNull UUID accountId) {
        JsonObject response = request(accountId, HttpRequest.BodyPublishers.noBody(), "GET");
        QuestPlayerState apiState = parseApi(accountId, response);
        if (response.has("isSaved") && !response.get("isSaved").getAsBoolean()) {
            File legacyFile = file(accountId);
            if (legacyFile.exists()) {
                QuestPlayerState legacyState = loadLegacy(legacyFile, accountId);
                save(legacyState);
                if (!legacyFile.delete()) {
                    throw new IllegalStateException("旧クエスト状態 YAML の削除に失敗しました: " + legacyFile);
                }
                return legacyState;
            }
        }
        return apiState;
    }

    /**
     * クエスト状態を API へ置換保存します。
     *
     * @param state 保存するクエスト状態
     * @throws RuntimeException API 通信または保存に失敗した場合
     */
    public void save(@NotNull QuestPlayerState state) {
        JsonObject body = new JsonObject();
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
        body.addProperty("updatedBy", state.accountId().toString());
        request(state.accountId(), HttpRequest.BodyPublishers.ofString(body.toString()), "PUT");
    }

    private @NotNull JsonObject request(
        @NotNull UUID accountId,
        @NotNull HttpRequest.BodyPublisher body,
        @NotNull String method
    ) {
        String path = "/api/account-quest/" + accountId;
        try (var client = ApiRequestUtil.buildClient()) {
            HttpRequest.Builder builder = ApiRequestUtil.buildRequestBuilder(path);
            HttpRequest request = "PUT".equals(method)
                ? builder.PUT(body).build()
                : builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for " + method + " " + path);
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
            parseLongMap(object, "cooldowns", "questId", "cooldownUntilEpochMillis")
        );
    }

    private @NotNull QuestPlayerState loadLegacy(@NotNull File file, @NotNull UUID accountId) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, QuestProgress> active = new LinkedHashMap<>();
        ConfigurationSection activeSection = yaml.getConfigurationSection("active");
        if (activeSection != null) {
            for (String questId : activeSection.getKeys(false)) {
                ConfigurationSection questSection = activeSection.getConfigurationSection(questId);
                if (questSection == null) {
                    continue;
                }
                Map<String, Integer> objectives = new LinkedHashMap<>();
                ConfigurationSection objectivesSection = questSection.getConfigurationSection("objectives");
                if (objectivesSection != null) {
                    for (String objectiveId : objectivesSection.getKeys(false)) {
                        objectives.put(objectiveId, objectivesSection.getInt(objectiveId, 0));
                    }
                }
                active.put(questId, new QuestProgress(
                    questId,
                    questSection.getLong("acceptedAt", System.currentTimeMillis()),
                    questSection.getString("acceptedNpcId"),
                    objectives,
                    questSection.getBoolean("readyToTurnIn", false)
                ));
            }
        }
        return new QuestPlayerState(
            accountId,
            active,
            readLongMap(yaml.getConfigurationSection("completedAt")),
            readLongMap(yaml.getConfigurationSection("cooldownUntil"))
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

    private @NotNull Map<String, Long> readLongMap(@Nullable ConfigurationSection section) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            result.put(key, section.getLong(key, 0L));
        }
        return result;
    }

    private @NotNull File file(@NotNull UUID accountId) {
        return new File(legacyDirectory, accountId + ".yml");
    }
}
