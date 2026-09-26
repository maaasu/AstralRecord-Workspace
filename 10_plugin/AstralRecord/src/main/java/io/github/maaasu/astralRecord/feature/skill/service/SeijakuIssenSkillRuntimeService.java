package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 静寂一閃の構え、被弾反撃、時間切れの飛び込み斬りを管理します。 */
public final class SeijakuIssenSkillRuntimeService {
    public static final String SKILL_ID = "swordmaster_seijaku_issen";
    private static final String EXPIRY_SCOPE = SKILL_ID + ":expiry";
    private static final String HUD_SCOPE = SKILL_ID + ":hud";
    private static final String DASH_SCOPE = SKILL_ID + ":dash";
    private static final String SWEEP_SCOPE = SKILL_ID + ":counter-sweep";
    private static final int DASH_SCAN_TICKS = 10;
    private static final double DASH_HORIZONTAL_VELOCITY = 1.5D;
    private static final double DASH_VERTICAL_VELOCITY = 0.3D;
    private static final double SWEEP_START_ANGLE = 55.0D;
    private static final double SWEEP_END_ANGLE = -55.0D;
    private static final int SWEEP_FRAMES = 6;
    private static final double[] SWEEP_RADIUS_BASES = {2.4D, 3.9D, 5.4D};

    private final SkillService skillService;
    private final SkillCombatService combatService;
    private final SkillTargetingService targetingService;
    private final SkillMovementService movementService;
    private final SkillEffectService effectService;
    private final SkillTaskService taskService;
    private final StatusService statusService;
    private final PlayerHudService hudService;
    private final NamespacedKey slowModifierKey;
    private final Map<UUID, Map<String, Configuration>> configurations = new ConcurrentHashMap<>();
    private final Map<UUID, CounterState> counters = new ConcurrentHashMap<>();
    private final Map<UUID, DashState> dashes = new ConcurrentHashMap<>();

    /**
     * 構えと反撃に必要な共有サービスで初期化します。
     *
     * @param plugin 属性修飾キーを所有するプラグイン
     * @param skillService 通常攻撃クールタイムと詠唱状態の管理元
     * @param combatService 反撃とENG回復の適用元
     * @param targetingService 飛び込み経路の対象検索元
     * @param movementService 移動可否を確認する移動サービス
     * @param effectService 音と粒子の表示サービス
     * @param taskService 構えと飛び込みの追跡タスク
     * @param statusService ENG消費と現在値の管理元
     * @param hudService カウンター残り時間の表示先
     */
    public SeijakuIssenSkillRuntimeService(
            @NotNull Plugin plugin,
            @NotNull SkillService skillService,
            @NotNull SkillCombatService combatService,
            @NotNull SkillTargetingService targetingService,
            @NotNull SkillMovementService movementService,
            @NotNull SkillEffectService effectService,
            @NotNull SkillTaskService taskService,
            @NotNull StatusService statusService,
            @NotNull PlayerHudService hudService
    ) {
        this.skillService = skillService;
        this.combatService = combatService;
        this.targetingService = targetingService;
        this.movementService = movementService;
        this.effectService = effectService;
        this.taskService = taskService;
        this.statusService = statusService;
        this.hudService = hudService;
        this.slowModifierKey = new NamespacedKey(plugin, "seijaku_issen_slow");
    }

