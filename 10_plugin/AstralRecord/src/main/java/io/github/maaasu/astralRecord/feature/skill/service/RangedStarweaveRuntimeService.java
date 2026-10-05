package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.SkillAttackWear;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.RangedStarweaveFollowupExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher.PhantomArcherRuntime;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 装備とバインドが有効な弓通常命中に、所有者ごと一回の星糸追矢を接続します。メインスレッド専用です。 */
public final class RangedStarweaveRuntimeService {
    private static final String SCOPE = "archer_starweave_followup";
    private final ActiveSkillServices services;
    private final InheritanceBuffService inheritance;
    private final PassiveSkillService passives;
    private final InventoryService inventory;
    private final SkillPermissionService permissions;
    private final DamageService damage;
    private final RangedStarweaveVisuals visuals;
    private final Map<UUID, State> states = new HashMap<>();

    /**
     * 追矢の有限処理と正規の装備・使用許可判定を接続します。
     * @param services 対象、戦闘、有限タスクの共有サービス
     * @param inheritance シャープシューターの現在の継承バフ判定
     * @param passives バインドされたパッシブの現在の有効性
     * @param inventory 装備中スキルブックのローカル正本
     * @param permissions 現職と使用許可の判定
     * @param damage 副次攻撃をスキルブック発動通知から除外するサービス
     * @param particles 閲覧者設定を尊重する表示サービス
     */
    public RangedStarweaveRuntimeService(@NotNull ActiveSkillServices services,
                                        @NotNull InheritanceBuffService inheritance,
                                        @NotNull PassiveSkillService passives,
                                        @NotNull InventoryService inventory,
                                        @NotNull SkillPermissionService permissions,
                                        @NotNull DamageService damage,
                                        @NotNull ParticleDisplayService particles) {
        this.services = services;
        this.inheritance = inheritance;
        this.passives = passives;
        this.inventory = inventory;
        this.permissions = permissions;
        this.damage = damage;
        this.visuals = new RangedStarweaveVisuals(particles);
    }

    /**
     * レベル反映済みのバインドを登録します。同一スキルの複数バインドは発動回数を増やしません。
     * @param context 有効になった習得個体。既存セッションの状態は破棄します
     */
    public void activate(@NotNull PassiveSkillContext context) {
        UUID owner = context.player().getBukkit().getUniqueId();
        State state = states.get(owner);
        if (state != null && state.player != context.player()) {
            removeState(owner);
            state = null;
        }
        if (state == null) {
            state = new State(context.player());
            states.put(owner, state);
        }
        state.bindings.put(bindingId(context), context.skill());
    }

    /**
     * バインドを解除し、最後の個体が失効したときは飛翔中の追矢も破棄します。
     * @param context 無効になった個体。旧セッションからの解除は現在の状態を変更しません
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID owner = context.player().getBukkit().getUniqueId();
        State state = states.get(owner);
        if (state == null || state.player != context.player()) return;
        state.bindings.remove(bindingId(context));
        if (state.bindings.isEmpty()) clearOwner(owner);
    }

    /**
     * 正規の弓通常攻撃の有効命中だけを受け、六秒に一度、同じ敵への追矢を始めます。
     * このメソッドはWeaponAttackSkillExecutorの通常弓着弾からだけ呼びます。
     * @param player 攻撃した現在のプレイヤー
     * @param target 命中した管理Mob
     * @param result 元攻撃の確定結果
     * @param launchEpoch 元弾の射出時の武器・職業・公開世代
     * @param attackWear 元弾と追矢で共有する攻撃側耐久消費状態
     */
    public void onNormalBowHit(@NotNull AstPlayer player, @NotNull AstEntity target,
                               @Nullable DamageResult result,
                               @NotNull PhantomArcherRuntime.ShotEpoch launchEpoch,
                               @NotNull SkillAttackWear attackWear) {
        if (!target.isMob() || !PhantomArcherRuntime.effective(result) || !active(player)) return;
        UUID owner = player.getBukkit().getUniqueId();
        if (!launchEpoch.equals(PhantomArcherRuntime.shotEpoch(owner))) return;
        State state = states.get(owner);
        if (state == null || state.player != player || Bukkit.getCurrentTick() < state.nextShotTick) return;
        SkillDefinition definition = strongestBinding(state);
        if (definition == null) return;
        SkillParamReader params = new SkillParamReader(definition.getId(), definition.getParams());
        double range = params.getDouble("range", 30.0D);
        if (!availableTarget(player, target, range)) return;
        double ratio = params.getDouble("followupRatio", 0.8D);
        if (PhantomArcherRuntime.marks(owner, target.id()) > 0 || inheritance.hasActiveInheritanceBuff(player)) {
            ratio += params.getDouble("comboBonusRatio", 0.4D);
        }
        state.nextShotTick = Bukkit.getCurrentTick() + definition.getCooldownTicks();
        Location origin = player.getBukkit().getEyeLocation();
        Vector initialDirection = services.targeting().center(target).toVector().subtract(origin.toVector());
        if (initialDirection.lengthSquared() > 1.0E-8D) origin.add(initialDirection.clone().normalize().multiply(0.35D));
        visuals.launch(origin, initialDirection);
        launch(state, target, origin, ratio, range, params.getInt("flightTicks", 6), launchEpoch, attackWear);
    }

