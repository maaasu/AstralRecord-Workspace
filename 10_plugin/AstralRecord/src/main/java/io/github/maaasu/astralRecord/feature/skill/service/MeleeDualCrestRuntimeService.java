package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.MeleeDualCrestExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 二つの近接印を蓄積し、三撃目に有限頻度の追撃を行います。全操作は同期スレッド限定です。 */
public final class MeleeDualCrestRuntimeService {
    private final ActiveSkillServices services;
    private final StatusService statuses;
    private final DamageService damageService;
    private final MeleeDualCrestVisuals visuals;
    private final Map<UUID, State> states = new HashMap<>();
    private PassiveSkillService passives;
    private boolean applyingFollowUp;

    /**
     * 共通PvE対象・ダメージ・粒子経路で初期化します。
     * @param services スキル共通サービス群
     * @param statuses 現在ENGと消費軽減を解決するサービス
     * @param damageService 追撃から別のスキルブックへ派生しないよう通知を抑止するサービス
     */
    public MeleeDualCrestRuntimeService(@NotNull ActiveSkillServices services, @NotNull StatusService statuses,
                                      @NotNull DamageService damageService) {
        this.services = services;
        this.statuses = statuses;
        this.damageService = damageService;
        this.visuals = new MeleeDualCrestVisuals(services.effects());
    }

    /** @param passives 現在の装備許可・バインドを再評価するサービス。nullなら命中処理を停止 */
    public void setPassiveSkillService(@Nullable PassiveSkillService passives) { this.passives = passives; }

    /** @param context 有効化する習得個体。重複個体は蓄積を共有し、追撃を増やしません */
    public void activate(@NotNull PassiveSkillContext context) {
        AstPlayer owner = context.player();
        if (!currentSession(owner)) return;
        UUID id = owner.getBukkit().getUniqueId();
        State state = states.get(id);
        if (state == null || state.owner != owner) {
            if (state != null) clearPlayer(id);
            state = new State(owner);
            states.put(id, state);
        }
        state.bindings.put(bindingId(context), context.skill());
    }

