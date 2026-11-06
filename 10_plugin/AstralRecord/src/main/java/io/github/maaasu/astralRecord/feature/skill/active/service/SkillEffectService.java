package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillPackDisplay.Phase;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 発動スキルの粒子と効果音を、負荷を抑えた図形単位で描画します。
 */
public final class SkillEffectService {

    private static final int MAX_VIEWER_CANDIDATES = 64;
    private static final int MAX_FRAME_VIEWERS = 32;
    private static final double VIEWER_DISTANCE = 64.0D;

    private final ParticleDisplayService particleDisplayService;
    private final @Nullable SkillPackDisplay packDisplay;
    private final @Nullable String skillId;
    private final @Nullable Player source;
    private final int[] budgetTicks = new int[Phase.values().length];
    private final int[] budgetUsed = new int[Phase.values().length];
    private final Map<PointCue, Set<Player>> pointCues = new HashMap<>();
    private int pointCueTick;

    /**
     * 共通 particle 表示サービスで初期化します。
     *
     * @param particleDisplayService プレイヤー設定を考慮する表示サービス
     */
    public SkillEffectService(@NotNull ParticleDisplayService particleDisplayService) {
        this(particleDisplayService, null, null, null);
    }

    /**
     * 発動単位の演出サービスを作るための基点を初期化します。
     * @param particleDisplayService パック非対応時や形状境界の表示サービス
     * @param packDisplay 対応パックのスプライト表示
     */
    public SkillEffectService(@NotNull ParticleDisplayService particleDisplayService, @NotNull SkillPackDisplay packDisplay) {
        this(particleDisplayService, packDisplay, null, null);
    }

    /** 発動IDと発動者を保持し、遅延・反復処理へ安全に引き継ぎます。 */
    private SkillEffectService(ParticleDisplayService particleDisplayService, @Nullable SkillPackDisplay packDisplay,
                               @Nullable String skillId, @Nullable Player source) {
        this.particleDisplayService = particleDisplayService;
        this.packDisplay = packDisplay;
        this.skillId = skillId;
        this.source = source;
    }

    /**
     * 発動者とスキルIDに対応するスプライトを使う描画サービスを返します。
     * @param id 現在のスキル ID
     * @param player 発動者
     * @return 1回の発動に対する描画と送信制限を持つサービス
     */
    public SkillEffectService forSkill(String id, Player player) {
        return packDisplay == null ? this : new SkillEffectService(particleDisplayService, packDisplay, id, player);
    }

    /**
     * スキル固有の段階を指定し、補助スプライトを描画します。
     * @param phase 発動、軌跡、着弾、範囲の段階
     * @param location 実際の演出座標
     * @param diameter 見た目の直径（ブロック）
     * @return パック側で処理済みの閲覧者
     */
    public Set<Player> pack(Phase phase, Location location, float diameter) {
        return pack(phase, location, diameter, null);
    }

    /** 形状の閲覧者を既に解決した場合は、同じ範囲でパック演出を送ります。 */
    private Set<Player> pack(Phase phase, Location location, float diameter, @Nullable List<Player> audience) {
        if (packDisplay == null || skillId == null) return Set.of();
        int tick = Bukkit.getCurrentTick();
        int index = phase.ordinal();
        int interval = switch (phase) {
            case CAST, IMPACT -> 3;
            case TRAIL -> 2;
            case AREA -> 6;
        };
        if (tick - budgetTicks[index] >= interval) {
            budgetTicks[index] = tick;
            budgetUsed[index] = 0;
        }
        if (budgetUsed[index] >= (phase == Phase.CAST ? 1 : 4)) return Set.of();
        budgetUsed[index]++;
        return packDisplay.show(skillId, phase, location, diameter, source, audience);
    }

    /** 単発の粒子を互換パックの着弾演出で描画し、未対応の閲覧者には既存粒子を送ります。 */
    public void point(@NotNull Location location, @NotNull SharedParticleDefinition definition) {
        point(location, definition, Phase.IMPACT, 1.15F);
    }