    /**
     * 一回だけの六tick追矢を三段階で進め、地形遮蔽・終了・持ち替えでは以後を停止します。
     * @param expected 発生時の所有者状態
     * @param target 元矢が命中した管理Mob
     * @param origin 追矢の始点
     * @param ratio 発生時に確定した追矢倍率
     * @param range 所有者からの最大射程
     * @param flightTicks 検証済みの飛翔tick数
     * @param launchEpoch 元弾の射出世代
     * @param wear 元弾と共有する攻撃側耐久状態
     */
    private void launch(State expected, AstEntity target, Location origin, double ratio, double range,
                        int flightTicks, PhantomArcherRuntime.ShotEpoch launchEpoch, SkillAttackWear wear) {
        AstPlayer owner = expected.player;
        UUID ownerId = owner.getBukkit().getUniqueId();
        UUID flightToken = expected.flightToken;
        Location[] previous = {origin.clone()};
        services.tasks().repeat(ownerId, SCOPE, 2L, 2L, flightTicks / 2, step -> {
            if (!active(owner) || states.get(ownerId) != expected || !expected.flightToken.equals(flightToken)
                    || !launchEpoch.equals(PhantomArcherRuntime.shotEpoch(ownerId))
                    || !availableTarget(owner, target, range)) {
                services.tasks().cancel(ownerId, SCOPE);
                return;
            }
            Location destination = services.targeting().center(target);
            double progress = (step + 1.0D) / (flightTicks / 2.0D);
            Location next = origin.clone().add(destination.toVector().subtract(origin.toVector()).multiply(progress));
            if (!services.targeting().hasLineOfSight(previous[0], next)) {
                services.tasks().cancel(ownerId, SCOPE);
                return;
            }
            Vector direction = next.toVector().subtract(previous[0].toVector());
            visuals.flight(previous[0], next, step);
            previous[0] = next;
            if (step + 1 == flightTicks / 2) {
                // 元弾の耐久状態を共有し、book同士の連鎖、継承と霊印の再発動は呼ばない。
                DamageResult hit = damage.withSkillbookHitSuppressed(() ->
                        services.combat().forAttackWear(wear).hit(AstEntity.player(owner), target,
                                AttackType.RANGED, DamageElement.NONE, ratio));
                if (PhantomArcherRuntime.effective(hit)) visuals.impact(destination, direction);
            }
        });
    }

