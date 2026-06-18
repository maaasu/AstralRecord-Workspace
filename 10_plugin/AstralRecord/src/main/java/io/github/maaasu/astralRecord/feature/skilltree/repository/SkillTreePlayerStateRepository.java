package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤー単位のスキルツリー進行を AstralRecord API 経由で永続化する repository です。
 */
public class SkillTreePlayerStateRepository {
    public SkillTreePlayerStateRepository(@NotNull Plugin plugin) {
    }

    @NotNull
    public SkillTreePlayerState load(@NotNull UUID accountId) {
        String path = "/api/account-skilltree/" + accountId;
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return switch (response.statusCode()) {
                    case 200 -> parse(accountId, JsonParser.parseString(response.body()).getAsJsonObject());
                    case 404 -> new SkillTreePlayerState(accountId, Set.of());
                    default -> throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void save(@NotNull SkillTreePlayerState state) {
        String path = "/api/account-skilltree/" + state.accountId();
        JsonObject body = new JsonObject();
        body.addProperty("skillPoints", 0);
        JsonArray unlockedNodeIds = new JsonArray();
        state.unlockedNodeIds().stream()
                .sorted()
                .forEach(unlockedNodeIds::add);
        body.add("unlockedNodeIds", unlockedNodeIds);
        body.addProperty("updatedBy", state.accountId().toString());
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                        .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for PUT " + path);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private SkillTreePlayerState parse(@NotNull UUID fallbackAccountId, @NotNull JsonObject obj) {
        UUID accountId = obj.has("accountId") && !obj.get("accountId").isJsonNull()
                ? UUID.fromString(obj.get("accountId").getAsString())
                : fallbackAccountId;
        Set<String> unlockedNodeIds = new LinkedHashSet<>();
        if (obj.has("unlockedNodeIds") && obj.get("unlockedNodeIds").isJsonArray()) {
            for (var element : obj.getAsJsonArray("unlockedNodeIds")) {
                if (element != null && !element.isJsonNull()) {
                    String nodeId = element.getAsString().trim();
                    if (!nodeId.isEmpty()) {
                        unlockedNodeIds.add(nodeId);
                    }
                }
            }
        }
        return new SkillTreePlayerState(accountId, unlockedNodeIds);
    }
}
