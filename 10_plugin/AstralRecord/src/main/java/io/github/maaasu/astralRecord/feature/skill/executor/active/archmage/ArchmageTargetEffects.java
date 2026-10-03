package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 1回の効果適用に成功した対象をまとめ、スキルごとの形と音で示します。 */
final class ArchmageTargetEffects {
    private static final int MAX_SHAPE_POINTS = 768;
    private static final int MAX_IMPACT_POINTS = 32;

    /** 効果の意味に対応した対象側の演出です。 */
    enum Style {
        STASIS(SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6F),
        REPULSION(SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_CYAN, Sound.ENTITY_BREEZE_WIND_BURST, 1.4F),
        METEOR(SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.2F),
        AEGIS(SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_CYAN, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F),
        RENEWAL(SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_MINT, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.4F),
        VIGOR(SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.8F),
        STARFALL(SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9F),
        SPEAR(SharedParticleDefinitions.SKILL_ASTRAL_RAY_BEAM, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.7F),
        VOLLEY(SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_AZURE, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.6F),
        NOVA(SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET, Sound.ENTITY_BREEZE_WIND_BURST, 0.7F);

        private final SharedParticleDefinition particle;
        private final Sound sound;
        private final float pitch;

        /**
         * 効果に合う既存粒子と短い効果音を選びます。
         * @param particle 図形の粒子
         * @param sound 効果成立時の音
         * @param pitch 音高
         */
        Style(SharedParticleDefinition particle, Sound sound, float pitch) {
            this.particle = particle;
            this.sound = sound;
            this.pitch = pitch;
        }
    }

    private final PlayerActiveSkillContext context;
    private final Style style;
    private final List<Location> points = new ArrayList<>();
    private final List<Location> impacts = new ArrayList<>();
    private Location soundLocation;

    /**
     * 周期効果または一撃ごとに作る、保存不要の描画バッチです。
     * @param context 同期実行中のスキル
     * @param style 表示する効果
     */
    ArchmageTargetEffects(@NotNull PlayerActiveSkillContext context, @NotNull Style style) {
        this.context = context;
        this.style = style;
    }

    /**
     * 実際に効果を受けた対象の足元と胴体へ最大24点の図形を追加します。
     * @param feet 効果適用時の足元
     * @param body 効果適用時の胴体。致死攻撃でも命中前の位置を渡します
     */
    void affected(@NotNull Location feet, @NotNull Location body) {
        if (soundLocation == null) {
            soundLocation = body.clone();
        }
        switch (style) {
            case STASIS -> {
                ring(feet.clone().add(0.0D, 0.18D, 0.0D), 0.58D, 12);
                ring(feet.clone().add(0.0D, 0.55D, 0.0D), 0.42D, 12);
            }
            case AEGIS -> {
                for (int index = 0; index < 24; index++) {
                    double angle = Math.PI * 2.0D * index / 24;
                    points.add(body.clone().add(Math.cos(angle) * 0.65D, Math.sin(angle) * 0.8D, 0.0D));
                }
            }
            case RENEWAL, VIGOR -> {
                for (int index = 0; index < 18; index++) {
                    double angle = Math.PI * 4.0D * index / 17;
                    double height = 0.15D + 1.6D * index / 17;
                    points.add(feet.clone().add(Math.cos(angle) * 0.45D, height, Math.sin(angle) * 0.45D));
                }
                if (style == Style.RENEWAL) {
                    impacts.add(body.clone());
                }
            }
            case METEOR, STARFALL -> {
                for (int index = 0; index < 8; index++) {
                    double fraction = index / 7.0D;
                    points.add(body.clone().add(0.6D * fraction, 2.8D * fraction, 0.3D * fraction));
                }
                ring(body, 0.55D, 12);
                impacts.add(body.clone());
            }
            case SPEAR, VOLLEY -> {
                for (int index = -3; index <= 3; index++) {
                    points.add(body.clone().add(index * 0.18D, 0.0D, 0.0D));
                    points.add(body.clone().add(0.0D, index * 0.18D, 0.0D));
                }
                impacts.add(body.clone());
            }
            case NOVA -> {
                ring(body, 0.65D, 16);
                impacts.add(body.clone());
            }
            case REPULSION -> ring(body, 0.5D, 12);
        }
    }

