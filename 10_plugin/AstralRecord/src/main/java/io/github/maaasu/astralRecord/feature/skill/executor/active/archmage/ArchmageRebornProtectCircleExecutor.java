package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.InvulnerabilityVisualService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 死亡地点に接する一人をその場で復活させる金色の水平魔法陣です。 */
public final class ArchmageRebornProtectCircleExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_reborn_protect_circle";
    private static final String CONFUSION_BUFF_ID = "archmage_battle_confusion";
    private static final int DISPLAY_INTERVAL_TICKS = 10;
    private static final double CONTACT_HEIGHT = 0.45D;
    private static final double GROUND_SEARCH_DEPTH = 8.0D;
    private static final double TWO_PI = Math.PI * 2.0D;

    private final PlayerDeathService playerDeathService;
    private final BossChallengeService bossChallengeService;
    private final DungeonService dungeonService;
    private final InvulnerabilityVisualService invulnerabilityVisualService;
    private final StatusService statusService;

    /**
     * 死亡状態、挑戦中の死亡回数、復活後効果を扱うサービスで初期化します。
     * @param services 発動スキルの共有サービス
     * @param playerDeathService 死亡地点とその場復活を管理するサービス
     * @param bossChallengeService ボス挑戦の死亡回数を管理するサービス
     * @param dungeonService ダンジョンの死亡回数を管理するサービス
     * @param invulnerabilityVisualService 復活後の無敵表示サービス
     * @param statusService 戦意の混乱を付与するステータスサービス
     */
    public ArchmageRebornProtectCircleExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PlayerDeathService playerDeathService,
            @NotNull BossChallengeService bossChallengeService,
            @NotNull DungeonService dungeonService,
            @NotNull InvulnerabilityVisualService invulnerabilityVisualService,
            @NotNull StatusService statusService
    ) {
        super(ID, services);
        this.playerDeathService = playerDeathService;
        this.bossChallengeService = bossChallengeService;
        this.dungeonService = dungeonService;
        this.invulnerabilityVisualService = invulnerabilityVisualService;
        this.statusService = statusService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : List.of("range", "radius")) {
            double value = params.getDouble(key, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0D) {
                throw new SkillParameterException(key, "リボーンプロテクトサークルの正数パラメータが必要です");
            }
        }
        if (params.getInt("durationTicks", 0) < 1) {
            throw new SkillParameterException("durationTicks", "魔法陣の維持時間は1tick以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        Location center = groundAtSight(context, params.getDouble("range", 16.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        double radius = params.getDouble("radius", 5.0D);
        int durationTicks = params.getInt("durationTicks", 400);
        UUID casterId = context.player().getUniqueId();
        String scope = ID + ":circle:" + UUID.randomUUID();
        Sigil sigil = new Sigil(center, radius);
        sigil.draw(context.services());
        UUID circleId = context.services().circles().register(casterId);
        try {
            context.services().tasks().repeat(casterId, scope, 1L, 1L, durationTicks, tick -> {
                if (tick % DISPLAY_INTERVAL_TICKS == 0) {
                    sigil.draw(context.services());
                }
                for (Player player : deadPlayersOnCircle(center, radius)) {
                    UUID playerId = player.getUniqueId();
                    AstPlayer target = AstPlayerCache.get(player);
                    if (target == null || !playerDeathService.recoverNow(playerId)) {
                        continue;
                    }
                    bossChallengeService.waivePendingDeathForRevival(playerId);
                    dungeonService.waivePendingDeathForRevival(playerId);
                    statusService.applyBuff(target, CONFUSION_BUFF_ID);
                    invulnerabilityVisualService.grantTemporary(player,
                            InvulnerabilityVisualService.THREE_SECONDS_TICKS);
                    context.services().effects().point(player.getLocation().add(0.0D, 1.0D, 0.0D),
                            SharedParticleDefinitions.SKILL_REBORN_PROTECT_LIGHT);
                    context.services().tasks().cancel(casterId, scope);
                    return;
                }
            }, () -> context.services().circles().remove(circleId));
        } catch (RuntimeException exception) {
            context.services().circles().remove(circleId);
            throw exception;
        }
        return context.success();
    }

    /**
     * 同じワールドの円面に死亡地点が接するオンラインプレイヤーを中心距離順で返します。
     * @param center 魔法陣の地表中心
     * @param radius 水平半径
     * @return 死亡地点が円上にあるプレイヤー
     */
    private @NotNull List<Player> deadPlayersOnCircle(@NotNull Location center, double radius) {
        List<Player> players = new ArrayList<>();
        World world = center.getWorld();
        if (world == null) {
            return players;
        }
        for (Player player : world.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            Location death = playerDeathService.lockLocation(player.getUniqueId());
            if (death == null || death.getWorld() != world
                    || Math.abs(death.getY() - center.getY()) > CONTACT_HEIGHT) {
                continue;
            }
            double dx = death.getX() - center.getX();
            double dz = death.getZ() - center.getZ();
            if (dx * dx + dz * dz <= radius * radius) {
                players.add(player);
            }
        }
        players.sort(Comparator.comparingDouble((Player player) -> {
            Location death = playerDeathService.lockLocation(player.getUniqueId());
            return death == null ? Double.POSITIVE_INFINITY : death.distanceSquared(center);
        }).thenComparing(player -> player.getUniqueId().toString()));
        return players;
    }

    /**
     * 視線が当たった上面か、その視点先の真下にある地表を水平な中心にします。
     * @param context 発動者の視線と対象判定
     * @param range 最大設置距離
     * @return 地表から少し浮かせた中心。地表がなければ null
     */
    private static @Nullable Location groundAtSight(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Vector direction = context.direction();
        SkillTargetingService.BlockHit hit = context.services().targeting().blockHit(eye, direction, range);
        Location sight = hit == null ? eye.clone().add(direction.multiply(range)) : hit.location();
        if (sight.getWorld() == null) {
            return null;
        }
        Location ground;
        if (hit != null && hit.normal().getY() > 0.5D) {
            ground = sight;
        } else {
            SkillTargetingService.BlockHit floor = context.services().targeting().blockHit(
                    sight.clone().add(0.0D, 0.5D, 0.0D),
                    new Vector(0.0D, -1.0D, 0.0D), GROUND_SEARCH_DEPTH);
            if (floor == null || floor.normal().getY() <= 0.5D) {
                return null;
            }
            ground = floor.location();
        }
        return ground.distanceSquared(eye) > range * range + 1.0E-6D
                ? null : ground.add(0.0D, 0.06D, 0.0D);
    }

    /** 添付図の同心円、四方星、放射線、三日月とルーンを金色の水平面へ描きます。 */
    private static final class Sigil {
        private final Location center;
        private final List<Location> bright = new ArrayList<>();
        private final List<Location> gold = new ArrayList<>();
        private final List<Location> amber = new ArrayList<>();

        private Sigil(@NotNull Location center, double radius) {
            this.center = center;
            ring(bright, radius * 0.98D, 72);
            ring(gold, radius * 0.85D, 64);
            ring(amber, radius * 0.62D, 52);
            ring(gold, radius * 0.38D, 40);
            for (int axis = 0; axis < 8; axis++) {
                double angle = axis * Math.PI / 4.0D;
                star(axis % 2 == 0 ? bright : amber, angle, radius * 0.88D,
                        radius * (axis % 2 == 0 ? 0.15D : 0.10D));
                line(gold, angle, radius * 0.40D, radius * 0.77D, 6);
            }
            for (int axis = 0; axis < 4; axis++) {
                double angle = Math.PI / 4.0D + axis * Math.PI / 2.0D;
                crescent(amber, angle, radius * 0.50D, radius * 0.13D);
            }
            star(bright, 0.0D, 0.0D, radius * 0.24D);
        }

        /** 一括した近傍閲覧者解決で三種類の金色粒子を送信します。 */
        private void draw(@NotNull ActiveSkillServices services) {
            services.effects().points(center, bright, SharedParticleDefinitions.SKILL_REBORN_PROTECT_BRIGHT);
            services.effects().points(center, gold, SharedParticleDefinitions.SKILL_REBORN_PROTECT_GOLD);
            services.effects().points(center, amber, SharedParticleDefinitions.SKILL_REBORN_PROTECT_AMBER);
        }

        /** 水平な同心円を点群へ変換します。 */
        private void ring(@NotNull List<Location> points, double radius, int count) {
            for (int index = 0; index < count; index++) {
                dot(points, TWO_PI * index / count, radius);
            }
        }

        /** 放射線を中心から外へ等間隔で配置します。 */
        private void line(@NotNull List<Location> points, double angle, double from, double to, int steps) {
            for (int index = 0; index <= steps; index++) {
                dot(points, angle, from + (to - from) * index / steps);
            }
        }

        /** 四方へ伸びる星の輪郭を水平面に描きます。 */
        private void star(@NotNull List<Location> points, double angle, double distance, double size) {
            double x = Math.cos(angle) * distance;
            double z = Math.sin(angle) * distance;
            for (int arm = 0; arm < 4; arm++) {
                double armAngle = angle + arm * Math.PI / 2.0D;
                double nextAngle = angle + (arm + 1) * Math.PI / 2.0D;
                for (int step = 0; step <= 4; step++) {
                    double t = step / 4.0D;
                    point(points,
                            x + size * ((1.0D - t) * Math.cos(armAngle)
                                    + t * Math.cos(nextAngle) * 0.27D),
                            z + size * ((1.0D - t) * Math.sin(armAngle)
                                    + t * Math.sin(nextAngle) * 0.27D));
                }
            }
        }

        /** 内周に配置する金色の三日月を二本の弧で表現します。 */
        private void crescent(@NotNull List<Location> points, double angle, double distance, double size) {
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            for (int index = 0; index <= 12; index++) {
                double t = -1.0D + 2.0D * index / 12.0D;
                double along = distance + size * (1.0D - t * t);
                double across = size * t;
                point(points, ux * along - uz * across, uz * along + ux * across);
                point(points, ux * (along - size * 0.38D) - uz * across,
                        uz * (along - size * 0.38D) + ux * across);
            }
        }

        /** 極座標から水平面の点を追加します。 */
        private void dot(@NotNull List<Location> points, double angle, double radius) {
            point(points, Math.cos(angle) * radius, Math.sin(angle) * radius);
        }

        /** Y座標を変えずに世界座標の点を追加します。 */
        private void point(@NotNull List<Location> points, double x, double z) {
            points.add(center.clone().add(x, 0.0D, z));
        }
    }
}
