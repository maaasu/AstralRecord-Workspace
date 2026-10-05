package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/** 陣地を星座として編み、星術で解放するアークメイジ固有の有限演出です。 */
final class ArchmageConstellationEffects {
    private static final double TWO_PI = Math.PI * 2.0D;
    private static final int MAX_LAYER_POINTS = 144;
    private static final int MAX_RECIPIENTS = 4;

    /** 効果対象と、色以外でも判別できる魔法陣の意匠を対応させます。 */
    enum FieldStyle {
        AEGIS(true), RENEWAL(true), VIGOR(true), STASIS(false), REPULSION(false), METEOR(false);

        final boolean supportsPlayers;

        /** @param supportsPlayers プレイヤー支援陣として不死鳥の対象連動へ登録するか */
        FieldStyle(boolean supportsPlayers) { this.supportsPlayers = supportsPlayers; }
    }

    private ArchmageConstellationEffects() { }

    /** 実ダメージが成立した場合だけ命中演出を許可します。 */
    static boolean effectiveHit(DamageResult result) {
        return !result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
    }

    /**
     * 設置成功時に、各陣の機能に対応する二層の音を一度だけ鳴らします。
     * @param context 発動サービス
     * @param center 実際の設置点
     * @param style 陣の意匠
     */
    static void fieldOpened(PlayerActiveSkillContext context, Location center, FieldStyle style) {
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65F, 0.9F);
        context.services().effects().sound(center, fieldSound(style), 0.55F, fieldPitch(style));
    }

    /** 自然満了した陣の輪郭を小さな星へ畳み、終了音を一度だけ鳴らします。 */
    static void fieldClosed(PlayerActiveSkillContext context, Location center, FieldStyle style) {
        List<Player> viewers = nearbyViewers(context, center);
        if (!viewers.isEmpty()) {
            List<Location> light = new ArrayList<>();
            star(light, center, 0.5D, 0.0D);
            display(context, viewers, light, fieldParticle(style));
        }
        context.services().effects().sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.25F, 1.6F);
    }

    /**
     * 外周は実効果半径を保ち、内側の紋様と上昇点だけを10tick間隔で動かします。
     * 1層144点、3層までに制限し、閲覧者がいなければ点群を生成しません。
     * @param context 発動サービス
     * @param center 固定された設置点
     * @param radius 実効果半径
     * @param style 技固有の形
     * @param age 発動後のtick
     * @param pulse 効果判定と同じ周期ならtrue
     */
    static void fieldFrame(PlayerActiveSkillContext context, Location center, double radius,
                           FieldStyle style, int age, boolean pulse) {
        List<Player> viewers = nearbyViewers(context, center);
        if (viewers.isEmpty()) { return; }
        List<Location> edge = new ArrayList<>();
        List<Location> motif = new ArrayList<>();
        List<Location> light = new ArrayList<>();
        ring(edge, center, radius, 40, 0.0D);
        double phase = age * 0.025D;
        switch (style) {
            case AEGIS -> {
                polygon(motif, center, radius * 0.8D, 6, 0.0D);
                polygon(motif, center, radius * 0.5D, 6, Math.PI / 6.0D);
                for (int rib = 0; rib < 6; rib++) {
                    double angle = rib * TWO_PI / 6.0D;
                    for (int step = 0; step < 7; step++) {
                        double elevation = Math.PI * 0.5D * step / 6.0D;
                        polar(light, center, radius * Math.cos(elevation), angle,
                                pulse ? radius * 0.6D * Math.sin(elevation) : 0.12D);
                    }
                }
            }
            case RENEWAL -> {
                for (int step = 0; step < 64; step++) {
                    double angle = TWO_PI * step / 64.0D;
                    polar(motif, center, radius * 0.78D * Math.abs(Math.sin(angle * 2.0D)), angle, 0.0D);
                }
                ring(light, center, radius * 0.32D, 16, pulse ? 0.55D : 0.1D);
                for (int petal = 0; petal < 8; petal++) {
                    polar(light, center, radius * 0.68D, petal * TWO_PI / 8.0D + phase,
                            0.2D + (1.0D + Math.sin(phase)) * 0.3D);
                }
            }
            case VIGOR -> {
                for (int arrow = 0; arrow < 6; arrow++) {
                    double angle = arrow * TWO_PI / 6.0D;
                    Vector radial = new Vector(Math.cos(angle), 0.0D, Math.sin(angle));
                    Vector side = new Vector(-radial.getZ(), 0.0D, radial.getX());
                    Location tip = center.clone().add(radial.clone().multiply(radius * 0.75D));
                    Location tail = center.clone().add(radial.clone().multiply(radius * 0.35D));
                    segment(motif, tail.clone().add(side.clone().multiply(radius * 0.18D)), tip, 5);
                    segment(motif, tail.clone().subtract(side.clone().multiply(radius * 0.18D)), tip, 5);
                    for (int step = 0; step < 4; step++) {
                        polar(light, center, radius * 0.5D, angle + phase,
                                0.2D + ((age / 10 + step) % 4) * 0.24D);
                    }
                }
            }
            case STASIS -> {
                ring(motif, center, radius * 0.7D, 32, 0.0D);
                for (int mark = 0; mark < 12; mark++) {
                    double angle = mark * TWO_PI / 12.0D;
                    segment(motif, at(center, radius * 0.79D, angle, 0.0D),
                            at(center, radius * 0.95D, angle, 0.0D), 3);
                }
                segment(light, center, at(center, radius * 0.58D, -phase, 0.0D), 9);
                segment(light, center, at(center, radius * 0.4D, -phase * 0.25D + Math.PI, 0.0D), 7);
                if (pulse) { ring(light, center, radius * 0.45D, 24, 0.3D); }
            }
            case REPULSION -> {
                ring(motif, center, radius * (0.25D + (age % 30) / 40.0D), 32, 0.1D);
                for (int arrow = 0; arrow < 8; arrow++) {
                    double angle = arrow * TWO_PI / 8.0D;
                    Location tip = at(center, radius * 0.93D, angle, 0.0D);
                    segment(light, at(center, radius * 0.6D, angle - 0.16D, 0.0D), tip, 4);
                    segment(light, at(center, radius * 0.6D, angle + 0.16D, 0.0D), tip, 4);
                }
            }
            case METEOR -> {
                polygon(motif, center, radius * 0.65D, 5, phase);
                for (int star = 0; star < 5; star++) {
                    Location orbit = at(center, radius * 0.62D, star * TWO_PI / 5.0D + phase, 1.4D);
                    star(light, orbit, 0.2D, 0.0D);
                }
            }
        }
        display(context, viewers, edge, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_AZURE);
        display(context, viewers, motif, fieldParticle(style));
        display(context, viewers, light, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
    }

    /** 一周期で成立した対象だけを最大4人/体に公平に間引き、二層で一括描画します。 */
    static final class FieldFeedback {
        private final List<Location> recipients = new ArrayList<>(MAX_RECIPIENTS);
        private int seen;

        /** 効果処理後の座標を複製し、均等な抽選で表示対象数だけを制限します。 */
        void accept(Location location) {
            seen++;
            if (recipients.size() < MAX_RECIPIENTS) {
                recipients.add(location.clone());
            } else {
                int slot = ThreadLocalRandom.current().nextInt(seen);
                if (slot < MAX_RECIPIENTS) { recipients.set(slot, location.clone()); }
            }
        }

        /**
         * 回復・減速・斥力・命中の成立点を描き、成立音は陣の中心で最大2秒に一度鳴らします。
         * @param context 発動サービス
         * @param center 設置点
         * @param style 技の意匠
         * @param playSound 前回成立音から40tick以上経過したか
         * @return 成立対象があり、成立音を鳴らした場合はtrue
         */
        boolean finish(PlayerActiveSkillContext context, Location center, FieldStyle style, boolean playSound) {
            if (seen == 0) { return false; }
            List<Player> viewers = nearbyViewers(context, center);
            if (!viewers.isEmpty()) {
                List<Location> marks = new ArrayList<>();
                List<Location> rays = new ArrayList<>();
                for (Location recipient : recipients) {
                    Location chest = recipient.clone().add(0.0D, 0.8D, 0.0D);
                    switch (style) {
                        case AEGIS -> polygon(marks, chest, 0.65D, 6, 0.0D);
                        case RENEWAL -> {
                            ring(marks, chest, 0.55D, 16, 0.0D);
                            segment(rays, recipient, chest.clone().add(0.0D, 0.7D, 0.0D), 8);
                        }
                        case VIGOR -> {
                            for (int ray = 0; ray < 3; ray++) {
                                Location base = at(recipient, 0.45D, ray * TWO_PI / 3.0D, 0.0D);
                                segment(marks, base, base.clone().add(0.0D, 1.15D, 0.0D), 7);
                            }
                        }
                        case STASIS -> {
                            ring(marks, recipient, 0.55D, 16, 0.12D);
                            segment(rays, chest.clone().add(-0.3D, -0.3D, 0.0D),
                                    chest.clone().add(0.3D, 0.3D, 0.0D), 6);
                            segment(rays, chest.clone().add(0.3D, -0.3D, 0.0D),
                                    chest.clone().add(-0.3D, 0.3D, 0.0D), 6);
                        }
                        case REPULSION -> segment(marks, center.clone().add(0.0D, 0.25D, 0.0D), chest, 12);
                        case METEOR -> {
                            segment(rays, chest.clone().add(0.5D, 2.6D, 0.0D), chest, 12);
                            star(marks, chest, 0.45D, 0.0D);
                        }
                    }
                }
                display(context, viewers, marks, fieldParticle(style));
                display(context, viewers, rays, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_WHITE);
            }
            if (playSound) {
                context.services().effects().sound(center, fieldSound(style), 0.3F, fieldPitch(style) + 0.2F);
            }
            return playSound;
        }
    }

    /** 落星を実効果地点へ描き、着弾後だけ短い星冠の残光を維持します。 */
    static void starfall(PlayerActiveSkillContext context, Location center, double radius, int circles,
                         List<Location> impacts) {
        context.services().effects().sound(center, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7F, 0.65F);
        if (!impacts.isEmpty()) {
            context.services().effects().sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.65F, 0.8F);
        }
        afterglow(context, center, "starfall", (frame, viewers) -> {
            List<Location> core = new ArrayList<>();
            List<Location> glow = new ArrayList<>();
            if (frame == 0) {
                Location top = center.clone().add(0.0D, 5.0D + circles, 0.0D);
                segment(core, top, center, 28);
                star(glow, top, 0.9D, 0.0D);
                for (int ray = 0; ray < 6; ray++) {
                    segment(glow, at(top, 0.4D, ray * TWO_PI / 6.0D, 0.0D),
                            at(center, radius * 0.55D, ray * TWO_PI / 6.0D, 0.0D), 10);
                }
            }
            star(core, center, radius * (0.65D + frame * 0.07D), frame * 0.08D);
            for (Location impact : impacts) { star(glow, impact, 0.4D + frame * 0.08D, 0.0D); }
            display(context, viewers, core, SharedParticleDefinitions.SKILL_ASTRAL_RAY_BEAM);
            display(context, viewers, glow, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
        });
    }

    /** 二重螺旋と槍先を実際の射線へ合わせ、空振りには命中音と命中冠を出しません。 */
    static void spear(PlayerActiveSkillContext context, Location origin, Location end, int circles, boolean hit) {
        context.services().effects().sound(origin, Sound.ITEM_TRIDENT_THROW, 0.65F, 1.3F);
        if (hit) { context.services().effects().sound(end, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.6F, 1.35F); }
        Vector delta = end.toVector().subtract(origin.toVector());
        Vector forward = delta.lengthSquared() < 1.0E-6D ? new Vector(0, 0, 1) : delta.clone().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6D) { right = new Vector(1, 0, 0); }
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        Vector side = right;
        afterglow(context, origin, "spear", (frame, viewers) -> {
            List<Location> core = new ArrayList<>();
            List<Location> helix = new ArrayList<>();
            segment(core, origin, end, 64);
            for (int step = 0; step <= 48; step++) {
                double fraction = step / 48.0D;
                double angle = fraction * TWO_PI * (2 + circles) + frame * 0.3D;
                double width = Math.sin(Math.PI * fraction) * (0.14D + circles * 0.05D);
                Location axis = origin.clone().add(delta.clone().multiply(fraction));
                Vector offset = side.clone().multiply(Math.cos(angle) * width)
                        .add(up.clone().multiply(Math.sin(angle) * width));
                helix.add(axis.clone().add(offset));
                helix.add(axis.clone().subtract(offset));
            }
            Location base = end.clone().subtract(forward.clone().multiply(0.65D));
            segment(core, base.clone().add(side.clone().multiply(0.3D)), end, 5);
            segment(core, base.clone().subtract(side.clone().multiply(0.3D)), end, 5);
            if (hit) { star(core, end, 0.45D + frame * 0.05D, 0.0D); }
            display(context, viewers, core, SharedParticleDefinitions.SKILL_ASTRAL_RAY_BEAM);
            display(context, viewers, helix, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET);
        });
    }

    /** 星弾の待機軌道を示します。瞬間描画なので魔法陣の個数へ登録しません。 */
    static void volleyOpened(PlayerActiveSkillContext context, Location center, int shots) {
        List<Player> viewers = nearbyViewers(context, center);
        if (!viewers.isEmpty()) {
            List<Location> stars = new ArrayList<>();
            for (int shot = 0; shot < shots; shot++) { star(stars, volleyOrigin(center, shot, shots), 0.3D, 0.0D); }
            display(context, viewers, stars, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
        }
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65F, 1.4F);
    }

    /** 各弾の実命中時に、軌道上の星から対象へ曲線を描き、弾順に音程を上げます。 */
    static void volleyShot(PlayerActiveSkillContext context, Location center, Location end,
                           int index, int shots, boolean hit) {
        Location origin = volleyOrigin(center, index, shots);
        context.services().effects().sound(origin, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.45F, 0.95F + index * 0.12F);
        if (hit) { context.services().effects().sound(end, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4F, 1.2F + index * 0.1F); }
        afterglow(context, center, "volley", (frame, viewers) -> {
            List<Location> trail = new ArrayList<>();
            List<Location> stars = new ArrayList<>();
            Vector delta = end.toVector().subtract(origin.toVector());
            for (int step = 0; step <= 28; step++) {
                double t = step / 28.0D;
                trail.add(origin.clone().add(delta.clone().multiply(t)).add(0.0D, Math.sin(Math.PI * t) * 0.65D, 0.0D));
            }
            if (hit) { star(stars, end, 0.32D + frame * 0.06D, index); }
            display(context, viewers, trail, SharedParticleDefinitions.SKILL_ASTRAL_RAY_CYAN);
            display(context, viewers, stars, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
        });
    }

    /** 重力核と直交する軌道、実効果半径で止まる斥力波を描きます。 */
    static void nova(PlayerActiveSkillContext context, Location center, double radius, List<Location> impacts) {
        context.services().effects().sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.65F, 0.65F);
        if (!impacts.isEmpty()) { context.services().effects().sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.55F, 1.3F); }
        afterglow(context, center, "nova", (frame, viewers) -> {
            List<Location> orbit = new ArrayList<>();
            List<Location> wave = new ArrayList<>();
            Location core = center.clone().add(0.0D, 0.6D, 0.0D);
            for (int step = 0; step < 32; step++) {
                double angle = TWO_PI * step / 32.0D;
                double r = 0.65D * (1.0D - frame * 0.16D);
                orbit.add(core.clone().add(Math.cos(angle) * r, Math.sin(angle) * r, 0.0D));
                orbit.add(core.clone().add(0.0D, Math.cos(angle) * r, Math.sin(angle) * r));
            }
            ring(wave, center, radius * (0.3D + frame * 0.175D), 48, 0.15D);
            for (Location impact : impacts) { ring(wave, impact, 0.5D, 8, 0.0D); }
            display(context, viewers, orbit, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET);
            display(context, viewers, wave, SharedParticleDefinitions.SKILL_ASTRAL_RAY_CYAN);
        });
    }

    /** 発動位置と陣数を保持した軌道星の始点を返します。 */
    private static Location volleyOrigin(Location center, int index, int shots) {
        return at(center, 1.25D, TWO_PI * index / shots, 2.2D);
    }

    /**
     * 初回は同期描画し、3tick間隔の残光4回だけを所有者の共有taskへ登録します。
     * ダメージ処理は行わず、死亡・退出・ワールド移動・task清掃で終了します。
     */
    private static void afterglow(PlayerActiveSkillContext context, Location center, String name,
                                 BiConsumer<Integer, List<Player>> draw) {
        List<Player> initialViewers = nearbyViewers(context, center);
        if (!initialViewers.isEmpty()) { draw.accept(0, initialViewers); }
        UUID casterId = context.player().getUniqueId();
        String scope = context.source().skill().getId() + ":visual:" + name + ":" + UUID.randomUUID();
        context.services().tasks().repeat(casterId, scope, 3L, 3L, 4, frame -> {
            if (!context.player().isOnline() || context.player().isDead()
                    || context.player().getWorld() != center.getWorld()
                    || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                context.services().tasks().cancel(casterId, scope);
                return;
            }
            List<Player> viewers = nearbyViewers(context, center);
            if (!viewers.isEmpty()) { draw.accept(frame + 1, viewers); }
        });
    }

    /** 一フレームの候補走査を64人、表示を発動者優先の16人に制限します。 */
    private static List<Player> nearbyViewers(PlayerActiveSkillContext context, Location center) {
        return context.services().effects().nearbyViewers(center, context.player(), 16);
    }

    /** 1層の点数を制限し、一括送信でプレイヤーの粒子密度設定に従います。 */
    private static void display(PlayerActiveSkillContext context, List<Player> viewers,
                                List<Location> points, SharedParticleDefinition particle) {
        context.services().effects().pointsForViewers(viewers,
                points.size() > MAX_LAYER_POINTS ? points.subList(0, MAX_LAYER_POINTS) : points, particle);
    }

    /** 各陣の意味に対応する粒子層を返します。 */
    private static SharedParticleDefinition fieldParticle(FieldStyle style) {
        return switch (style) {
            case AEGIS -> SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_CYAN;
            case RENEWAL -> SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_MINT;
            case VIGOR, METEOR -> SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD;
            case STASIS, REPULSION -> SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET;
        };
    }

    /** 各陣の意味に対応する短い音色を返します。 */
    private static Sound fieldSound(FieldStyle style) {
        return switch (style) {
            case AEGIS -> Sound.ITEM_ARMOR_EQUIP_DIAMOND;
            case RENEWAL -> Sound.BLOCK_NOTE_BLOCK_HARP;
            case VIGOR -> Sound.BLOCK_NOTE_BLOCK_BELL;
            case STASIS -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE;
            case REPULSION -> Sound.ENTITY_BREEZE_WIND_BURST;
            case METEOR -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
        };
    }

    /** 設置音と成立音の基準音程を技能ごとに分けます。 */
    private static float fieldPitch(FieldStyle style) {
        return switch (style) {
            case AEGIS -> 0.9F;
            case RENEWAL -> 1.2F;
            case VIGOR -> 1.55F;
            case STASIS -> 0.65F;
            case REPULSION -> 1.0F;
            case METEOR -> 1.35F;
        };
    }

    /** 水平面の極座標を複製した座標へ変換します。 */
    private static Location at(Location center, double radius, double angle, double y) {
        return center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    /** 極座標の一点を追加します。 */
    private static void polar(List<Location> points, Location center, double radius, double angle, double y) {
        points.add(at(center, radius, angle, y));
    }

    /** 有限点数の水平円を追加します。 */
    private static void ring(List<Location> points, Location center, double radius, int count, double y) {
        for (int step = 0; step < count; step++) { polar(points, center, radius, TWO_PI * step / count, y); }
    }

    /** 有限点数の線分を追加します。 */
    private static void segment(List<Location> points, Location from, Location to, int steps) {
        Vector delta = to.toVector().subtract(from.toVector());
        for (int step = 0; step <= steps; step++) { points.add(from.clone().add(delta.clone().multiply((double) step / steps))); }
    }

    /** 辺あたり6分割の多角形を追加します。 */
    private static void polygon(List<Location> points, Location center, double radius, int sides, double phase) {
        for (int edge = 0; edge < sides; edge++) {
            segment(points, at(center, radius, TWO_PI * edge / sides + phase, 0.0D),
                    at(center, radius, TWO_PI * (edge + 1) / sides + phase, 0.0D), 6);
        }
    }

    /** 五芒星の五つの辺を追加します。 */
    private static void star(List<Location> points, Location center, double radius, double phase) {
        for (int edge = 0; edge < 5; edge++) {
            segment(points, at(center, radius, TWO_PI * edge / 5 + phase, 0.0D),
                    at(center, radius, TWO_PI * (edge + 2) / 5 + phase, 0.0D), 4);
        }
    }
}
