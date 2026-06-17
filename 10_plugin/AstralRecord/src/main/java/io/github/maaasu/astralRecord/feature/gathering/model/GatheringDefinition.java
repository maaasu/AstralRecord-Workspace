package io.github.maaasu.astralRecord.feature.gathering.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

public record GatheringDefinition(
        int schemaVersion,
        @NotNull String id,
        @NotNull String category,
        @NotNull String name,
        int maxHealth,
        @NotNull Material displayBlock,
        @NotNull Vector3f displayScale,
        @NotNull List<String> requiredToolTags,
        @NotNull MobDropConfig drops,
        @NotNull GatheringSoundConfig sounds
) {

    public GatheringDefinition {
        maxHealth = Math.max(1, maxHealth);
        requiredToolTags = requiredToolTags == null ? List.of() : List.copyOf(requiredToolTags);
        sounds = sounds == null ? GatheringSoundConfig.empty() : sounds;
    }

    /**
     * 採集オブジェクトの採集音設定です。
     *
     * @param hit        HP を削ったときに鳴らす音。未指定時は鳴らしません。
     * @param breakSound HP が 0 になり破壊されたときに鳴らす音。未指定時は鳴らしません。
     */
    public record GatheringSoundConfig(
            @Nullable GatheringSound hit,
            @Nullable GatheringSound breakSound
    ) {
        /**
         * 音を鳴らさない空設定を返します。
         *
         * @return 空の採集音設定
         */
        public static @NotNull GatheringSoundConfig empty() {
            return new GatheringSoundConfig(null, null);
        }
    }

    /**
     * Bukkit sound key と再生パラメータです。
     *
     * @param soundKey Bukkit sound key
     * @param volume   音量
     * @param pitch    ピッチ
     */
    public record GatheringSound(
            @NotNull String soundKey,
            float volume,
            float pitch
    ) {}
}