    /**
     * 固有の段階と大きさを持つ単発演出を送り、未対応の接続に既存粒子を送ります。
     * @param location 描画する座標
     * @param definition 代替粒子
     * @param phase 表示する段階
     * @param diameter スプライトの直径（ブロック）
     */
    public void point(Location location, SharedParticleDefinition definition, Phase phase, float diameter) {
        Set<Player> handled = pointPack(phase, location, diameter);
        particleDisplayService.spawnForNearbyViewers(location, definition, false, viewer -> !handled.contains(viewer));
    }

    /** 同一点の複数の粒子層を、同じtick内では一つのパックスプライトで表現します。 */
    private Set<Player> pointPack(Phase phase, Location location, float diameter) {
        if (skillId == null || location.getWorld() == null) return Set.of();
        int tick = Bukkit.getCurrentTick();
        if (pointCueTick != tick) {
            pointCues.clear();
            pointCueTick = tick;
        }
        PointCue cue = new PointCue(location.getWorld(), location.getX(), location.getY(), location.getZ(), phase);
        return pointCues.computeIfAbsent(cue, ignored -> pack(phase, location, diameter));
    }

    private record PointCue(World world, double x, double y, double z, Phase phase) {}

    /** 飛翔体とスキル固有の着弾が同じ地点なら、同じ着弾スプライトを共有します。 */
    void projectileImpact(Location location, float diameter) {
        pointPack(Phase.IMPACT, location, diameter);
    }

    /** 連続飛翔中のスプライトは、実際に進んだ区間の終点に置きます。 */
    void projectileTrail(Location start, Location end) {
        if (start.getWorld() == end.getWorld() && start.distanceSquared(end) > 1.0E-8D) {
            pack(Phase.TRAIL, end, 0.65F);
        }
    }

    /** 複数地点のparticleを一度のviewer探索でまとめて表示します。 */
    public void points(
            @NotNull Location viewerCenter,
            @NotNull List<Location> locations,
            @NotNull SharedParticleDefinition definition
    ) {
        if (!locations.isEmpty()) {
            accent(locations, Phase.AREA, 0.65F, 2);
            particleDisplayService.spawnForNearbyViewers(viewerCenter, locations, definition);
        }
    }

    /**
     * 近傍候補の走査を64人、表示先を最大32人に制限した一フレームの閲覧者を返します。
     * ワールド全体を走査せず、範囲内の発動者を先に選びます。全粒子層で同じ結果を再使用します。
     * @param center 閲覧範囲の中心
     * @param preferred 範囲内にいる場合に優先する発動者。所有者不在の表示ではnull
     * @param limit 表示上限。1〜32へ補正する
     * @return 同一worldの64m球内にいるオンライン閲覧者の不変スナップショット
     */
    public @NotNull List<Player> nearbyViewers(@NotNull Location center, @Nullable Player preferred, int limit) {
        World world = center.getWorld();
        if (world == null) { return List.of(); }
        int safeLimit = Math.max(1, Math.min(MAX_FRAME_VIEWERS, limit));
        List<Player> viewers = new ArrayList<>(safeLimit);
        double rangeSquared = VIEWER_DISTANCE * VIEWER_DISTANCE;
        if (preferred != null && preferred.isOnline() && preferred.getWorld() == world
                && preferred.getLocation().distanceSquared(center) <= rangeSquared) {
            viewers.add(preferred);
        }
        int checked = 0;
        for (Player viewer : world.getNearbyPlayers(center, VIEWER_DISTANCE)) {
            if (viewers.size() >= safeLimit || checked++ >= MAX_VIEWER_CANDIDATES) { break; }
            if (viewer != preferred && viewer.isOnline() && viewer.getWorld() == world
                    && viewer.getLocation().distanceSquared(center) <= rangeSquared) {
                viewers.add(viewer);
            }
        }
        return List.copyOf(viewers);
    }