    /**
     * バインド済みスキル個体のレベル別設定を登録します。
     *
     * @param context 有効化されたスキル個体
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Configuration configuration = new Configuration(
                params.getInt("counterTicks", 1),
                params.getDouble("counterDamageRatio", 3.9D),
                params.getDouble("failureDamageRatio", 0.1D),
                params.getDouble("failureEnergyCost", 10.0D),
                params.getDouble("failureTravelDistance", 10.0D),
                params.getDouble("failureHitRadius", 2.0D),
                params.getDouble("energyRecoveryRatio", 0.1D),
                params.getDouble("explosionDamageRatio", 5.1D),
                params.getDouble("explosionRadius", 3.0D),
                params.getDouble("counterSweepRange", 5.5D),
                params.getInt("counterSweepMaxTargets", 5),
                params.getDouble("tackleDamageReductionRatio", 0.5D),
                params.getInt("tackleHitCooldownMultiplier", 10)
        );
        UUID playerId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(configurationKey(context), configuration);
    }

    /**
     * バインド解除時に設定を外し、最後の個体なら構えも中断します。
     *
     * @param context 無効化されたスキル個体
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID playerId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> playerConfigurations = configurations.get(playerId);
        if (playerConfigurations == null) {
            endCounter(playerId, true);
            taskService.cancel(playerId, DASH_SCOPE);
            taskService.cancel(playerId, SWEEP_SCOPE);
            return;
        }
        playerConfigurations.remove(configurationKey(context));
        if (playerConfigurations.isEmpty()) {
            configurations.remove(playerId, playerConfigurations);
            endCounter(playerId, true);
            taskService.cancel(playerId, DASH_SCOPE);
            taskService.cancel(playerId, SWEEP_SCOPE);
        }
    }

    /**
     * バインド済みの構え設定があるか確認します。
     *
     * @param player 判定するプレイヤー
     * @return 有効な個体があればtrue
     */
    public boolean isActive(@NotNull AstPlayer player) {
        return effectiveConfiguration(player.getBukkit().getUniqueId()) != null;
    }

    /**
     * 剣の通常攻撃を構えへ置換し、構え時間と移動低下を開始します。
     *
     * @param astPlayer 発動者。剣の通常攻撃を開始できることが前提
     * @param attackSkillId 通常攻撃の表示用スキルID
     * @param baseCooldownTicks 剣通常攻撃の基本クールタイムtick
     * @param attackSpeedMultiplier 攻撃開始時の一時攻撃速度倍率
     * @return 構えを開始できた場合はtrue
     */
    public boolean beginCounter(
            @NotNull AstPlayer astPlayer,
            @NotNull String attackSkillId,
            long baseCooldownTicks,
            double attackSpeedMultiplier
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        Configuration configuration = effectiveConfiguration(playerId);
        if (configuration == null || !player.isOnline() || player.isDead()
                || counters.containsKey(playerId) || dashes.containsKey(playerId)) {
            return false;
        }
        long expiresAtTick = (long) Bukkit.getCurrentTick() + configuration.counterTicks();
        Function<AstPlayer, Component> renderer = ignored -> counterActionBar(playerId);
        CounterState state = new CounterState(astPlayer, configuration, expiresAtTick,
                renderer, attackSkillId, baseCooldownTicks, attackSpeedMultiplier);
        counters.put(playerId, state);
        applySlow(player);
        hudService.setPrimaryActionBarRendererIfAbsent(playerId, renderer);
        hudService.refreshActionBar(astPlayer);
        Location center = player.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        effectService.sound(center, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0F, 0.5F);
        effectService.sound(center, Sound.ITEM_ARMOR_EQUIP_IRON, 1.0F, 0.75F);
        effectService.ring(center, 0.8D, 24, SharedParticleDefinitions.SEIJAKU_ISSEN_GUARD);
        taskService.repeat(playerId, HUD_SCOPE, 1L, 1L, configuration.counterTicks(),
                ignored -> {
                    if (counters.get(playerId) == state && player.isOnline()
                            && !skillService.isCasting(new PlayerSkillCaster(astPlayer))) {
                        hudService.setPrimaryActionBarRendererIfAbsent(playerId, renderer);
                        hudService.refreshActionBar(astPlayer);
                    }
                });
        taskService.later(playerId, EXPIRY_SCOPE, configuration.counterTicks(), () -> expireCounter(playerId, state));
        return true;
    }

