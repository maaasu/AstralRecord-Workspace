package io.github.maaasu.astralRecord.feature.skill.executor.passive.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.WizardLightningStrikeSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 感電付与に反応するウィザードの単体落雷を管理します。 */
public final class WizardLightningStrikeRuntimeService {
    private static final int BOLT_SEGMENTS = 8;
    private static final double BOLT_HEIGHT = 6.0D;

    private final SkillService skillService;
    private final SkillCombatService combatService;
    private final SkillEffectService effects;
    private final StatusService statusService;
    @Nullable
    private PassiveSkillService passiveSkillService;

    /**
     * 落雷パッシブの実行に必要なサービスを関連付けます。
     *
     * @param skillService スキル定義と効果時のMP消費を管理するサービス
     * @param combatService 共通スキルダメージを適用するサービス
     * @param effects 雷の粒子演出を表示するサービス
     * @param statusService 消費軽減を含む現在ステータスを返すサービス
     */
    public WizardLightningStrikeRuntimeService(
            @NotNull SkillService skillService,
            @NotNull SkillCombatService combatService,
            @NotNull SkillEffectService effects,
            @NotNull StatusService statusService
    ) {
        this.skillService = skillService;
        this.combatService = combatService;
        this.effects = effects;
        this.statusService = statusService;
    }

    /**
     * バインドを含むパッシブの有効状態を調べるサービスを設定します。
     *
     * @param passiveSkillService パッシブ管理サービス。nullの場合は落雷を発動しない
     */
    public void setPassiveSkillService(@Nullable PassiveSkillService passiveSkillService) {
        this.passiveSkillService = passiveSkillService;
    }

    /**
     * 自身の攻撃で感電の新規付与または再付与が成功したとき、MPを消費して無音の粒子落雷を表示し、対象へ雷ダメージを与えます。
     *
     * @param request 成功した状態異常の付与要求
     */
    public void onConditionApplied(@NotNull ConditionApplyRequest request) {
        AstEntity attacker = request.source();
        AstEntity target = request.target();
        if (request.type() != ConditionType.SHOCKED
                || request.reason() != ConditionApplyReason.SKILL
                || request.attackType() == null
                || attacker == null
                || !attacker.isPlayer()
                || attacker.player() == null
                || !target.isMob()
                || target.mob() == null) {
            return;
        }

        PassiveSkillService passives = passiveSkillService;
        if (passives == null || !passives.isPassiveSkillActive(
                attacker.player(), WizardLightningStrikeSkillExecutor.ID)) {
            return;
        }
        SkillDefinition definition = skillService.registry().getDefinition(WizardLightningStrikeSkillExecutor.ID);
        if (definition == null) {
            return;
        }

        SkillParamReader params = new SkillParamReader(definition.getId(), definition.getParams());
        double range = params.getDouble("range", 30.0D);
        Location attackerLocation = attacker.location();
        Location targetLocation = target.location();
        World world = targetLocation.getWorld();
        if (world == null || !world.equals(attackerLocation.getWorld())
                || attackerLocation.distanceSquared(targetLocation) > range * range) {
            return;
        }

        if (!skillService.tryConsumeEffectResources(
                new PlayerSkillCaster(attacker.player()),
                definition,
                statusService.getStatus(attacker.player()),
                () -> true)) {
            return;
        }

        displayLightning(targetLocation);
        combatService.hit(attacker, target, AttackType.MAGIC, DamageElement.LIGHTNING,
                params.getDouble("damageRatio", 1.5D));
    }

    /**
     * 対象の頭上から着地点までの屈曲した主幹と枝、着弾火花を粒子だけで描画します。
     *
     * @param targetLocation 対象の足元。worldを持つことが呼び出し元の前提
     */
    private void displayLightning(@NotNull Location targetLocation) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location impact = targetLocation.clone().add(0.0D, 0.12D, 0.0D);
        List<Location> nodes = new ArrayList<>(BOLT_SEGMENTS + 1);
        for (int index = 0; index <= BOLT_SEGMENTS; index++) {
            double fraction = (double) index / BOLT_SEGMENTS;
            double x = index == 0 || index == BOLT_SEGMENTS ? 0.0D : random.nextDouble(-0.65D, 0.65D);
            double z = index == 0 || index == BOLT_SEGMENTS ? 0.0D : random.nextDouble(-0.65D, 0.65D);
            nodes.add(impact.clone().add(x, BOLT_HEIGHT * (1.0D - fraction), z));
        }

        List<SkillEffectLineSegment> segments = new ArrayList<>(BOLT_SEGMENTS + 6);
        for (int index = 0; index < BOLT_SEGMENTS; index++) {
            segments.add(new SkillEffectLineSegment(nodes.get(index), nodes.get(index + 1)));
        }
        for (int index = 2; index <= 6; index += 2) {
            Location root = nodes.get(index);
            double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
            Location bend = root.clone().add(Math.cos(angle) * 0.55D, -0.45D, Math.sin(angle) * 0.55D);
            Location tip = root.clone().add(Math.cos(angle + 0.3D) * 1.25D, -1.15D,
                    Math.sin(angle + 0.3D) * 1.25D);
            segments.add(new SkillEffectLineSegment(root, bend));
            segments.add(new SkillEffectLineSegment(bend, tip));
        }

        effects.lines(impact, segments, 0.22D, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
        effects.lines(impact, segments, 0.35D, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_GLOW);
        effects.lines(impact, segments, 0.42D, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_SPARK);
        effects.ring(impact, 0.65D, 14, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_SPARK);
        effects.point(impact, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_IMPACT);
    }
}