    /**
     * 解決済みの閲覧者へ点群を送信し、共有サービスの粒子密度・統合版設定に従います。
     * @param viewers nearbyViewersで解決した同一フレームの閲覧者
     * @param locations 一括表示する地点。異なるworldの地点は共有サービスが除外する
     * @param definition 共通粒子定義
     */
    public void pointsForViewers(@NotNull List<Player> viewers, @NotNull List<Location> locations,
                                 @NotNull SharedParticleDefinition definition) {
        if (locations.isEmpty() || viewers.isEmpty()) { return; }
        int limit = Math.min(MAX_FRAME_VIEWERS, viewers.size());
        accent(locations, Phase.AREA, 0.65F, 2, viewers.subList(0, limit));
        for (int index = 0; index < limit; index++) {
            Player viewer = viewers.get(index);
            if (viewer.isOnline()) { particleDisplayService.spawnForViewer(viewer, locations, definition); }
        }
    }

    /**
     * 解決済み閲覧者へ独立した各発生源の演出を送り、フレームの32人上限を維持します。
     * @param viewers 同一フレームで一度だけ解決した閲覧者
     * @param locations それぞれ独立した発生源
     * @param definition 各発生源の単発演出
     */
    public void burstsForViewers(@NotNull List<Player> viewers, @NotNull List<Location> locations,
                                 @NotNull SharedParticleDefinition definition) {
        if (locations.isEmpty() || viewers.isEmpty()) return;
        int limit = Math.min(MAX_FRAME_VIEWERS, viewers.size());
        accent(locations, Phase.IMPACT, 0.8F, 3, viewers.subList(0, limit));
        for (int index = 0; index < limit; index++) {
            Player viewer = viewers.get(index);
            if (viewer.isOnline()) particleDisplayService.spawnBurstsForViewer(viewer, locations, definition);
        }
    }

    /**
     * 広範囲に散った点群を、地点ごとの近傍viewerだけへ一括表示します。
     *
     * @param locations 同一worldに属する表示地点
     * @param definition 表示する共通パーティクル定義
     */
    public void pointsNearViewers(
            @NotNull List<Location> locations,
            @NotNull SharedParticleDefinition definition
    ) {
        if (!locations.isEmpty()) {
            accent(locations, Phase.AREA, 0.65F, 2);
            particleDisplayService.spawnForNearbyViewers(locations, definition);
        }
    }

    /**
     * 独立した各地点の単発演出を、中心近傍のviewerへ一括表示します。
     *
     * @param viewerCenter viewer探索の中心
     * @param locations それぞれ独立した表示地点
     * @param definition 表示する共通パーティクル定義
     */
    public void bursts(
            @NotNull Location viewerCenter,
            @NotNull List<Location> locations,
            @NotNull SharedParticleDefinition definition
    ) {
        if (!locations.isEmpty()) {
            accent(locations, Phase.IMPACT, 0.95F, 3);
            particleDisplayService.spawnBurstsForNearbyViewers(viewerCenter, locations, definition);
        }
    }

    /** 指定地点の地面ブロックを使った粉塵を表示します。 */
    public void blockDust(@NotNull Location location, @NotNull BlockData blockData) {
        var blockParticle = SharedParticleDefinitions.resolveParticle("BLOCK");
        if (blockParticle == null) {
            return;
        }
        particleDisplayService.spawnForNearbyViewers(
                location,
                blockParticle,
                18,
                0.42D,
                0.12D,
                0.42D,
                0.22D,
                blockData
        );
    }

    /** 密度設定に応じて間引けるよう、線分へ標準密度の倍の候補点を等間隔に配置します。 */
    public void line(
            @NotNull Location start,
            @NotNull Location end,
            double interval,
            @NotNull SharedParticleDefinition definition
    ) {
        drawLine(start, end, interval, definition, true);
    }

    /** 飛翔体の元の粒子軌跡を描画します。パックの表示は衝突位置の解決後に行います。 */
    void projectileParticles(Location start, Location end, double interval, SharedParticleDefinition definition) {
        drawLine(start, end, interval, definition, false);
    }