    /**
     * 構え中の直接攻撃を無効化して攻撃元へ反撃します。
     *
     * @param victim 被弾者
     * @param attacker 攻撃元。特定できない場合はnull
     * @param source 攻撃の発生元種別
     * @param calculated 計算済みダメージ
     * @return 攻撃を無効化した場合はtrue
     */
    public boolean tryCounterDirectDamage(
            @NotNull AstEntity victim,
            @Nullable AstEntity attacker,
            @NotNull DamageSource source,
            @NotNull DamageResult calculated
    ) {
        if ((source != DamageSource.NORMAL_ATTACK && source != DamageSource.SKILL)
                || !victim.isPlayer() || victim.player() == null
                || attacker == null || attacker.id().equals(victim.id())
                || calculated.evaded()) {
            return false;
        }
        UUID playerId = victim.id();
        CounterState state = counters.get(playerId);
        if (state == null || Bukkit.getCurrentTick() >= state.expiresAtTick()) {
            return false;
        }
        if (!counters.remove(playerId, state)) {
            return false;
        }
        finishCounter(playerId, state);
        Location counterTarget = attacker.location().clone();
        DamageResult firstHit = combatService.hit(victim, attacker, AttackType.MELEE, DamageElement.NONE,
                state.configuration().counterDamageRatio());
        if (isSuccessfulHit(firstHit)) {
            combatService.recoverEnergyByMaxRatio(state.player(), state.configuration().energyRecoveryRatio());
        }
        renderCounterHit(state.player().getBukkit(), counterTarget);
        startCounterSweep(state, counterTarget);
        return true;
    }

    /**
     * タックルの移動判定中に受けるダメージ倍率を返します。
     *
     * @param victim 被弾者
     * @return タックル中は0.5、通常時は1.0
     */
    public double damageTakenMultiplier(@NotNull AstEntity victim) {
        if (!victim.isPlayer()) {
            return 1.0D;
        }
        DashState state = dashes.get(victim.id());
        return state == null ? 1.0D : 1.0D - state.configuration().tackleDamageReductionRatio();
    }

    /**
     * 退出時に設定、構え、飛び込み判定を消去します。
     *
     * @param playerId 退出するプレイヤーのUUID
     */
    public void clearPlayer(@NotNull UUID playerId) {
        configurations.remove(playerId);
        interrupt(playerId);
    }

    /**
     * 死亡などの中断時に構えと飛び込みを消し、有効なパッシブ設定は保持します。
     *
     * @param playerId 中断対象のプレイヤーUUID
     */
    public void interrupt(@NotNull UUID playerId) {
        endCounter(playerId, false);
        taskService.cancel(playerId, DASH_SCOPE);
        taskService.cancel(playerId, SWEEP_SCOPE);
    }

    /** Plugin停止時にすべての短命状態を消去します。 */
    public void clearAll() {
        for (UUID playerId : Set.copyOf(counters.keySet())) {
            endCounter(playerId, false);
        }
        for (UUID playerId : Set.copyOf(configurations.keySet())) {
            taskService.cancel(playerId, DASH_SCOPE);
            taskService.cancel(playerId, SWEEP_SCOPE);
        }
        dashes.clear();
        configurations.clear();
    }

    private void expireCounter(@NotNull UUID playerId, @NotNull CounterState state) {
        if (!counters.remove(playerId, state)) {
            return;
        }
        CooldownWindow cooldown = finishCounter(playerId, state);
        startFailureDash(state, cooldown);
    }

    private void endCounter(@NotNull UUID playerId, boolean startCooldown) {
        CounterState state = counters.remove(playerId);
        if (state != null) {
            if (startCooldown) {
                finishCounter(playerId, state);
            } else {
                cleanupCounter(playerId, state);
            }
        }
    }

    private @NotNull CooldownWindow finishCounter(@NotNull UUID playerId, @NotNull CounterState state) {
        cleanupCounter(playerId, state);
        PlayerSkillCaster caster = new PlayerSkillCaster(state.player());
        long durationTicks = skillService.resolveAttackCooldownTicks(
                caster, state.baseCooldownTicks(), state.attackSpeedMultiplier());
        long startedAtMillis = System.currentTimeMillis();
        skillService.setAttackCooldownFromStart(
                caster, state.attackSkillId(), durationTicks, startedAtMillis);
        return new CooldownWindow(startedAtMillis, durationTicks);
    }

