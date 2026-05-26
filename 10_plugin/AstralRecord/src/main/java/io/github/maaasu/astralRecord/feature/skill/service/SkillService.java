package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキル定義の同期・発動前検証・実行クラス委譲を担うサービス。
 * <p>
 * 共通制御（要求レベル・MP・クールダウン・発動サウンド）はここで一元管理し、
 * 個別ロジックは {@link SkillExecutor} に委譲する。
 *
 * <p>本サービスは Plugin メインスレッドからの利用を想定する。
 * API 呼び出しを伴う {@link #reloadDefinitions()} は非同期実行することを推奨する。</p>
 */
public class SkillService {

    /** Minecraft 1 tick あたりのミリ秒（20 tick = 1 秒）。 */
    private static final long MS_PER_TICK = 50L;

    private final SkillRepository repository;
    private final SkillRegistry registry;

    /** 発動者ごと・スキルごとの cooldown 終了予定時刻（{@link System#currentTimeMillis()} 基準）。 */
    private final Map<UUID, Map<String, Long>> cooldownExpiryByCaster = new ConcurrentHashMap<>();

    /**
     * 既定のリポジトリとレジストリでサービスを構築します。
     */
    public SkillService() {
        this(new SkillRepository(), new SkillRegistry());
    }

    /**
     * テスト等で依存を注入するためのコンストラクタ。
     *
     * @param repository リポジトリ
     * @param registry   レジストリ
     */
    public SkillService(@NotNull SkillRepository repository, @NotNull SkillRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /**
     * 実行クラスを登録します。
     *
     * @param executor 実行クラス
     * @return 登録できたら {@code true}
     */
    public boolean registerExecutor(@NotNull SkillExecutor executor) {
        return registry.registerExecutor(executor);
    }

    /**
     * レジストリを返します。テストや別 feature からの参照用。
     *
     * @return スキルレジストリ
     */
    @NotNull
    public SkillRegistry registry() {
        return registry;
    }

    /**
     * スキル定義を API から再取得し、レジストリへ反映します。
     * <p>
     * {@code implementationId} が空、または対応する実行クラスが未登録のスキルはスキップし
     * {@link LogId#W_5801} を出力する。{@code validateParams} で例外が発生した場合も同様に
     * スキップする。失敗分は隔離し、有効分のみが新マップとしてスワップされる。
     *
     * @return 反映できた定義の件数
     */
    public int reloadDefinitions() {
        List<SkillSummary> summaries;
        try {
            summaries = repository.findAll();
        } catch (Exception e) {
            Logger.log(LogId.E_5801, e, "reloadDefinitions");
            return registry.definitionCount();
        }

        Map<String, SkillDefinition> next = new LinkedHashMap<>();
        for (SkillSummary summary : summaries) {
            SkillDefinition definition;
            try {
                definition = repository.findById(summary.getId());
            } catch (Exception e) {
                Logger.log(LogId.W_5801, summary.getId(), summary.getImplementationId(),
                        "詳細取得失敗: " + e.getMessage());
                continue;
            }
            if (definition == null) {
                Logger.log(LogId.W_5801, summary.getId(), summary.getImplementationId(),
                        "API から定義を取得できませんでした");
                continue;
            }
            if (definition.getImplementationId().isBlank()) {
                Logger.log(LogId.W_5801, definition.getId(), definition.getImplementationId(),
                        "implementationId が空です");
                continue;
            }
            SkillExecutor executor = registry.getExecutor(definition.getImplementationId());
            if (executor == null) {
                Logger.log(LogId.W_5801, definition.getId(), definition.getImplementationId(),
                        "実行クラスが未登録です");
                continue;
            }
            try {
                executor.validateParams(definition);
            } catch (SkillParameterException e) {
                Logger.log(LogId.W_5801, definition.getId(), definition.getImplementationId(),
                        "params 検証失敗: key=" + e.key() + ", message=" + e.getMessage());
                continue;
            }
            next.put(definition.getId(), definition);
        }

        registry.replaceDefinitions(next);
        Logger.log(LogId.I_5800, next.size());
        return next.size();
    }

    /**
     * 発動可否を判定します。失敗時は失敗理由を {@link SkillCastResult#messageId} で返します。
     *
     * @param caster 発動者
     * @param skill  スキル定義
     * @return 判定結果。発動可能なら {@link SkillCastResult#success(double, long)} 相当の成功結果
     */
    @NotNull
    public SkillCastResult canCast(@NotNull SkillCaster caster, @NotNull SkillDefinition skill) {
        if (caster.level() < skill.getRequiredLevel()) {
            return SkillCastResult.failure(PlayerMsgId.P_5800);
        }
        if (caster.currentMana() < skill.getManaCost()) {
            return SkillCastResult.failure(PlayerMsgId.P_5801);
        }
        if (isOnCooldown(caster, skill.getId())) {
            return SkillCastResult.failure(PlayerMsgId.P_5802);
        }
        return SkillCastResult.success(skill.getManaCost(), skill.getCooldownTicks());
    }

    /**
     * スキルを発動します。共通検証 → 実行クラス委譲 → 成功時の MP 消費・cooldown 開始を一括で行います。
     *
     * @param caster        発動者
     * @param skillId       スキル ID
     * @param trigger       発動契機
     * @param castLocation  発動位置
     * @param primaryTarget 主対象（任意）
     * @param targets       範囲・複数対象（変更不可）
     * @return 実行結果
     */
    @NotNull
    public SkillCastResult castSkill(
            @NotNull SkillCaster caster,
            @NotNull String skillId,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        SkillDefinition definition = registry.getDefinition(skillId);
        if (definition == null) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5803);
            notifyIfFailed(caster, failure, skillId);
            return failure;
        }

        SkillCastResult guard = canCast(caster, definition);
        if (!guard.success()) {
            notifyIfFailed(caster, guard, skillId);
            return guard;
        }

        if (definition.getCastTimeTicks() > 0L) {
            // 詠唱開始ハンドリングは将来拡張対象。現状は通過のみで実体処理は executor 側に委ねる。
        }

        playOnCastSound(castLocation, definition.getOnCastSound());

        SkillExecutor executor = registry.getExecutor(definition.getImplementationId());
        if (executor == null) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5804);
            notifyIfFailed(caster, failure, skillId);
            return failure;
        }

        SkillCastContext context = new SkillCastContext(
                definition,
                caster,
                primaryTarget,
                targets,
                castLocation,
                caster.statusSnapshot(),
                trigger,
                Instant.now()
        );

        SkillCastResult result;
        try {
            result = executor.cast(context);
        } catch (RuntimeException e) {
            Logger.log(LogId.E_5802, e, definition.getId(), definition.getImplementationId());
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5805);
            notifyIfFailed(caster, failure, skillId);
            return failure;
        }

        if (result.success()) {
            caster.consumeMana(result.consumedMana());
            if (result.startedCooldownTicks() > 0L) {
                startCooldown(caster, definition.getId(), result.startedCooldownTicks());
            }
        } else {
            notifyIfFailed(caster, result, skillId);
        }
        return result;
    }

    /**
     * 発動者・スキルの cooldown を開始します。
     *
     * @param caster        発動者
     * @param skillId       スキル ID
     * @param cooldownTicks クールダウン（tick）
     */
    public void startCooldown(@NotNull SkillCaster caster, @NotNull String skillId, long cooldownTicks) {
        if (cooldownTicks <= 0L) return;
        long expiry = System.currentTimeMillis() + cooldownTicks * MS_PER_TICK;
        cooldownExpiryByCaster
                .computeIfAbsent(caster.casterId(), id -> new ConcurrentHashMap<>())
                .put(normalize(skillId), expiry);
    }

    /**
     * 発動者・スキルの cooldown が残っているかを判定します。
     *
     * @param caster  発動者
     * @param skillId スキル ID
     * @return cooldown 中なら {@code true}
     */
    public boolean isOnCooldown(@NotNull SkillCaster caster, @NotNull String skillId) {
        Map<String, Long> byCaster = cooldownExpiryByCaster.get(caster.casterId());
        if (byCaster == null) return false;
        Long expiry = byCaster.get(normalize(skillId));
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            byCaster.remove(normalize(skillId));
            return false;
        }
        return true;
    }

    private void notifyIfFailed(@NotNull SkillCaster caster, @NotNull SkillCastResult result, @NotNull String skillId) {
        if (result.success()) return;
        PlayerMsgId messageId = result.messageId();
        if (messageId == null) return;
        if (messageId == PlayerMsgId.P_5803 || messageId == PlayerMsgId.P_5804) {
            caster.notify(messageId, skillId);
        } else {
            caster.notify(messageId);
        }
    }

    private void playOnCastSound(@NotNull Location location, @Nullable String soundKey) {
        if (soundKey == null || soundKey.isBlank()) return;
        if (location.getWorld() == null) return;
        // YAML 上は "entity.player.attack.sweep" のようなドット区切りで指定されるため
        // String オーバーロード経由でそのまま渡し、サウンドキー解決はサーバー側に委ねる。
        location.getWorld().playSound(location, soundKey, 1.0f, 1.0f);
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