    /** 線分上の点群と、必要なら長さに応じたパック演出を描画します。 */
    private void drawLine(Location start, Location end, double interval, SharedParticleDefinition definition,
                          boolean showPack) {
        if (start.getWorld() == null || start.getWorld() != end.getWorld()) {
            return;
        }
        Vector offset = end.toVector().subtract(start.toVector());
        double distance = offset.length();
        if (distance <= 1.0E-8D) {
            particleDisplayService.spawnForNearbyViewers(start, definition);
            return;
        }
        int points = Math.max(1, Math.min(4095,
                (int) Math.ceil(distance / Math.max(0.075D, interval / 2.0D))));
        List<Location> locations = new ArrayList<>(points + 1);
        for (int index = 0; index <= points; index++) {
            locations.add(start.clone().add(offset.clone().multiply((double) index / points)));
        }
        if (showPack) {
            accent(locations, Phase.TRAIL, 0.75F, Math.clamp((int) Math.ceil(distance / 4.0D), 1, 4));
        }
        particleDisplayService.spawnForNearbyViewers(start, locations, definition);
    }

    /**
     * 複数の線分を一度のviewer探索でまとめて描画します。
     *
     * @param viewerCenter viewer探索の中心
     * @param segments 描画する線分
     * @param interval 標準密度でのparticle間隔。候補点はその半分の間隔で配置する
     * @param definition particle定義
     */
    public void lines(
            @NotNull Location viewerCenter,
            @NotNull List<SkillEffectLineSegment> segments,
            double interval,
            @NotNull SharedParticleDefinition definition
    ) {
        List<Location> locations = new ArrayList<>();
        for (SkillEffectLineSegment segment : segments) {
            Location start = segment.start();
            Location end = segment.end();
            if (start.getWorld() == null || start.getWorld() != end.getWorld()) {
                continue;
            }
            Vector offset = end.toVector().subtract(start.toVector());
            double distance = offset.length();
            int points = Math.max(1, Math.min(4095,
                    (int) Math.ceil(distance / Math.max(0.075D, interval / 2.0D))));
            for (int index = 0; index <= points; index++) {
                if (locations.size() >= 4096) break;
                locations.add(start.clone().add(offset.clone().multiply((double) index / points)));
            }
            if (locations.size() >= 4096) break;
        }
        if (!locations.isEmpty()) {
            accent(locations, Phase.TRAIL, 0.65F, 4);
            particleDisplayService.spawnForNearbyViewers(viewerCenter, locations, definition);
        }
    }

