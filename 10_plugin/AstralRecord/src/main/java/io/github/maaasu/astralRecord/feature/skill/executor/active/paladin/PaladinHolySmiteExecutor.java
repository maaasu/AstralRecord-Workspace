package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 聖柱を維持し、範囲内のMobへ毎秒ランダムな断罪を落とすパラディンの発動スキルです。 */
public final class PaladinHolySmiteExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_holy_smite";
    private static final double DEFAULT_DAMAGE_RATIO = 0.50D;
    private static final double DEFAULT_RADIUS = 1.5D;
    private static final int DEFAULT_DURATION_TICKS = 20;
    private static final int DEFAULT_IMPACT_INTERVAL_TICKS = 20;
    private static final int DEFAULT_MAX_TARGETS = 1;
    private static final double DEFAULT_WEAKNESS_CHANCE = 5.0D;
    private static final int DEFAULT_WEAKNESS_DURATION_TICKS = 100;
    private static final double DEFAULT_WEAKNESS_STRENGTH = 1.0D;
    private static final double DEFAULT_PILLAR_HEIGHT = 5.0D;
    private static final double DEFAULT_SLASH_RING_RADIUS = 1.0D;
    private final PaladinHolySmiteRuntimeService runtimeService;

    /**
     * 共有発動スキルサービスと聖柱実行時状態サービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param runtimeService 聖柱の残り持続時間を管理するサービス
     */
    public PaladinHolySmiteExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinHolySmiteRuntimeService runtimeService
    ) {
        super(ID, services);
        this.runtimeService = runtimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "damageRatio");
        requirePositive(params, "radius");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "impactIntervalTicks");
        requirePositiveInt(params, "maxTargets");
        requireRange(params, "weaknessChance", 0.0D, 100.0D);
        requirePositiveInt(params, "weaknessDurationTicks");
        requirePositive(params, "weaknessStrength");
        requirePositive(params, "pillarHeight");
        requirePositive(params, "slashRingRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double radius = params.getDouble("radius", DEFAULT_RADIUS);
        int durationTicks = params.getInt("durationTicks", DEFAULT_DURATION_TICKS);
        int impactIntervalTicks = params.getInt("impactIntervalTicks", DEFAULT_IMPACT_INTERVAL_TICKS);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        double pillarHeight = params.getDouble("pillarHeight", DEFAULT_PILLAR_HEIGHT);
        double slashRingRadius = params.getDouble("slashRingRadius", DEFAULT_SLASH_RING_RADIUS);
        ActiveSkillCondition weakness = new ActiveSkillCondition(
                ConditionType.WEAKNESS,
                params.getDouble("weaknessChance", DEFAULT_WEAKNESS_CHANCE),
                params.getInt("weaknessDurationTicks", DEFAULT_WEAKNESS_DURATION_TICKS),
                params.getDouble("weaknessStrength", DEFAULT_WEAKNESS_STRENGTH)
        );
        Location center = context.services().targeting().groundAt(context.player().getLocation(), 3, 16);
        HolyPillarState state = new HolyPillarState(center, pillarHeight);
        UUID pillarId = UUID.randomUUID();
        String scope = ID + ":" + pillarId;
        try {
            state.spawnDisplays();
            runtimeService.register(pillarId, state, durationTicks);
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    Integer.MAX_VALUE,
                    tick -> {
                        advance(
                                context,
                                state,
                                tick,
                                radius,
                                maxTargets,
                                damageRatio,
                                impactIntervalTicks,
                                weakness,
                                slashRingRadius
                        );
                        if (!runtimeService.consumeTick(pillarId)) {
                            context.services().tasks().cancel(context.player().getUniqueId(), scope);
                        }
                    },
                    () -> {
                        runtimeService.unregister(pillarId);
                        state.destroy();
                    }
            );
        } catch (RuntimeException exception) {
            runtimeService.unregister(pillarId);
            state.destroy();
            throw exception;
        }
        return context.success();
    }

    /**
     * 聖柱を1tick進め、表示更新と攻撃間隔に応じた断罪を実行します。
     *
     * @param context 発動スキル実行コンテキスト
     * @param state 表示中の聖柱
     * @param tick 発動からの経過tick index
     * @param radius 球形の対象半径
     * @param maxTargets 最大対象数
     * @param damageRatio 近接攻撃力倍率
     * @param impactIntervalTicks 断罪の実行間隔
     * @param weakness 命中時に評価する衰弱
     * @param slashRingRadius 対象上部の断罪リング半径
     */
    private void advance(
            @NotNull PlayerActiveSkillContext context,
            @NotNull HolyPillarState state,
            int tick,
            double radius,
            int maxTargets,
            double damageRatio,
            int impactIntervalTicks,
            @NotNull ActiveSkillCondition weakness,
            double slashRingRadius
    ) {
        state.render(context.services().effects());
        if (tick % impactIntervalTicks != 0) {
            return;
        }
        impact(context, state, radius, maxTargets, damageRatio, slashRingRadius, weakness);
    }

    /**
     * 聖柱の現在範囲から対象をランダムに選び、断罪演出と近接無属性ダメージを1回適用します。
     * 渡された状態異常は各命中へそのまま引き渡します。
     *
     * @param context 発動スキル実行コンテキスト
     * @param state 表示中の聖柱
     * @param radius 球形の対象半径
     * @param maxTargets 最大対象数
     * @param damageRatio 近接攻撃力倍率
     * @param slashRingRadius 対象上部の断罪リング半径
     * @param conditions 命中時に評価する状態異常
     */
    static void impact(
            @NotNull PlayerActiveSkillContext context,
            @NotNull HolyPillarState state,
            double radius,
            int maxTargets,
            double damageRatio,
            double slashRingRadius,
            @NotNull ActiveSkillCondition... conditions
    ) {
        List<AstEntity> candidates = new ArrayList<>(context.services().targeting().inSphere(
                context.player(), state.center(), radius, Integer.MAX_VALUE, true
        ));
        Collections.shuffle(candidates);
        int selectedCount = Math.min(maxTargets, candidates.size());
        for (int index = 0; index < selectedCount; index++) {
            AstEntity target = candidates.get(index);
            Location base = target.location();
            renderJudgement(context, state, base, slashRingRadius);
            context.services().combat().hit(
                    context.source().skill(), context.attacker(), target,
                    AttackType.MELEE, DamageElement.NONE, damageRatio, conditions
            );
        }
    }

    private static void renderJudgement(
            @NotNull PlayerActiveSkillContext context,
            @NotNull HolyPillarState state,
            @NotNull Location base,
            double slashRingRadius
    ) {
        Location upperRing = base.clone().add(0.0D, state.pillarHeight(), 0.0D);
        context.services().effects().ring(
                upperRing,
                slashRingRadius,
                16,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
        context.services().effects().line(
                base.clone().add(0.0D, 0.10D, 0.0D),
                upperRing,
                0.30D,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は1以上の整数が必要です");
        }
    }

    private static void requireRange(@NotNull SkillParamReader params, @NotNull String key, double minimum, double maximum) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value >= minimum && value <= maximum)) {
            throw new SkillParameterException(key, "ホーリースマイトの params[" + key + "] は"
                    + minimum + "以上" + maximum + "以下が必要です");
        }
    }

    /** 白金の祭壇と聖柱の表示Entity・粒子を、生成元のスキルに依存せず管理します。 */
    static final class HolyPillarState {
        private static final int ALTAR_SUPPORT_COUNT = 4;
        private static final int CROWN_SEGMENT_COUNT = 8;
        private static final int DISPLAY_UPDATE_INTERVAL_TICKS = 4;
        private static final int MAX_PARTICLE_VIEWERS = 32;
        private static final int MAX_SHAFT_PARTICLE_POINTS = 64;
        private static final double ALTAR_RADIUS = 1.15D;
        private static final double LANTERN_HEIGHT = 1.88D;
        private static final float LANTERN_SIZE = 0.36F;
        private Location center;
        private final double pillarHeight;
        private final double ornamentHeightScale;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private final List<BlockDisplay> floatingLanterns = new ArrayList<>();
        private int visualTick;
        private boolean active;

        /**
         * 発動者の視点角を取り除き、指定高さの聖柱表示状態を準備します。
         *
         * @param center 地表の中心位置
         * @param pillarHeight 正数の聖柱高さ。低い柱では祭壇装飾の高さも縮小する
         */
        HolyPillarState(@NotNull Location center, double pillarHeight) {
            this.center = upright(center);
            this.pillarHeight = pillarHeight;
            this.ornamentHeightScale = Math.min(1.0D, pillarHeight / 3.0D);
        }

        /** 白い段状台座・金の縁取り・四方の支柱・連続光柱・八角の冠を生成します。 */
        void spawnDisplays() {
            if (center.getWorld() == null) {
                return;
            }
            active = true;
            visualTick = 0;
            spawnAltarPart(0.0D, 0.07D, 0.0D, Material.SMOOTH_QUARTZ, 2.60F, 0.14F, 2.60F, 0.0F);
            spawnAltarPart(0.0D, 0.18D, 0.0D, Material.GOLD_BLOCK, 2.40F, 0.08F, 2.40F, 0.0F);
            spawnAltarPart(0.0D, 0.26D, 0.0D, Material.SMOOTH_QUARTZ, 2.28F, 0.08F, 2.28F, 0.0F);
            spawnAltarPart(0.0D, 0.36D, 0.0D, Material.SEA_LANTERN, 0.60F, 0.12F, 0.60F, 0.0F);

            double shaftHeight = pillarHeight - 0.84D * ornamentHeightScale;
            Location shaftCenter = center.clone().add(0.0D, pillarHeight / 2.0D, 0.0D);
            spawnDisplay(shaftCenter, Material.WHITE_STAINED_GLASS, 0.30F, (float) shaftHeight, 0.30F, 0.0F);
            spawnDisplay(shaftCenter, Material.SEA_LANTERN, 0.09F, (float) shaftHeight, 0.09F, 0.0F);

            for (int index = 0; index < ALTAR_SUPPORT_COUNT; index++) {
                double angle = supportAngle(index);
                double x = Math.cos(angle) * ALTAR_RADIUS;
                double z = Math.sin(angle) * ALTAR_RADIUS;
                spawnAltarPart(x, 0.37D, z, Material.GOLD_BLOCK, 0.58F, 0.14F, 0.58F, 0.0F);
                spawnAltarPart(x, 0.965D, z, Material.QUARTZ_PILLAR, 0.34F, 1.05F, 0.34F, 0.0F);
                spawnAltarPart(x, 1.55D, z, Material.GOLD_BLOCK, 0.50F, 0.12F, 0.50F, 0.0F);
                floatingLanterns.add(spawnAltarPart(
                        x, LANTERN_HEIGHT, z, Material.SEA_LANTERN,
                        LANTERN_SIZE, LANTERN_SIZE, LANTERN_SIZE, (float) -angle
                ));
                double rayAngle = Math.PI * 2.0D * index / ALTAR_SUPPORT_COUNT;
                spawnAltarPart(
                        Math.cos(rayAngle) * 0.50D, 0.323D, Math.sin(rayAngle) * 0.50D,
                        Material.GOLD_BLOCK, 0.68F, 0.045F, 0.055F, (float) -rayAngle
                );
            }
            for (int index = 0; index < CROWN_SEGMENT_COUNT; index++) {
                double angle = Math.PI * 2.0D * index / CROWN_SEGMENT_COUNT;
                spawnDisplay(
                        center.clone().add(Math.cos(angle) * 0.64D,
                                pillarHeight - 0.28D * ornamentHeightScale, Math.sin(angle) * 0.64D),
                        Material.GOLD_BLOCK, 0.53F, (float) (0.08D * ornamentHeightScale), 0.065F,
                        (float) (Math.PI / 2.0D - angle)
                );
            }
            spawnDisplay(
                    center.clone().add(0.0D, pillarHeight - 0.36D * ornamentHeightScale, 0.0D),
                    Material.SEA_LANTERN, 0.40F, (float) (0.40D * ornamentHeightScale), 0.40F, 0.0F
            );
            spawnDisplay(
                    center.clone().add(0.0D, pillarHeight - 0.10D * ornamentHeightScale, 0.0D),
                    Material.GOLD_BLOCK, 0.14F, (float) (0.20D * ornamentHeightScale), 0.14F, 0.0F
            );
        }

        /**
         * 聖柱の共通演出を1tick進めます。閲覧者を上限付きで一度だけ解決し、不在時は粒子生成を省略します。
         *
         * @param effects プレイヤー設定を考慮する共通演出サービス
         */
        void render(@NotNull SkillEffectService effects) {
            if (!active) {
                return;
            }
            int tick = visualTick++;
            if (tick % DISPLAY_UPDATE_INTERVAL_TICKS == 0) {
                updateDisplays(tick);
            }
            if ((tick & 1) != 0) {
                return;
            }
            List<Player> viewers = effects.nearbyViewers(center, null, MAX_PARTICLE_VIEWERS);
            if (viewers.isEmpty()) {
                return;
            }
            double shaftHeight = pillarHeight - 0.84D * ornamentHeightScale;
            int shaftPoints = (int) Math.min(MAX_SHAFT_PARTICLE_POINTS,
                    Math.max(2.0D, Math.ceil(shaftHeight / 0.35D) + 1.0D));
            List<Location> white = new ArrayList<>(shaftPoints + 24);
            for (int index = 0; index < shaftPoints; index++) {
                double progress = (double) index / (shaftPoints - 1);
                white.add(center.clone().add(0.0D,
                        0.42D * ornamentHeightScale + shaftHeight * progress, 0.0D));
            }
            if (tick % DISPLAY_UPDATE_INTERVAL_TICKS == 0) {
                List<Location> gold = new ArrayList<>(88);
                addRing(gold, 1.98D, 0.12D * ornamentHeightScale, 48);
                addRing(gold, 0.75D, pillarHeight - 0.23D * ornamentHeightScale, 24);
                for (int index = 0; index < 8; index++) {
                    double progress = (double) index / 8;
                    double angle = progress * Math.PI * 2.0D + tick * 0.025D;
                    double y = 0.42D * ornamentHeightScale + shaftHeight * progress;
                    for (int strand = 0; strand < 2; strand++) {
                        double strandAngle = angle + strand * Math.PI;
                        gold.add(center.clone().add(Math.cos(strandAngle) * 0.42D,
                                y, Math.sin(strandAngle) * 0.42D));
                    }
                }
                effects.pointsForViewers(viewers, gold, SharedParticleDefinitions.SKILL_PALADIN_HOLY_PILLAR_GOLD);
                addRing(white, 0.87D, 0.34D * ornamentHeightScale, 24);
                if (tick % 8 == 0) {
                    List<Location> sparks = new ArrayList<>(ALTAR_SUPPORT_COUNT);
                    for (int index = 0; index < ALTAR_SUPPORT_COUNT; index++) {
                        double angle = supportAngle(index);
                        sparks.add(center.clone().add(Math.cos(angle) * ALTAR_RADIUS,
                                (LANTERN_HEIGHT + lanternBob(tick) + 0.21D) * ornamentHeightScale,
                                Math.sin(angle) * ALTAR_RADIUS));
                    }
                    effects.pointsForViewers(viewers, sparks, SharedParticleDefinitions.SKILL_PALADIN_HOLY_PILLAR_SPARK);
                }
            }
            effects.pointsForViewers(viewers, white, SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST);
        }

        /**
         * 円周上の確定座標を追加し、粒子のランダム拡散に依存しない光輪を組み立てます。
         *
         * @param points 追加先の描画座標一覧
         * @param radius 水平半径
         * @param height 中心からの高さ
         * @param count 円周の分割数
         */
        private void addRing(@NotNull List<Location> points, double radius, double height, int count) {
            for (int index = 0; index < count; index++) {
                double angle = Math.PI * 2.0D * index / count;
                points.add(center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
            }
        }

        /**
         * 四方の灯りを同じ高さ・位相で動かし、回転中心を固定したまま4tickで補間します。
         *
         * @param tick 生成または移動からの演出tick
         */
        private void updateDisplays(int tick) {
            double height = (LANTERN_HEIGHT + lanternBob(tick)) * ornamentHeightScale;
            for (int index = 0; index < floatingLanterns.size(); index++) {
                BlockDisplay lantern = floatingLanterns.get(index);
                if (!lantern.isValid()) {
                    continue;
                }
                double angle = supportAngle(index);
                lantern.teleport(center.clone().add(
                        Math.cos(angle) * ALTAR_RADIUS, height, Math.sin(angle) * ALTAR_RADIUS
                ));
                lantern.setInterpolationDelay(0);
                lantern.setTransformation(transformation(LANTERN_SIZE,
                        (float) (LANTERN_SIZE * ornamentHeightScale), LANTERN_SIZE,
                        (float) (tick * 0.018D - angle)));
            }
        }

        /**
         * 四方の支柱を台座の対角線へ等間隔に配置する角度を返します。
         * @param index 支柱のindex（0〜3）
         * @return 中心から支柱への角度（radian）
         */
        private static double supportAngle(int index) {
            return Math.PI / 4.0D + Math.PI * 2.0D * index / ALTAR_SUPPORT_COUNT;
        }

        /**
         * 四方の灯りへ共通適用する小さな上下移動量を返します。
         * @param tick 生成または移動からの演出tick
         * @return 標準高さでの上下移動量
         */
        private static double lanternBob(int tick) {
            return Math.sin(tick * 0.08D) * 0.045D;
        }

        /**
         * 低い柱の装飾も柱高へ収めるため、台座部品のY座標とY寸法を同率で縮小して生成します。
         *
         * @param x 中心からのX距離
         * @param y 標準高さでの中心からのY距離
         * @param z 中心からのZ距離
         * @param material 部品のブロック種別
         * @param scaleX X寸法
         * @param scaleY 標準高さでのY寸法
         * @param scaleZ Z寸法
         * @param yaw 部品中心のY軸回転角（radian）
         * @return 生成して管理対象へ登録した表示Entity
         */
        private @NotNull BlockDisplay spawnAltarPart(
                double x, double y, double z, @NotNull Material material,
                float scaleX, float scaleY, float scaleZ, float yaw
        ) {
            return spawnDisplay(center.clone().add(x, y * ornamentHeightScale, z), material,
                    scaleX, (float) (scaleY * ornamentHeightScale), scaleZ, yaw);
        }

        /**
         * 部品の中心を指定位置へ固定し、初回送信から変形済み・影なしの発光表示を生成します。
         *
         * @param location 部品中心の位置
         * @param material 部品のブロック種別
         * @param scaleX X寸法
         * @param scaleY Y寸法
         * @param scaleZ Z寸法
         * @param yaw 部品中心のY軸回転角（radian）
         * @return 生成して管理対象へ登録した表示Entity
         */
        private @NotNull BlockDisplay spawnDisplay(
                @NotNull Location location,
                @NotNull Material material,
                float scaleX,
                float scaleY,
                float scaleZ,
                float yaw
        ) {
            BlockDisplay display = center.getWorld().spawn(upright(location), BlockDisplay.class, entity -> {
                entity.setBlock(material.createBlockData());
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setTeleportDuration(DISPLAY_UPDATE_INTERVAL_TICKS);
                entity.setInterpolationDuration(DISPLAY_UPDATE_INTERVAL_TICKS);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setShadowRadius(0.0F);
                entity.setShadowStrength(0.0F);
                entity.setTransformation(transformation(scaleX, scaleY, scaleZ, yaw));
            });
            displays.add(display);
            return display;
        }

        /**
         * ブロックの中心(0.5, 0.5, 0.5)を、拡縮・回転後もEntity原点へ一致させます。
         *
         * @param scaleX X寸法
         * @param scaleY Y寸法
         * @param scaleZ Z寸法
         * @param yaw Y軸回転角（radian）
         * @return 回転済みの中心補正を持つ変形
         */
        private static @NotNull Transformation transformation(float scaleX, float scaleY, float scaleZ, float yaw) {
            Quaternionf rotation = new Quaternionf().rotateY(yaw);
            Vector3f translation = rotation.transform(new Vector3f(-scaleX / 2.0F, -scaleY / 2.0F, -scaleZ / 2.0F));
            return new Transformation(
                    translation,
                    rotation,
                    new Vector3f(scaleX, scaleY, scaleZ),
                    new Quaternionf()
            );
        }

        /**
         * 入力を変更せず視点角だけを0へ揃え、傾きのない表示座標を返します。
         * @param location 複製元の位置
         * @return yaw/pitchだけを0へ揃えた位置の複製
         */
        private static @NotNull Location upright(@NotNull Location location) {
            Location copy = location.clone();
            copy.setYaw(0.0F);
            copy.setPitch(0.0F);
            return copy;
        }

        /** 管理している全表示Entityと浮遊灯参照を破棄し、粒子更新も停止します。 */
        void destroy() {
            active = false;
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
            floatingLanterns.clear();
        }

        /**
         * 現在の表示Entityをすべて破棄し、指定地点へ聖柱を再生成します。
         *
         * @param destination 新しい聖柱の中心。worldがない場合は何も変更しない
         */
        void moveTo(@NotNull Location destination) {
            if (destination.getWorld() == null) {
                return;
            }
            destroy();
            center = upright(destination);
            spawnDisplays();
        }

        boolean isActive() {
            return active;
        }

        @NotNull Location center() {
            return center.clone();
        }

        double pillarHeight() {
            return pillarHeight;
        }
    }
}