    private void cleanupCounter(@NotNull UUID playerId, @NotNull CounterState state) {
        taskService.cancel(playerId, EXPIRY_SCOPE);
        taskService.cancel(playerId, HUD_SCOPE);
        removeSlow(state.player().getBukkit());
        hudService.clearPrimaryActionBarRendererIfSame(playerId, state.renderer());
        if (state.player().getBukkit().isOnline()) {
            hudService.refreshActionBar(state.player());
        }
    }

    private void startFailureDash(@NotNull CounterState state, @NotNull CooldownWindow cooldown) {
        AstPlayer astPlayer = state.player();
        Player player = astPlayer.getBukkit();
        Configuration configuration = state.configuration();
        if (!player.isOnline() || player.isDead()
                || statusService.getStatus(astPlayer).getCurrentEnergy() < configuration.failureEnergyCost()) {
            return;
        }
        Vector direction = player.getEyeLocation().getDirection().setY(0.0D);
        if (direction.lengthSquared() <= 1.0E-8D) {
            return;
        }
        direction.normalize();
        Vector launchVelocity = direction.clone().multiply(DASH_HORIZONTAL_VELOCITY)
                .setY(DASH_VERTICAL_VELOCITY);
        if (movementService.velocity(player, AstEntity.player(astPlayer), launchVelocity) == null) {
            return;
        }
        statusService.consumeEnergy(astPlayer, configuration.failureEnergyCost());
        UUID playerId = player.getUniqueId();
        DashState dash = new DashState(state, cooldown, player.getWorld(), player.getLocation().clone());
        dashes.put(playerId, dash);
        taskService.repeat(playerId, DASH_SCOPE, 1L, 1L, DASH_SCAN_TICKS,
                ignored -> scanDashFrame(playerId, dash),
                () -> dashes.remove(playerId, dash));
    }

