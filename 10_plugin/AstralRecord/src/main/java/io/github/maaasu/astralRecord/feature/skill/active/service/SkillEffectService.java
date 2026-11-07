package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService.SkillEffectsClient;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillPackDisplay.Phase;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService.EffectPoints;
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
import java.util.HashSet;
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
    private static final int MAX_BEDROCK_TRAIL_SPRITES_PER_TICK = 8;

    private final ParticleDisplayService particleDisplayService;
    private final @Nullable SkillPackDisplay packDisplay;
    private final @Nullable String skillId;
    private final @Nullable Player source;
    private final int[] budgetTicks = new int[Phase.values().length];
    private final int[] budgetUsed = new int[Phase.values().length];
    private final Map<PointCue, Set<Player>> pointCues = new HashMap<>();
    private int pointCueTick;
    private int bedrockTrailTick;
    private int bedrockTrailUsed;

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
        return pack(phase, location, diameter, audience, null);
    }

    /** 一方の版に専用の軌跡がある場合も同じ発動単位の通常スプライト上限を使います。 */
    private Set<Player> pack(Phase phase, Location location, float diameter, @Nullable List<Player> audience,
                             @Nullable SkillEffectsClient edition) {
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
        return packDisplay.show(skillId, phase, location, diameter, source, audience, edition);
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
        World world = location.getWorld();
        if (world == null) return;
        particleDisplayService.spawnBudgetedForViewers(world, List.of(location), definition, null,
                viewer -> !handled.contains(viewer), EffectPoints.BURSTS, viewer -> handled.contains(viewer) ? 0 : 1);
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

    /** 専用軌跡がある飛翔体の補助スプライトを区間の終点に置きます。 */
    void projectileTrail(Location start, Location end) {
        if (start.getWorld() == end.getWorld() && start.distanceSquared(end) > 1.0E-8D) {
            pack(Phase.TRAIL, end, 0.65F);
        }
    }

    /** 通常の飛翔体は通過区間をスプライトで描き、統合版の補助粒子を絞ります。 */
    void projectileTrail(Location start, Location end, SharedParticleDefinition trail,
                         @Nullable SharedParticleDefinition accentTrail) {
        if (start.getWorld() != end.getWorld() || start.distanceSquared(end) <= 1.0E-8D) return;
        pack(Phase.TRAIL, end, 0.65F, null, SkillEffectsClient.JAVA);
        renderLine(start, end, 0.45D, trail, LineDisplay.PROJECTILE);
        if (accentTrail != null) renderLine(start, end, 0.65D, accentTrail, LineDisplay.ACCENT);
    }

    /**
     * 攻撃の連鎖をパックの軌跡で示します。
     * @param start 連鎖元の座標
     * @param end 連鎖先の座標
     * @param interval Java版の標準粒子間隔
     * @param definition 粒子定義。統合版のパック未適用時にも使用
     */
    public void chain(Location start, Location end, double interval, SharedParticleDefinition definition) {
        renderLine(start, end, interval, definition, LineDisplay.CHAIN);
    }

    /** 複数地点のparticleを一度のviewer探索でまとめて表示します。 */
    public void points(
            @NotNull Location viewerCenter,
            @NotNull List<Location> locations,
            @NotNull SharedParticleDefinition definition
    ) {
        if (!locations.isEmpty()) {
            Set<Player> rendered = accent(locations, Phase.AREA, 0.65F, 2, null);
            particles(viewerCenter, locations, definition, null, EffectPoints.LINE, rendered, 3, 6);
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
        List<Player> audience = viewers.subList(0, limit);
        Set<Player> rendered = accent(locations, Phase.AREA, 0.65F, 2, audience);
        particles(locations.getFirst(), locations, definition, audience, EffectPoints.LINE, rendered, 3, 6);
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
        List<Player> audience = viewers.subList(0, limit);
        Set<Player> rendered = accent(locations, Phase.IMPACT, 0.8F, 3, audience);
        particles(locations.getFirst(), locations, definition, audience, EffectPoints.BURSTS, rendered, 0, 2);
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
            Set<Player> rendered = accent(locations, Phase.AREA, 0.65F, 2, null);
            particles(locations.getFirst(), locations, definition, null, EffectPoints.LINE, rendered, 3, 6);
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
            Set<Player> rendered = accent(locations, Phase.IMPACT, 0.95F, 3, null);
            particles(viewerCenter, locations, definition, null, EffectPoints.BURSTS, rendered, 0, 2);
        }
    }

    /** 指定地点の地面ブロックを使った粉塵を表示します。 */
    public void blockDust(@NotNull Location location, @NotNull BlockData blockData) {
        var blockParticle = SharedParticleDefinitions.resolveParticle("BLOCK");
        if (blockParticle == null) {
            return;
        }
        particles(location, List.of(location), new SharedParticleDefinition(
                "skill_block_dust", blockParticle, 18, 0.42D, 0.12D, 0.42D, 0.22D, blockData, true),
                null, EffectPoints.BURSTS, Set.of(), 0, 0);
    }

    /** 密度設定に応じて間引けるよう、線分へ標準密度の倍の候補点を等間隔に配置します。 */
    public void line(
            @NotNull Location start,
            @NotNull Location end,
            double interval,
            @NotNull SharedParticleDefinition definition
    ) {
        renderLine(start, end, interval, definition, LineDisplay.OUTLINE);
    }

    private enum LineDisplay {
        OUTLINE(2, 5), PROJECTILE(0, 2), CHAIN(0, 3), ACCENT(0, 0);

        private final int withPack;
        private final int fallback;

        LineDisplay(int withPack, int fallback) {
            this.withPack = withPack;
            this.fallback = fallback;
        }
    }

    /** 判定に沿った線分をパックと少数の代替粒子へ描画します。 */
    private void renderLine(Location start, Location end, double interval, SharedParticleDefinition definition,
                            LineDisplay display) {
        if (start.getWorld() == null || start.getWorld() != end.getWorld()) {
            return;
        }
        Vector offset = end.toVector().subtract(start.toVector());
        double distance = offset.length();
        if (distance <= 1.0E-8D) {
            particles(start, List.of(start), definition, null, EffectPoints.BURSTS, Set.of(), 0, display.fallback);
            return;
        }
        int points = Math.max(1, Math.min(4095,
                (int) Math.ceil(distance / Math.max(0.075D, interval / 2.0D))));
        List<Location> locations = new ArrayList<>(points + 1);
        for (int index = 0; index <= points; index++) {
            locations.add(start.clone().add(offset.clone().multiply((double) index / points)));
        }
        Set<Player> rendered = switch (display) {
            case ACCENT -> Set.of();
            case PROJECTILE -> bedrockTrail(start, end, 1.25F,
                    Math.clamp((int) Math.ceil(distance / 1.4D), 1, 2));
            case CHAIN -> {
                accent(locations, Phase.TRAIL, 0.75F, Math.clamp((int) Math.ceil(distance / 4.0D), 1, 4),
                        null, SkillEffectsClient.JAVA);
                yield bedrockTrail(start, end, 1.4F, Math.clamp((int) Math.ceil(distance / 1.6D), 1, 3));
            }
            case OUTLINE -> accent(locations, Phase.TRAIL, 0.75F,
                    Math.clamp((int) Math.ceil(distance / 4.0D), 1, 4), null);
        };
        particles(start, locations, definition, null, EffectPoints.LINE, rendered, display.withPack, display.fallback);
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
            Set<Player> rendered = accent(locations, Phase.TRAIL, 0.65F, 4, null);
            particles(viewerCenter, locations, definition, null, EffectPoints.LINE, rendered, 2, 6);
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
        Set<Player> rendered = accent(locations, Phase.AREA, 0.7F, 4, null);
        particles(center, locations, definition, null, EffectPoints.LOOP, rendered, 4, 8);
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
        Set<Player> rendered = accent(locations, Phase.AREA, 0.7F, 4, viewers);
        particles(center, locations, definition, viewers, EffectPoints.LOOP, rendered, 4, 8);
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
        Set<Player> rendered = accent(locations, Phase.TRAIL, 0.9F, 3, null);
        particles(origin, locations, definition, null, EffectPoints.LINE, rendered, 3, 6);
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
        Set<Player> rendered = accent(locations, Phase.TRAIL, 0.9F, 3, null);
        particles(origin, locations, definition, null, EffectPoints.LINE, rendered, 3, 6);
    }

    /** 飛翔・連鎖の実区間へ統合版専用のスプライトを均等に配置します。 */
    private Set<Player> bedrockTrail(Location start, Location end, float diameter, int count) {
        if (packDisplay == null || skillId == null) return Set.of();
        int tick = Bukkit.getCurrentTick();
        if (bedrockTrailTick != tick) {
            bedrockTrailTick = tick;
            bedrockTrailUsed = 0;
        }
        if (bedrockTrailUsed + count > MAX_BEDROCK_TRAIL_SPRITES_PER_TICK) return Set.of();
        bedrockTrailUsed += count;
        Vector offset = end.toVector().subtract(start.toVector());
        Set<Player> rendered = new HashSet<>();
        for (int index = 0; index < count; index++) {
            Location point = start.clone().add(offset.clone().multiply((index + 0.5D) / count));
            Set<Player> viewers = packDisplay.show(skillId, Phase.TRAIL, point, diameter, source,
                    null, SkillEffectsClient.BEDROCK);
            if (index == 0) rendered.addAll(viewers);
            else rendered.retainAll(viewers);
        }
        return rendered;
    }

    /** 判定形状に沿う地点を選び、全地点のスプライトを処理できた閲覧者を返します。 */
    private Set<Player> accent(List<Location> locations, Phase phase, float diameter, int limit,
                               @Nullable List<Player> audience) {
        return accent(locations, phase, diameter, limit, audience, null);
    }

    /** 対象版が指定された場合は、その版だけにスプライトを送ります。 */
    private Set<Player> accent(List<Location> locations, Phase phase, float diameter, int limit,
                               @Nullable List<Player> audience, @Nullable SkillEffectsClient edition) {
        if (skillId == null || locations.isEmpty()) return Set.of();
        int count = Math.min(limit, locations.size());
        Set<Player> rendered = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int sample = (2 * index + 1) * locations.size() / (2 * count);
            Set<Player> viewers = pack(phase, locations.get(sample), diameter, audience, edition);
            if (index == 0) rendered.addAll(viewers);
            else rendered.retainAll(viewers);
        }
        return rendered;
    }

    /** Javaの粒子を維持し、統合版には専用表示の結果に応じた少数の粒子だけを送ります。 */
    private void particles(Location center, List<Location> points, SharedParticleDefinition definition,
                           @Nullable List<Player> audience, EffectPoints mode, Set<Player> rendered,
                           int withPack, int fallback) {
        World world = center.getWorld();
        if (world != null) {
            particleDisplayService.spawnBudgetedForViewers(world, points, definition, audience, viewer -> true,
                    mode, viewer -> rendered.contains(viewer) ? withPack : fallback);
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
