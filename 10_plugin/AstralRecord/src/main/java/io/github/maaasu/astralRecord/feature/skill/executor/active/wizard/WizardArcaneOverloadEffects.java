package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 魔力の圧縮、瞬間爆発、有限の衝撃波をオーバーロード固有の形と音で表現します。 */
final class WizardArcaneOverloadEffects {
    private static final int CHARGE_INTERVAL_TICKS = 4;
    private static final int AFTERGLOW_INTERVAL_TICKS = 2;
    private static final int AFTERGLOW_FRAMES = 8;
    private static final int WAVE_FRAMES = 5;
    private static final int RING_POINTS = 32;

    private WizardArcaneOverloadEffects() {
    }

    /**
     * 詠唱進捗に合わせ、発動者の周囲から三本の螺旋を魔力核へ収束させます。
     * 4tickごとに最大66点を一括描画し、音は8tickごとに限定します。
     * 設置物や別タスクを持たないため、詠唱中断後に演出を継続しません。
     *
     * @param effects 共通演出サービス
     * @param caster 詠唱中の発動者
     * @param elapsedTicks 詠唱経過tick数
     * @param totalTicks 詠唱短縮適用後の総tick数
     */
    static void charge(@NotNull SkillEffectService effects, @NotNull Player caster,
                       long elapsedTicks, long totalTicks) {
        if (elapsedTicks % CHARGE_INTERVAL_TICKS != 0L) return;
        double progress = Math.clamp((double) elapsedTicks / Math.max(1L, totalTicks), 0.0D, 1.0D);
        Location core = caster.getLocation().add(0.0D, 1.05D, 0.0D);
        double radius = 1.15D - progress * 0.75D;
        List<Location> ribbons = new ArrayList<>(54);
        List<Location> motes = new ArrayList<>(6);
        for (int arm = 0; arm < 3; arm++) {
            for (int step = 0; step < 18; step++) {
                double fraction = (double) step / 17;
                double angle = arm * Math.PI * 2.0D / 3.0D + elapsedTicks * 0.15D + fraction * Math.PI;
                double orbit = radius * (1.0D - fraction * 0.55D);
                ribbons.add(core.clone().add(Math.cos(angle) * orbit,
                        (fraction - 0.5D) * 1.25D, Math.sin(angle) * orbit));
            }
            double angle = arm * Math.PI * 2.0D / 3.0D + elapsedTicks * 0.15D;
            motes.add(core.clone().add(Math.cos(angle) * radius, -0.5D, Math.sin(angle) * radius));
            motes.add(core.clone().add(Math.cos(angle + Math.PI) * radius * 0.45D,
                    0.5D, Math.sin(angle + Math.PI) * radius * 0.45D));
        }
        effects.points(core, ribbons, SharedParticleDefinitions.WIZARD_OVERLOAD_ARC);
        effects.ring(core, 0.15D + progress * 0.18D, 6, SharedParticleDefinitions.WIZARD_OVERLOAD_CORE);
        effects.points(core, motes, SharedParticleDefinitions.WIZARD_OVERLOAD_MOTE);
        if (elapsedTicks == 0L) {
            effects.sound(core, Sound.BLOCK_BEACON_ACTIVATE, 0.55F, 1.2F);
        }
        if (elapsedTicks % 8L == 0L) {
            effects.sound(core, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.45F, 0.7F + (float) progress * 0.8F);
        }
    }

