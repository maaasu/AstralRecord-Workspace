package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** 星糸の紡錘、二重螺旋、四尖の星を有限の点群で描きます。 */
final class RangedStarweaveVisuals {
    private final ParticleDisplayService particles;

    /** @param particles 閲覧者設定と近傍走査を共通化する表示サービス */
    RangedStarweaveVisuals(ParticleDisplayService particles) { this.particles = particles; }

    /**
     * 三本の星糸を前方へ絞る紡錘を描き、追矢の始動音を一度だけ出します。
     * @param origin 発生位置
     * @param direction 追矢の初期方向
     */
    void launch(Location origin, Vector direction) {
        Frame frame = frame(direction);
        List<Location> gold = new ArrayList<>(24);
        List<Location> cyan = new ArrayList<>(12);
        for (int strand = 0; strand < 3; strand++) {
            for (int step = 0; step < 12; step++) {
                double progress = step / 11.0D;
                double angle = strand * Math.PI * 2.0D / 3.0D + progress * Math.PI;
                double width = Math.sin(progress * Math.PI) * 0.42D;
                Location point = point(origin, frame, progress * 1.25D,
                        Math.cos(angle) * width, Math.sin(angle) * width);
                (strand == 1 ? cyan : gold).add(point);
            }
        }
        show(gold, cyan);
        sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45F, 1.65F);
        sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.35F, 1.35F);
    }

    /**
     * 直前位置から次位置へ二本の糸を編み、先頭に小さな矢尻を付けます。
     * @param from 前回位置
     * @param to 今回位置
     * @param step 飛翔段階の0始まり番号
     */
    void flight(Location from, Location to, int step) {
        Vector segment = to.toVector().subtract(from.toVector());
        double length = segment.length();
        if (length < 1.0E-8D) return;
        Frame frame = frame(segment);
        List<Location> gold = new ArrayList<>(20);
        List<Location> cyan = new ArrayList<>(20);
        for (int index = 0; index < 18; index++) {
            double progress = index / 17.0D;
            double angle = (step + progress) * Math.PI * 2.0D;
            double width = 0.16D * Math.sin(progress * Math.PI);
            gold.add(point(from, frame, progress * length, Math.cos(angle) * width, Math.sin(angle) * width));
            cyan.add(point(from, frame, progress * length, -Math.cos(angle) * width, -Math.sin(angle) * width));
        }
        gold.add(point(to, frame, -0.25D, 0.14D, 0.0D));
        gold.add(point(to, frame, -0.25D, -0.14D, 0.0D));
        show(gold, cyan);
    }

    /**
     * 命中点に四尖の星と内側の菱形を描き、有効命中だけに短い着弾音を鳴らします。
     * @param center 有効命中位置
     * @param direction 着弾方向
     */
    void impact(Location center, Vector direction) {
        Frame frame = frame(direction);
        List<Location> gold = new ArrayList<>(32);
        List<Location> cyan = new ArrayList<>(16);
        for (int arm = 0; arm < 4; arm++) {
            double angle = arm * Math.PI * 0.5D;
            for (int step = 0; step < 8; step++) {
                double radius = 0.12D + step * 0.1D;
                gold.add(point(center, frame, 0.0D, Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
            for (int step = 0; step < 4; step++) {
                double progress = step / 4.0D;
                double next = angle + Math.PI * 0.5D;
                cyan.add(point(center, frame, 0.0D,
                        0.34D * (Math.cos(angle) * (1.0D - progress) + Math.cos(next) * progress),
                        0.34D * (Math.sin(angle) * (1.0D - progress) + Math.sin(next) * progress)));
            }
        }
        show(gold, cyan);
        sound(center, Sound.ENTITY_ARROW_HIT, 0.4F, 1.5F);
        sound(center, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.3F, 1.8F);
    }

    /**
     * 同色の点をまとめて送り、点ごとの閲覧者走査を避けます。
     * @param gold 金色の点群
     * @param cyan 青緑の点群
     */
    private void show(List<Location> gold, List<Location> cyan) {
        particles.spawnForNearbyViewers(gold, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_GOLD);
        particles.spawnForNearbyViewers(cyan, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /**
     * 視線が上下を向いていても縮退しない直交基底を作ります。
     * @param direction 基準方向
     * @return 正規化した前・右・上の基底
     */
    private static Frame frame(Vector direction) {
        Vector forward = direction.lengthSquared() < 1.0E-8D ? new Vector(0, 0, 1) : direction.clone().normalize();
        Vector axis = Math.abs(forward.getY()) > 0.95D ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(axis).normalize();
        return new Frame(forward, right, right.clone().crossProduct(forward).normalize());
    }

    /**
     * 基底座標から新しいワールド座標を作り、入力座標とベクトルは変更しません。
     * @param origin 原点
     * @param frame 直交基底
     * @param ahead 前方向の距離
     * @param side 右方向の距離
     * @param up 上方向の距離
     * @return 新しいワールド座標
     */
    private static Location point(Location origin, Frame frame, double ahead, double side, double up) {
        return origin.clone().add(frame.forward.clone().multiply(ahead))
                .add(frame.right.clone().multiply(side)).add(frame.up.clone().multiply(up));
    }

    /**
     * ワールドが存在する発生地点に、上限付きの短い音を一回だけ鳴らします。
     * @param center 発生位置
     * @param sound 既存Minecraftサウンド
     * @param volume 小音量のコンテンツ設定
     * @param pitch 音高
     */
    private static void sound(Location center, Sound sound, float volume, float pitch) {
        if (center.getWorld() != null) center.getWorld().playSound(center, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private record Frame(Vector forward, Vector right, Vector up) { }
}
