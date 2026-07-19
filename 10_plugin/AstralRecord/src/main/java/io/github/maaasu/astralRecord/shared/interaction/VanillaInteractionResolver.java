package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * 明示的な vanilla block/entity 操作と vanilla entity 攻撃を候補として表現します。
 * executor は何も変更せず、gateway がイベントを cancel しないことで Bukkit へ処理を委譲します。
 */
public final class VanillaInteractionResolver implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double FALLBACK_DISTANCE = 0.0D;

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() == InputFamily.RIGHT_CLICK) {
            PlayerInputCandidate entityCandidate = resolveEntity(context, snapshot);
            if (entityCandidate != null) {
                return List.of(entityCandidate);
            }
            PlayerInputCandidate blockCandidate = resolveBlock(context, snapshot);
            return blockCandidate == null ? List.of() : List.of(blockCandidate);
        }
        if (context.family() == InputFamily.LEFT_CLICK
            && context.source() == InputSource.PRE_PLAYER_ATTACK_ENTITY
            && snapshot.willAttack()) {
            String targetKey = snapshot.targetEntity() == null
                ? "vanilla-combat"
                : snapshot.targetEntity().getUniqueId().toString();
            return List.of(new PlayerInputCandidate(
                "vanilla-combat",
                InteractionTier.FALLBACK,
                FALLBACK_DISTANCE,
                InteractionCandidateOrder.VANILLA_COMBAT,
                targetKey,
                InputClaimPolicy.CLAIM,
                () -> {
                }
            ));
        }
        return List.of();
    }

    private PlayerInputCandidate resolveEntity(
        PlayerInputContext<PlayerInteractionSnapshot> context,
        PlayerInteractionSnapshot snapshot
    ) {
        if (snapshot.targetEntity() == null
            || (context.source() != InputSource.PLAYER_INTERACT_ENTITY
            && context.source() != InputSource.PLAYER_INTERACT_AT_ENTITY)) {
            return null;
        }
        Double hitDistance = snapshot.hitDistance(snapshot.targetEntity());
        if (hitDistance == null || !snapshot.isVisible(hitDistance)) {
            return null;
        }
        return new PlayerInputCandidate(
            "vanilla-entity-interaction",
            InteractionTier.WORLD_INTERACTION,
            hitDistance,
            InteractionCandidateOrder.VANILLA_INTERACTION,
            snapshot.targetEntity().getUniqueId().toString(),
            InputClaimPolicy.CLAIM,
            () -> {
            }
        );
    }

    private PlayerInputCandidate resolveBlock(
        PlayerInputContext<PlayerInteractionSnapshot> context,
        PlayerInteractionSnapshot snapshot
    ) {
        if (context.source() != InputSource.PLAYER_INTERACT || snapshot.clickedBlock() == null) {
            return null;
        }
        if (!(snapshot.event() instanceof PlayerInteractEvent interactEvent)
            || interactEvent.useInteractedBlock() == Event.Result.DENY) {
            return null;
        }
        Double hitDistance = snapshot.hitDistance(snapshot.clickedBlock());
        if (hitDistance == null || !snapshot.isVisible(hitDistance)) {
            return null;
        }
        return new PlayerInputCandidate(
            "vanilla-block-interaction",
            InteractionTier.WORLD_INTERACTION,
            hitDistance,
            InteractionCandidateOrder.VANILLA_INTERACTION,
            snapshot.directTargetKey(),
            InputClaimPolicy.CLAIM,
            () -> {
            }
        );
    }
}
