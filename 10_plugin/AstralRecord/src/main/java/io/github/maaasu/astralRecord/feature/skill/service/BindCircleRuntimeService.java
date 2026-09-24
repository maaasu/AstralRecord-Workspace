package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobSkillService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** バインドサークル専用の拘束を管理します。状態異常のボス用時間短縮は適用しません。 */
public final class BindCircleRuntimeService {

    private static final int VISUAL_INTERVAL_TICKS = 5;
    private static final int DISPLAY_COUNT = 4;
    private final MobService mobService;
    private final MobSkillService mobSkillService;
    private final Map<UUID, BoundMob> boundMobs = new HashMap<>();

    /**
     * 管理対象 Mob の取得元を保持します。
     *
     * @param mobService Mob 実体と経路探索の管理サービス
     * @param mobSkillService 進行中の通常 Mob スキル詠唱を中断するサービス
     */
    public BindCircleRuntimeService(@NotNull MobService mobService, @NotNull MobSkillService mobSkillService) {
        this.mobService = mobService;
        this.mobSkillService = mobSkillService;
    }

    /**
     * 対象を現在位置で指定 tick 数だけ拘束します。同一 Mob の古い拘束は解除します。
     *
     * @param target 管理対象 Mob
     * @param durationTicks 拘束時間
     * @param effects 周辺プレイヤーへの粒子表示
     */
    public void bind(
            @NotNull MobInstance target,
            int durationTicks,
            @NotNull SkillEffectService effects
    ) {
        releaseForUltimate(target.instanceId());
        Entity entity = mobService.entityController().getEntity(target);
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        boolean originalAi = !(entity instanceof Mob mob) || mob.hasAI();
        if (entity instanceof Mob mob) {
            mob.setAI(false);
        }
        entity.setVelocity(new Vector());
        mobService.stopPathfinding(target);
        mobSkillService.interruptCast(target.instanceId());
        BoundMob state = new BoundMob(target.instanceId(), entity, originalAi);
        boundMobs.put(target.instanceId(), state);
        try {
            state.spawnDisplays();
            int[] elapsed = {0};
            state.task = mobService.plugin().getServer().getScheduler().runTaskTimer(mobService.plugin(), () -> {
                try {
                    if (!entity.isValid() || entity.isDead() || boundMobs.get(target.instanceId()) != state) {
                        releaseState(state);
                        return;
                    }
                    entity.setVelocity(new Vector());
                    mobService.stopPathfinding(target);
                    if (elapsed[0] % VISUAL_INTERVAL_TICKS == 0) {
                        state.draw(effects);
                    }
                    if (++elapsed[0] >= durationTicks) {
                        releaseState(state);
                    }
                } catch (RuntimeException exception) {
                    releaseState(state);
                    throw exception;
                }
            }, 0L, 1L);
        } catch (RuntimeException exception) {
            releaseState(state);
            throw exception;
        }
    }

    /**
     * Mob が通常行動を禁止される拘束中か判定します。
     *
     * @param instanceId Mob インスタンス UUID
     * @return 拘束中なら true
     */
    public boolean isBound(@NotNull UUID instanceId) {
        return boundMobs.containsKey(instanceId);
    }

    /**
     * 必殺技の開始前に対象の拘束と表示を解除します。
     *
     * @param instanceId Mob インスタンス UUID
     */
    public void releaseForUltimate(@NotNull UUID instanceId) {
        BoundMob state = boundMobs.get(instanceId);
        if (state != null) {
            releaseState(state);
        }
    }

    /** 全拘束を解除し、サーバー停止時に表示と Mob AI を復元します。 */
    public void stop() {
        for (BoundMob state : List.copyOf(boundMobs.values())) {
            releaseForUltimate(state.instanceId);
        }
    }

    /** 指定した拘束が現行状態であれば表示と AI 設定を復元します。 */
    private void releaseState(@NotNull BoundMob state) {
        if (!boundMobs.remove(state.instanceId, state)) {
            return;
        }
        if (state.task != null) {
            state.task.cancel();
        }
        state.displays.forEach(Entity::remove);
        state.displays.clear();
        if (state.entity.isValid() && state.entity instanceof Mob mob) {
            mob.setAI(state.originalAi);
        }
    }

    /** 拘束された個体に付随する表示と元の AI 状態です。 */
    private static final class BoundMob {
        private final UUID instanceId;
        private final Entity entity;
        private final boolean originalAi;
        private BukkitTask task;
        private final List<BlockDisplay> displays = new ArrayList<>(DISPLAY_COUNT);

        /** 一回の拘束の所有者、対象 Entity と元の AI 設定を保持します。 */
        private BoundMob(UUID instanceId, Entity entity, boolean originalAi) {
            this.instanceId = instanceId;
            this.entity = entity;
            this.originalAi = originalAi;
        }

        /** 対象の周囲へ四本の赤い BlockDisplay を作成します。 */
        private void spawnDisplays() {
            Location base = entity.getLocation();
            for (int index = 0; index < DISPLAY_COUNT; index++) {
                double angle = index * Math.PI * 2.0D / DISPLAY_COUNT;
                Location location = base.clone().add(Math.cos(angle) * 0.8D, 0.95D, Math.sin(angle) * 0.8D);
                BlockDisplay display = base.getWorld().spawn(location, BlockDisplay.class, spawned -> {
                    spawned.setBlock(Material.REDSTONE_BLOCK.createBlockData());
                    spawned.setPersistent(false);
                    spawned.setInvulnerable(true);
                    spawned.setTeleportDuration(VISUAL_INTERVAL_TICKS);
                    spawned.setTransformation(new Transformation(
                            new Vector3f(-0.08F, -0.48F, -0.08F),
                            new Quaternionf(), new Vector3f(0.16F, 0.96F, 0.16F), new Quaternionf()
                    ));
                });
                displays.add(display);
            }
        }

        /** BlockDisplay を対象へ追従させ、拘束が分かる二重の粒子環を表示します。 */
        private void draw(@NotNull SkillEffectService effects) {
            Location base = entity.getLocation();
            for (int index = 0; index < displays.size(); index++) {
                double angle = index * Math.PI * 2.0D / DISPLAY_COUNT;
                displays.get(index).teleport(base.clone().add(
                        Math.cos(angle) * 0.8D, 0.95D, Math.sin(angle) * 0.8D));
            }
            List<Location> points = new ArrayList<>(32);
            for (int index = 0; index < 16; index++) {
                double angle = index * Math.PI * 2.0D / 16.0D;
                for (double height : new double[]{0.35D, 1.55D}) {
                    points.add(base.clone().add(Math.cos(angle) * 0.85D, height, Math.sin(angle) * 0.85D));
                }
            }
            effects.points(base, points, SharedParticleDefinitions.BIND_CIRCLE_LOCK);
        }
    }
}
