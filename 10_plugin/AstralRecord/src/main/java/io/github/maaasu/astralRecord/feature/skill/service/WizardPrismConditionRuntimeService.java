package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMagicCircleRegistry;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 状態異常の付与地点に、一度だけMPを回復するプリズムの魔法陣を維持します。 */
public final class WizardPrismConditionRuntimeService {
    private static final int PARTICLE_INTERVAL_TICKS = 4;
    private static final double VERTICAL_REACH = 2.0D;

    private final Plugin plugin;
    private final StatusService statusService;
    private final ParticleDisplayService particleDisplayService;
    private final SkillMagicCircleRegistry circleRegistry;
    private final Map<UUID, Map<String, Configuration>> configurations = new HashMap<>();
    private final List<Field> fields = new ArrayList<>();
    private BukkitTask task;

    /**
     * @param plugin 魔法陣の同期tickを登録するプラグイン
     * @param statusService MP回復サービス
     * @param particleDisplayService 近傍プレイヤーへの粒子表示サービス
     * @param circleRegistry 発動者ごとの現存魔法陣
     */
    public WizardPrismConditionRuntimeService(
            @NotNull Plugin plugin,
            @NotNull StatusService statusService,
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull SkillMagicCircleRegistry circleRegistry
    ) {
        this.plugin = plugin;
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
        this.circleRegistry = circleRegistry;
    }

    /**
     * バインドされたスキル個体の現在レベルの設定を登録します。
     * 呼び出し側は解決済みのスキル定義を持つ有効なパッシブコンテキストを渡します。
     *
     * @param context 有効化したプレイヤー、スキル個体、現在レベルの設定
     */
    public void activate(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        Configuration configuration = new Configuration(
                params.getDouble("radius", 0.0D),
                params.getInt("durationTicks", 0),
                params.getDouble("manaRecoveryRatio", 0.0D)
        );
        UUID casterId = context.player().getBukkit().getUniqueId();
        configurations.computeIfAbsent(casterId, ignored -> new HashMap<>())
                .put(configurationKey(context), configuration);
    }

    /**
     * バインドを解除したスキル個体の設定を消去します。
     * 最後の有効個体が解除された場合は、そのプレイヤーが作った魔法陣と不要な同期taskも破棄します。
     *
     * @param context 無効化するプレイヤーとスキル個体
     */
    public void deactivate(@NotNull PassiveSkillContext context) {
        UUID casterId = context.player().getBukkit().getUniqueId();
        Map<String, Configuration> byInstance = configurations.get(casterId);
        if (byInstance == null) {
            return;
        }
        byInstance.remove(configurationKey(context));
        if (byInstance.isEmpty()) {
            clearPlayer(casterId);
        }
    }

    /**
     * 攻撃で状態異常の付与または更新に成功した場所へ魔法陣を作ります。
     * 呼び出し側は付与成功後だけ通知します。攻撃元がバインド済みのプレイヤーなら魔法陣を追加し、
     * 必要に応じて同期taskを開始します。
     *
     * @param request 成功した状態異常の付与・更新要求
     */
    public void onConditionApplied(@NotNull ConditionApplyRequest request) {
        AstEntity source = request.source();
        if (request.reason() != ConditionApplyReason.SKILL
                || request.attackType() == null
                || source == null
                || !source.isPlayer()
                || source.player() == null) {
            return;
        }
        UUID casterId = source.player().getBukkit().getUniqueId();
        Configuration configuration = effectiveConfiguration(casterId);
        if (configuration == null) {
            return;
        }
        Location center = request.target().location().clone().add(0.0D, 0.08D, 0.0D);
        if (center.getWorld() == null) {
            return;
        }
        Field field = new Field(casterId, center, configuration, circleRegistry.register(casterId));
        fields.add(field);
        try {
            render(field);
            if (task == null) {
                task = Bukkit.getScheduler().runTaskTimer(plugin, this::advance, 1L, 1L);
            }
        } catch (RuntimeException exception) {
            fields.remove(field);
            circleRegistry.remove(field.circleId);
            throw exception;
        }
    }

    /**
     * 退出したプレイヤーの設定と、そのプレイヤーが作った魔法陣を破棄します。
     * 魔法陣がなくなった場合は同期taskも停止します。
     *
     * @param casterId 退出したプレイヤーのUUID
     */
    public void clearPlayer(@NotNull UUID casterId) {
        configurations.remove(casterId);
        fields.removeIf(field -> {
            if (!field.casterId.equals(casterId)) {
                return false;
            }
            circleRegistry.remove(field.circleId);
            return true;
        });
        stopIfEmpty();
    }