    /**
     * 実際に加算された速度の向きへ3本の短い矢印を加えます。
     * @param body 対象の胴体
     * @param velocity 適用前後の速度差。ゼロなら何も表示しません
     */
    void pushed(@NotNull Location body, @NotNull Vector velocity) {
        if (velocity.lengthSquared() <= 1.0E-10D) {
            return;
        }
        if (soundLocation == null) {
            soundLocation = body.clone();
        }
        Vector direction = velocity.clone().normalize();
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
        if (side.lengthSquared() < 1.0E-10D) {
            side.setX(1.0D);
        } else {
            side.normalize();
        }
        for (int step = 0; step < 3; step++) {
            Location tip = body.clone().add(direction.clone().multiply(0.4D + step * 0.4D));
            points.add(tip);
            points.add(tip.clone().subtract(direction.clone().multiply(0.25D)).add(side.clone().multiply(0.22D)));
            points.add(tip.clone().subtract(direction.clone().multiply(0.25D)).subtract(side.clone().multiply(0.22D)));
        }
    }

    /**
     * 全対象の同種粒子をまとめて送信し、成功対象の位置で1回だけ鳴らします。
     * 対象不在・全失敗では描画も再生も行いません。状態や追加タスクは残しません。
     */
    void render() {
        context.services().effects().pointsNearViewers(visibleSlice(points, MAX_SHAPE_POINTS), style.particle);
        context.services().effects().pointsNearViewers(visibleSlice(impacts, MAX_IMPACT_POINTS), style == Style.RENEWAL
                ? SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL
                : SharedParticleDefinitions.SKILL_ASTRAL_RAY_IMPACT);
        if (soundLocation != null) {
            context.services().effects().sound(soundLocation, style.sound, 0.45F, style.pitch);
        }
    }

    /**
     * 混雑時だけ送信点数を制限し、毎秒表示範囲を巡回します。効果適用対象は変えません。
     * @param locations 成立した効果から集めた点
     * @param maximum 1回に送る最大点数
     * @return 通常時は元の点群、上限超過時は巡回中の点群
     */
    private static List<Location> visibleSlice(List<Location> locations, int maximum) {
        if (locations.size() <= maximum) {
            return locations;
        }
        int start = Math.floorMod((long) Bukkit.getCurrentTick() / 20L * maximum, locations.size());
        List<Location> visible = new ArrayList<>(maximum);
        for (int index = 0; index < maximum; index++) {
            visible.add(locations.get((start + index) % locations.size()));
        }
        return visible;
    }

    /**
     * 回避・無効化を除き、HPまたはシールドへ実ダメージが入ったか確認します。
     * @param result 共通戦闘処理の結果
     * @return 実ダメージがあればtrue
     */
    static boolean damaged(@NotNull DamageResult result) {
        return !result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
    }

    /**
     * 既存の押し出しを1回実行し、実体に加算された速度を返します。
     * 耐性・受付間隔・実体不在による不成立を、演出で成功扱いしないために使います。
     * @param context 同期実行中の戦闘サービス
     * @param target 押し出す敵Mob
     * @param source 押し出し元
     * @param horizontal 水平方向の強さ
     * @param vertical 垂直方向の強さ
     * @return 実際の速度差。不成立ならゼロベクトル
     */
    static @NotNull Vector push(@NotNull PlayerActiveSkillContext context, @NotNull AstEntity target,
                                @NotNull Location source, double horizontal, double vertical) {
        Entity entity = target.mob() == null || target.mob().bukkitEntityId() == null
                ? null : Bukkit.getEntity(target.mob().bukkitEntityId());
        Vector before = entity == null ? new Vector() : entity.getVelocity();
        context.services().combat().knockback(target, source, horizontal, vertical);
        return entity == null ? new Vector() : entity.getVelocity().subtract(before);
    }

    /**
     * 指定中心の水平輪を固定点数でバッチへ追加します。
     * @param center 輪の中心
     * @param radius 半径
     * @param count 描画点数
     */
    private void ring(Location center, double radius, int count) {
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0D * index / count;
            points.add(center.clone().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius));
        }
    }
}
