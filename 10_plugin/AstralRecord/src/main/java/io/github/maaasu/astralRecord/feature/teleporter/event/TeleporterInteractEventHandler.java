package io.github.maaasu.astralRecord.feature.teleporter.event;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.teleporter.service.WaystoneHitBoxResolver;
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
 * ウェイストーンの左右クリック候補を副作用なしで解決します。
 * 実際のウェイストーン処理は共通 gateway が本候補を勝者に選んだ場合だけ実行します。
 */
public final class TeleporterInteractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final TeleporterService teleporterService;
    private final WaystoneHitBoxResolver hitBoxResolver;

    /**
     * ウェイストーン候補 resolver を生成します。
     *
     * @param teleporterService ウェイストーン操作サービス
     * @param hitBoxResolver 視線 hitbox resolver
     */
    public TeleporterInteractEventHandler(
        @NotNull TeleporterService teleporterService,
        @NotNull WaystoneHitBoxResolver hitBoxResolver
    ) {
        this.teleporterService = teleporterService;
        this.hitBoxResolver = hitBoxResolver;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if ((context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK)
            || !context.inputSnapshot().isMainHandInput()) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return List.of();
        }
        WaystoneHitBoxResolver.WaystoneHit hit = hitBoxResolver.resolveHit(snapshot.player());
        if (hit == null || !snapshot.isVisible(hit.hitDistance())) {
            return List.of();
        }
        boolean rightClick = context.family() == InputFamily.RIGHT_CLICK;
        return List.of(new PlayerInputCandidate(
            "waystone-interaction",
            InteractionTier.WORLD_INTERACTION,
            hit.hitDistance(),
            InteractionCandidateOrder.WAYSTONE,
            hit.definition().id(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
                PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
                WaystoneHitBoxResolver.WaystoneHit current = hitBoxResolver.resolveHit(snapshot.player());
                return current != null
                    && current.definition().id().equals(hit.definition().id())
                    && currentSnapshot.isVisible(current.hitDistance());
            },
            () -> teleporterService.handleWaystoneClick(
                snapshot.player(),
                astPlayer,
                hit.definition(),
                rightClick
            )
        ));
    }
}
