package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.MobSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * スキル定義の同期・発動前検証・実行クラス委譲を担うサービス。
 * <p>
 * 共通制御（要求レベル・消費リソース・クールダウン・発動サウンド）はここで一元管理し、
 * 個別ロジックは {@link SkillExecutor} に委譲する。
 *
 * <p>本サービスは Plugin メインスレッドからの利用を想定する。
 * API 呼び出しを伴う {@link #reloadDefinitions()} は非同期実行することを推奨する。</p>
 */
public class SkillService {

    /** 全武器種の通常攻撃が共有するクールダウンIDです。 */
    public static final String WEAPON_NORMAL_ATTACK_COOLDOWN_ID = "weapon_normal_attack";
    /** Minecraft 1 tick あたりのミリ秒（20 tick = 1 秒）。 */
    private static final long MS_PER_TICK = 50L;
    /** 詠唱中の移動速度補正を識別する属性 modifier のキーです。 */
    private static final NamespacedKey CAST_MOVEMENT_SPEED_MODIFIER_KEY =
            new NamespacedKey("astralrecord", "skill_casting_slowdown");
    /** 詠唱中は通常の移動速度を半分にします。 */
    private static final double CAST_MOVEMENT_SPEED_MODIFIER_AMOUNT = -0.5D;

    private final SkillRepository repository;
    private final SkillRegistry registry;
    private final AstralRecord plugin;
    private SkillOwnershipService ownershipService;
    private SkillPermissionService permissionService;
    private LearnedSkillResolver learnedSkillResolver;
    private ConditionService conditionService;
    private PlayerHudService playerHudService;
    private BiConsumer<AstPlayer, String> playerCastSuccessListener = (player, skillId) -> { };
    private BiConsumer<AstPlayer, String> playerSkillUseListener = (player, skillId) -> { };
    private final SkillCastFeedback castFeedback = new SkillCastFeedback();
    private final Map<String, SkillDefinition> builtInDefinitions = new ConcurrentHashMap<>();

    /** 発動者ごと・共有キーごとの cooldown 終了時刻と、発動時に採用した総tick。 */
    private final Map<UUID, Map<String, CooldownState>> cooldownExpiryByCaster = new ConcurrentHashMap<>();
    private final Map<UUID, CastingSession> castingSessions = new ConcurrentHashMap<>();

    /**
     * プレイヤーのスキル実行成功を受け取る listener を設定します。
     *
     * @param listener プレイヤーとスキル ID を受け取る listener
     */
    public void setPlayerCastSuccessListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.playerCastSuccessListener = listener;
    }

    /**
     * プレイヤーがスキル発動を開始した時点の listener を設定します。
     * 詠唱時間を持つスキルでも、詠唱開始直後に一度だけ呼び出されます。
     *
     * @param listener プレイヤーとスキル ID を受け取る listener
     */
    public void setPlayerSkillUseListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.playerSkillUseListener = listener;
    }

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

    public void setPermissionService(@NotNull SkillPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public void setLearnedSkillResolver(@NotNull LearnedSkillResolver learnedSkillResolver) {
        this.learnedSkillResolver = learnedSkillResolver;
    }

    /**
     * 状態異常サービスを設定します。
     *
     * @param conditionService 状態異常サービス
     */
    public void setConditionService(@NotNull ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * 詠唱中 ActionBar を管理する HUD サービスを設定します。
     *
     * @param playerHudService HUD サービス
     */
    public void setPlayerHudService(@NotNull PlayerHudService playerHudService) {
        this.playerHudService = playerHudService;
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
     * 共通項目、実行クラス、実装固有 params のいずれかが不正なスキルは
     * {@link LogId#W_5801} を出力してその定義だけを隔離する。
     * 有効分のみが新マップとしてスワップされる。
     *
     * @return 検証を通過した immutable な定義スナップショット
     */
    public @NotNull Map<String, SkillDefinition> loadDefinitions() {
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
            addValidatedDefinition(next, definition);
        }

        for (SkillDefinition definition : builtInDefinitions.values()) {
            addValidatedDefinition(next, definition);
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(next));
    }

    private void addValidatedDefinition(
            @NotNull Map<String, SkillDefinition> definitions,
            @NotNull SkillDefinition definition
    ) {
        String skillId = definition.getId();
        String implementationId = definition.getImplementationId();
        try {
            validateCommonDefinition(definition);
            SkillExecutor executor = registry.getExecutor(implementationId);
            if (executor == null) {
                throw new IllegalArgumentException("実行クラスが未登録です");
            }

            executor.validateParams(definition);
            definitions.put(skillId, withResolvedDefinition(definition, executor));
        } catch (RuntimeException e) {
            Logger.log(LogId.W_5801, skillId, implementationId, validationFailureReason(e));
        }
    }

    private void validateCommonDefinition(@NotNull SkillDefinition definition) {
        if (definition.getId().isBlank()) {
            throw new IllegalArgumentException("id が空です");
        }
        if (definition.getImplementationId().isBlank()) {
            throw new IllegalArgumentException("implementationId が空です");
        }
        if (definition.getCooldownTicks() < 0L) {
            throw new IllegalArgumentException("cooldownTicks は 0 以上で指定してください");
        }
        if (definition.getCastTimeTicks() < 0L) {
            throw new IllegalArgumentException("castTimeTicks は 0 以上で指定してください");
        }
        if (definition.getRequiredLevel() < 0) {
            throw new IllegalArgumentException("requiredLevel は 0 以上で指定してください");
        }
        if (!Double.isFinite(definition.getManaCost()) || definition.getManaCost() < 0.0D) {
            throw new IllegalArgumentException("manaCost は有限の 0 以上で指定してください");
        }

        resolveResourceType(definition);
        double resourceCost = resolveResourceCost(definition);
        if (!Double.isFinite(resourceCost) || resourceCost < 0.0D) {
            throw new IllegalArgumentException("resourceCost は有限の 0 以上で指定してください");
        }
        if (definition.getCooldownId() != null && definition.getCooldownId().isBlank()) {
            throw new IllegalArgumentException("cooldownId は未指定または空でない文字列にしてください");
        }
        if (definition.getMaxLevel() < 1) {
            throw new IllegalArgumentException("maxLevel は 1 以上で指定してください");
        }

        Set<Integer> levelNumbers = new HashSet<>();
        long resolvedCooldown = definition.getCooldownTicks();
        long resolvedCastTime = definition.getCastTimeTicks();
        double resolvedCost = resourceCost;
        for (var level : definition.getLevels()) {
            if (level.getLevel() < 2 || level.getLevel() > definition.getMaxLevel()) {
                throw new IllegalArgumentException("levels.level は 2 から maxLevel の範囲で指定してください");
            }
            if (!levelNumbers.add(level.getLevel())) {
                throw new IllegalArgumentException("levels.level が重複しています: " + level.getLevel());
            }
            if (!Double.isFinite(level.getResourceCostDelta())) {
                throw new IllegalArgumentException("levels.resourceCostDelta は有限値で指定してください");
            }
            for (Map.Entry<String, Double> delta : level.getParamDeltas().entrySet()) {
                if (delta.getKey().isBlank() || !Double.isFinite(delta.getValue())) {
                    throw new IllegalArgumentException("levels.paramDeltas は空でないkeyと有限値で指定してください");
                }
            }
            for (var modifier : level.getStatusModifiers()) {
                if (StatusType.fromId(modifier.getStatus().trim().toUpperCase(Locale.ROOT)) == null
                    || !Double.isFinite(modifier.getValue())) {
                    throw new IllegalArgumentException("levels.statusModifiers に不正なstatusまたはvalueがあります");
                }
            }
            try {
                resolvedCooldown = Math.addExact(resolvedCooldown, level.getCooldownTicksDelta());
                resolvedCastTime = Math.addExact(resolvedCastTime, level.getCastTimeTicksDelta());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("levels のtick差分が範囲外です", e);
            }
            resolvedCost += level.getResourceCostDelta();
            if (resolvedCooldown < 0L || resolvedCastTime < 0L
                || !Double.isFinite(resolvedCost) || resolvedCost < 0.0D) {
                throw new IllegalArgumentException("各レベル適用後のcooldown/castTime/resourceCostは0以上にしてください");
            }
        }
        if (levelNumbers.size() != definition.getMaxLevel() - 1) {
            throw new IllegalArgumentException("levels は Lv.2 から maxLevel まで各レベルを定義してください");
        }

        Set<Integer> sigilSlotLevels = new HashSet<>();
        int previousSlots = -1;
        for (var slot : definition.getSigilSlotsByLevel()) {
            if (slot.getLevel() < 1 || slot.getLevel() > definition.getMaxLevel()
                || slot.getSlots() < 0 || !sigilSlotLevels.add(slot.getLevel())) {
                throw new IllegalArgumentException("sigilSlotsByLevel に不正なlevel/slotsまたは重複があります");
            }
            if (slot.getSlots() < previousSlots) {
                throw new IllegalArgumentException("sigilSlotsByLevel.slots はレベルとともに減少できません");
            }
            previousSlots = slot.getSlots();
        }
        Set<String> allowedSigils = new HashSet<>();
        for (String sigilId : definition.getAllowedSigilIds()) {
            if (sigilId.isBlank() || !allowedSigils.add(sigilId.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("allowedSigilIds に空値または重複があります");
            }
        }
    }

    private @NotNull String validationFailureReason(@NotNull RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (exception instanceof SkillParameterException parameterException) {
            return "params 検証失敗: key=" + parameterException.key() + ", message=" + message;
        }
        return "定義検証失敗: " + message;
    }

    /**
     * API から定義を読み込み、レジストリ交換をメインスレッド上で順序付けます。
     *
     * @return 読み込みと検証を通過した定義件数
     */
    public int reloadDefinitions() {
        Map<String, SkillDefinition> next = loadDefinitions();
        if (plugin != null && !Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> replaceDefinitions(next));
        } else {
            replaceDefinitions(next);
        }
        return next.size();
    }

    /**
     * 検証済み定義スナップショットをレジストリへ公開します。
     * Bukkit 実行環境ではメインスレッドから呼び出してください。
     *
     * @param definitions 検証済み定義
     */
    public void replaceDefinitions(@NotNull Map<String, SkillDefinition> definitions) {
        if (plugin != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Skill definitions must be published on the primary thread");
        }
        registry.replaceDefinitions(definitions);
        Logger.log(LogId.I_5800, definitions.size());
    }

    /**
     * 発動可否を判定します。失敗時は失敗理由を {@link SkillCastResult#messageId} で返します。
     *
     * @param caster 発動者
     * @param skill  スキル定義
     * @return 判定結果。発動可能なら {@link SkillCastResult#succeeded()} の成功結果
     */
    @NotNull
    public SkillCastResult canCast(@NotNull SkillCaster caster, @NotNull SkillDefinition skill) {
        return canCast(caster, skill, caster.statusSnapshot());
    }

    /** 習得個体のレベル・シジル補正を含めた発動可否を返します。 */
    public @NotNull SkillCastResult canCast(
        @NotNull SkillCaster caster,
        @NotNull ResolvedLearnedSkill resolved
    ) {
        return canCast(
            caster,
            resolved.definition(),
            caster.statusSnapshot().withFlatBonuses(resolved.statusBonuses())
        );
    }

    /** 現在のマスタと習得個体から表示・発動共通の解決結果を作成します。 */
    public @Nullable ResolvedLearnedSkill resolveLearnedSkill(@NotNull LearnedSkillInstance learned) {
        SkillDefinition base = registry.getDefinition(learned.getSkillId());
        if (base == null) return null;
        if (learnedSkillResolver != null) return learnedSkillResolver.resolve(base, learned);
        return new ResolvedLearnedSkill(learned, base, Map.of(), learned.getSigils().stream()
            .map(io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil::getSigilId)
            .collect(java.util.stream.Collectors.toSet()));
    }

    /** 習得個体に適用される短縮を含むクールダウンtick数を返します。 */
    public long resolvedCooldownTicks(
        @NotNull SkillCaster caster,
        @NotNull ResolvedLearnedSkill resolved
    ) {
        StatusSnapshot effective = caster.statusSnapshot().withFlatBonuses(resolved.statusBonuses());
        return resolveCooldownTicks(effective, resolved.definition().getCooldownTicks());
    }

    private @NotNull SkillCastResult canCast(
        @NotNull SkillCaster caster,
        @NotNull SkillDefinition skill,
        @NotNull StatusSnapshot statusSnapshot
    ) {
        if (skill.getKind() == SkillKind.PASSIVE) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        AstEntity conditionTarget = toAstEntity(caster);
        if (conditionService != null && conditionTarget != null && !conditionService.canCastSkill(conditionTarget)) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        if (caster.level() < skill.getRequiredLevel()) {
            return SkillCastResult.failure(PlayerMsgId.P_5800);
        }
        SkillResourceType resourceType = resolveResourceType(skill);
        double requiredCost = resolveResourceCost(statusSnapshot, skill);
        if (currentResource(caster, resourceType) < requiredCost) {
            return SkillCastResult.failure(resourceType.insufficientMessageId());
        }
        if (isOnCooldown(caster, cooldownKey(skill))) {
            return SkillCastResult.failure(PlayerMsgId.P_5802);
        }
        if (isCasting(caster)) {
            return SkillCastResult.failure(PlayerMsgId.P_5810);
        }
        return SkillCastResult.succeeded();
    }

    /**
     * 発動者が進行中の詠唱を持つか判定します。
     *
     * @param caster 発動者
     * @return 詠唱中の場合は true
     */
    private boolean isCasting(@NotNull SkillCaster caster) {
        if (castingSessions.containsKey(caster.casterId())) {
            return true;
        }
        if (caster instanceof PlayerSkillCaster playerCaster) {
            return playerCaster.player().isSkillCasting();
        }
        if (caster instanceof MobSkillCaster mobCaster) {
            return mobCaster.mob().isSkillCasting();
        }
        return false;
    }

    private @Nullable AstEntity toAstEntity(@NotNull SkillCaster caster) {
        if (caster instanceof PlayerSkillCaster playerCaster) {
            return AstEntity.player(playerCaster.player());
        }
        if (caster instanceof MobSkillCaster mobCaster) {
            return AstEntity.mob(mobCaster.mob());
        }
        return null;
    }

    /**
     * スキルを発動します。共通検証 → 実行クラス委譲 → 成功時のリソース消費・cooldown 開始を一括で行います。
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
        PlayerMsgId ownershipFailure = requiresOwnershipCheck(caster, trigger)
            ? ownershipFailure((PlayerSkillCaster) caster, skillId)
            : null;
        if (ownershipFailure != null) {
            SkillCastResult failure = SkillCastResult.failure(ownershipFailure);
            notifyIfFailed(caster, failure, skillId);
            return failure;
        }

        SkillCastResult guard = canCast(caster, definition);
        if (!guard.success()) {
            notifyIfFailed(caster, guard, skillId);
            return guard;
        }

        notifyPlayerSkillUse(caster, definition);

        if (resolveCastTimeTicks(caster, definition) > 0L) {
            return beginCast(caster, definition, trigger, castLocation, primaryTarget, targets);
        }

        return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets);
    }

    /**
     * 習得個体 UUID からスキルを発動します。所持・使用許可を分離して検証し、
     * 個体のレベル差分と装着済みシジルをこの発動だけへ適用します。
     */
    public @NotNull SkillCastResult castLearnedSkill(
        @NotNull PlayerSkillCaster caster,
        @NotNull String learnedSkillId,
        @NotNull SkillCastTrigger trigger,
        @NotNull Location castLocation,
        @Nullable LivingEntity primaryTarget,
        @NotNull List<LivingEntity> targets
    ) {
        LearnedSkillInstance learned = ownershipService == null
            ? null
            : ownershipService.findInstance(caster.player(), learnedSkillId);
        if (learned == null) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5809);
            notifyIfFailed(caster, failure, learnedSkillId);
            return failure;
        }
        if (permissionService != null && !permissionService.isPermitted(caster.player(), learned.getSkillId())) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5863);
            notifyIfFailed(caster, failure, learned.getSkillId());
            return failure;
        }

        ResolvedLearnedSkill resolved = resolveLearnedSkill(learned);
        if (resolved == null) {
            SkillCastResult failure = SkillCastResult.failure(PlayerMsgId.P_5803);
            notifyIfFailed(caster, failure, learned.getSkillId());
            return failure;
        }
        LearnedCast runtime = new LearnedCast(
            learned,
            caster.statusSnapshot().withFlatBonuses(resolved.statusBonuses()),
            resolved.sigilIds()
        );
        SkillDefinition definition = resolved.definition();
        SkillCastResult guard = canCast(caster, definition, runtime.statusSnapshot());
        if (!guard.success()) {
            notifyIfFailed(caster, guard, definition.getId());
            return guard;
        }
        notifyPlayerSkillUse(caster, definition);
        if (resolveCastTimeTicks(caster, definition, runtime.statusSnapshot()) > 0L) {
            return beginCast(caster, definition, trigger, castLocation, primaryTarget, targets, runtime);
        }
        return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets, runtime);
    }

    private @NotNull SkillCastResult executeSkillNow(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets, null);
    }

    private @NotNull SkillCastResult executeSkillNow(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets,
            @Nullable LearnedCast runtime
    ) {
        StatusSnapshot effectiveStatus = runtime == null ? caster.statusSnapshot() : runtime.statusSnapshot();
        SkillCastResult guard = canCast(caster, definition, effectiveStatus);
        if (!guard.success()) {
            notifyIfFailed(caster, guard, definition.getId());
            return guard;
        }

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
                effectiveStatus,
                trigger,
                Instant.now(),
                runtime == null ? null : runtime.learnedSkill(),
                runtime == null ? Set.of() : runtime.sigilIds()
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
            playOnCastSound(castLocation, definition.getOnCastSound());
            consumeResource(caster, resolveResourceType(definition), resolveResourceCost(effectiveStatus, definition));
            if (definition.getCooldownTicks() > 0L) {
                startCooldown(
                    caster,
                    cooldownKey(definition),
                    definition.getId(),
                    resolveCooldownTicks(effectiveStatus, definition.getCooldownTicks())
                );
            }
            if (caster instanceof PlayerSkillCaster playerCaster) {
                playerCastSuccessListener.accept(playerCaster.player(), definition.getId());
            }
        } else {
            notifyIfFailed(caster, result, definition.getId());
        }
        return result;
    }

    private void notifyPlayerSkillUse(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition
    ) {
        if (caster instanceof PlayerSkillCaster playerCaster) {
            playerSkillUseListener.accept(playerCaster.player(), definition.getId());
        }
    }

    private @NotNull SkillCastResult beginCast(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        return beginCast(caster, definition, trigger, castLocation, primaryTarget, targets, null);
    }

    private @NotNull SkillCastResult beginCast(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets,
            @Nullable LearnedCast runtime
    ) {
        if (plugin == null) {
            return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets, runtime);
        }

        if (caster instanceof MobSkillCaster mobCaster) {
            return beginMobCast(mobCaster, definition, trigger, castLocation, primaryTarget, targets);
        }

        if (!(caster instanceof PlayerSkillCaster playerCaster)) {
            return executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets, runtime);
        }

        var astPlayer = playerCaster.player();
        Player player = astPlayer.getBukkit();

        StatusSnapshot effectiveStatus = runtime == null ? caster.statusSnapshot() : runtime.statusSnapshot();
        long castTimeTicks = resolveCastTimeTicks(playerCaster, definition, effectiveStatus);
        astPlayer.setSkillCastingUntilMs(System.currentTimeMillis() + castTimeTicks * MS_PER_TICK);
        AttributeInstance movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        Runnable movementSpeedCleanup = movementSpeed == null
                ? () -> { }
                : applyCastMovementSpeedModifier(movementSpeed);
        AtomicLong remainingCastTicks = new AtomicLong(castTimeTicks);
        startPlayerCastFeedback(astPlayer, definition, castTimeTicks, remainingCastTicks::get);

        BukkitRunnable runnable = new BukkitRunnable() {
            private long elapsedTicks = 0L;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    finishCast(player, astPlayer, false, playerCaster, definition, trigger, castLocation, primaryTarget, targets, runtime);
                    cancel();
                    return;
                }
                if (conditionService != null
                        && !conditionService.canCastSkill(AstEntity.player(astPlayer))) {
                    finishCast(player, astPlayer, false, playerCaster, definition, trigger, castLocation, primaryTarget, targets, runtime);
                    cancel();
                    return;
                }
                long remainingTicks = Math.max(0L, castTimeTicks - elapsedTicks);
                remainingCastTicks.set(remainingTicks);
                refreshPlayerCastFeedback(astPlayer, definition, castTimeTicks, remainingTicks);
                if (castFeedback.shouldPlaySound(elapsedTicks)) {
                    castFeedback.playSound(player, elapsedTicks, castTimeTicks);
                }
                elapsedTicks++;
                if (elapsedTicks >= castTimeTicks) {
                    finishCast(player, astPlayer, true, playerCaster, definition, trigger, castLocation, primaryTarget, targets, runtime);
                    cancel();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, 1L);
        castingSessions.put(player.getUniqueId(), new CastingSession(task, () -> {
            astPlayer.setSkillCastingUntilMs(0L);
            movementSpeedCleanup.run();
            stopPlayerCastFeedback(astPlayer);
        }));
        return SkillCastResult.succeeded();
    }

    /**
     * 詠唱中だけ移動速度を半分にする一時 modifier を適用します。
     * <p>
     * 開始時の速度値を保存して復元するのではなく modifier だけを除去するため、
     * 詠唱中にステータスや装備が更新されても更新後の速度を維持できます。
     *
     * @param attribute プレイヤーの移動速度属性
     * @return 詠唱終了時に適用した modifier だけを除去する cleanup
     */
    static @NotNull Runnable applyCastMovementSpeedModifier(@NotNull AttributeInstance attribute) {
        if (attribute.getModifier(CAST_MOVEMENT_SPEED_MODIFIER_KEY) != null) {
            attribute.removeModifier(CAST_MOVEMENT_SPEED_MODIFIER_KEY);
        }
        attribute.addTransientModifier(new AttributeModifier(
                CAST_MOVEMENT_SPEED_MODIFIER_KEY,
                CAST_MOVEMENT_SPEED_MODIFIER_AMOUNT,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1
        ));
        return () -> {
            if (attribute.getModifier(CAST_MOVEMENT_SPEED_MODIFIER_KEY) != null) {
                attribute.removeModifier(CAST_MOVEMENT_SPEED_MODIFIER_KEY);
            }
        };
    }

    private @NotNull SkillCastResult beginMobCast(
            @NotNull MobSkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        long castTimeTicks = resolveCastTimeTicks(caster, definition);
        caster.mob().startSkillCasting(SkillPresentationUtil.legacyName(definition, definition.getId()), castTimeTicks);
        playMobCastStartSound(castLocation, definition);

        BukkitRunnable runnable = new BukkitRunnable() {
            private long elapsedTicks = 0L;

            @Override
            public void run() {
                if (caster.mob().state() == io.github.maaasu.astralRecord.feature.mob.model.MobState.DEAD) {
                    finishMobCast(caster, false, definition, trigger, castLocation, primaryTarget, targets);
                    cancel();
                    return;
                }
                if (conditionService != null
                        && !conditionService.canCastSkill(AstEntity.mob(caster.mob()))) {
                    finishMobCast(caster, false, definition, trigger, castLocation, primaryTarget, targets);
                    cancel();
                    return;
                }

                long remainingTicks = Math.max(0L, castTimeTicks - elapsedTicks);
                caster.mob().updateSkillCastingRemaining(remainingTicks);
                elapsedTicks++;
                if (elapsedTicks >= castTimeTicks) {
                    finishMobCast(caster, true, definition, trigger, castLocation, primaryTarget, targets);
                    cancel();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, 1L);
        castingSessions.put(caster.casterId(), new CastingSession(task, caster.mob()::clearSkillCasting));
        return SkillCastResult.succeeded();
    }

    private long resolveCastTimeTicks(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition
    ) {
        return resolveCastTimeTicks(caster, definition, caster.statusSnapshot());
    }

    private long resolveCastTimeTicks(
            @NotNull SkillCaster caster,
            @NotNull SkillDefinition definition,
            @NotNull StatusSnapshot statusSnapshot
    ) {
        double multiplier = 1.0D;
        double reduction = Math.max(0.0D, statusSnapshot.rollValue(StatusType.CAST_TIME_REDUCTION));
        multiplier *= Math.max(0.0D, 1.0D - reduction / 100.0D);
        if (conditionService != null) {
            if (caster instanceof PlayerSkillCaster playerCaster) {
                multiplier *= conditionService.castTimeMultiplier(AstEntity.player(playerCaster.player()));
            } else if (caster instanceof MobSkillCaster mobCaster) {
                multiplier *= conditionService.castTimeMultiplier(AstEntity.mob(mobCaster.mob()));
            }
        }
        return Math.max(0L, (long) Math.ceil(definition.getCastTimeTicks() * multiplier));
    }

    private void finishMobCast(
            @NotNull MobSkillCaster caster,
            boolean execute,
            @NotNull SkillDefinition definition,
            @NotNull SkillCastTrigger trigger,
            @NotNull Location castLocation,
            @Nullable LivingEntity primaryTarget,
            @NotNull List<LivingEntity> targets
    ) {
        CastingSession session = castingSessions.remove(caster.casterId());
        if (session != null) {
            session.cleanup().run();
        } else {
            caster.mob().clearSkillCasting();
        }
        if (execute) {
            executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets);
        }
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
            @NotNull List<LivingEntity> targets,
            @Nullable LearnedCast runtime
    ) {
        CastingSession session = castingSessions.remove(player.getUniqueId());
        if (session != null) {
            session.cleanup().run();
        } else {
            astPlayer.setSkillCastingUntilMs(0L);
        }
        if (execute && canStillUseAtCastCompletion(caster, definition, trigger, runtime)) {
            executeSkillNow(caster, definition, trigger, castLocation, primaryTarget, targets, runtime);
        }
    }

    private boolean canStillUseAtCastCompletion(
        @NotNull PlayerSkillCaster caster,
        @NotNull SkillDefinition definition,
        @NotNull SkillCastTrigger trigger,
        @Nullable LearnedCast runtime
    ) {
        if (!requiresOwnershipCheck(caster, trigger)) {
            return true;
        }
        PlayerMsgId denial;
        if (runtime != null) {
            LearnedSkillInstance learned = runtime.learnedSkill();
            if (ownershipService == null || !ownershipService.ownsInstance(caster.player(), learned.getLearnedSkillId())) {
                denial = PlayerMsgId.P_5809;
            } else if (permissionService != null && !permissionService.isPermitted(caster.player(), learned.getSkillId())) {
                denial = PlayerMsgId.P_5863;
            } else {
                denial = null;
            }
        } else {
            denial = ownershipFailure(caster, definition.getId());
        }
        if (denial != null) {
            notifyIfFailed(caster, SkillCastResult.failure(denial), definition.getId());
            return false;
        }
        return true;
    }

    /**
     * 詠唱中 ActionBar を HUD の primary renderer として登録し、即時描画します。
     *
     * @param astPlayer 対象プレイヤー
     * @param definition 対象スキル定義
     * @param castTimeTicks 詠唱の総 tick 数
     * @param remainingTicks 現在の残り tick 数を返す supplier
     */
    private void startPlayerCastFeedback(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillDefinition definition,
            long castTimeTicks,
            @NotNull LongSupplier remainingTicks
    ) {
        if (playerHudService == null) {
            return;
        }
        playerHudService.setPrimaryActionBarRenderer(
                astPlayer.getBukkit().getUniqueId(),
                ignored -> castFeedback.createActionBar(
                        definition,
                        castTimeTicks,
                        remainingTicks.getAsLong()
                )
        );
        playerHudService.refreshActionBar(astPlayer);
    }

    /**
     * 現在の詠唱進捗を ActionBar へ再描画します。
     * HUD サービス未設定時は対象プレイヤーへ直接描画します。
     *
     * @param astPlayer 対象プレイヤー
     * @param definition 対象スキル定義
     * @param castTimeTicks 詠唱の総 tick 数
     * @param remainingTicks 残り tick 数
     */
    private void refreshPlayerCastFeedback(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillDefinition definition,
            long castTimeTicks,
            long remainingTicks
    ) {
        if (playerHudService != null) {
            playerHudService.refreshActionBar(astPlayer);
            return;
        }
        astPlayer.getBukkit().sendActionBar(
                castFeedback.createActionBar(definition, castTimeTicks, remainingTicks)
        );
    }

    /**
     * 詠唱中 ActionBar を解除し、通常 HUD を即時再描画します。
     *
     * @param astPlayer 対象プレイヤー
     */
    private void stopPlayerCastFeedback(@NotNull AstPlayer astPlayer) {
        if (playerHudService == null) {
            return;
        }
        playerHudService.clearPrimaryActionBarRenderer(astPlayer.getBukkit().getUniqueId());
        playerHudService.refreshActionBar(astPlayer);
    }

    private boolean requiresOwnershipCheck(@NotNull SkillCaster caster, @NotNull SkillCastTrigger trigger) {
        return caster instanceof PlayerSkillCaster
            && trigger != SkillCastTrigger.AUTO_ATTACK
            && ownershipService != null;
    }

    private @Nullable PlayerMsgId ownershipFailure(@NotNull PlayerSkillCaster caster, @NotNull String skillId) {
        if (ownershipService != null && !ownershipService.owns(caster.player(), skillId)) {
            return PlayerMsgId.P_5809;
        }
        if (permissionService != null && !permissionService.isPermitted(caster.player(), skillId)) {
            return PlayerMsgId.P_5863;
        }
        return null;
    }

    /**
     * 発動者・スキルの cooldown を開始します。
     *
     * @param caster        発動者
     * @param cooldownId    クールダウンキー（スキルIDや統合クールダウンID）
     * @param displaySkillId 表示用スキル ID
     * @param cooldownTicks クールダウン（tick）
     */
    private void startCooldown(
            @NotNull SkillCaster caster,
            @NotNull String cooldownId,
            @NotNull String displaySkillId,
            long cooldownTicks
    ) {
        if (cooldownTicks <= 0L) return;
        long now = System.currentTimeMillis();
        long expiry = now + cooldownTicks * MS_PER_TICK;
        cooldownExpiryByCaster
                .computeIfAbsent(caster.casterId(), id -> new ConcurrentHashMap<>())
                .put(normalize(cooldownId), new CooldownState(
                        expiry,
                        cooldownTicks,
                        now,
                        normalize(displaySkillId)
                ));
    }

    /**
     * 発動者・スキルの cooldown を開始します。
     *
     * @param caster        発動者
     * @param skillId       スキル ID
     * @param cooldownTicks クールダウン（tick）
     */
    public void startCooldown(@NotNull SkillCaster caster, @NotNull String skillId, long cooldownTicks) {
        startCooldown(caster, skillId, skillId, cooldownTicks);
    }

    /**
     * 発動者の有効なクールダウンを開始時刻の降順で取得します。
     *
     * @param caster 発動者
     * @return 有効中クールダウン一覧（開始時刻の降順）
     */
    public @NotNull List<ActiveCooldown> getActiveCooldowns(@NotNull SkillCaster caster) {
        return getActiveCooldowns(caster.casterId());
    }

    /**
     * 発動者の有効なクールダウンを開始時刻の降順で取得します。
     *
     * @param casterId 発動者 UUID
     * @return 有効中クールダウン一覧（開始時刻の降順）
     */
    public @NotNull List<ActiveCooldown> getActiveCooldowns(@NotNull UUID casterId) {
        Map<String, CooldownState> byCaster = cooldownExpiryByCaster.get(casterId);
        if (byCaster == null || byCaster.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<ActiveCooldown> activeCooldowns = new ArrayList<>();
        Iterator<Map.Entry<String, CooldownState>> iterator = byCaster.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CooldownState> entry = iterator.next();
            CooldownState state = entry.getValue();
            if (state == null || state.expiryMillis() <= now) {
                iterator.remove();
                continue;
            }
            long remainingTicks = Math.max(1L, (state.expiryMillis() - now + MS_PER_TICK - 1L) / MS_PER_TICK);
            activeCooldowns.add(new ActiveCooldown(
                    entry.getKey(),
                    state.displaySkillId(),
                    resolveSkillDisplayName(state.displaySkillId()),
                    remainingTicks,
                    state.durationTicks(),
                    state.startedAtMillis()
            ));
        }
        activeCooldowns.sort(Comparator.comparingLong(ActiveCooldown::startedAtMillis).reversed());
        return activeCooldowns;
    }

    /**
     * 装備の通常攻撃・攻撃行動に、CD短縮率と攻撃速度を適用して cooldown を開始します。
     *
     * @param caster 発動者
     * @param skillId 攻撃に対応する表示用スキル ID
     * @param baseCooldownTicks 装備定義上の基本攻撃間隔 tick
     */
    public void startAttackCooldown(
            @NotNull SkillCaster caster,
            @NotNull String skillId,
            long baseCooldownTicks
    ) {
        long cooldownTicks = resolveCooldownTicks(caster.statusSnapshot(), baseCooldownTicks);
        cooldownTicks = CombatTimingCalculator.resolveAttackIntervalTicks(
                cooldownTicks,
                caster.statusSnapshot().rollValue(StatusType.ATTACK_SPEED)
        );
        startCooldown(caster, WEAPON_NORMAL_ATTACK_COOLDOWN_ID, skillId, cooldownTicks);
    }

    private long resolveCooldownTicks(@NotNull SkillCaster caster, long baseCooldownTicks) {
        return resolveCooldownTicks(caster.statusSnapshot(), baseCooldownTicks);
    }

    private long resolveCooldownTicks(@NotNull StatusSnapshot statusSnapshot, long baseCooldownTicks) {
        return CombatTimingCalculator.resolveCooldownTicks(
                baseCooldownTicks,
                statusSnapshot.rollValue(StatusType.COOLDOWN_REDUCTION)
        );
    }

    /**
     * 発動者・スキルの cooldown が残っているかを判定します。
     *
     * @param caster  発動者
     * @param skillId スキル ID
     * @return cooldown 中なら {@code true}
     */
    public boolean isOnCooldown(@NotNull SkillCaster caster, @NotNull String skillId) {
        Map<String, CooldownState> byCaster = cooldownExpiryByCaster.get(caster.casterId());
        if (byCaster == null) return false;
        CooldownState state = byCaster.get(normalize(skillId));
        if (state == null) return false;
        if (System.currentTimeMillis() >= state.expiryMillis()) {
            byCaster.remove(normalize(skillId));
            return false;
        }
        return true;
    }

    /**
     * 発動者・スキルに残っている cooldown を tick 単位で返します。
     *
     * @param caster 発動者
     * @param skillId スキル ID
     * @return 残り cooldown tick。cooldown 外は {@code 0}
     */
    public long getRemainingCooldownTicks(@NotNull SkillCaster caster, @NotNull String skillId) {
        Map<String, CooldownState> byCaster = cooldownExpiryByCaster.get(caster.casterId());
        if (byCaster == null) {
            return 0L;
        }
        String normalizedSkillId = normalize(skillId);
        CooldownState state = byCaster.get(normalizedSkillId);
        if (state == null) {
            return 0L;
        }
        long remainingMillis = state.expiryMillis() - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            byCaster.remove(normalizedSkillId);
            return 0L;
        }
        return Math.max(1L, (remainingMillis + MS_PER_TICK - 1L) / MS_PER_TICK);
    }

    private @NotNull String resolveSkillDisplayName(@NotNull String skillId) {
        SkillDefinition definition = registry.getDefinition(skillId);
        return SkillPresentationUtil.legacyName(definition, ColorCodeUtil.RED + skillId);
    }

    /** 共有cooldownを開始したスキルが採用した総tickを返します。 */
    public long getCooldownDurationTicks(@NotNull SkillCaster caster, @NotNull String skillId) {
        Map<String, CooldownState> byCaster = cooldownExpiryByCaster.get(caster.casterId());
        if (byCaster == null) return 0L;
        String key = normalize(skillId);
        CooldownState state = byCaster.get(key);
        if (state == null) return 0L;
        if (System.currentTimeMillis() >= state.expiryMillis()) {
            byCaster.remove(key);
            return 0L;
        }
        return state.durationTicks();
    }

    /**
     * 指定した発動者の進行中詠唱を停止します。クールダウン状態は保持します。
     *
     * @param casterId 発動者 UUID
     */
    public void cancelCasting(@NotNull UUID casterId) {
        CastingSession session = castingSessions.remove(casterId);
        if (session == null) {
            return;
        }

        session.task().cancel();
        session.cleanup().run();
    }

    /**
     * 指定した発動者の詠唱とクールダウンを破棄します。
     * 退出・死亡など、発動者のライフサイクル終了時に使用します。
     *
     * @param casterId 発動者 UUID
     */
    public void clearCasterState(@NotNull UUID casterId) {
        try {
            cancelCasting(casterId);
        } finally {
            cooldownExpiryByCaster.remove(casterId);
        }
    }

    /**
     * 進行中の詠唱を停止し、詠唱中に変更した歩行速度を戻します。
     */
    public void stop() {
        for (UUID casterId : List.copyOf(castingSessions.keySet())) {
            cancelCasting(casterId);
        }
    }

    private void notifyIfFailed(@NotNull SkillCaster caster, @NotNull SkillCastResult result, @NotNull String skillId) {
        if (result.success()) return;
        PlayerMsgId messageId = result.messageId();
        if (messageId == null) return;
        if (messageId == PlayerMsgId.P_5803
            || messageId == PlayerMsgId.P_5804
            || messageId == PlayerMsgId.P_5809
            || messageId == PlayerMsgId.P_5863) {
            caster.notify(messageId, SkillPresentationUtil.plainName(registry.getDefinition(skillId), "未定義スキル"));
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

    private void playMobCastStartSound(@NotNull Location location, @NotNull SkillDefinition definition) {
        Object raw = definition.getParams().get("castSound");
        if (!(raw instanceof String soundKey) || soundKey.isBlank()) {
            return;
        }
        if (location.getWorld() == null) {
            return;
        }
        float volume = floatParam(definition, "castSoundVolume", 1.0F);
        float pitch = floatParam(definition, "castSoundPitch", 1.0F);
        location.getWorld().playSound(location, soundKey.trim(), volume, pitch);
    }

    private float floatParam(@NotNull SkillDefinition definition, @NotNull String key, float defaultValue) {
        Object raw = definition.getParams().get(key);
        if (raw instanceof Number number) {
            return number.floatValue();
        }
        return defaultValue;
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** cooldownId 未指定時は skillId を共有キーとして使用します。 */
    public @NotNull String resolveCooldownId(@NotNull String skillId) {
        SkillDefinition definition = registry.getDefinition(skillId);
        return definition == null ? skillId : cooldownKey(definition);
    }

    private @NotNull String cooldownKey(@NotNull SkillDefinition definition) {
        String cooldownId = definition.getCooldownId();
        return cooldownId == null || cooldownId.isBlank() ? definition.getId() : cooldownId.trim();
    }

    private @NotNull SkillResourceType resolveResourceType(@NotNull SkillDefinition skill) {
        SkillResourceType declaredType = skill.getResourceType();
        if (declaredType != null) {
            return declaredType;
        }

        Object raw = skill.getParams().get("resourceType");
        if (raw == null) {
            return SkillResourceType.MANA;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new SkillParameterException("resourceType", "MANA または ENERGY を指定してください");
        }
        try {
            return SkillResourceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkillParameterException("resourceType", "MANA または ENERGY を指定してください");
        }
    }

    private double resolveResourceCost(@NotNull SkillDefinition skill) {
        Double declaredCost = skill.getResourceCost();
        if (declaredCost != null) {
            return declaredCost;
        }

        Object raw = skill.getParams().get("resourceCost");
        if (raw == null) {
            return skill.getManaCost();
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        throw new SkillParameterException("resourceCost", "number を指定してください");
    }

    /**
     * 発動者の消費軽減率を反映したスキルの実消費量を返します。
     *
     * @param caster 発動者
     * @param skill  スキル定義
     * @return 0 以上の実消費量
     */
    private double resolveResourceCost(@NotNull StatusSnapshot statusSnapshot, @NotNull SkillDefinition skill) {
        double baseCost = resolveResourceCost(skill);
        StatusType reductionType = resolveResourceType(skill) == SkillResourceType.MANA
                ? StatusType.MANA_COST_REDUCTION
                : StatusType.ENERGY_COST_REDUCTION;
        double reduction = Math.max(0.0D, statusSnapshot.rollValue(reductionType));
        return Math.max(0.0D, baseCost * (1.0D - reduction / 100.0D));
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

    private @NotNull SkillDefinition withResolvedDefinition(
            @NotNull SkillDefinition definition,
            @NotNull SkillExecutor executor
    ) {
        return new SkillDefinition(
                definition.getId(),
                definition.getImplementationId(),
                definition.getName(),
                definition.getDescription(),
                definition.getIcon(),
                definition.getLore(),
                definition.getCooldownTicks(),
                definition.getManaCost(),
                definition.getCastTimeTicks(),
                definition.getRequiredLevel(),
                definition.getOnCastSound(),
                definition.getParams(),
                definition.getTags(),
                executor.kind(),
                definition.getPassiveBindRequired(),
                resolveResourceType(definition),
                resolveResourceCost(definition),
                definition.getCooldownId(),
                definition.getMaxLevel(),
                definition.getLevels(),
                definition.getSigilSlotsByLevel(),
                definition.getAllowedSigilIds()
        );
    }

    private record LearnedCast(
        @NotNull LearnedSkillInstance learnedSkill,
        @NotNull StatusSnapshot statusSnapshot,
        @NotNull Set<String> sigilIds
    ) {
        private LearnedCast {
            sigilIds = Set.copyOf(sigilIds);
        }
    }

    public record ActiveCooldown(
            @NotNull String cooldownKey,
            @NotNull String displaySkillId,
            @NotNull String skillName,
            long remainingTicks,
            long totalTicks,
            long startedAtMillis
    ) {
    }

    private record CooldownState(
            long expiryMillis,
            long durationTicks,
            long startedAtMillis,
            @NotNull String displaySkillId
    ) {
    }

    private record CastingSession(@NotNull BukkitTask task, @NotNull Runnable cleanup) {
    }
}
