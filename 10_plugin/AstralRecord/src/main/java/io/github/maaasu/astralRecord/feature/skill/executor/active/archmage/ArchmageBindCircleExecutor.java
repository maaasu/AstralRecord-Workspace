package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.BindCircleRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 視線先の水平面に設置し、接触した敵を引き寄せて一体だけ拘束する魔法です。 */
public final class ArchmageBindCircleExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_bind_circle";
    private static final int CIRCLE_LIFETIME_TICKS = 400;
    private static final int PULL_TICKS = 20;
    private static final int BIND_TICKS = 600;
    private static final int SIGIL_INTERVAL_TICKS = 10;
    private static final double CIRCLE_RADIUS = 4.0D;
    private final BindCircleRuntimeService binds;
    private final Plugin plugin;

    /**
     * 共有発動サービスと専用拘束状態を受け取ります。
     *
     * @param services 照準の共有サービス
     * @param binds Mob 拘束状態
     * @param plugin 設置中の寿命を発動者とは独立して管理するプラグイン
     */
    public ArchmageBindCircleExecutor(@NotNull ActiveSkillServices services,
                                      @NotNull BindCircleRuntimeService binds,
                                      @NotNull Plugin plugin) {
        super(ID, services);
        this.binds = binds;
        this.plugin = plugin;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        if (!(params.getDouble("range", 0.0D) > 0.0D)) {
            throw new SkillParameterException("range", "バインドサークルの射程は正数が必要です");
        }
        if (params.getDouble("radius", 0.0D) != CIRCLE_RADIUS
                || params.getInt("pullTicks", 0) != PULL_TICKS
                || params.getInt("bindTicks", 0) != BIND_TICKS
                || params.getInt("circleLifetimeTicks", 0) != CIRCLE_LIFETIME_TICKS) {
            throw new SkillParameterException("params", "バインドサークルの固定値と YAML が一致しません");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player caster = context.player();
        double range = context.params().getDouble("range", 16.0D);
        Location center = context.services().targeting().groundTarget(caster, range);
        CircleState state = new CircleState(context.services(), center, binds, caster.getUniqueId());
        try {
            state.spawn();
            state.searchContact(0);
            new BukkitRunnable() {
                private int tick;

                /** 設置済みの円を発動者の生死や現在Worldと無関係に進めます。 */
                @Override
                public void run() {
                    try {
                        if (state.tick(tick++)) {
                            state.destroy();
                            cancel();
                        }
                    } catch (RuntimeException exception) {
                        state.destroy();
                        cancel();
                        throw exception;
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
        return context.success();
    }

    /** 一回の設置から引き寄せ、拘束開始までの状態です。 */
    private static final class CircleState {
        private final ActiveSkillServices services;
        private final Location center;
        private final BindCircleRuntimeService binds;
        private final BindCircleParticleVisual visual;
        private final UUID casterId;
        private UUID circleId;
        private List<MobInstance> touched = List.of();
        private int pulledAt = -1;
        private boolean active = true;

        /** 発動時に固定された設置位置と描画点を保持します。 */
        private CircleState(ActiveSkillServices services, Location center,
                            BindCircleRuntimeService binds, UUID casterId) {
            this.services = services;
            this.center = center.clone();
            this.binds = binds;
            this.casterId = casterId;
            this.visual = new BindCircleParticleVisual(center);
        }

        /** 魔法陣を最初の一回描画します。 */
        private void spawn() {
            visual.draw(services.effects());
            services.effects().sound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7F, 0.85F);
            circleId = services.circles().register(casterId);
        }

        /** 接触待ち・引き寄せ・一体拘束の遷移を一 tick 進めます。 */
        private boolean tick(int tick) {
            if (!active) {
                return true;
            }
            if (pulledAt < 0 && tick >= CIRCLE_LIFETIME_TICKS) {
                return true;
            }
            if (pulledAt >= 0 && tick >= pulledAt + PULL_TICKS) {
                List<MobInstance> atBindStart = services.targeting().inRadius(
                        casterId, center, CIRCLE_RADIUS, 0.35D, Integer.MAX_VALUE, false).stream()
                        .map(AstEntity::mob).filter(java.util.Objects::nonNull).toList();
                MobInstance closest = java.util.stream.Stream.concat(touched.stream(), atBindStart.stream())
                        .filter(mob -> mob.state() != MobState.DEAD)
                        .filter(mob -> liveEntity(mob) != null)
                        .min(Comparator.comparingDouble(this::horizontalDistanceSquared)
                                .thenComparing(mob -> mob.instanceId().toString()))
                        .orElse(null);
                destroy();
                if (closest != null) {
                    binds.bind(closest, BIND_TICKS, services.effects());
                    if (binds.isBound(closest.instanceId())) {
                        Entity bound = liveEntity(closest);
                        if (bound != null) {
                            services.effects().sound(bound.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0F, 0.7F);
                        }
                    }
                }
                return true;
            }
            if (tick > 0 && tick % SIGIL_INTERVAL_TICKS == 0) {
                visual.draw(services.effects());
            }
            if (pulledAt < 0) {
                if (tick % 2 == 0 || tick == CIRCLE_LIFETIME_TICKS - 1) {
                    searchContact(tick);
                }
                return false;
            }
            List<Location> feedback = tick % 5 == 0 ? new ArrayList<>() : null;
            for (MobInstance mob : touched) {
                pull(mob, feedback);
            }
            showPullFeedback(feedback, false);
            return false;
        }

        /** 設置面に接触した敵を検出し、引き寄せを開始します。 */
        private void searchContact(int tick) {
            if (pulledAt >= 0) {
                return;
            }
            List<AstEntity> hits = services.targeting().inRadius(
                    casterId, center, CIRCLE_RADIUS, 0.35D, Integer.MAX_VALUE, false);
            if (hits.isEmpty()) {
                return;
            }
            touched = hits.stream().map(AstEntity::mob).filter(java.util.Objects::nonNull).toList();
            pulledAt = tick;
            List<Location> feedback = new ArrayList<>();
            for (MobInstance mob : touched) {
                pull(mob, feedback);
            }
            showPullFeedback(feedback, true);
        }

        /**
         * 衝突を保つ水平速度で敵を引き寄せ、中心へ向かう速度を持つ対象を表示点へ加えます。
         *
         * @param mob 引き寄せる管理対象Mob
         * @param feedback 表示間隔の対象点。表示しないtickではnull
         */
        private void pull(@NotNull MobInstance mob, List<Location> feedback) {
            Entity entity = liveEntity(mob);
            if (entity == null) {
                return;
            }
            Location position = entity.getLocation();
            Vector offset = center.toVector().subtract(position.toVector()).setY(0.0D);
            double distance = offset.length();
            if (distance <= 0.5D) {
                entity.setVelocity(new Vector());
            } else {
                // 衝突判定を保つため速度だけを与え、壁越しの座標変更は行わない。
                entity.setVelocity(offset.normalize().multiply(Math.min(0.35D, distance * 0.18D)));
            }
            if (feedback != null && distance > 0.5D
                    && entity.getVelocity().dot(center.toVector().subtract(position.toVector())) > 0.0D) {
                Vector toward = center.toVector().subtract(position.toVector()).setY(0.0D);
                for (int index = 0; index < 4; index++) {
                    feedback.add(position.clone().add(toward.clone().multiply((index + 1) / 5.0D))
                            .add(0.0D, 1.0D, 0.0D));
                }
            }
        }

        /**
         * 引き寄せが成立した対象の短い光線を一括描画し、初回だけ中心で音を鳴らします。
         *
         * @param feedback 実際に速度が変化した対象から集めた点
         * @param firstContact 接触した最初の一回ならtrue
         */
        private void showPullFeedback(List<Location> feedback, boolean firstContact) {
            if (feedback == null || feedback.isEmpty()) {
                return;
            }
            services.effects().pointsNearViewers(feedback, SharedParticleDefinitions.BIND_CIRCLE_LOCK);
            if (firstContact) {
                services.effects().sound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6F, 1.25F);
            }
        }

        /** 現在位置から円の中心までの水平距離二乗を返します。 */
        private double horizontalDistanceSquared(@NotNull MobInstance mob) {
            Entity entity = liveEntity(mob);
            if (entity == null) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = entity.getLocation().getX() - center.getX();
            double dz = entity.getLocation().getZ() - center.getZ();
            return dx * dx + dz * dz;
        }

        /** 同じワールドに生存する Bukkit 実体を返します。 */
        private Entity liveEntity(@NotNull MobInstance mob) {
            if (mob.bukkitEntityId() == null) {
                return null;
            }
            Entity entity = Bukkit.getEntity(mob.bukkitEntityId());
            return entity != null && entity.isValid() && !entity.isDead()
                    && entity.getWorld() == center.getWorld() ? entity : null;
        }

        /** 継続表示を終了します。すでに出した粒子は自然消滅します。 */
        private void destroy() {
            active = false;
            if (circleId != null) {
                services.circles().remove(circleId);
                circleId = null;
            }
        }
    }
}
