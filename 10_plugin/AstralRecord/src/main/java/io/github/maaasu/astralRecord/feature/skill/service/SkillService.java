package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
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
    private final AstralRecord plugin;
    private SkillOwnershipService ownershipService;
    private final Map<String, SkillDefinition> builtInDefinitions = new ConcurrentHashMap<>();

    /** 発動者ごと・スキルごとの cooldown 終了予定時刻（{@link System#currentTimeMillis()} 基準）。 */
    private final Map<UUID, Map<String, Long>> cooldownExpiryByCaster = new ConcurrentHashMap<>();
    private final Map<UUID, CastingSession> castingSessions = new ConcurrentHashMap<>();

    /**
     * 既定のリポジトリとレジストリでサービスを構築します。
     */
    public SkillService() {
        this(new SkillRepository(), new SkillRegistry(), AstralRecord.getInstance());
    }

    /**
     * テスト等で依存を注入するためのコンストラクタ。
     *
     * @param repository リポジトリ
     * @param registry   レジストリ
     */
    public SkillService(@NotNull SkillRepository repository, @NotNull SkillRegistry registry) {
        this(repository, registry, AstralRecord.getInstance());
    }

    /**
     * テスト等で依存を注入するためのコンストラクタ。
     *
     * @param repository リポジトリ
     * @param registry   レジストリ
     * @param plugin     scheduler 用プラグイン
     */
    public SkillService(@NotNull SkillRepository repository, @NotNull SkillRegistry registry, @Nullable AstralRecord plugin) {
        this.repository = repository;
        this.registry = registry;
        this.plugin = plugin;
    }

    /**
     * プレイヤー所持スキル判定サービスを設定します。
     *
     * @param ownershipService 所持スキル判定サービス
     */
    public void setOwnershipService(@NotNull SkillOwnershipService ownershipService) {
        this.ownershipService = ownershipService;
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
     * Plugin 側で定義する組み込みスキル定義を登録します。
     *
     * @param definition スキル定義
     */
    public void registerBuiltInDefinition(@NotNull SkillDefinition definition) {
        builtInDefinitions.put(definition.getId(), definition);
    }

    /**
     * Plugin 側で定義する組み込みスキル定義をまとめて登録します。
     *
     * @param definitions スキル定義一覧
     */
    public void registerBuiltInDefinitions(@NotNull Iterable<SkillDefinition> definitions) {
        for (SkillDefinition definition : definitions) {
            registerBuiltInDefinition(definition);
        }
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
        List<SkillSummary> summaries = List.of();
        try {
            summaries = repository.findAll();
        } catch (Exception e) {
            Logger.log(LogId.E_5801, e, "reloadDefinitions");
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

        for (SkillDefinition definition : builtInDefinitions.values()) {
            if (definition.getImplementationId().isBlank()) {
                Logger.log(LogId.W_5801, definition.getId(), definition.getImplementationId(),
                        "implementationId が空です");
                continue;
            }
            SkillExecutor executor = registry.getExecutor(definition.getImplementationId());
            if (executor == null) {
                Logger.log(LogId.W_5801, definition.getId(), definition.getImplementationId(),
                        "実行クラスが登録されていません");
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
        SkillResourceType resourceType = resolveResourceType(skill);
        double requiredCost = resolveResourceCost(skill);
        if (currentResource(caster, resourceType) < requiredCost) {
            return SkillCastResult.failure(resourceType.insufficientMessageId());
        }
        if (isOnCooldown(caster, skill.getId())) {
            return SkillCastResult.failure(PlayerMsgId.P_5802);
        }
        return SkillCastResult.success(requiredCost, skill.getCooldownTicks());
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
        if (requiresOwnershipCheck(caster, trigger) && !ownsSkill((PlayerSkillCaster) caster, skillId)) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5809);
            notifyIfFailed(caster, failure, skillId);
            return failure;
        }

        SkillCastResult guard = canCast(caster, definition);
        if (!guard.success()) {
            notifyIfFailed(caster, guard, skillId);
            return guard;
        }

        if (definition.getCastTimeTicks() > 0L) {
            return beginCast(caster, definition, trigger, castLocation, primaryTarget, targets);
        }

        return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets);
    }

    private @NotNull SkillCastResult executeSkillNow(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        SkillCastResult guard = canCast(caster, definition);
        if (!guard.success()) {
            notifyIfFailed(caster, guard, definition.getId());
            return guard;
        }

        playOnCastSound(castLocation, definition.getOnCastSound());

        SkillExecutor executor = registry.getExecutor(definition.getImplementationId());
        if (executor == null) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5804);
            notifyIfFailed(caster, failure, definition.getId());
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
            notifyIfFailed(caster, failure, definition.getId());
            return failure;
        }

        if (result.success()) {
            consumeResource(caster, resolveResourceType(definition), result.consumedMana());
            if (result.startedCooldownTicks() > 0L) {
                startCooldown(caster, definition.getId(), result.startedCooldownTicks());
            }
        } else {
            notifyIfFailed(caster, result, definition.getId());
        }
        return result;
    }

    private @NotNull SkillCastResult beginCast(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        if (!(caster instanceof PlayerSkillCaster playerCaster) || plugin == null) {
            return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets);
        }

        var astPlayer = playerCaster.player();
        Player player = astPlayer.getBukkit();
        if (astPlayer.isSkillCasting() || castingSessions.containsKey(player.getUniqueId())) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5810);
            notifyIfFailed(caster, failure, definition.getId());
            return failure;
        }

        long castTimeTicks = definition.getCastTimeTicks();
        astPlayer.setSkillCastingUntilMs(System.currentTimeMillis() + castTimeTicks * MS_PER_TICK);
        float originalWalkSpeed = player.getWalkSpeed();
        player.setWalkSpeed(clampWalkSpeed(originalWalkSpeed * 0.5F));

        BukkitRunnable runnable = new BukkitRunnable() {
            private long elapsedTicks = 0L;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    finishCast(player, astPlayer, false, playerCaster, definition, trigger, castLocation, primaryTarget, targets);
                    cancel();
                    return;
                }
                showCastActionBar(player, definition, castTimeTicks - elapsedTicks);
                elapsedTicks++;
                if (elapsedTicks >= castTimeTicks) {
                    finishCast(player, astPlayer, true, playerCaster, definition, trigger, castLocation, primaryTarget, targets);
                    cancel();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, 1L);
        castingSessions.put(player.getUniqueId(), new CastingSession(task, originalWalkSpeed));
        return SkillCastResult.success(0.0D, 0L);
    }

    private void finishCast(
            @NotNull Player player,
            @NotNull io.github.maaasu.astralRecord.feature.player.model.AstPlayer astPlayer,
            boolean execute,
            @NotNull PlayerSkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        CastingSession session = castingSessions.remove(player.getUniqueId());
        if (session != null) {
            player.setWalkSpeed(session.originalWalkSpeed());
        }
        astPlayer.setSkillCastingUntilMs(0L);
        if (execute) {
            executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets);
        }
    }

    private void showCastActionBar(@NotNull Player player, @NotNull SkillDefinition definition, long remainingTicks) {
        double seconds = Math.max(0.0D, remainingTicks / 20.0D);
        String message = PlayerMsgResource.format(
                PlayerMsgId.P_5811.getId(),
                definition.getName(),
                String.format(Locale.ROOT, "%.1f", seconds)
        );
        Component component = LegacyComponentSerializer.legacySection().deserialize(message);
        player.sendActionBar(component);
    }

    private boolean requiresOwnershipCheck(@NotNull SkillCaster caster, @NotNull SkillCastTrigger trigger) {
        return caster instanceof PlayerSkillCaster
            && trigger != SkillCastTrigger.AUTO_ATTACK
            && ownershipService != null;
    }

    private boolean ownsSkill(@NotNull PlayerSkillCaster caster, @NotNull String skillId) {
        return ownershipService == null || ownershipService.owns(caster.player(), skillId);
    }

    private float clampWalkSpeed(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
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

    /**
     * 進行中の詠唱を停止し、詠唱中に変更した歩行速度を戻します。
     */
    public void stop() {
        for (Map.Entry<UUID, CastingSession> entry : castingSessions.entrySet()) {
            entry.getValue().task().cancel();
            if (plugin != null) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    player.setWalkSpeed(entry.getValue().originalWalkSpeed());
                }
            }
        }
        castingSessions.clear();
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

    private @NotNull SkillResourceType resolveResourceType(@NotNull SkillDefinition skill) {
        return SkillResourceType.fromRaw(skill.getParams().get("resourceType"));
    }

    private double resolveResourceCost(@NotNull SkillDefinition skill) {
        Object raw = skill.getParams().get("resourceCost");
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return skill.getManaCost();
    }

    private double currentResource(@NotNull SkillCaster caster, @NotNull SkillResourceType resourceType) {
        return switch (resourceType) {
            case MANA -> caster.currentMana();
            case ENERGY -> caster.currentEnergy();
        };
    }

    private void consumeResource(
            @NotNull SkillCaster caster,
            @NotNull SkillResourceType resourceType,
            double amount
    ) {
        if (amount <= 0.0D) {
            return;
        }
        switch (resourceType) {
            case MANA -> caster.consumeMana(amount);
            case ENERGY -> caster.consumeEnergy(amount);
        }
    }

    private record CastingSession(@NotNull BukkitTask task, float originalWalkSpeed) {
    }
}