    /** @param context 解除する習得個体。印と予約を破棄し、同一セッションの追撃CDを維持 */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID id = context.player().getBukkit().getUniqueId();
        State state = states.get(id);
        if (state == null || state.owner != context.player()) return;
        state.bindings.remove(bindingId(context));
        clearPlayer(id);
    }

    /** @param context 20tick周期の現個体。死亡・world変更と印の期限を検査。常時描画は行いません */
    public void tick(@NotNull PassiveSkillContext context) {
        if (!currentSession(context.player())) {
            State old = states.get(context.player().getBukkit().getUniqueId());
            if (old != null && old.owner == context.player()) {
                clearPlayer(context.player().getBukkit().getUniqueId());
                states.remove(context.player().getBukkit().getUniqueId());
            }
            return;
        }
        activate(context);
        State state = states.get(context.player().getBukkit().getUniqueId());
        resetIfNeeded(state, Bukkit.getCurrentTick());
    }

    /**
     * 共通ダメージ確定後の正のHP/Shield直撃を受け取ります。
     * @param owner 攻撃者の現セッション
     * @param target 元の命中対象。NPC・PvP・遠距離・遮蔽対象は共通選択で除外
     * @param attackType 元の攻撃種別。MELEEだけ蓄積
     * @param source 通常攻撃またはスキルだけ蓄積。DoTなどOTHERは対象外
     * @param attackWear 元攻撃の耐久消費単位。追撃にも共有
     */
    public void onDirectMobHit(@NotNull AstPlayer owner, @NotNull MobInstance target,
                              @NotNull AttackType attackType, @NotNull DamageSource source,
                              @Nullable SkillAttackWear attackWear) {
        if (applyingFollowUp || attackType != AttackType.MELEE || source == DamageSource.OTHER
                || !currentSession(owner) || passives == null
                || !passives.isPassiveSkillActive(owner, MeleeDualCrestExecutor.ID)) return;
        State state = states.get(owner.getBukkit().getUniqueId());
        if (state == null || state.owner != owner || state.bindings.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        resetIfNeeded(state, now);
        if (owner.getBukkit().isDead() || owner.getStatusSnapshot().getCurrentHp() <= 0.0D
                || state.pending || now < state.nextProc || now < state.nextHit) return;
        SkillDefinition definition = strongestDefinition(state);
        SkillParamReader params = new SkillParamReader(definition.getId(), definition.getParams());
        AstEntity eligible = eligibleTarget(owner, target, params);
        if (eligible == null) return;
        int threshold = params.getInt("hitThreshold", 3);
        state.expiry = now + params.getInt("crestDurationTicks", 120);
        state.nextHit = now + params.getInt("hitIntervalTicks", 10);
        if (state.crests < threshold - 1) {
            state.crests++;
            visuals.accumulate(owner.getBukkit().getLocation(), state.crests);
            return;
        }
        state.pending = true;
        long generation = state.generation;
        services.tasks().later(owner.getBukkit().getUniqueId(), MeleeDualCrestExecutor.ID, 1L,
                () -> completeFollowUp(state, generation, target, definition, attackWear));
    }

    /** 本体の消費が終わった次tickに対象と資源を再確認して追撃します。旧callbackは現状態を触りません。 */
    private void completeFollowUp(State state, long generation, MobInstance target,
                                  SkillDefinition definition, @Nullable SkillAttackWear attackWear) {
        AstPlayer owner = state.owner;
        UUID id = owner.getBukkit().getUniqueId();
        if (!currentSession(owner) || state.generation != generation || states.get(id) != state) return;
        if (passives == null || !passives.isPassiveSkillActive(owner, MeleeDualCrestExecutor.ID)
                || states.get(id) != state || state.generation != generation) return;
        state.pending = false;
        long now = Bukkit.getCurrentTick();
        resetIfNeeded(state, now);
        if (state.generation != generation || state.crests == 0) return;
        SkillParamReader params = new SkillParamReader(definition.getId(), definition.getParams());
        AstEntity eligible = eligibleTarget(owner, target, params);
        if (eligible == null) return;
        var snapshot = statuses.getStatus(owner);
        double reduction = snapshot.rollValue(StatusType.ENERGY_COST_REDUCTION);
        double cost = definition.getResourceCost() * (1.0D - (Double.isFinite(reduction)
                ? Math.clamp(reduction, 0.0D, 100.0D) : 0.0D) / 100.0D);
        if (snapshot.getCurrentEnergy() + 1.0E-6D < cost) return;
        state.crests = 0;
        state.nextProc = now + definition.getCooldownTicks();
        statuses.consumeEnergy(owner, cost);
        visuals.release(owner.getBukkit().getLocation(), services.targeting().center(eligible));
        applyingFollowUp = true;
        try {
            // 定義付き命中通知を使わず、本人の印・タグ追撃の再帰を防ぐ。
            DamageResult result = damageService.withSkillbookHitSuppressed(() ->
                    (attackWear == null ? services.combat() : services.combat().forAttackWear(attackWear))
                            .hit(AstEntity.player(owner), eligible, AttackType.MELEE, DamageElement.NONE,
                                    params.getDouble("damageRatio", 0.35D)));
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                visuals.impact(services.targeting().center(eligible), owner.getBukkit().getLocation());
            }
        } finally {
            applyingFollowUp = false;
        }
    }

    /** @param playerId 死亡・world移動・初期化で印/予約を解除するUUID。現バインドと追撃CDは維持 */
    public void clearPlayer(@NotNull UUID playerId) {
        State state = states.get(playerId);
        if (state == null) return;
        services.tasks().cancel(playerId, MeleeDualCrestExecutor.ID);
        state.generation++;
        state.crests = 0;
        state.nextHit = 0L;
        state.pending = false;
        state.world = state.owner.getBukkit().getWorld();
    }

    /** @param playerId 退出時にセッションの個体・印・CDをすべて破棄するBukkit UUID */
    public void forgetPlayer(@NotNull UUID playerId) {
        clearPlayer(playerId);
        states.remove(playerId);
    }
    /** Plugin停止時に全個体と印を破棄します。 */
    public void shutdown() {
        java.util.List.copyOf(states.keySet()).forEach(this::clearPlayer);
        states.clear(); passives = null;
    }

    /** マスタ再読込・共通初期化で、バインドと追撃CDを維持して全員の印・予約を解除します。 */
    public void resetAll() { java.util.List.copyOf(states.keySet()).forEach(this::clearPlayer); }

    /** 現在のAstPlayerだけが蓄積状態を変更できることを検証します。 */
    private boolean currentSession(AstPlayer owner) {
        return AstPlayerCache.get(owner.getBukkit()) == owner && !owner.isPassiveSkillSessionClosed()
                && owner.getBukkit().isOnline();
    }

    /** 死亡・world変更では印と予約、時間切れでは印だけを解除し、追撃CDは維持します。 */
    private void resetIfNeeded(State state, long now) {
        World world = state.owner.getBukkit().getWorld();
        if (state.world != world || state.owner.getBukkit().isDead()
                || state.owner.getStatusSnapshot().getCurrentHp() <= 0.0D) {
            clearPlayer(state.owner.getBukkit().getUniqueId());
        } else if (now >= state.expiry) state.crests = 0;
    }

    /** 追撃先を元の1体に固定し、共通PvE・体積射程・LOS条件を現在位置で検証します。 */
    private AstEntity eligibleTarget(AstPlayer owner, MobInstance target, SkillParamReader params) {
        return services.targeting().inSphere(owner.getBukkit(), owner.getBukkit().getEyeLocation(),
                params.getDouble("targetRange", 7.0D), Integer.MAX_VALUE, true).stream()
                .filter(candidate -> candidate.id().equals(target.instanceId()) && candidate.currentHealth() > 0.0D)
                .findFirst().orElse(null);
    }

    /** 重複バインドから最大倍率の一個だけを採用します。 */
    private SkillDefinition strongestDefinition(State state) {
        return state.bindings.values().stream().max(java.util.Comparator.comparingDouble(definition ->
                new SkillParamReader(definition.getId(), definition.getParams()).getDouble("damageRatio", 0.35D)))
                .orElseThrow();
    }

    /** 習得個体をライフサイクルの所有キーへ変換します。 */
    private UUID bindingId(PassiveSkillContext context) {
        return context.learnedSkill() == null ? new UUID(0L, 0L) : context.learnedSkill().getLearnedSkillId();
    }

    /** 現セッションだけに紐付く最大二印と内部CDです。 */
    private static final class State {
        private final AstPlayer owner;
        private final Map<UUID, SkillDefinition> bindings = new HashMap<>();
        private World world;
        private int crests;
        private long expiry;
        private long nextHit;
        private long nextProc;
        private long generation;
        private boolean pending;
        /** 所有者とworldを固定し、印を空で生成します。 */
        private State(AstPlayer owner) { this.owner = owner; this.world = owner.getBukkit().getWorld(); }
    }
}
