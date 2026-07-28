package io.github.maaasu.astralRecord.feature.trainingdummy.event;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import java.util.Collection;
import java.util.List;

/** 視線先のカカシに対する Drop キーを設定 GUI 起動へ変換します。 */
public final class TrainingDummyInputResolver implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final MobService mobService;
    private final TrainingDummyService service;
    private final TrainingDummyGui gui;
    public TrainingDummyInputResolver(@NotNull MobService mobService, @NotNull TrainingDummyService service, @NotNull TrainingDummyGui gui) { this.mobService = mobService; this.service = service; this.gui = gui; }
    @Override public @NotNull Collection<PlayerInputCandidate> resolve(@NotNull PlayerInputContext<PlayerInteractionSnapshot> context) {
        if (context.family() != InputFamily.DROP_ITEM) return List.of();
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        for (String id : service.ids()) {
            MobInstance instance = service.find(id) == null ? null : serviceInstance(id);
            if (instance == null || instance.bukkitEntityId() == null) continue;
            var entity = Bukkit.getEntity(instance.bukkitEntityId());
            if (entity == null) continue;
            Double distance = snapshot.hitDistance(entity, MobService.NPC_INTERACTION_RAY_SIZE);
            if (distance == null || !snapshot.isVisible(distance)) continue;
            TrainingDummyDefinition definition = service.find(id);
            return List.of(new PlayerInputCandidate("training-dummy", InteractionTier.WORLD_INTERACTION, distance, InteractionCandidateOrder.NPC, id, InputClaimPolicy.CLAIM_AND_CANCEL, () -> service.find(id) != null, () -> gui.open(snapshot.player(), definition)));
        }
        return List.of();
    }
    private MobInstance serviceInstance(String id) { for (MobInstance instance : mobService.getInstances()) { TrainingDummyDefinition definition = service.findByInstance(instance); if (definition != null && definition.id().equals(id)) return instance; } return null; }
}
