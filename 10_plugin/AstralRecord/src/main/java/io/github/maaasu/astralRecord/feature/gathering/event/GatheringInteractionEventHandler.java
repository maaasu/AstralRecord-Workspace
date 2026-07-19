package io.github.maaasu.astralRecord.feature.gathering.event;

import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
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
import java.util.UUID;

/**
 * 左クリック時の採集対象を候補として解決します。
 */
public final class GatheringInteractionEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final GatheringService gatheringService;

    public GatheringInteractionEventHandler(@NotNull GatheringService gatheringService) {
        this.gatheringService = gatheringService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.LEFT_CLICK || !snapshot.isMainHandInput()) {
            return List.of();
        }
        GatheringService.GatheringHit hit = gatheringService.findTargetedHit(snapshot.player());
        if (hit == null || !snapshot.isVisible(hit.hitDistance())) {
            return List.of();
        }
        UUID targetId = hit.instance().instanceId();
        return List.of(new PlayerInputCandidate(
            "gathering-interaction",
            InteractionTier.WORLD_INTERACTION,
            hit.hitDistance(),
            InteractionCandidateOrder.GATHERING,
            targetId.toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
                PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
                GatheringService.GatheringHit current = gatheringService.findTargetedHit(snapshot.player());
                return current != null
                    && current.instance().instanceId().equals(targetId)
                    && currentSnapshot.isVisible(current.hitDistance());
            },
            () -> gatheringService.startMining(snapshot.player())
        ));
    }
}
