package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 遠隔スキル後の次回ドッジを無償化するスペルステップの状態を管理します。 */
public final class SpellStepSkillRuntimeService {
    /** 遠隔スキル成功からドッジ無償化が有効な tick 数です。 */
    public static final int TRIGGER_WINDOW_TICKS = 20;
    /** 発動音量の既定値です。 */
    public static final double DEFAULT_TRIGGER_SOUND_VOLUME = 0.8D;
    /** 発動音程の既定値です。 */
    public static final double DEFAULT_TRIGGER_SOUND_PITCH = 1.3D;

    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> armedUntilTick = new ConcurrentHashMap<>();

    /**
     * 有効化されたスキル個体の設定を登録します。
     *
     * @param context 有効化されたパッシブコンテキスト
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Configuration configuration = new Configuration(
                params.getInt("windowTicks", TRIGGER_WINDOW_TICKS),
                params.requireString("triggerSound").trim(),
                (float) params.getDouble("triggerSoundVolume", DEFAULT_TRIGGER_SOUND_VOLUME),
                (float) params.getDouble("triggerSoundPitch", DEFAULT_TRIGGER_SOUND_PITCH)
        );
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), configuration);
    }

    /**
     * 無効化されたスキル個体の設定と待機中の一時状態を破棄します。
     *
     * @param context 無効化されるパッシブコンテキスト
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            armedUntilTick.remove(playerId);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            armedUntilTick.remove(playerId);
        }
    }

    /**
     * 成功したスキルが遠隔タグを持つ場合、次回ドッジを無償化する待機状態を開始します。
     *
     * @param player 発動者
     * @param skill 成功したスキル定義。定義を解決できない場合は無視します
     */
    public void onSkillCast(@NotNull AstPlayer player, @Nullable SkillDefinition skill) {
        UUID playerId = playerId(player);
        if (skill == null || !hasRangedTag(skill)) return;
        Configuration configuration = effectiveConfiguration(playerId);
        if (configuration == null) return;
        armedUntilTick.put(
                playerId,
                (long) Bukkit.getCurrentTick() + configuration.windowTicks()
        );
    }

    /**
     * 待機中の無償ドッジ権を一度だけ消費し、発動音を再生します。
     *
     * @param player ドッジを行うプレイヤー
     * @return 無償ドッジ権を消費した場合は {@code true}
     */
    public boolean consumeFreeDodge(@NotNull AstPlayer player) {
        UUID playerId = playerId(player);
        Configuration configuration = effectiveConfiguration(playerId);
        Long expiresAtTick = armedUntilTick.get(playerId);
        if (configuration == null || expiresAtTick == null) {
            armedUntilTick.remove(playerId);
            return false;
        }

        long currentTick = Bukkit.getCurrentTick();
        if (currentTick >= expiresAtTick || !armedUntilTick.remove(playerId, expiresAtTick)) {
            if (currentTick >= expiresAtTick) armedUntilTick.remove(playerId, expiresAtTick);
            return false;
        }

        player.getBukkit().playSound(
                player.getBukkit().getLocation(),
                configuration.triggerSound(),
                SoundCategory.PLAYERS,
                configuration.triggerSoundVolume(),
                configuration.triggerSoundPitch()
        );
        return true;
    }

    /** プレイヤーの死亡・ワールド移動時に待機中の一時状態だけを破棄します。 */
    public void clearArmedState(@NotNull UUID playerId) {
        armedUntilTick.remove(playerId);
    }

    /** プレイヤー退出時に設定と待機中の一時状態を破棄します。 */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        clearArmedState(playerId);
    }

    /** Plugin 停止時に全プレイヤーの設定と一時状態を破棄します。 */
    public void clearAll() {
        configurations.clear();
        armedUntilTick.clear();
    }

    private @NotNull UUID playerId(@NotNull AstPlayer player) {
        return player.getBukkit().getUniqueId();
    }

    private boolean hasRangedTag(@NotNull SkillDefinition skill) {
        return skill.getTags().stream()
                .filter(tag -> tag != null)
                .map(String::trim)
                .anyMatch(MasterTagIds.CombatRole.RANGED::equalsIgnoreCase);
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        if (context.learnedSkill() != null) {
            return context.learnedSkill().getLearnedSkillId().toString();
        }
        return context.skill().getId();
    }

    private @Nullable Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null || playerConfigurations.isEmpty()) return null;
        return playerConfigurations.values().stream()
                .max(Comparator.comparingInt(Configuration::windowTicks))
                .orElse(null);
    }

    private record Configuration(
            int windowTicks,
            @NotNull String triggerSound,
            float triggerSoundVolume,
            float triggerSoundPitch
    ) {
    }
}
