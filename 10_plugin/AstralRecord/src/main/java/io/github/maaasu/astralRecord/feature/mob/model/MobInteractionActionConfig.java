package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * NPC クリック時に実行するアクション定義です。
 *
 * @param id     アクション ID
 * @param params アクションごとの任意パラメータ
 */
public record MobInteractionActionConfig(
        @NotNull String id,
        @NotNull Map<String, String> params
) {

    public MobInteractionActionConfig {
        id = id == null ? "" : id.trim();
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
