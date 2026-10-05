package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 三つの傾斜軌道と結晶の収束・散開で天球儀の連携を表現します。攻撃判定は持ちません。 */
final class WizardSpectralOrreryVisuals {
    private static final String VISUAL_SCOPE = "wizard-spectral-orrery:visual";
    private static final List<SharedParticleDefinition> ELEMENTS = List.of(
            SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST,
            SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST,
            SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
    private final ActiveSkillServices services;

    /** @param services 有限回数のtaskと共通ParticleDisplayServiceへ委譲する演出サービス */
    WizardSpectralOrreryVisuals(@NotNull ActiveSkillServices services) { this.services = services; }

    /**
     * 新しく集めた属性を発動者の右肩に小さな三角結晶として表示します。
     * @param owner 発動者
     * @param element 追加された属性
     * @param count 現在集めた属性数。音程へ反映します
     */
    void charge(@NotNull AstPlayer owner, @NotNull DamageElement element, int count) {
        Location eye = owner.getBukkit().getEyeLocation();
        Vector forward = eye.getDirection().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-8D) forward.setZ(1.0D);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        Location center = eye.clone().add(right.clone().multiply(0.6D)).add(0.0D, -0.15D, 0.0D);
        int index = switch (element) { case FIRE -> 0; case ICE -> 1; default -> 2; };
        List<Location> facet = new ArrayList<>(24);
        for (int edge = 0; edge < 3; edge++) {
            Vector a = vertex(edge, 0.28D, right);
            Vector b = vertex((edge + 1) % 3, 0.28D, right);
            for (int point = 0; point < 8; point++) {
                facet.add(center.clone().add(a.clone().multiply(1.0D - point / 8.0D))
                        .add(b.clone().multiply(point / 8.0D)));
            }
        }
        services.effects().points(center, facet, ELEMENTS.get(index));
        services.effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.24F, 0.95F + count * 0.18F);
    }

    /**
     * 攻撃地点へ三つの斜め軌道を重ね、短い散開の余韻を表示します。
     * @param owner taskを所有する発動者。死亡・セッション切替・world移動で停止します
     * @param center 固定された追撃中心
     * @param radius 実際の攻撃半径
     */
    void release(@NotNull AstPlayer owner, @NotNull Location center, double radius) {
        frame(center, radius, 0);
        services.effects().point(center, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_IMPACT.withCount(18));
        services.effects().sound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.60F, 1.30F);
        services.effects().sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 0.42F, 1.65F);
        UUID id = owner.getBukkit().getUniqueId();
        services.tasks().repeat(id, VISUAL_SCOPE, 2L, 2L, 6, index -> {
            if (!owner.getBukkit().isOnline() || owner.getBukkit().isDead()
                    || owner.isPassiveSkillSessionClosed() || AstPlayerCache.get(owner.getBukkit()) != owner
                    || owner.getBukkit().getWorld() != center.getWorld()) {
                clear(id);
                return;
            }
            frame(center, radius, index + 1);
            if (index == 2) services.effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.32F, 1.75F);
        });
    }

    /**
     * 有効命中した敵だけに白紫の八面体と三方向の結晶片を表示します。
     * @param center 敵の実命中地点
     */
    void impact(@NotNull Location center) {
        List<Location> edges = new ArrayList<>(72);
        for (int side = 0; side < 4; side++) {
            double angle = Math.PI * 0.5D * side;
            Vector equator = new Vector(Math.cos(angle) * 0.38D, 0.0D, Math.sin(angle) * 0.38D);
            Vector next = new Vector(Math.cos(angle + Math.PI * 0.5D) * 0.38D,
                    0.0D, Math.sin(angle + Math.PI * 0.5D) * 0.38D);
            for (Vector endpoint : List.of(new Vector(0.0D, 0.65D, 0.0D),
                    new Vector(0.0D, -0.45D, 0.0D), next)) {
                for (int point = 0; point < 6; point++) {
                    double t = point / 5.0D;
                    edges.add(center.clone().add(equator.clone().multiply(1.0D - t))
                            .add(endpoint.clone().multiply(t)));
                }
            }
        }
        services.effects().points(center, edges, SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
        services.effects().point(center, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_SPARK.withCount(8));
        services.effects().sound(center, Sound.BLOCK_GLASS_BREAK, 0.30F, 1.85F);
    }

    /** @param id 停止する発動者UUID。表示Entityや魔法陣登録はありません */
    void clear(@NotNull UUID id) { services.tasks().cancel(id, VISUAL_SCOPE); }

    /** 最大三軌道各28点と三放射線各12点を一括描画し、12tickで終了します。 */
    private void frame(Location center, double radius, int frame) {
        double progress = frame / 6.0D;
        double shellRadius = radius * (0.35D + progress * 0.65D);
        for (int element = 0; element < 3; element++) {
            List<Location> orbit = new ArrayList<>(40);
            double turn = element * Math.PI * 2.0D / 3.0D + progress * Math.PI * 0.75D;
            for (int point = 0; point < 28; point++) {
                double angle = point * Math.PI * 2.0D / 28.0D;
                // 異なる軸の傾斜円を同じ核へ交差させる。終盤は一部を欠き、消えゆく軌道を表す。
                if (frame >= 5 && point % 3 != 0) continue;
                Vector offset = new Vector(Math.cos(angle) * shellRadius,
                        Math.sin(angle) * shellRadius * 0.65D, Math.sin(angle) * shellRadius * 0.75D)
                        .rotateAroundY(turn);
                orbit.add(center.clone().add(offset));
            }
            Vector direction = new Vector(Math.cos(turn), 0.25D, Math.sin(turn));
            for (int point = 0; point < 12; point++) {
                double distance = shellRadius * point / 11.0D;
                orbit.add(center.clone().add(direction.clone().multiply(distance)));
            }
            services.effects().points(center, orbit, ELEMENTS.get(element));
        }
    }

    /** 右肩の垂直面へ三角結晶の頂点を作ります。 */
    private static Vector vertex(int corner, double radius, Vector right) {
        double angle = Math.PI * 0.5D + corner * Math.PI * 2.0D / 3.0D;
        return right.clone().multiply(Math.cos(angle) * radius).setY(Math.sin(angle) * radius);
    }
}
