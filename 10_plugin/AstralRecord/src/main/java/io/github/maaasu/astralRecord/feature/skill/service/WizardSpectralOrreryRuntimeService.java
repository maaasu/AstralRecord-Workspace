package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.model.SkillAttackWear;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.WizardSpectralOrreryExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 魔法有効命中の三属性を発動者別に蓄積し、天球儀の追撃を一度だけ発生させます。 */
public final class WizardSpectralOrreryRuntimeService {
    private static final String PROC_SCOPE = "wizard-spectral-orrery:proc";
    private final ActiveSkillServices services;
    private final StatusService statuses;
    private final DamageService damage;
    private final WizardSpectralOrreryVisuals visuals;
    private final Map<UUID, State> states = new HashMap<>();
    private PassiveSkillService passives;
    private boolean applyingOrrery;

    /**
     * メインスレッドで使う共通戦闘・対象選択・演出サービスを接続します。
     * @param services 共通のスキルサービス群
     * @param statuses MPを確認・消費するサービス
     * @param damage 副次攻撃のスキルブック通知を抑止するダメージサービス
     */
    public WizardSpectralOrreryRuntimeService(@NotNull ActiveSkillServices services,
            @NotNull StatusService statuses, @NotNull DamageService damage) {
        this.services = services;
        this.statuses = statuses;
        this.damage = damage;
        this.visuals = new WizardSpectralOrreryVisuals(services);
    }

    /**
     * 装備変更後の未反映許可を命中・追撃直前に再照合するサービスを接続します。
     * @param passives 共通パッシブサービス。初期登録が完了した後、戦闘開始前に接続します
     */
    public void setPassiveSkillService(@NotNull PassiveSkillService passives) { this.passives = passives; }

    /**
     * 現在セッションのバインド個体を登録します。同時個体は最高倍率だけを使います。
     * @param context 解決済みパラメーターを持つ有効パッシブ
     */
    public void activate(@NotNull PassiveSkillContext context) {
        AstPlayer player = context.player();
        if (!sessionValid(player)) return;
        State state = currentState(player);
        if (state == null) {
            state = new State(player);
            states.put(player.getBukkit().getUniqueId(), state);
        }
        state.bindings.put(bindingId(context), readParameters(context.skill()));
    }

    /**
     * バインドを解除します。最後の個体では連携と予約攻撃を破棄し、内部CDは保持します。
     * @param context 解除されるパッシブ個体
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        State state = states.get(context.player().getBukkit().getUniqueId());
        if (state == null || state.owner != context.player()) return;
        state.bindings.remove(bindingId(context));
        if (state.bindings.isEmpty()) resetChain(state);
    }

    /**
     * HPかShieldへ有効命中した魔法の属性を受け取ります。呼出元で回避・副次通知を除外します。
     * 通常攻撃とスキルの火・氷・雷だけを蓄積し、同一属性の連打は期限を延長しません。
     * @param player 攻撃者
     * @param target 最後に命中した管理Mob
     * @param attackType 参照攻撃種別
     * @param components 実際の攻撃を構成する属性倍率
     * @param source 通常攻撃またはスキル
     * @param wear 元攻撃の耐久消費状態。未設定なら追撃専用の状態を一つ作ります
     */
    public void onMagicMobHit(@NotNull AstPlayer player, @NotNull MobInstance target,
            @NotNull AttackType attackType, @NotNull List<DamageComponent> components,
            @NotNull DamageSource source, @Nullable SkillAttackWear wear) {
        if (applyingOrrery || attackType != AttackType.MAGIC
                || (source != DamageSource.SKILL && source != DamageSource.NORMAL_ATTACK)) return;
        if (!hasPermission(player)) return;
        State state = currentState(player);
        if (state == null || state.bindings.isEmpty() || state.pending) return;
        long now = Bukkit.getCurrentTick();
        if (now < state.nextReadyTick) return;
        Parameters params = parameters(state);
        World world = player.getBukkit().getWorld();
        Location center = target.currentLocation().clone().add(0.0D, 0.9D, 0.0D);
        if (center.getWorld() != world || player.getBukkit().getLocation().distanceSquared(center)
                > params.range() * params.range()) return;
        if (state.chainWorld != world || now > state.expiresTick) state.elements.clear();
        boolean newElement = false;
        for (DamageComponent component : components) {
            if (component.ratio() > 0.0D && Double.isFinite(component.ratio())
                    && isTriadElement(component.element())) {
                if (state.elements.isEmpty()) {
                    state.chainWorld = world;
                    state.expiresTick = now + params.windowTicks();
                }
                if (state.elements.add(component.element())) {
                    visuals.charge(player, component.element(), state.elements.size());
                    newElement = true;
                }
            }
        }
        if (!newElement || state.elements.size() != 3) return;
        state.elements.clear();
        state.pending = true;
        SkillAttackWear procWear = wear == null ? new SkillAttackWear(player.getBukkit().getUniqueId()) : wear;
        // 本体の共通MP消費を終えた後に残量を判定するため、追撃は次tickへ予約する。
        services.tasks().later(player.getBukkit().getUniqueId(), PROC_SCOPE, 1L,
                () -> release(state, center, params, procWear));
    }

