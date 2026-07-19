package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * ボス入口円内のスニーク入力を、共通入力gatewayへ候補として提供します。
 */
public final class BossEntryEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final BossChallengeService bossChallengeService;

    public BossEntryEventHandler(@NotNull BossChallengeService bossChallengeService) {
        this.bossChallengeService = bossChallengeService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.SNEAK
            || !(snapshot.event() instanceof PlayerToggleSneakEvent event)
            || !event.isSneaking()) {
            return List.of();
        }
        BossChallengeService.BossEntryHit hit = bossChallengeService.findNearestChallengeEntry(snapshot.player());
        if (hit == null) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "boss-entry",
            InteractionTier.WORLD_INTERACTION,
            hit.hitDistance(),
            InteractionCandidateOrder.BOSS_ENTRY,
            hit.bossId(),
            InputClaimPolicy.CLAIM,
            () -> runSafely(
                () -> bossChallengeService.acceptChallenge(snapshot.player(), hit.bossId()),
                LogId.E_6501,
                snapshot.player().getName()
            )
        ));
    }
}
