package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
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
 * スニーク入力を壁張り付き・ドッジのfallback候補として提供します。
 */
public class PlayerSneakEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final AirActionService airActionService;
    private final DodgeService dodgeService;

    public PlayerSneakEventHandler(AirActionService airActionService, DodgeService dodgeService) {
        this.airActionService = airActionService;
        this.dodgeService = dodgeService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.SNEAK
            || !(snapshot.event() instanceof PlayerToggleSneakEvent event)) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "player-sneak-control",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.PLAYER_CONTROL,
            context.playerId().toString(),
            InputClaimPolicy.CLAIM,
            () -> runSafely(
                () -> handleSneak(astPlayer, event.isSneaking()),
                LogId.E_5170,
                snapshot.player().getName()
            )
        ));
    }

    private void handleSneak(@NotNull AstPlayer astPlayer, boolean sneaking) {
        if (sneaking) {
            if (airActionService.tryStartWallCling(astPlayer)) {
                return;
            }
            dodgeService.beginSneakWindow(astPlayer);
            return;
        }
        if (airActionService.releaseWallCling(astPlayer)) {
            return;
        }
        dodgeService.tryTriggerOnSneakRelease(astPlayer);
    }
}
