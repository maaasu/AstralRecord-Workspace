package io.github.maaasu.astralRecord.feature.menu.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * メニューショートカット設定をアカウント DB カラムへ保存するリポジトリです。
 */
public class MenuShortcutRepository {
    private final ConcurrentMap<UUID, MenuShortcutSettings> cache = new ConcurrentHashMap<>();

    /**
     * 指定アカウントのショートカット設定を取得します。
     * キャッシュ未取得の場合は API から同期取得します。このメソッドは必ず非同期スレッドから呼び出してください。
     *
     * @param accountId アカウント ID
     * @return ショートカット設定
     */
    public @NotNull MenuShortcutSettings findByAccountId(@NotNull UUID accountId) {
        return cache.computeIfAbsent(accountId, this::fetchByAccountId);
    }

    private @NotNull MenuShortcutSettings fetchByAccountId(@NotNull UUID accountId) {
        try {
            var request = ApiRequestUtil.buildRequestBuilder("/api/account/" + accountId)
                .GET()
                .build();
            var response = ApiRequestUtil.buildClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return MenuShortcutSettings.defaults();
            }
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " for GET /api/account/" + accountId);
            }
            JsonObject account = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement shortcutsJson = account.get("menuShortcutsJson");
            return parseSettings(shortcutsJson == null || shortcutsJson.isJsonNull() ? null : shortcutsJson.getAsString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log(LogId.W_5601, e, accountId);
            return MenuShortcutSettings.defaults();
        } catch (IOException e) {
            Logger.log(LogId.W_5601, e, accountId);
            return MenuShortcutSettings.defaults();
        }
    }

    /**
     * 指定アカウントの1スロット分のショートカット設定を保存します。
     * キャッシュを楽観的に更新した後、API への書き込みを非同期で行います。
     * <p>
     * API 書き込みに失敗した場合はキャッシュをデフォルト設定で上書きし、警告ログを記録します。
     * 次回 API 取得または再ログイン時に再同期されます。
     *
     * @param accountId アカウント ID
     * @param slotIndex 0始まりのショートカットスロット番号
     * @param action 保存する項目
     * @return API 書き込み成功なら {@code true}、失敗なら {@code false} を返す CompletableFuture
     */
    public @NotNull CompletableFuture<Boolean> updateSlot(
        @NotNull UUID accountId,
        int slotIndex,
        @NotNull MenuShortcutAction action
    ) {
        if (slotIndex < 0 || slotIndex >= MenuShortcutSettings.SLOT_COUNT) {
            return CompletableFuture.completedFuture(false);
        }
        MenuShortcutSettings settings = findByAccountId(accountId).withAction(slotIndex, action);
        cache.put(accountId, settings);
        return CompletableFuture.supplyAsync(() -> putToApi(accountId, settings));
    }

    private boolean putToApi(@NotNull UUID accountId, @NotNull MenuShortcutSettings settings) {
        String body = ApiRequestUtil.buildJsonBody(json -> {
            json.addProperty("accountName", (String) null);
            json.addProperty("isActive", (Boolean) null);
            json.addProperty("mode", (Number) null);
            json.addProperty("menuShortcutsJson", toJson(settings));
            json.addProperty("updatedBy", accountId.toString());
            return null;
        });

        try {
            var request = ApiRequestUtil.buildRequestBuilder("/api/account/" + accountId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = ApiRequestUtil.buildClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Unexpected status " + response.statusCode() + " for PUT /api/account/" + accountId);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log(LogId.W_5602, e, accountId);
            cache.put(accountId, MenuShortcutSettings.defaults());
            return false;
        } catch (IOException e) {
            Logger.log(LogId.W_5602, e, accountId);
            cache.put(accountId, MenuShortcutSettings.defaults());
            return false;
        }
    }

    private @NotNull MenuShortcutSettings parseSettings(String shortcutsJson) {
        if (shortcutsJson == null || shortcutsJson.isBlank()) {
            return MenuShortcutSettings.defaults();
        }
        MenuShortcutAction[] actions = new MenuShortcutAction[MenuShortcutSettings.SLOT_COUNT];
        try {
            JsonArray array = JsonParser.parseString(shortcutsJson).getAsJsonArray();
            for (int slot = 0; slot < MenuShortcutSettings.SLOT_COUNT; slot++) {
                actions[slot] = slot < array.size()
                    ? MenuShortcutAction.fromCode(array.get(slot).getAsString())
                    : MenuShortcutAction.defaultForSlot(slot);
            }
        } catch (RuntimeException e) {
            return MenuShortcutSettings.defaults();
        }
        return new MenuShortcutSettings(actions);
    }

    private @NotNull String toJson(@NotNull MenuShortcutSettings settings) {
        JsonArray array = new JsonArray();
        for (MenuShortcutAction action : settings.asList()) {
            array.add(action.getCode());
        }
        return array.toString();
    }
}
