package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/** ダンジョン開始地点の挑戦中止装置です。 */
public final class DungeonCancelController {
    private static final double INTERACTION_DISTANCE_SQUARED = 3.5D * 3.5D;
    private final UUID sessionId;
    private final Location center;
    private final BlockDisplay baseDisplay;
    private final BlockDisplay topDisplay;
    private final Interaction interaction;
    private final DisplayTextService.ManagedTextDisplay promptDisplay;

    private DungeonCancelController(
            @NotNull UUID sessionId,
            @NotNull Location center,
            @NotNull BlockDisplay baseDisplay,
            @NotNull BlockDisplay topDisplay,
            @NotNull Interaction interaction,
            @NotNull DisplayTextService.ManagedTextDisplay promptDisplay
    ) {
        this.sessionId = sessionId;
        this.center = center.clone();
        this.baseDisplay = baseDisplay;
        this.topDisplay = topDisplay;
        this.interaction = interaction;
        this.promptDisplay = promptDisplay;
    }

    /**
     * 中止装置を生成します。
     *
     * @param sessionId セッション ID
     * @param location 装置中心
     * @param displayTextService TextDisplay 管理サービス
     * @return 生成済み装置
     */
    public static @NotNull DungeonCancelController spawn(
            @NotNull UUID sessionId,
            @NotNull Location location,
            @NotNull DisplayTextService displayTextService
    ) {
        BlockDisplay base = null;
        BlockDisplay top = null;
        Interaction interaction = null;
        DisplayTextService.ManagedTextDisplay prompt = null;
        try {
            base = location.getWorld().spawn(location, BlockDisplay.class);
            base.setBlock(Material.POLISHED_BLACKSTONE.createBlockData());
            base.setTransformation(new Transformation(new Vector3f(-0.45F, 0.0F, -0.45F), new org.joml.Quaternionf(),
                    new Vector3f(0.9F, 0.35F, 0.9F), new org.joml.Quaternionf()));
            top = location.getWorld().spawn(location.clone().add(0.0D, 0.35D, 0.0D), BlockDisplay.class);
            top.setBlock(Material.REDSTONE_BLOCK.createBlockData());
            top.setTransformation(new Transformation(new Vector3f(-0.3F, 0.0F, -0.3F), new org.joml.Quaternionf(),
                    new Vector3f(0.6F, 0.55F, 0.6F), new org.joml.Quaternionf()));
            interaction = location.getWorld().spawn(location.clone().add(0.0D, 0.35D, 0.0D), Interaction.class);
            interaction.setInteractionWidth(1.8F);
            interaction.setInteractionHeight(1.8F);
            prompt = displayTextService.create(
                    DisplayAnchor.fixed(location.clone().add(0.0D, 2.25D, 0.0D)),
                    DisplayTextOptions.defaults(PlayerMsgResource.getMessage(PlayerMsgId.P_7036.getId()))
                            .withLineWidth(260).withViewRange(48.0F).withShadowed(true)
            );
            return new DungeonCancelController(sessionId, location, base, top, interaction, prompt);
        } catch (RuntimeException failure) {
            if (prompt != null) prompt.destroy();
            if (base != null && !base.isDead()) base.remove();
            if (top != null && !top.isDead()) top.remove();
            if (interaction != null && !interaction.isDead()) interaction.remove();
            throw failure;
        }
    }

    /** @return セッション ID */
    public @NotNull UUID sessionId() { return sessionId; }

    /** @return 操作用 Interaction */
    public @NotNull Interaction interaction() { return interaction; }

    /** @return 同一ワールドかつ3.5 block以内なら {@code true} */
    public boolean isNear(@NotNull Player player) {
        return player.getWorld().getUID().equals(center.getWorld().getUID())
                && player.getLocation().distanceSquared(center) <= INTERACTION_DISTANCE_SQUARED;
    }

    /** 生成した表示エンティティを破棄します。 */
    public void destroy() {
        promptDisplay.destroy();
        for (Entity entity : List.of(baseDisplay, topDisplay, interaction)) {
            if (!entity.isDead()) entity.remove();
        }
    }
}