    /**
     * 元の命中tickに核の閃光と放射線を表示し、攻撃を伴わない0.8秒の余韻を開始します。
     * 増幅の有無で核・音程・追加の衝撃輪を変え、攻撃半径は共通のままにします。
     *
     * @param context 成功した発動のコンテキスト
     * @param center 発動時に固定した攻撃中心
     * @param radius 解決済みの攻撃半径
     * @param empowered 消費前MPによる増幅が成立した場合はtrue
     */
    static void detonate(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, boolean empowered) {
        SkillEffectService effects = context.services().effects();
        Location core = center.clone().add(0.0D, 0.65D, 0.0D);
        effects.line(context.eyeLocation(), core, 0.45D, SharedParticleDefinitions.WIZARD_OVERLOAD_ARC);
        List<Location> rays = new ArrayList<>(48);
        for (int arm = 0; arm < 6; arm++) {
            double angle = arm * Math.PI / 3.0D;
            for (int step = 1; step <= 8; step++) {
                double distance = radius * step / 8.0D;
                rays.add(center.clone().add(Math.cos(angle) * distance,
                        0.12D + Math.sin(step * Math.PI / 8.0D) * 0.3D, Math.sin(angle) * distance));
            }
        }
        effects.points(center, rays, SharedParticleDefinitions.WIZARD_OVERLOAD_ARC);
        effects.ring(center, radius, RING_POINTS, SharedParticleDefinitions.WIZARD_OVERLOAD_ARC);
        effects.point(core, SharedParticleDefinitions.WIZARD_OVERLOAD_FLASH);
        effects.point(core, empowered
                ? SharedParticleDefinitions.WIZARD_OVERLOAD_BURST.withCount(60)
                : SharedParticleDefinitions.WIZARD_OVERLOAD_BURST);
        effects.sound(core, Sound.ENTITY_GENERIC_EXPLODE, empowered ? 1.7F : 1.25F, empowered ? 0.65F : 0.85F);
        effects.sound(core, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7F, empowered ? 0.8F : 1.05F);
        if (empowered) {
            effects.ring(core, 0.65D, 16, SharedParticleDefinitions.WIZARD_OVERLOAD_EMPOWERED);
            effects.sound(core, Sound.BLOCK_BEACON_POWER_SELECT, 0.65F, 1.45F);
        }
        UUID casterId = context.player().getUniqueId();
        String scope = WizardArcaneOverloadExecutor.ID + ":afterglow:" + UUID.randomUUID();
        context.services().tasks().repeat(casterId, scope,
                AFTERGLOW_INTERVAL_TICKS, AFTERGLOW_INTERVAL_TICKS, AFTERGLOW_FRAMES, frame -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != center.getWorld()
                            || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                        context.services().tasks().cancel(casterId, scope);
                        return;
                    }
                    if (frame < WAVE_FRAMES) {
                        shockwave(effects, center, radius * (frame + 1) / WAVE_FRAMES, frame, empowered);
                    } else {
                        effects.point(core.clone().add(0.0D, (frame - WAVE_FRAMES) * 0.12D, 0.0D),
                                SharedParticleDefinitions.WIZARD_OVERLOAD_RESIDUE.withCount(12 - (frame - WAVE_FRAMES) * 4));
                        if (frame == WAVE_FRAMES) {
                            effects.sound(core, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.4F, 0.65F);
                        }
                    }
                });
    }

    /**
     * 地表の衝撃輪と上半球の二本の弧を一括描画します。
     * 増幅時の追加弧を含め最大128点で、拡大半径は実際の攻撃半径以内です。
     *
     * @param effects 共通演出サービス
     * @param center 固定した攻撃中心
     * @param radius このフレームの衝撃波半径
     * @param frame 回転位相を決めるフレーム番号
     * @param empowered 増幅成立時に白い追加弧を描く場合はtrue
     */
    private static void shockwave(@NotNull SkillEffectService effects, @NotNull Location center,
                                  double radius, int frame, boolean empowered) {
        List<Location> shell = new ArrayList<>(64);
        for (int arc = 0; arc < 2; arc++) {
            double plane = arc * Math.PI / 2.0D + frame * 0.12D;
            for (int point = 0; point < 32; point++) {
                double angle = Math.PI * point / 31.0D;
                shell.add(center.clone().add(Math.cos(plane) * Math.cos(angle) * radius,
                        0.12D + Math.sin(angle) * radius, Math.sin(plane) * Math.cos(angle) * radius));
            }
        }
        effects.points(center, shell, SharedParticleDefinitions.WIZARD_OVERLOAD_ARC);
        effects.ring(center, radius, RING_POINTS, SharedParticleDefinitions.WIZARD_OVERLOAD_CORE);
        if (empowered) {
            List<Location> crown = new ArrayList<>(32);
            for (int point = 0; point < 32; point++) {
                double angle = Math.PI * 2.0D * point / 32.0D;
                crown.add(center.clone().add(Math.cos(angle) * radius * 0.7D,
                        0.12D + radius * 0.7D, Math.sin(angle) * radius * 0.7D));
            }
            effects.points(center, crown, SharedParticleDefinitions.WIZARD_OVERLOAD_EMPOWERED);
        }
    }
}