    private void scanDashFrame(@NotNull UUID playerId, @NotNull DashState dash) {
        AstPlayer astPlayer = dash.counter.player();
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || player.getWorld() != dash.world) {
            taskService.cancel(playerId, DASH_SCOPE);
            return;
        }
        Location current = player.getLocation().clone();
        Vector movement = current.toVector().subtract(dash.previous.toVector());
        double distance = movement.length();
        double remaining = dash.counter.configuration().failureTravelDistance() - dash.traveled;
        if (distance > 1.0E-4D && remaining > 0.0D) {
            double scanDistance = Math.min(distance, remaining);
            Location scanOrigin = dash.previous.clone().add(0.0D, 1.0D, 0.0D);
            for (AstEntity target : targetingService.inLine(player, scanOrigin, movement,
                    scanDistance, dash.counter.configuration().failureHitRadius(), Integer.MAX_VALUE)) {
                if (!dash.pathHitTargets.add(target.id())) {
                    continue;
                }
                DamageResult hit = combatService.hit(AstEntity.player(astPlayer), target,
                        AttackType.MELEE, DamageElement.NONE,
                        dash.counter.configuration().failureDamageRatio());
                if (isSuccessfulHit(hit)) {
                    onDashHit(dash, target);
                }
                effectService.point(target.location().clone().add(0.0D, 1.0D, 0.0D),
                        SharedParticleDefinitions.SEIJAKU_ISSEN_ENCHANTED_HIT);
            }
            dash.traveled += scanDistance;
            effectService.point(current.clone().add(0.0D, 1.0D, 0.0D),
                    SharedParticleDefinitions.SEIJAKU_ISSEN_DASH_CRIT);
            effectService.point(current.clone().add(0.0D, 0.5D, 0.0D),
                    SharedParticleDefinitions.SEIJAKU_ISSEN_DASH_SPARK);
        }
        dash.previous = current;
        if (dash.traveled >= dash.counter.configuration().failureTravelDistance()) {
            taskService.cancel(playerId, DASH_SCOPE);
        }
    }

    private void onDashHit(@NotNull DashState dash, @NotNull AstEntity directTarget) {
        Configuration configuration = dash.counter.configuration();
        if (!dash.cooldownExtended) {
            dash.cooldownExtended = true;
            skillService.setAttackCooldownFromStart(
                    new PlayerSkillCaster(dash.counter.player()),
                    dash.counter.attackSkillId(),
                    dash.cooldown.durationTicks() * configuration.tackleHitCooldownMultiplier(),
                    dash.cooldown.startedAtMillis());
        }
        Location center = directTarget.location().clone().add(0.0D, 0.9D, 0.0D);
        effectService.point(center, SharedParticleDefinitions.SEIJAKU_ISSEN_EXPLOSION);
        effectService.ring(center, configuration.explosionRadius(), 32,
                SharedParticleDefinitions.SEIJAKU_ISSEN_ENCHANTED_HIT);
        effectService.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.9F, 1.35F);
        AstEntity attacker = AstEntity.player(dash.counter.player());
        for (AstEntity target : targetingService.inSphere(
                dash.counter.player().getBukkit(), center, configuration.explosionRadius(),
                Integer.MAX_VALUE, true)) {
            if (dash.explosionHitTargets.add(target.id())) {
                combatService.hit(attacker, target, AttackType.MELEE, DamageElement.NONE,
                        configuration.explosionDamageRatio());
            }
        }
    }

    private void startCounterSweep(@NotNull CounterState state, @NotNull Location counterTarget) {
        Player player = state.player().getBukkit();
        UUID playerId = player.getUniqueId();
        World world = player.getWorld();
        Location origin = player.getEyeLocation();
        Vector direction = counterTarget.clone().add(0.0D, 0.9D, 0.0D)
                .toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= 1.0E-8D) {
            direction = origin.getDirection();
        }
        Vector sweepDirection = direction.normalize();
        Set<UUID> sweepHitTargets = new HashSet<>();
        Configuration configuration = state.configuration();
        effectService.sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.15F);
        taskService.repeat(playerId, SWEEP_SCOPE, 1L, 1L, SWEEP_FRAMES, frame -> {
            if (!player.isOnline() || player.getWorld() != world) {
                taskService.cancel(playerId, SWEEP_SCOPE);
                return;
            }
            double headStart = SWEEP_START_ANGLE
                    + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * frame / SWEEP_FRAMES;
            double headEnd = SWEEP_START_ANGLE
                    + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * (frame + 1) / SWEEP_FRAMES;
            for (double baseRadius : SWEEP_RADIUS_BASES) {
                double radius = baseRadius * configuration.counterSweepRange() / 5.5D;
                effectService.viewArcSegment(origin, sweepDirection, radius,
                        headStart, headEnd, 6,
                        SharedParticleDefinitions.SEIJAKU_ISSEN_ENCHANTED_HIT);
                effectService.viewArcSegment(origin, sweepDirection, radius,
                        headStart, headEnd, 4,
                        SharedParticleDefinitions.SEIJAKU_ISSEN_DASH_SPARK);
            }
            Vector sweepOffset = sweepDirection.clone().setY(0.0D);
            if (sweepOffset.lengthSquared() > 1.0E-8D) {
                sweepOffset.normalize().rotateAroundY(Math.toRadians(headEnd))
                        .multiply(configuration.counterSweepRange() * 0.55D);
                effectService.point(origin.clone().add(sweepOffset),
                        SharedParticleDefinitions.SEIJAKU_ISSEN_SWEEP_ATTACK);
            }
            for (AstEntity target : targetingService.inViewArcSegment(
                    player, origin, sweepDirection, configuration.counterSweepRange(),
                    headStart, headEnd, configuration.counterSweepMaxTargets(), true)) {
                if (sweepHitTargets.size() >= configuration.counterSweepMaxTargets()
                        || !sweepHitTargets.add(target.id())) {
                    continue;
                }
                combatService.hit(AstEntity.player(state.player()), target, AttackType.MELEE,
                        DamageElement.NONE, configuration.counterDamageRatio());
            }
        });
    }

    private static boolean isSuccessfulHit(@NotNull DamageResult result) {
        return !result.evaded()
                && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
    }

    private void renderCounterHit(@NotNull Player player, @NotNull Location target) {
        Location center = target.clone().add(0.0D, 1.0D, 0.0D);
        effectService.ring(center, 1.3D, 32, SharedParticleDefinitions.SEIJAKU_ISSEN_ENCHANTED_HIT);
        effectService.ring(center, 0.8D, 24, SharedParticleDefinitions.SEIJAKU_ISSEN_DASH_CRIT);
        effectService.line(player.getEyeLocation(), center, 0.2D,
                SharedParticleDefinitions.SEIJAKU_ISSEN_DASH_SPARK);
        effectService.sound(center, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0F, 1.5F);
        effectService.sound(center, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 2.0F);
        effectService.sound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0F, 0.5F);
        effectService.sound(center, Sound.BLOCK_ANVIL_LAND, 1.0F, 2.0F);
        effectService.sound(center, Sound.BLOCK_CHAIN_HIT, 1.0F, 0.5F);
    }

    private @NotNull Component counterActionBar(@NotNull UUID playerId) {
        CounterState state = counters.get(playerId);
        if (state == null) {
            return Component.empty();
        }
        long remaining = Math.max(0L, state.expiresAtTick() - Bukkit.getCurrentTick());
        int filled = Math.clamp((int) Math.ceil(
                remaining * 10.0D / state.configuration().counterTicks()), 0, 10);
        String bar = "■".repeat(filled) + "□".repeat(10 - filled);
        return Component.text("カウンター [" + bar + "] " + remaining + "tick", NamedTextColor.AQUA);
    }

    private void applySlow(@NotNull Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        removeSlow(player);
        speed.addTransientModifier(new AttributeModifier(
                slowModifierKey, -0.7D, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void removeSlow(@NotNull Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(slowModifierKey) != null) {
            speed.removeModifier(slowModifierKey);
        }
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        return context.learnedSkill() == null
                ? context.skill().getId()
                : context.learnedSkill().getLearnedSkillId().toString();
    }

    private @Nullable Configuration effectiveConfiguration(@NotNull UUID playerId) {
        Map<String, Configuration> available = configurations.get(playerId);
        if (available == null || available.isEmpty()) {
            return null;
        }
        return available.values().stream()
                .max(Comparator.comparingInt(Configuration::counterTicks)
                        .thenComparingDouble(Configuration::counterDamageRatio))
                .orElse(null);
    }

    private record Configuration(
            int counterTicks,
            double counterDamageRatio,
            double failureDamageRatio,
            double failureEnergyCost,
            double failureTravelDistance,
            double failureHitRadius,
            double energyRecoveryRatio,
            double explosionDamageRatio,
            double explosionRadius,
            double counterSweepRange,
            int counterSweepMaxTargets,
            double tackleDamageReductionRatio,
            int tackleHitCooldownMultiplier
    ) {
    }

    private record CounterState(
            @NotNull AstPlayer player,
            @NotNull Configuration configuration,
            long expiresAtTick,
            @NotNull Function<AstPlayer, Component> renderer,
            @NotNull String attackSkillId,
            long baseCooldownTicks,
            double attackSpeedMultiplier
    ) {
    }

    private record CooldownWindow(long startedAtMillis, long durationTicks) {
    }

    private static final class DashState {
        private final CounterState counter;
        private final CooldownWindow cooldown;
        private final World world;
        private final Set<UUID> pathHitTargets = new HashSet<>();
        private final Set<UUID> explosionHitTargets = new HashSet<>();
        private Location previous;
        private double traveled;
        private boolean cooldownExtended;

        private DashState(CounterState counter, CooldownWindow cooldown, World world, Location previous) {
            this.counter = counter;
            this.cooldown = cooldown;
            this.world = world;
            this.previous = previous;
        }

        private Configuration configuration() {
            return counter.configuration();
        }
    }
}