    /** プラグイン終了時に全プレイヤーの設定と魔法陣を消去し、同期taskを停止します。 */
    public void clearAll() {
        configurations.clear();
        fields.forEach(field -> circleRegistry.remove(field.circleId));
        fields.clear();
        stopIfEmpty();
    }

    private void advance() {
        try {
            Iterator<Field> iterator = fields.iterator();
            while (iterator.hasNext()) {
                Field field = iterator.next();
                if (field.remainingTicks <= 0 || recoverFirstPlayer(field)) {
                    circleRegistry.remove(field.circleId);
                    iterator.remove();
                    continue;
                }
                if (field.ageTicks % PARTICLE_INTERVAL_TICKS == 0) {
                    render(field);
                }
                field.ageTicks++;
                field.remainingTicks--;
            }
            stopIfEmpty();
        } catch (RuntimeException exception) {
            fields.forEach(field -> circleRegistry.remove(field.circleId));
            fields.clear();
            stopIfEmpty();
            throw exception;
        }
    }

    private boolean recoverFirstPlayer(@NotNull Field field) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!within(field, player)) {
                continue;
            }
            AstPlayer recipient = AstPlayerCache.get(player);
            if (recipient == null) {
                continue;
            }
            var before = statusService.getStatus(recipient);
            double recovery = before.getMaxValue(StatusType.MAX_MANA) * field.configuration.manaRecoveryRatio;
            var after = statusService.recoverFixedMp(recipient, recovery);
            if (after.getCurrentMp() > before.getCurrentMp()) {
                return true;
            }
        }
        return false;
    }

    private boolean within(@NotNull Field field, @NotNull Player player) {
        if (!player.isOnline() || player.isDead() || !field.center.getWorld().equals(player.getWorld())) {
            return false;
        }
        Location location = player.getLocation();
        double dx = location.getX() - field.center.getX();
        double dz = location.getZ() - field.center.getZ();
        return dx * dx + dz * dz <= field.configuration.radius * field.configuration.radius
                && Math.abs(location.getY() - field.center.getY()) <= VERTICAL_REACH;
    }

    private void render(@NotNull Field field) {
        Location center = field.center;
        double radius = field.configuration.radius;
        int outerPoints = Math.max(24, (int) Math.ceil(radius * 16.0D));
        List<Location> locations = new ArrayList<>(outerPoints + 30);
        for (int index = 0; index < outerPoints; index++) {
            double angle = 2.0D * Math.PI * index / outerPoints;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius));
        }
        for (int index = 0; index < 12; index++) {
            double angle = 2.0D * Math.PI * index / 12;
            locations.add(center.clone().add(
                    Math.cos(angle) * radius * 0.45D,
                    0.0D,
                    Math.sin(angle) * radius * 0.45D
            ));
        }
        for (int spoke = 0; spoke < 6; spoke++) {
            double angle = 2.0D * Math.PI * spoke / 6;
            for (int step = 1; step <= 3; step++) {
                double distance = radius * (0.55D + step * 0.10D);
                locations.add(center.clone().add(
                        Math.cos(angle) * distance,
                        0.0D,
                        Math.sin(angle) * distance
                ));
            }
        }
        particleDisplayService.spawnForNearbyViewers(
                center, locations, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST
        );
    }

    private @Nullable Configuration effectiveConfiguration(@NotNull UUID casterId) {
        Map<String, Configuration> byInstance = configurations.get(casterId);
        if (byInstance == null || byInstance.isEmpty()) {
            return null;
        }
        return byInstance.values().stream().max(Comparator.comparingDouble(Configuration::radius)).orElse(null);
    }

    private @NotNull String configurationKey(@NotNull PassiveSkillContext context) {
        return context.learnedSkill() == null
                ? context.skill().getId()
                : context.learnedSkill().getLearnedSkillId().toString();
    }

    private void stopIfEmpty() {
        if (fields.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private record Configuration(double radius, int durationTicks, double manaRecoveryRatio) {
    }

    private static final class Field {
        private final UUID casterId;
        private final UUID circleId;
        private final Location center;
        private final Configuration configuration;
        private int remainingTicks;
        private int ageTicks;

        private Field(UUID casterId, Location center, Configuration configuration, UUID circleId) {
            this.casterId = casterId;
            this.circleId = circleId;
            this.center = center;
            this.configuration = configuration;
            this.remainingTicks = configuration.durationTicks;
        }
    }
}
