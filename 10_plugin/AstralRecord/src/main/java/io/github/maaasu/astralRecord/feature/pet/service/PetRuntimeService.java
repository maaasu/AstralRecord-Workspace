package io.github.maaasu.astralRecord.feature.pet.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.service.BuffService;
import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.combat.service.DamageCalculator;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.*;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 装備済みペットの追従、独自戦闘、習得スキルと保存前の状態を管理します。
 * 全メソッドは Bukkit メインスレッドで呼び出し、保存は橋渡し先の非同期保存境界へ委譲します。
 */
public final class PetRuntimeService implements Listener {
    private static final String BUFF_PREFIX = "pet-runtime:";
    private static final String BASIC_ATTACK_COOLDOWN = "__basic_attack";
    private final Plugin plugin;
    private final StateBridge bridge;
    private final StatusService statusService;
    private final BuffService buffService;
    private final DamageService damageService;
    private final PlayerDeathService playerDeathService;
    private ConditionService conditionService;
    private final Map<UUID, Session> byOwner = new HashMap<>();
    private final Map<UUID, Session> byEntity = new HashMap<>();
    private final Map<UUID, TimedAmount> defenseBreaks = new HashMap<>();
    private BukkitTask task;
    private long tick;

    /**
     * @param plugin タスクとイベントの所有者
     * @param bridge 個体キャッシュへの連携先。戦闘中に同期 API 通信をしてはいけません
     * @param statusService 主人の能力・回復サービス
     * @param buffService 主人へ付与するスキルバフ
     * @param damageService 共通ダメージ・報酬サービス
     * @param playerDeathService 主人の死亡判定
     */
    public PetRuntimeService(@NotNull Plugin plugin, @NotNull StateBridge bridge,
                             @NotNull StatusService statusService, @NotNull BuffService buffService,
                             @NotNull DamageService damageService, @Nullable PlayerDeathService playerDeathService) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.statusService = statusService;
        this.buffService = buffService;
        this.damageService = damageService;
        this.playerDeathService = playerDeathService;
    }

    /** イベントと5 tick 間隔の追従・スキル更新を開始します。重複開始しません。 */
    public void start() {
        if (task != null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    /** @param conditionService ペットへの行動不能と移動速度低下の参照先 */
    public void setConditionService(@Nullable ConditionService conditionService) { this.conditionService = conditionService; }

    /**
     * 装備保存・復活・個体更新の確定後に召喚状態を反映します。
     * @param owner 読み込み済みの主人
     */
    public void refresh(@NotNull AstPlayer owner) {
        UUID ownerId = owner.getBukkit().getUniqueId();
        RuntimePet selected = bridge.equipped(owner);
        Session previous = byOwner.get(ownerId);
        if (selected == null || selected.dead() || selected.healthRatio() <= 0.0D
                || !owner.getBukkit().isOnline() || !AccountModeGuard.isGameplayPlayer(owner)
                || playerDeathService != null && playerDeathService.isDead(ownerId)) {
            if (previous != null && selected != null && previous.pet.instanceId().equals(selected.instanceId())
                    && (selected.dead() || selected.healthRatio() <= 0.0D)) {
                previous.dead = true;
                previous.healthRatio = 0.0D;
            }
            dismiss(owner);
            return;
        }
        if (previous != null && previous.pet.instanceId().equals(selected.instanceId())) {
            previous.pet = selected;
            return;
        }
        dismiss(owner);
        Location location = behind(owner.getBukkit());
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, selected.entityType());
        entity.setPersistent(false);
        entity.setRemoveWhenFarAway(false);
        entity.setSilent(false);
        entity.customName(ColorCodeUtil.toComponent(selected.name(), ""));
        entity.setCustomNameVisible(true);
        if (entity.getAttribute(Attribute.SCALE) != null) {
            entity.getAttribute(Attribute.SCALE).setBaseValue(selected.size());
        }
        if (entity instanceof Tameable tameable) {
            tameable.setOwner(owner.getBukkit());
            tameable.setTamed(true);
        }
        if (entity instanceof Mob mob) {
            Bukkit.getMobGoals().removeAllGoals(mob);
            mob.setAI(true);
            mob.setAware(true);
            mob.setCanPickupItems(false);
            mob.getPathfinder().setCanFloat(true);
        }
        if (entity instanceof Animals animal) animal.setBreed(false);
        Session session = new Session(owner, selected, entity);
        byOwner.put(ownerId, session);
        byEntity.put(entity.getUniqueId(), session);
        session.publish();
    }

    /**
     * 保存用の状態をキャッシュへ反映し、召喚と主人のペット由来バフを解除します。
     * @param owner 主人。ログアウト保存より先に呼びます
     */
    public void dismiss(@NotNull AstPlayer owner) {
        Session session = byOwner.remove(owner.getBukkit().getUniqueId());
        if (session != null) {
            session.publish();
            byEntity.remove(session.entity.getUniqueId());
            if (conditionService != null) conditionService.clearAll(AstEntity.pet(session));
            session.entity.remove();
        }
        removePetBuffs(owner);
    }

    /** 全ペットの状態をキャッシュへ反映し、召喚と更新タスクを停止します。 */
    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (Session session : List.copyOf(byOwner.values())) dismiss(session.owner);
        defenseBreaks.clear();
    }

    /** @param entity Bukkit エンティティ @return 召喚中ペット。対象外なら null */
    public @Nullable PetCombatActor resolve(@NotNull Entity entity) { return byEntity.get(entity.getUniqueId()); }

    /** @param id 召喚エンティティ UUID @return 生存中で主人も有効なペット。対象外なら null */
    public @Nullable LivingEntity target(@NotNull UUID id) {
        Session session = byEntity.get(id);
        return session != null && session.active() ? session.entity : null;
    }

    /** @param origin 敵の現在位置 @param range 検索距離 @return 範囲内の有効なペット */
    public @NotNull List<LivingEntity> targets(@NotNull Location origin, double range) {
        double rangeSq = range * range;
        return byOwner.values().stream().filter(Session::active)
                .filter(session -> session.entity.getWorld() == origin.getWorld())
                .filter(session -> session.entity.getLocation().distanceSquared(origin) <= rangeSq)
                .map(session -> session.entity).toList();
    }

    /** @param entityId ペットまたはプレイヤー UUID @return ペットなら主人 UUID、その他は元の UUID */
    public @NotNull UUID rewardOwner(@NotNull UUID entityId) {
        Session session = byEntity.get(entityId);
        return session == null ? entityId : session.owner.getBukkit().getUniqueId();
    }

    /**
     * 主人の実際に命中した直接攻撃または回避成功をスキルの起点にします。
     * @param attacker 攻撃者
     * @param victim 被弾者
     * @param result 共通計算の適用結果
     * @param source 発生元。ペット攻撃からは呼び出しません
     */
    public void onDamage(@Nullable AstEntity attacker, @NotNull AstEntity victim,
                         @NotNull DamageResult result, @NotNull DamageSource source) {
        if (source != DamageSource.NORMAL_ATTACK && source != DamageSource.SKILL) return;
        if (attacker != null && attacker.isPlayer() && victim.isMob() && !result.evaded()
                && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
            Session session = byOwner.get(attacker.id());
            if (session != null && session.active()) {
                session.trigger(SkillTrigger.OWNER_HIT, victim.mob());
                session.queueBasicAttack(victim.mob());
            }
        }
        if (victim.isPlayer() && result.evaded() && attacker != null && attacker.isMob()) {
            Session session = byOwner.get(victim.id());
            if (session != null && session.active()) session.trigger(SkillTrigger.OWNER_DODGE, attacker.mob());
        }
    }

    /**
     * 討伐1回につき卵を1回抽選し、有効な受取人の召喚ペットへ討伐経験値を渡します。
     * @param mob 討伐された敵
     * @param recipients 重複のない報酬受取人
     * @param results 同じ順序の報酬。AFK 対象は呼び出し側で経験値0へ補正します
     */
    public void onMobDefeated(@NotNull MobInstance mob, @NotNull List<AstPlayer> recipients,
                              @NotNull List<MobDropResult> results) {
        if (mob.template().category() == MobCategory.NPC || mob.nonLethal()) return;
        for (int i = 0; i < Math.min(recipients.size(), results.size()); i++) {
            AstPlayer owner = recipients.get(i);
            Session session = byOwner.get(owner.getBukkit().getUniqueId());
            if (session != null && session.active() && results.get(i).exp() > 0) {
                bridge.grantExperience(owner, results.get(i).exp());
            }
        }
        List<String> species = bridge.eggSpecies();
        if (recipients.isEmpty() || species.isEmpty()
                || ThreadLocalRandom.current().nextDouble() >= bridge.eggDropChance()) return;
        AstPlayer recipient = recipients.get(ThreadLocalRandom.current().nextInt(recipients.size()));
        bridge.awardEgg(recipient, species.get(ThreadLocalRandom.current().nextInt(species.size())), mob.instanceId());
    }

    /** @param target ダメージ対象 @return ペットスキル由来の防御倍率 */
    public double defenseMultiplier(@NotNull AstEntity target) {
        TimedAmount debuff = defenseBreaks.get(target.id());
        return debuff != null && debuff.expiresAt > System.currentTimeMillis()
                ? Math.max(0.0D, 1.0D - debuff.amount) : 1.0D;
    }

    /** @param victim 被弾者 @return 猫の一時的な被ダメージ倍率 */
    public double damageTakenMultiplier(@NotNull AstEntity victim) {
        Session session = byOwner.get(victim.id());
        return session != null && session.active() ? 1.0D - session.reduction() : 1.0D;
    }

    /** @param event ペットの環境被害。バニラ HP を独自 HP へ移管します */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEnvironmentDamage(EntityDamageEvent event) {
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) return;
        Session session = byEntity.get(event.getEntity().getUniqueId());
        if (session == null) return;
        double damage = event.getFinalDamage();
        event.setCancelled(true);
        if (damage > 0.0D) session.damage(damage);
    }

    /** @param event バニラ AI の独立した攻撃・味方誤認を停止します */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPetTarget(EntityTargetLivingEntityEvent event) {
        if (byEntity.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    /** @param event 管理外のバニラ死亡が発生した場合も個体死亡を保存します */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Session session = byEntity.get(event.getEntity().getUniqueId());
        if (session == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        session.damage(session.maxHealth());
    }

    /** @param event ログアウト時にバニラ召喚個体を残さず状態をキャッシュへ反映します */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = byOwner.get(event.getPlayer().getUniqueId());
        if (session != null) dismiss(session.owner);
    }

    /** 追従と周期スキルを更新し、期限切れの敵防御低下を除去します。 */
    private void tick() {
        tick += 5L;
        for (Session session : List.copyOf(byOwner.values())) {
            if (!session.entity.isValid()) { session.damage(session.maxHealth()); continue; }
            if (!session.active()) continue;
            if (tick % 20L == 0L) {
                RuntimePet current = bridge.equipped(session.owner);
                if (current == null || !current.instanceId().equals(session.pet.instanceId()) || current.dead()) {
                    dismiss(session.owner); continue;
                }
                session.pet = current;
                session.readOwnerStats();
                session.trigger(SkillTrigger.PERIODIC, null);
            }
            if (conditionService != null && !conditionService.canMove(AstEntity.pet(session))) {
                if (session.entity instanceof Mob mob) mob.getPathfinder().stopPathfinding();
                continue;
            }
            if (session.tickBasicAttack()) continue;
            Location desired = behind(session.owner.getBukkit());
            if (desired.getWorld() != session.entity.getWorld()
                    || desired.distanceSquared(session.entity.getLocation()) > 144.0D) {
                session.entity.teleport(desired);
            } else if (session.entity.getLocation().distanceSquared(desired) > 3.0D) {
                if (session.entity instanceof Mob mob) {
                    double multiplier = conditionService == null ? 1.0D : conditionService.movementSpeedMultiplier(AstEntity.pet(session));
                    mob.getPathfinder().moveTo(desired, 1.15D * multiplier);
                }
            } else if (session.entity instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        }
        if (tick % 200L == 0L) defenseBreaks.values().removeIf(value -> value.expiresAt <= System.currentTimeMillis());
    }

    /** 主人の水平向きから後方2ブロックの追従先を求めます。 */
    private Location behind(Player owner) {
        Location location = owner.getLocation();
        Vector direction = location.getDirection().setY(0.0D);
        if (direction.lengthSquared() > 0.0D) direction.normalize().multiply(-2.0D);
        return location.add(direction);
    }

    /** ペット由来のバフを解除し、元の主人ステータスへ戻します。 */
    private void removePetBuffs(AstPlayer owner) {
        if (owner.getActiveBuffs().removeIf(buff -> buff.getType().getId().startsWith(BUFF_PREFIX))) {
            statusService.refreshStatus(owner);
        }
    }

    /** 保存・マスター型に依存しない、読み込み済み個体キャッシュの契約です。 */
    public interface StateBridge {
        /** @param owner 主人 @return 装備確定した個体。卵・未装備は null */
        @Nullable RuntimePet equipped(@NotNull AstPlayer owner);
        /** @param owner 主人 @param instanceId 個体 ID @param healthRatio HP 比率 @param dead 死亡状態 @param cooldowns スキル次回使用時刻 */
        void updateRuntime(@NotNull AstPlayer owner, @NotNull UUID instanceId, double healthRatio,
                           boolean dead, @NotNull Map<String, Long> cooldowns);
        /** @param owner 主人 @param delta 活動による増分。レベルと習得判定は保存先が確定します */
        void grantExperience(@NotNull AstPlayer owner, long delta);
        /** @param owner 受取人 @param speciesId 卵の種 @param rewardId 討伐ごとの冪等識別子 */
        void awardEgg(@NotNull AstPlayer owner, @NotNull String speciesId, @NotNull UUID rewardId);
        /** @return 卵抽選可能な種類 */
        @NotNull List<String> eggSpecies();
        /** @return 1討伐当たりの卵ドロップ確率 */
        double eggDropChance();
    }

    /** 個体値・成長・潜在倍率をマスター式へ適用した係数です。すべて比率で保持します。 */
    public record PetCoefficients(double vitality, double power, double defense, double evasion, double support) { }
    /** 召喚に必要な、孵化後だけ取得可能な個体スナップショットです。 */
    public record RuntimePet(UUID instanceId, String name, EntityType entityType, double size,
                             double healthRatio, boolean dead, PetCoefficients coefficients,
                             List<PetSkill> skills, Map<String, Long> cooldowns, @Nullable BasicAttack basicAttack) {
        /** マスター・個体の可変コレクションを召喚スナップショットへ持ち込まないよう複製します。 */
        public RuntimePet {
            skills = List.copyOf(skills);
            cooldowns = Map.copyOf(cooldowns);
        }
    }
    /** 全種共通のスキル実行定義。value と chance は比率、時刻はミリ秒です。 */
    public record PetSkill(String id, String name, SkillTrigger trigger, SkillEffect effect,
                           double value, double chance, long cooldownMillis, long durationMillis,
                           int hitCount, int attackCount) { }
    /** スロットを消費しない種族固有の追撃。damageRatio は換算済みペット攻撃力へ掛けます。 */
    public record BasicAttack(double damageRatio, long cooldownMillis, double range) { }
    /** スキル発動の起点です。 */
    public enum SkillTrigger { OWNER_HIT, OWNER_DODGE, PERIODIC }
    /** マスターで指定するペットスキル効果です。 */
    public enum SkillEffect { FOLLOW_UP, ATTACK_SPEED, ATTACK_BUFF, DEFENSE_BREAK, EVADE_BUFF,
        DEFENSE_BUFF, COUNTER, DAMAGE_REDUCTION, HEAL_HP, HEAL_MP, HEAL_ENERGY, ENERGY_SAVING, HEAL_ALL,
        HUNT_ORDER, MOON_DANCE }
    private record TimedAmount(double amount, long expiresAt) { }

    /** 1召喚個体の独自 HP とスキル状態です。 */
    private final class Session implements PetCombatActor {
        private final AstPlayer owner;
        private final LivingEntity entity;
        private RuntimePet pet;
        private StatusSnapshot baseOwnerStats;
        private final Map<String, Long> cooldowns = new HashMap<>();
        private final Map<String, Integer> attacks = new HashMap<>();
        private final Map<String, TimedAmount> reductions = new HashMap<>();
        private final Map<String, TimedAmount> speeds = new HashMap<>();
        private final Map<String, TimedAmount> attackBonuses = new HashMap<>();
        private double healthRatio;
        private boolean dead;
        private MobInstance basicTarget;
        private long basicTargetExpiresAt;

        /** 読み込み済み個体から召喚の状態を構成します。 */
        private Session(AstPlayer owner, RuntimePet pet, LivingEntity entity) {
            this.owner = owner; this.pet = pet; this.entity = entity;
            healthRatio = Math.max(0.0D, Math.min(1.0D, pet.healthRatio()));
            dead = pet.dead();
            cooldowns.putAll(pet.cooldowns());
            readOwnerStats();
        }

        /** 自身が付けたバフを計算入力から取り除き、循環的な主人依存を防ぎます。 */
        private void readOwnerStats() {
            statusService.getStatus(owner);
            baseOwnerStats = statusService.getStatusExcludingBuffs(owner,
                    buff -> buff.getType().getId().startsWith(BUFF_PREFIX));
        }

        /** 主人が通常アカウントで生存し、召喚個体も生存中かを判定します。 */
        private boolean active() {
            return !dead && entity.isValid() && owner.getBukkit().isOnline()
                    && AccountModeGuard.isGameplayPlayer(owner)
                    && (playerDeathService == null || !playerDeathService.isDead(owner.getBukkit().getUniqueId()));
        }

        /** @return 主人 */
        @Override public @NotNull AstPlayer owner() { return owner; }
        /** @return 召喚エンティティ */
        @Override public @NotNull LivingEntity entity() { return entity; }
        /** @return 現在 HP */
        @Override public double currentHealth() { return healthRatio * maxHealth(); }
        /** @return 主人依存の最大 HP */
        @Override public double maxHealth() { return Math.max(1.0D, baseOwnerStats.getMaxValue(StatusType.MAX_HEALTH) * pet.coefficients().vitality()); }
        /** @return 死亡済みの場合 true */
        @Override public boolean dead() { return dead; }
        /** @param type 参照ステータス @return ペットの主人換算後の値 */
        @Override public double statValue(@NotNull StatusType type) {
            return switch (type) {
                case MAX_HEALTH -> maxHealth();
                case ATTACK -> attackPower() * pet.coefficients().power() * attackMultiplier();
                case DEFENSE -> baseOwnerStats.getMaxValue(StatusType.DEFENSE) * pet.coefficients().defense();
                case EVASION -> Math.min(20.0D, baseOwnerStats.getMaxValue(StatusType.EVASION) + pet.coefficients().evasion() * 100.0D);
                case ACCURACY -> Math.max(100.0D, baseOwnerStats.getMaxValue(StatusType.ACCURACY));
                case CRITICAL_RATE, CRITICAL_DAMAGE -> baseOwnerStats.getMaxValue(type);
                default -> 0.0D;
            };
        }

        /** 主人の最も強い攻撃系統の解決攻撃力を参照します。 */
        private double attackPower() {
            AstEntity source = AstEntity.player(owner, baseOwnerStats);
            return Arrays.stream(AttackType.values()).mapToDouble(type -> DamageCalculator.calculateAttackPower(source, type)).max().orElse(0.0D);
        }

        /** @param amount 確定ダメージ。死亡で退場し、保存キャッシュへ即時反映します */
        @Override public void damage(double amount) {
            if (dead || !Double.isFinite(amount) || amount <= 0.0D) return;
            healthRatio = Math.max(0.0D, healthRatio - amount / maxHealth());
            if (healthRatio == 0.0D) dead = true;
            publish();
            if (dead) dismiss(owner);
        }

        /** HP・死亡・クールダウンをメインスレッドの保存キャッシュへ反映します。 */
        private void publish() { bridge.updateRuntime(owner, pet.instanceId(), healthRatio, dead, Map.copyOf(cooldowns)); }

        /** 主人が実際に命中した敵だけを、短時間の基本追撃対象として保持します。 */
        private void queueBasicAttack(MobInstance target) {
            if (pet.basicAttack() == null || target.state() == MobState.DEAD) return;
            basicTarget = target;
            basicTargetExpiresAt = System.currentTimeMillis() + 3000L;
            tickBasicAttack();
        }

        /** 必要なら射程まで接近し、基本追撃を一度適用します。自動索敵は行いません。 */
        private boolean tickBasicAttack() {
            BasicAttack basic = pet.basicAttack();
            long now = System.currentTimeMillis();
            if (basic == null || basicTarget == null || basicTargetExpiresAt <= now
                    || basicTarget.state() == MobState.DEAD || basicTarget.currentHealth() <= 0.0D
                    || basicTarget.template().category() == MobCategory.NPC
                    || basicTarget.currentLocation().getWorld() != entity.getWorld()
                    || owner.getBukkit().getLocation().distanceSquared(basicTarget.currentLocation()) > 576.0D
                    || cooldowns.getOrDefault(BASIC_ATTACK_COOLDOWN, 0L) > now
                    || conditionService != null && !conditionService.canAttack(AstEntity.pet(this))) return false;
            double distanceSq = entity.getLocation().distanceSquared(basicTarget.currentLocation());
            if (distanceSq > basic.range() * basic.range()) {
                if (entity instanceof Mob mob) {
                    double multiplier = conditionService == null ? 1.0D : conditionService.movementSpeedMultiplier(AstEntity.pet(this));
                    mob.getPathfinder().moveTo(basicTarget.currentLocation(), 1.15D * multiplier);
                }
                return true;
            }
            damageService.attack(AstEntity.pet(this), AstEntity.mob(basicTarget), AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, basic.damageRatio())), DamageSource.OTHER);
            cooldowns.put(BASIC_ATTACK_COOLDOWN, now + attackCooldown(basic.cooldownMillis(), now));
            basicTarget = null;
            publish();
            return false;
        }

        /** ペット自身の攻撃速度補助をクールダウンへ適用します。 */
        private long attackCooldown(long baseMillis, long now) {
            double speed = speeds.values().stream().filter(value -> value.expiresAt > now).mapToDouble(TimedAmount::amount).sum();
            return Math.max(250L, Math.round(Math.max(250L, baseMillis) / (1.0D + speed)));
        }

        /** 起点に一致する習得済みスキルだけを抽選します。 */
        private void trigger(SkillTrigger trigger, @Nullable MobInstance target) {
            if (conditionService != null && !conditionService.canCastSkill(AstEntity.pet(this))) return;
            long now = System.currentTimeMillis();
            for (PetSkill skill : pet.skills()) {
                if (skill.trigger() != trigger || cooldowns.getOrDefault(skill.id(), 0L) > now) continue;
                if (target != null && (target.state() == MobState.DEAD || target.currentHealth() <= 0.0D
                        || target.currentLocation().getWorld() != entity.getWorld()
                        || target.currentLocation().distanceSquared(entity.getLocation()) > 576.0D)) continue;
                int count = attacks.merge(skill.id(), 1, Integer::sum);
                if (count < Math.max(1, skill.attackCount())) continue;
                attacks.put(skill.id(), 0);
                if (ThreadLocalRandom.current().nextDouble() >= skill.chance()) continue;
                if (!apply(skill, target, now)) continue;
                long interval = Math.max(250L, skill.cooldownMillis());
                if (skill.effect() == SkillEffect.FOLLOW_UP || skill.effect() == SkillEffect.COUNTER) {
                    interval = attackCooldown(interval, now);
                }
                cooldowns.put(skill.id(), now + interval);
                publish();
            }
        }

        /** 効果量を支援力で補正し、共通戦闘・回復経路へ委譲します。 */
        private boolean apply(PetSkill skill, @Nullable MobInstance target, long now) {
            double value = skill.value() * (1.0D + pet.coefficients().support());
            long duration = Math.max(1000L, skill.durationMillis());
            long seconds = Math.max(1L, (duration + 999L) / 1000L);
            switch (skill.effect()) {
                case FOLLOW_UP, COUNTER -> {
                    if (target == null || target.state() == MobState.DEAD) return false;
                    for (int i = 0; i < Math.max(1, skill.hitCount()) && target.state() != MobState.DEAD; i++) {
                        damageService.attack(AstEntity.pet(this), AstEntity.mob(target), AttackType.MELEE,
                                List.of(new DamageComponent(DamageElement.NONE, skill.value())), DamageSource.OTHER);
                    }
                }
                case ATTACK_SPEED -> speeds.put(skill.id(), new TimedAmount(value, now + duration));
                case DEFENSE_BREAK -> {
                    if (target == null) return false;
                    defenseBreaks.put(target.instanceId(), new TimedAmount(Math.min(0.75D, value), now + duration));
                }
                case ATTACK_BUFF -> buff(skill, StatusType.ATTACK, baseAttackPower() * value, seconds);
                case HUNT_ORDER -> {
                    buff(skill, StatusType.ATTACK, baseAttackPower() * value, seconds);
                    attackBonuses.put(skill.id(), new TimedAmount(value, now + duration));
                }
                case MOON_DANCE -> {
                    buff(skill, StatusType.EVASION, value * 100.0D, seconds);
                    buff(skill, StatusType.DEFENSE, baseOwnerStats.getMaxValue(StatusType.DEFENSE) * value, seconds);
                }
                case DEFENSE_BUFF -> buff(skill, StatusType.DEFENSE, baseOwnerStats.getMaxValue(StatusType.DEFENSE) * value, seconds);
                case EVADE_BUFF -> buff(skill, StatusType.EVASION, value * 100.0D, seconds);
                case ENERGY_SAVING -> buff(skill, StatusType.ENERGY_COST_REDUCTION, value * 100.0D, seconds);
                case DAMAGE_REDUCTION -> reductions.put(skill.id(), new TimedAmount(Math.min(0.75D, value), now + duration));
                case HEAL_HP -> heal(skill, value, true, false, false);
                case HEAL_MP -> heal(skill, value, false, true, false);
                case HEAL_ENERGY -> heal(skill, value, false, false, true);
                case HEAL_ALL -> {
                    heal(skill, value, true, true, true);
                    if (skill.durationMillis() > 0L) {
                        UUID petId = pet.instanceId();
                        for (long delay = 20L; delay * 50L < skill.durationMillis(); delay += 20L) {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (active() && pet.instanceId().equals(petId)) heal(skill, value, true, true, true);
                            }, delay);
                        }
                    }
                }
            }
            return true;
        }

        /** 主人へID単位の一時固定バフを付与します。 */
        private void buff(PetSkill skill, StatusType type, double amount, long seconds) {
            if (amount <= 0.0D) return;
            buffService.applyTemporaryFlat(owner, BUFF_PREFIX + pet.instanceId() + ":" + skill.id() + ":" + type.getId(), skill.name(), type, amount, seconds);
            statusService.refreshStatus(owner);
        }

        /** 主人最大リソースに対する比率で回復し、既存の通知経路を使用します。 */
        private void heal(PetSkill skill, double ratio, boolean hp, boolean mp, boolean energy) {
            if (hp) statusService.recoverHp(owner, baseOwnerStats.getMaxValue(StatusType.MAX_HEALTH) * ratio,
                    HealthRecoveryContext.by(owner, skill.name()));
            if (mp) statusService.recoverMp(owner, baseOwnerStats.getMaxValue(StatusType.MAX_MANA) * ratio);
            if (energy) statusService.recoverEnergy(owner, baseOwnerStats.getMaxValue(StatusType.MAX_ENERGY) * ratio);
        }

        /** 猫の被ダメージ軽減を上限75%で集約します。 */
        private double reduction() {
            long now = System.currentTimeMillis();
            return Math.min(0.75D, reductions.values().stream().filter(value -> value.expiresAt > now).mapToDouble(TimedAmount::amount).sum());
        }

        /** 主人の基本攻撃力と最大種別攻撃力を合成し、属性能力補正は既存計算で適用します。 */
        private double baseAttackPower() {
            return baseOwnerStats.getMaxValue(StatusType.ATTACK) + Math.max(baseOwnerStats.getMaxValue(StatusType.MELEE_ATTACK),
                    Math.max(baseOwnerStats.getMaxValue(StatusType.RANGED_ATTACK), baseOwnerStats.getMaxValue(StatusType.MAGIC_ATTACK)));
        }

        /** 狩りの号令によるペット自身の攻撃倍率を集約します。 */
        private double attackMultiplier() {
            long now = System.currentTimeMillis();
            return 1.0D + attackBonuses.values().stream().filter(value -> value.expiresAt > now).mapToDouble(TimedAmount::amount).sum();
        }
    }
}
