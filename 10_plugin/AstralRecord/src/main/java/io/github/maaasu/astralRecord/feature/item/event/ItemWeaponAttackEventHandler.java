package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * 武器装備の左クリックアクションを共通入力 gateway の候補として提供します。
 */
public final class ItemWeaponAttackEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final ItemWeaponAttackService itemWeaponAttackService;
    private final SkillActionRingService actionRingService;
    private final SkillTreeService skillTreeService;
    private final ConditionService conditionService;

    /**
     * 武器候補 resolver を生成します。
     *
     * @param itemWeaponAttackService 武器アクションサービス
     * @param actionRingService アクションリング状態サービス
     * @param skillTreeService スキルツリー状態サービス
     * @param conditionService 攻撃可否サービス
     */
    public ItemWeaponAttackEventHandler(
        @NotNull ItemWeaponAttackService itemWeaponAttackService,
        @NotNull SkillActionRingService actionRingService,
        @NotNull SkillTreeService skillTreeService,
        @NotNull ConditionService conditionService
    ) {
        this.itemWeaponAttackService = itemWeaponAttackService;
        this.actionRingService = actionRingService;
        this.skillTreeService = skillTreeService;
        this.conditionService = conditionService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() != InputFamily.LEFT_CLICK || !context.inputSnapshot().isMainHandInput()) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (skillTreeService.isSkillTreeEditing(snapshot.player())
            || actionRingService.isOpen(snapshot.player())
            || actionRingService.isAttackSuppressed(snapshot.player())) {
            return List.of();
        }
        var astPlayer = AstPlayerCache.get(snapshot.player());
        if (astPlayer == null
            || astPlayer.getAccount().getMode() != AccountMode.PLAYER
            || !actionRingService.hasLeftClickBind(astPlayer)) {
            return List.of();
        }
        if (!conditionService.canAttack(AstEntity.player(astPlayer))) {
            return List.of(new PlayerInputCandidate(
                "weapon-attack-condition-guard",
                InteractionTier.FALLBACK,
                0.0D,
                InteractionCandidateOrder.ATTACK_CONDITION_GUARD,
                snapshot.player().getUniqueId().toString(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
            ));
        }
        return List.of(new PlayerInputCandidate(
            "weapon-left-click-action",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.WEAPON_ACTION,
            snapshot.player().getUniqueId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> astPlayer.getAccount().getMode() == AccountMode.PLAYER
                && !skillTreeService.isSkillTreeEditing(snapshot.player())
                && !actionRingService.isOpen(snapshot.player())
                && !actionRingService.isAttackSuppressed(snapshot.player())
                && actionRingService.hasLeftClickBind(astPlayer)
                && conditionService.canAttack(AstEntity.player(astPlayer)),
            () -> actionRingService.activateLeftClickBind(astPlayer)
        ));
    }
}
