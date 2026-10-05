package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 実際の攻撃地点を白金の折れた雷で結び、連鎖順と有効命中を描画します。 */
final class WizardStormChainEffects {
    private static final int STEP_TICKS = 2;
    private static final int LINK_HOLD_TICKS = 6;
    private static final int MAX_BOLT_POINTS = 128;
    private static final int HALO_POINTS = 20;

    private WizardStormChainEffects() {
    }

    /**
     * ダメージを追加せず、命中時に記録した経路を2tickずつ順に強調します。
     * 空振りは遮蔽後の射程まで放電し、敵への命中リングと雷鳴を出しません。
     * 全座標を固定し、発動者の死亡・退出・ワールド移動時は共有taskで停止します。
     *
     * @param context 発動者、共有演出・taskサービス、解決済み射程
     * @param origin 実際の攻撃開始地点
     * @param missedEnd 発動時に固定した遮蔽後の空振り終点
     * @param impacts 同一発動で攻撃した敵の中心座標と有効命中の成否。攻撃順、最大8地点
     */
    static void play(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Location missedEnd,
            @NotNull List<Impact> impacts
    ) {
        List<Impact> destinations = impacts.isEmpty() ? List.of(new Impact(missedEnd, false)) : impacts;
        List<Bolt> bolts = new ArrayList<>(destinations.size());
        Location from = origin;
        for (Impact destination : destinations) {
            bolts.add(new Bolt(from, destination.location(), destination.effectiveHit()));
            from = destination.location();
        }

        SkillEffectService effects = context.services().effects();
        effects.point(origin, SharedParticleDefinitions.WIZARD_STORM_CHAIN_CHARGE);
        effects.sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.65F, 1.6F);
        effects.sound(origin, Sound.ENTITY_BREEZE_SHOOT, 0.7F, 1.4F);
        // 最初の雷は有償パッシブ判定と同じtickに表示し、以後は演出のみ進めます。
        draw(effects, bolts, 0);
        int durationTicks = (bolts.size() - 1) * STEP_TICKS + LINK_HOLD_TICKS;
        String scope = "wizard-storm-chain:" + UUID.randomUUID();
        context.services().tasks().repeat(
                context.player().getUniqueId(), scope, STEP_TICKS, STEP_TICKS,
                durationTicks / STEP_TICKS,
                index -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != origin.getWorld()
                            || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                        context.services().tasks().cancel(context.player().getUniqueId(), scope);
                        return;
                    }
                    draw(effects, bolts, (index + 1) * STEP_TICKS);
                }
        );
    }

    /**
     * 新しい雷芯と古い残光を別の点群へまとめ、近傍viewerへ一括描画します。
     * 一つの雷は最大4回だけ描き、音と命中火花はその雷の開始時だけ再生します。
     *
     * @param effects 共有描画サービス
     * @param bolts 命中地点間の固定経路
     * @param elapsedTicks 演出開始からの経過tick
     */
    private static void draw(SkillEffectService effects, List<Bolt> bolts, int elapsedTicks) {
        List<Location> core = new ArrayList<>();
        List<Location> glow = new ArrayList<>();
        List<Location> afterglow = new ArrayList<>();
        List<Location> sparks = new ArrayList<>();
        for (int index = 0; index < bolts.size(); index++) {
            int age = elapsedTicks - index * STEP_TICKS;
            if (age < 0 || age > LINK_HOLD_TICKS) {
                continue;
            }
            Bolt bolt = bolts.get(index);
            if (age <= STEP_TICKS) {
                core.addAll(bolt.core);
                glow.addAll(bolt.glow);
                sparks.addAll(bolt.sparks);
            } else {
                afterglow.addAll(bolt.core);
            }
            if (bolt.effectiveHit) {
                halo(age <= STEP_TICKS ? core : afterglow, bolt.impact,
                        (index == bolts.size() - 1 ? 0.75D : 0.55D) + age * 0.035D);
            }
            if (age == 0) {
                if (index == 0) {
                    effects.sound(bolt.source, Sound.ITEM_TRIDENT_THROW, 0.65F, 1.7F);
                }
                if (bolt.effectiveHit) {
                    effects.point(bolt.impact, SharedParticleDefinitions.WIZARD_STORM_CHAIN_IMPACT);
                    float pitch = 1.15F + index * 0.1F;
                    effects.sound(bolt.impact, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.55F, pitch);
                    effects.sound(bolt.impact, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6F, pitch + 0.2F);
                    if (index == bolts.size() - 1) {
                        effects.sound(bolt.impact, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.85F, 1.2F);
                        effects.sound(bolt.impact, Sound.ITEM_TRIDENT_THUNDER, 0.5F, 1.55F);
                    }
                }
            }
        }
        effects.pointsNearViewers(glow, SharedParticleDefinitions.WIZARD_STORM_CHAIN_GLOW);
        effects.pointsNearViewers(core, SharedParticleDefinitions.WIZARD_STORM_CHAIN_CORE);
        effects.pointsNearViewers(afterglow, SharedParticleDefinitions.WIZARD_STORM_CHAIN_AFTERGLOW);
        effects.pointsNearViewers(sparks, SharedParticleDefinitions.WIZARD_STORM_CHAIN_SPARK);
    }

    /**
     * 攻撃時の中心座標と、回避なしでHPまたはShieldへ実ダメージが発生したかを保持します。
     *
     * @param location 連鎖の攻撃地点
     * @param effectiveHit 命中リングと命中音を表示する有効ダメージがあるか
     */
    record Impact(@NotNull Location location, boolean effectiveHit) {
    }

    /**
     * 命中した敵の中心を囲む水平・垂直の三つのリングを点群へ追加します。
     *
     * @param points 一括表示する点群
     * @param center 攻撃時の敵の中心
     * @param radius 放電リングの半径
     */
    private static void halo(List<Location> points, Location center, double radius) {
        for (int index = 0; index < HALO_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / HALO_POINTS;
            double a = Math.cos(angle) * radius;
            double b = Math.sin(angle) * radius;
            points.add(center.clone().add(a, 0.0D, b));
            points.add(center.clone().add(a, b, 0.0D));
            points.add(center.clone().add(0.0D, a, b));
        }
    }

    /** 一度生成した折線と枝分かれを保持し、残光で経路が飛び散らないようにします。 */
    private static final class Bolt {
        private final Location source;
        private final Location impact;
        private final boolean effectiveHit;
        private final List<Location> core = new ArrayList<>();
        private final List<Location> glow = new ArrayList<>();
        private final List<Location> sparks = new ArrayList<>();

        /**
         * 両端を必ず実命中地点へ合わせ、0.7m単位の折線を高密度に補間します。
         * 枝は同じ敵へ戻すため、攻撃していない敵への連鎖を示しません。
         *
         * @param from この雷の始点
         * @param to この雷の終点
         * @param effectiveHit 終点で有効ダメージが成立したか
         */
        private Bolt(Location from, Location to, boolean effectiveHit) {
            source = from.clone();
            impact = to.clone();
            this.effectiveHit = effectiveHit;
            Vector delta = to.toVector().subtract(from.toVector());
            double distance = delta.length();
            if (distance < 1.0E-6D) {
                core.add(impact.clone());
                return;
            }
            Vector forward = delta.clone().normalize();
            Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
            if (right.lengthSquared() < 1.0E-6D) {
                right = new Vector(1.0D, 0.0D, 0.0D);
            } else {
                right.normalize();
            }
            Vector up = right.clone().crossProduct(forward).normalize();
            int corners = Math.max(2, Math.min(36, (int) Math.ceil(distance / 0.7D)));
            List<Location> nodes = new ArrayList<>(corners + 1);
            for (int index = 0; index <= corners; index++) {
                double fraction = (double) index / corners;
                double envelope = Math.sin(Math.PI * fraction);
                Location node = from.clone().add(delta.clone().multiply(fraction));
                if (index > 0 && index < corners) {
                    node.add(right.clone().multiply(randomOffset() * envelope))
                            .add(up.clone().multiply(randomOffset() * envelope));
                }
                nodes.add(node);
            }
            int stepsPerSegment = Math.max(1, Math.min(6, (MAX_BOLT_POINTS - 1) / corners));
            for (int segment = 0; segment < corners; segment++) {
                Location start = nodes.get(segment);
                Vector edge = nodes.get(segment + 1).toVector().subtract(start.toVector());
                for (int step = 0; step < stepsPerSegment; step++) {
                    core.add(start.clone().add(edge.clone().multiply((double) step / stepsPerSegment)));
                }
            }
            core.add(impact.clone());
            for (int index = 0; index < core.size(); index++) {
                Location point = core.get(index);
                if (index % 2 == 0) {
                    glow.add(point.clone());
                }
                if (index % 5 == 0) {
                    sparks.add(point.clone());
                }
                // 分岐は中ほどだけを膨らませ、雷芯と同じ始点・終点へ収束させます。
                double fraction = (double) index / Math.max(1, core.size() - 1);
                double fork = Math.sin(Math.PI * fraction) * 0.24D;
                if (index % 2 == 0 && fork > 0.04D) {
                    glow.add(point.clone().add(right.clone().multiply(fork)));
                    glow.add(point.clone().subtract(right.clone().multiply(fork)));
                }
            }
        }

        /** 固定経路の生成時だけ、±0.3mの雷の折れ幅を抽選します。 */
        private static double randomOffset() {
            return ThreadLocalRandom.current().nextDouble(-0.3D, 0.3D);
        }
    }
}