    /**
     * 現在の装備許可を毎回確認し、解除直後の飛翔からの攻撃を防ぎます。
     * @param player 現在の所有者
     * @return 装備・使用許可・パッシブバインドがすべて有効ならtrue
     */
    private boolean active(AstPlayer player) {
        Player bukkit = player.getBukkit();
        return bukkit.isOnline() && !bukkit.isDead() && PhantomArcherRuntime.hasBow(player)
                && inventory.getEquippedSkillbookUsableSkills(player).contains("skill:" + RangedStarweaveFollowupExecutor.ID)
                && permissions.isPermitted(player, RangedStarweaveFollowupExecutor.ID)
                && passives.isPassiveSkillActive(player, RangedStarweaveFollowupExecutor.ID);
    }

    /**
     * 生存・同ワールド・射程と、共有対象サービスの現在のMob攻撃許可を確認します。
     * @param player 追矢の所有者
     * @param target 元矢の命中対象
     * @param range 射程m
     * @return 生存し、現在も攻撃可能な管理Mobならtrue
     */
    private boolean availableTarget(AstPlayer player, AstEntity target, double range) {
        if (!target.isMob() || target.mob() == null || target.mob().state() == MobState.DEAD
                || target.currentHealth() <= 0.0D || target.location().getWorld() != player.getBukkit().getWorld()) return false;
        Location center = services.targeting().center(target);
        if (player.getBukkit().getEyeLocation().distanceSquared(center) > range * range) return false;
        return services.targeting().inSphere(player.getBukkit(), center, 0.01D, Integer.MAX_VALUE, false)
                .stream().anyMatch(candidate -> candidate.id().equals(target.id()));
    }

    /**
     * 重複バインドは倍率の最大値だけを選び、加算しません。
     * @param state 所有者の有効バインド集合
     * @return 倍率最大のレベル反映済み定義。バインドなしならnull
     */
    private static SkillDefinition strongestBinding(State state) {
        return state.bindings.values().stream().max(Comparator.comparingDouble(definition ->
                new SkillParamReader(definition.getId(), definition.getParams()).getDouble("followupRatio", 0.8D)))
                .orElse(null);
    }

    /**
     * 習得個体を一意に識別し、互換contextだけは固定キーに集約します。
     * @param context 有効または解除するパッシブ個体
     * @return 習得個体UUID
     */
    private static UUID bindingId(PassiveSkillContext context) {
        return context.learnedSkill() == null ? new UUID(0L, 0L) : context.learnedSkill().getLearnedSkillId();
    }

    /**
     * 死亡・持ち替え・転職・challenge終了等で、所有者の飛翔を即時停止します。
     * 再使用待機と有効バインドを保持し、中断を利用した待機リセットを防ぎます。
     * @param owner 停止するBukkitプレイヤーUUID
     */
    public void clearOwner(@NotNull UUID owner) {
        services.tasks().cancel(owner, SCOPE);
        State state = states.get(owner);
        if (state != null) state.flightToken = UUID.randomUUID();
    }

    /** マスタ公開・challenge終了時に全飛翔を止め、有効バインドと再使用待機は保持します。 */
    public void clearAll() {
        states.keySet().forEach(this::clearOwner);
    }

    /** Plugin停止時に飛翔タスクと所有者状態をすべて破棄します。 */
    public void stop() {
        clearAll();
        states.clear();
    }

    /**
     * 退出したセッションのバインドと飛翔を破棄します。死亡・移動にはclearOwnerを使います。
     * @param owner 退出するBukkitプレイヤーUUID
     */
    public void forgetOwner(@NotNull UUID owner) { removeState(owner); }

    /**
     * 退出と旧セッション置換で担当状態を破棄します。
     * @param owner 破棄するBukkitプレイヤーUUID
     */
    private void removeState(UUID owner) {
        clearOwner(owner);
        states.remove(owner);
    }

    private static final class State {
        private final AstPlayer player;
        private final Map<UUID, SkillDefinition> bindings = new HashMap<>();
        private long nextShotTick;
        private UUID flightToken = UUID.randomUUID();

        /** 一つの現在セッションだけに状態を関連付けます。 */
        private State(AstPlayer player) { this.player = player; }
    }
}