    /**
     * 退出・アカウント切替時に所有セッションの状態と短命演出を破棄します。
     * @param player 終了するセッション。旧callbackは新セッションを消しません
     */
    public void clearPlayer(@NotNull AstPlayer player) {
        UUID id = player.getBukkit().getUniqueId();
        State state = states.get(id);
        if (state != null && state.owner == player) {
            resetChain(state);
            states.remove(id);
        }
    }

    /**
     * 死亡・ワールド移動時はバインドとCDを保持し、途中の連携だけを破棄します。
     * @param player 対象セッション
     */
    public void resetPlayer(@NotNull AstPlayer player) {
        State state = states.get(player.getBukkit().getUniqueId());
        if (state != null && state.owner == player) resetChain(state);
    }

    /** Plugin停止時に全連携と予約攻撃を破棄します。 */
    public void clearAll() {
        for (State state : states.values()) resetChain(state);
        states.clear();
    }

    /** マスタ再読込時にバインドを保持し、全連携・予約攻撃・短命演出だけを破棄します。 */
    public void resetAll() { for (State state : states.values()) resetChain(state); }

    /** 本体消費後のMPと共通の消費軽減を再確認し、同じ耐久単位で三属性追撃を一回適用します。 */
    private void release(State state, Location center, Parameters params, SkillAttackWear wear) {
        state.pending = false;
        if (!hasPermission(state.owner) || currentState(state.owner) != state || state.bindings.isEmpty()
                || state.owner.getBukkit().getWorld() != center.getWorld()
                || state.owner.getBukkit().getLocation().distanceSquared(center) > params.range() * params.range()) return;
        var status = statuses.getStatus(state.owner);
        double manaCost = SkillService.resolveResourceCost(status, SkillResourceType.MANA, params.manaCost());
        if (status.getCurrentMp() < manaCost) return;
        var targets = services.targeting().inSphere(state.owner.getBukkit(), center,
                params.radius(), params.maxTargets(), true);
        if (targets.isEmpty()) return;
        statuses.consumeMp(state.owner, manaCost);
        state.nextReadyTick = Bukkit.getCurrentTick() + params.cooldownTicks();
        var combat = services.combat().forAttackWear(wear);
        applyingOrrery = true;
        try {
            damage.withSkillbookHitSuppressed(() -> {
                visuals.release(state.owner, center, params.radius());
                for (AstEntity target : targets) {
                    var result = combat.hit(AstEntity.player(state.owner), target, AttackType.MAGIC,
                            List.of(new DamageComponent(DamageElement.FIRE, params.damageRatio()),
                                    new DamageComponent(DamageElement.ICE, params.damageRatio()),
                                    new DamageComponent(DamageElement.LIGHTNING, params.damageRatio())));
                    if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                        visuals.impact(target.location().add(0.0D, 0.9D, 0.0D));
                    }
                }
                return null;
            });
        } finally {
            applyingOrrery = false;
        }
    }

    /** バインド個体を最高倍率で選び、同じスキルの複数個体による追撃重複を防ぎます。 */
    private static Parameters parameters(State state) {
        return state.bindings.values().stream().max(Comparator.comparingDouble(Parameters::damageRatio))
                .orElseThrow();
    }

    /** 現在のログイン・アカウント所有権を確認し、古い状態は破棄します。 */
    private State currentState(AstPlayer player) {
        if (!sessionValid(player)) return null;
        State state = states.get(player.getBukkit().getUniqueId());
        if (state != null && (state.owner != player || !state.accountId.equals(player.getAccount().getUuid()))) {
            clearPlayer(state.owner);
            return null;
        }
        return state;
    }

    /** 生存中のゲームプレイセッションだけを処理します。 */
    private static boolean sessionValid(AstPlayer player) {
        return !player.isPassiveSkillSessionClosed() && AstPlayerCache.get(player.getBukkit()) == player
                && player.getBukkit().isOnline() && !player.getBukkit().isDead();
    }

    /** 失効した装備使用許可を共通reconcile経路で更新し、現在のバインド有効性を確認します。 */
    private boolean hasPermission(AstPlayer player) {
        return sessionValid(player) && passives != null
                && passives.isPassiveSkillActive(player, WizardSpectralOrreryExecutor.ID);
    }

    /** 保留処理と演出を停止し、途中の三属性を消去します。 */
    private void resetChain(State state) {
        UUID id = state.owner.getBukkit().getUniqueId();
        services.tasks().cancel(id, PROC_SCOPE);
        visuals.clear(id);
        state.elements.clear();
        state.chainWorld = null;
        state.pending = false;
    }

    /** 火・氷・雷の連携対象属性かを判定します。 */
    private static boolean isTriadElement(DamageElement element) {
        return element == DamageElement.FIRE || element == DamageElement.ICE || element == DamageElement.LIGHTNING;
    }

    /** 習得個体UUIDがない管理用コンテキストにも安定した識別子を割り当てます。 */
    private static UUID bindingId(PassiveSkillContext context) {
        return context.learnedSkill() == null
                ? UUID.nameUUIDFromBytes(context.skill().getId().getBytes(StandardCharsets.UTF_8))
                : context.learnedSkill().getLearnedSkillId();
    }

    /**
     * パラメーターを有限かつ描画・対象数の上限内で検証します。
     * @param skill レベル解決済みスキル定義
     * @return 不変の実行パラメーター
     * @throws SkillParameterException 型、有限性、値域が契約外の場合
     */
    public static @NotNull Parameters readParameters(@NotNull SkillDefinition skill) {
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        double ratio = bounded(p, "damageRatio", 0.40D, 0.01D, 2.0D);
        double radius = bounded(p, "radius", 2.5D, 0.1D, 5.0D);
        double range = bounded(p, "range", 24.0D, 1.0D, 32.0D);
        double cost = skill.getResourceCost() == null ? Double.NaN : skill.getResourceCost();
        long cooldownTicks = skill.getCooldownTicks();
        if (skill.getResourceType() != SkillResourceType.MANA || !Double.isFinite(cost)
                || cost < 1.0D || cost > 100.0D || cooldownTicks < 40L || cooldownTicks > 1200L) {
            throw new SkillParameterException("resourceCost/cooldownTicks", "天球儀の共通消費・CDが許容範囲外です");
        }
        int maxTargets = integer(p, "maxTargets", 3, 1, 5);
        int window = integer(p, "chainWindowTicks", 200, 20, 400);
        int cooldown = (int) cooldownTicks;
        return new Parameters(ratio, radius, range, cost, maxTargets, window, cooldown);
    }

    /** 数値パラメーターの有限性と上下限を検証します。 */
    private static double bounded(SkillParamReader p, String key, double fallback, double min, double max) {
        double value = p.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new SkillParameterException(key, "天球儀の値が許容範囲外です");
        }
        return value;
    }

    /** 整数パラメーターの型と上下限を検証します。 */
    private static int integer(SkillParamReader p, String key, int fallback, int min, int max) {
        int value = p.getInt(key, fallback);
        if (value < min || value > max) throw new SkillParameterException(key, "天球儀の値が許容範囲外です");
        return value;
    }

    /**
     * 型と値域を検証した天球儀の契約です。
     * @param damageRatio 火・氷・雷それぞれの攻撃倍率
     * @param radius 追撃の球体半径
     * @param range 連携と追撃を認める発動者からの最大距離
     * @param manaCost 共通定義から取得するMP消費
     * @param maxTargets 一回の追撃の対象上限
     * @param windowTicks 最初の属性から三属性が揃うまでの制限tick
     * @param cooldownTicks 共通定義から取得する成立後の内部CD
     */
    public record Parameters(double damageRatio, double radius, double range, double manaCost,
            int maxTargets, int windowTicks, int cooldownTicks) { }

    private static final class State {
        private final AstPlayer owner;
        private final UUID accountId;
        private final Map<UUID, Parameters> bindings = new HashMap<>();
        private final EnumSet<DamageElement> elements = EnumSet.noneOf(DamageElement.class);
        private World chainWorld;
        private long expiresTick;
        private long nextReadyTick;
        private boolean pending;

        /** @param owner 状態を所有するログイン・アカウントセッション */
        private State(AstPlayer owner) {
            this.owner = owner;
            this.accountId = owner.getAccount().getUuid();
        }
    }
}
