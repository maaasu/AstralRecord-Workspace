package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** メイジのアーケインフローが参照する連続スキル状態と発動演出を管理します。 */
public final class ArcaneFlowSkillRuntimeService {
    /** Lv.1 の詠唱時間短縮率です。 */
    public static final double BASE_CAST_TIME_REDUCTION_PERCENT = 5.0D;
    /** Lv.2 以降で加算される詠唱時間短縮率です。 */
    public static final double LEVEL_CAST_TIME_REDUCTION_DELTA_PERCENT = 1.25D;
    /** Lv.5 の詠唱時間短縮率です。 */
    public static final double MAX_CAST_TIME_REDUCTION_PERCENT = 10.0D;

    private static final int ACTIVATION_PARTICLE_POINTS = 16;
    private static final double ACTIVATION_PARTICLE_RADIUS = 0.72D;
    private static final double ACTIVATION_PARTICLE_HEIGHT = 1.0D;

    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSuccessfulSkillIds = new ConcurrentHashMap<>();

    /**
     * パーティクル表示サービスを受け取って runtime を構築します。
     *
     * @param particleDisplayService プレイヤーの表示設定を考慮するパーティクルサービス
     */
    public ArcaneFlowSkillRuntimeService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 有効化されたアーケインフロー個体の設定を登録します。
     *
     * @param context 有効化されたパッシブコンテキスト
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        double reduction = params.getDouble("castTimeReductionPercent", 0.0D);
        UUID playerId = playerId(context.player());
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), new Configuration(reduction));
    }

    /**
     * 無効化されたアーケインフロー個体の設定を破棄します。
     *
     * @param context 無効化されるパッシブコンテキスト
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = playerId(context.player());
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            lastSuccessfulSkillIds.remove(playerId);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            lastSuccessfulSkillIds.remove(playerId);
        }
    }

    /**
     * 現在の魔法スキルへ適用する追加詠唱時間短縮率を返します。
     * <p>
     * 現在のスキルが魔法タグを持ち、直前に成功したスキル ID と異なる場合だけ有効です。
     * このメソッドは詠唱時間解決中に複数回呼ばれるため、前回スキル状態は変更しません。
     *
     * @param player 発動者
     * @param skill 詠唱対象のスキル定義
     * @return 追加短縮率。条件外は 0%
     */
    public double castTimeReductionPercent(
            @NotNull AstPlayer player,
            @NotNull SkillDefinition skill
    ) {
        if (!hasMagicTag(skill)) {
            return 0.0D;
        }
        Configuration configuration = effectiveConfiguration(playerId(player));
        if (configuration == null) {
            return 0.0D;
        }
        String previousSkillId = lastSuccessfulSkillIds.get(playerId(player));
        if (previousSkillId == null || previousSkillId.equals(skill.getId())) {
            return 0.0D;
        }
        return configuration.castTimeReductionPercent();
    }

    /**
     * プレイヤーのスキル成功を記録し、条件成立時にアーケインフローの演出を表示します。
     *
     * @param player 発動者
     * @param skill 成功したスキル定義。定義を解決できない場合は無視します
     */
    public void onSkillCast(@NotNull AstPlayer player, @Nullable SkillDefinition skill) {
        if (skill == null || skill.getId().isBlank()) {
            return;
        }

        UUID playerId = playerId(player);
        Configuration configuration = effectiveConfiguration(playerId);
        if (configuration == null) {
            lastSuccessfulSkillIds.remove(playerId);
            return;
        }

        String previousSkillId = lastSuccessfulSkillIds.put(playerId, skill.getId());
        if (hasMagicTag(skill)
                && previousSkillId != null
                && !previousSkillId.equals(skill.getId())) {
            renderActivation(player);
        }
    }

    /** プレイヤーの死亡・ワールド移動時に直前スキルだけを破棄します。 */
    public void clearPreviousSkill(@NotNull UUID playerId) {
        lastSuccessfulSkillIds.remove(playerId);
    }

    /** プレイヤー退出時に設定と直前スキルを破棄します。 */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        clearPreviousSkill(playerId);
    }

    /** Plugin 停止時に全プレイヤーの設定と直前スキルを破棄します。 */
    public void clearAll() {
        configurations.clear();
        lastSuccessfulSkillIds.clear();
    }

    private void renderActivation(@NotNull AstPlayer player) {
        Location base = player.getBukkit().getLocation();
        if (base.getWorld() == null) {
            return;
        }
        Location center = base.clone().add(0.0D, ACTIVATION_PARTICLE_HEIGHT, 0.0D);
        List<Location> locations = new ArrayList<>(ACTIVATION_PARTICLE_POINTS);
        for (int index = 0; index < ACTIVATION_PARTICLE_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / ACTIVATION_PARTICLE_POINTS;
            locations.add(center.clone().add(
                    Math.cos(angle) * ACTIVATION_PARTICLE_RADIUS,
                    0.14D * Math.sin(angle * 2.0D),
                    Math.sin(angle) * ACTIVATION_PARTICLE_RADIUS
            ));
        }
        particleDisplayService.spawnForNearbyViewers(
                center,
                locations,
                SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST
        );
    }

    private boolean hasMagicTag(@NotNull SkillDefinition skill) {
        return skill.getTags().stream()
                .filter(tag -> tag != null)
                .map(String::trim)
                .anyMatch(MasterTagIds.CombatRole.MAGIC::equalsIgnoreCase);
    }

    private @NotNull UUID playerId(@NotNull AstPlayer player) {
        return player.getBukkit().getUniqueId();
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        if (context.learnedSkill() != null) {
            return context.learnedSkill().getLearnedSkillId().toString();
        }
        return context.skill().getId();
    }

    private @Nullable Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null || playerConfigurations.isEmpty()) {
            return null;
        }
        return playerConfigurations.values().stream()
                .max(Comparator.comparingDouble(Configuration::castTimeReductionPercent))
                .orElse(null);
    }

    private record Configuration(double castTimeReductionPercent) {
    }
}
