package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.executor.PaladinDivineChaserSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** ディバインチェイサーの命中後追撃と演出を管理します。 */
public final class PaladinDivineChaserRuntimeService {
    private static final double DEFAULT_DAMAGE_RATIO = 0.20D;
    private static final double DEFAULT_SPHERE_RADIUS = 0.8D;
    private static final int DEFAULT_SPHERE_POINTS = 32;
    private static final double DEFAULT_PROJECTILE_SPEED = 1.25D;
    private static final double DEFAULT_PROJECTILE_HIT_RADIUS = 0.35D;
    private static final double DEFAULT_ENERGY_COST = 2.0D;
    private static final double COST_EPSILON = 1.0E-6D;

    private final SkillService skillService;
    private final SkillCombatService combatService;
    private final SkillEffectService effectService;
    private final SkillProjectileService projectileService;
    private final StatusService statusService;
    @Nullable
    private PassiveSkillService passiveSkillService;

    /**
     * ディバインチェイサー runtime を初期化します。
     *
     * @param skillService スキル定義を取得するサービス
     * @param combatService 共通ダメージサービス
     * @param effectService 共通演出サービス
     * @param projectileService 共通仮想 projectile サービス
     * @param statusService リソースとステータスを管理するサービス
     */
    public PaladinDivineChaserRuntimeService(
            @NotNull SkillService skillService,
            @NotNull SkillCombatService combatService,
            @NotNull SkillEffectService effectService,
            @NotNull SkillProjectileService projectileService,
            @NotNull StatusService statusService
    ) {
        this.skillService = skillService;
        this.combatService = combatService;
        this.effectService = effectService;
        this.projectileService = projectileService;
        this.statusService = statusService;
    }

    /**
     * パッシブ有効状態の解決先を設定します。
     *
     * @param passiveSkillService パッシブ管理サービス。{@code null} で追撃を無効化
     */
    public void setPassiveSkillService(@Nullable PassiveSkillService passiveSkillService) {
        this.passiveSkillService = passiveSkillService;
    }

    /**
     * スキル命中結果を受け取り、条件を満たす場合だけ追撃を開始します。
     *
     * @param sourceSkill 命中を発生させたスキル定義
     * @param attacker 攻撃者
     * @param target 命中対象
     * @param result 主攻撃の結果
     */
    public void onSkillHit(
            @NotNull SkillDefinition sourceSkill,
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull DamageResult result
    ) {
        if (!hasHolyKnightTag(sourceSkill)
                || result.evaded()
                || (result.finalDamage() <= 0.0D && result.shieldDamage() <= 0.0D)
                || !attacker.isPlayer()
                || attacker.player() == null
                || !target.isMob()
                || target.mob() == null) {
            return;
        }

        Player player = attacker.player().getBukkit();
        PassiveSkillService passives = passiveSkillService;
        if (passives == null
                || !passives.isPassiveSkillActive(attacker.player(), PaladinDivineChaserSkillExecutor.ID)) {
            return;
        }

        SkillDefinition passiveDefinition = skillService.registry()
                .getDefinition(PaladinDivineChaserSkillExecutor.ID);
        if (passiveDefinition == null) {
            return;
        }

        SkillParamReader params = new SkillParamReader(
                passiveDefinition.getId(), passiveDefinition.getParams()
        );
        StatusSnapshot currentStatus = statusService.getStatus(attacker.player());
        double energyCost = resolveEnergyCost(passiveDefinition, currentStatus);
        if (currentStatus.getCurrentEnergy() + COST_EPSILON < energyCost) {
            return;
        }
        statusService.consumeEnergy(attacker.player(), energyCost);

        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double sphereRadius = params.getDouble("sphereRadius", DEFAULT_SPHERE_RADIUS);
        int spherePoints = params.getInt("spherePoints", DEFAULT_SPHERE_POINTS);
        double projectileSpeed = params.getDouble("projectileSpeed", DEFAULT_PROJECTILE_SPEED);
        double projectileHitRadius = params.getDouble(
                "projectileHitRadius", DEFAULT_PROJECTILE_HIT_RADIUS
        );

        AstEntity followUpAttacker = AstEntity.player(attacker.player());
        double resolvedAttackPower = resolveAttackPower(followUpAttacker);
        Location origin = player.getEyeLocation().clone().add(0.0D, 2.0D, 0.0D);
        effectService.points(origin, sphere(origin, sphereRadius, spherePoints),
                SharedParticleDefinitions.SKILL_PALADIN_DIVINE_CHASER_DUST);

        Location targetLocation = target.location().clone().add(0.0D, 1.0D, 0.0D);
        Vector direction = targetLocation.toVector().subtract(origin.toVector());
        double range = Math.max(0.1D, direction.length());
        if (direction.lengthSquared() <= 1.0E-8D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }

        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range,
                projectileSpeed,
                projectileHitRadius,
                false,
                1,
                SharedParticleDefinitions.SKILL_PALADIN_DIVINE_CHASER_END_ROD,
                null
        );
        UUID targetId = target.id();
        projectileService.launchAtTarget(
                player,
                origin,
                targetId,
                direction,
                projectile,
                (hit, ignored) -> combatService.hitWithResolvedAttackPower(
                        followUpAttacker,
                        hit,
                        AttackType.MELEE,
                        resolvedAttackPower,
                        DamageElement.NONE,
                        damageRatio
                ),
                ignored -> { }
        );
    }

    private boolean hasHolyKnightTag(@NotNull SkillDefinition skill) {
        return skill.getTags().stream()
                .anyMatch(tag -> MasterTagIds.Theme.HOLY_KNIGHT.equalsIgnoreCase(tag));
    }

    private double resolveEnergyCost(
            @NotNull SkillDefinition skill,
            @NotNull StatusSnapshot status
    ) {
        double baseCost = skill.getResourceCost() == null
                ? DEFAULT_ENERGY_COST
                : skill.getResourceCost();
        if (!Double.isFinite(baseCost) || baseCost < 0.0D) {
            baseCost = DEFAULT_ENERGY_COST;
        }
        double rawReduction = status.rollValue(StatusType.ENERGY_COST_REDUCTION);
        double reduction = Double.isFinite(rawReduction)
                ? Math.clamp(rawReduction, 0.0D, 100.0D)
                : 0.0D;
        return Math.max(0.0D, baseCost * (1.0D - reduction / 100.0D));
    }

    private double resolveAttackPower(@NotNull AstEntity attacker) {
        double meleeDefense = attacker.statValue(StatusType.MELEE_DEFENSE);
        double magicDefense = attacker.statValue(StatusType.MAGIC_DEFENSE);
        double strength = attacker.statValue(StatusType.STRENGTH);
        double defense = attacker.statValue(StatusType.DEFENSE);
        double value = (meleeDefense + magicDefense) * strength + defense / 2.0D;
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private @NotNull List<Location> sphere(
            @NotNull Location center,
            double radius,
            int pointCount
    ) {
        int safePointCount = Math.max(4, pointCount);
        List<Location> points = new ArrayList<>(safePointCount);
        double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int index = 0; index < safePointCount; index++) {
            double fraction = (index + 0.5D) / safePointCount;
            double y = 1.0D - 2.0D * fraction;
            double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            double angle = goldenAngle * index;
            points.add(center.clone().add(
                    Math.cos(angle) * horizontal * radius,
                    y * radius,
                    Math.sin(angle) * horizontal * radius
            ));
        }
        return points;
    }
}