    /** 標準密度の倍の候補点で水平リングを描き、共有表示サービスへ渡します。 */
    public void ring(
            @NotNull Location center,
            double radius,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        int safePoints = Math.min(2048, Math.max(4, points)) * 2;
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double angle = Math.PI * 2.0D * index / safePoints;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius));
        }
        accent(locations, Phase.AREA, 0.7F, 4);
        particleDisplayService.spawnForNearbyViewers(center, locations, definition);
    }

    /**
     * 統合版の近傍閲覧者だけへ表示エンティティの代替となる水平リングを描きます。
     * 統合版を先に抽出し、Java版の人数で代替表示の32人上限を消費しません。
     *
     * @param center リングの中心
     * @param radius 水平半径
     * @param points 標準密度で表示する点数
     * @param definition 共通粒子定義
     */
    public void bedrockRing(
            @NotNull Location center,
            double radius,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        World world = center.getWorld();
        if (world == null) return;
        List<Player> viewers = new ArrayList<>(MAX_FRAME_VIEWERS);
        for (Player viewer : world.getNearbyPlayers(center, VIEWER_DISTANCE)) {
            if (viewers.size() >= MAX_FRAME_VIEWERS) break;
            if (!viewer.isOnline() || viewer.getWorld() != world
                    || viewer.getLocation().distanceSquared(center) > VIEWER_DISTANCE * VIEWER_DISTANCE) continue;
            AstPlayer astPlayer = AstPlayerCache.get(viewer);
            if (astPlayer == null || !astPlayer.isBedrock()) continue;
            viewers.add(viewer);
        }
        if (viewers.isEmpty()) {
            return;
        }
        int safePoints = Math.min(2048, Math.max(4, points)) * 2;
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double angle = Math.PI * 2.0D * index / safePoints;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius));
        }
        for (Player viewer : viewers) {
            particleDisplayService.spawnForViewer(viewer, locations, definition);
        }
    }

    /** 視線方向を中心とする水平円弧を表示します。 */
    public void arc(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double angleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        arcSegment(origin, direction, radius, -angleDegrees / 2.0D, angleDegrees / 2.0D, points, definition);
    }

    /** 両端を含む標準密度の倍の候補点で水平円弧を表示します。 */
    public void arcSegment(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double startAngleDegrees,
            double endAngleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() <= 1.0E-8D) {
            horizontal.setZ(1.0D);
        }
        horizontal.normalize();
        int safePoints = Math.min(2048, Math.max(2, points)) * 2 - 1;
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double fraction = (double) index / (safePoints - 1);
            double angle = Math.toRadians(startAngleDegrees
                    + (endAngleDegrees - startAngleDegrees) * fraction);
            Vector offset = horizontal.clone().rotateAroundY(angle).multiply(radius);
            locations.add(origin.clone().add(offset));
        }
        accent(locations, Phase.TRAIL, 0.9F, 3);
        particleDisplayService.spawnForNearbyViewers(origin, locations, definition);
    }

    /**
     * 視線方向を軸にした円弧を、上下方向を含めて表示します。
     *
     * @param origin 円弧の中心
     * @param direction 視線方向
     * @param radius 円弧の半径
     * @param startAngleDegrees 開始角度。正の角度は視線から見て右側
     * @param endAngleDegrees 終了角度
     * @param points 標準密度での表示点数。候補点は端点を共有して約2倍にする
     * @param definition パーティクル定義
     */
    public void viewArcSegment(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double startAngleDegrees,
            double endAngleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        Vector forward = direction.clone();
        if (forward.lengthSquared() <= 1.0E-8D) {
            forward.setZ(1.0D);
        }
        forward.normalize();
        Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() <= 1.0E-8D) {
            right.setX(1.0D);
        } else {
            right.normalize();
        }
        int safePoints = Math.min(2048, Math.max(2, points)) * 2 - 1;
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double fraction = (double) index / (safePoints - 1);
            double angle = Math.toRadians(startAngleDegrees
                    + (endAngleDegrees - startAngleDegrees) * fraction);
            Vector offset = forward.clone().multiply(Math.cos(angle) * radius)
                    .add(right.clone().multiply(Math.sin(angle) * radius));
            locations.add(origin.clone().add(offset));
        }
        accent(locations, Phase.TRAIL, 0.9F, 3);
        particleDisplayService.spawnForNearbyViewers(origin, locations, definition);
    }

    /** 判定形状に沿った地点を均等に選び、形状全体の粒子は維持します。 */
    private void accent(List<Location> locations, Phase phase, float diameter, int limit) {
        accent(locations, phase, diameter, limit, null);
    }

    /** 元の粒子と同じ閲覧者だけに補助スプライトを表示します。 */
    private void accent(List<Location> locations, Phase phase, float diameter, int limit,
                        @Nullable List<Player> audience) {
        if (skillId == null || locations.isEmpty()) return;
        int count = Math.min(limit, locations.size());
        for (int index = 0; index < count; index++) {
            int sample = (2 * index + 1) * locations.size() / (2 * count);
            pack(phase, locations.get(sample), diameter, audience);
        }
    }

    /** 効果音を周辺プレイヤーへ再生します。 */
    public void sound(@NotNull Location location, @NotNull Sound sound, float volume, float pitch) {
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}
