package io.github.maaasu.astralRecord.feature.boss.view;

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

/**
 * ボス出現地点に表示する挑戦中止操作装置です。
 * BlockDisplay を見た目、Interaction をインタラクト判定として使用します。
 */
public final class BossChallengeCancelController {
    private static final double INTERACTION_DISTANCE_SQUARED = 3.5D * 3.5D;

    private final UUID challengeId;
    private final Location center;
    private final BlockDisplay baseDisplay;
    private final BlockDisplay topDisplay;
    private final Interaction interaction;
    private final DisplayTextService.ManagedTextDisplay promptDisplay;

    private BossChallengeCancelController(
            @NotNull UUID challengeId,
            @NotNull Location center,
            @NotNull BlockDisplay baseDisplay,
            @NotNull BlockDisplay topDisplay,
            @NotNull Interaction interaction,
            @NotNull DisplayTextService.ManagedTextDisplay promptDisplay
    ) {
        this.challengeId = challengeId;
        this.center = center.clone();
        this.baseDisplay = baseDisplay;
        this.topDisplay = topDisplay;
        this.interaction = interaction;
        this.promptDisplay = promptDisplay;
    }

    /**
     * ボス出現地点に操作装置を生成します。
     *
     * @param challengeId 挑戦 ID
     * @param location 装置の中心座標
     * @param displayTextService テキストディスプレイ管理サービス
     * @return 生成した操作装置
     */
    public static @NotNull BossChallengeCancelController spawn(
            @NotNull UUID challengeId,
            @NotNull Location location,
            @NotNull DisplayTextService displayTextService
    ) {
        Location center = location.clone();
        BlockDisplay base = center.getWorld().spawn(center, BlockDisplay.class);
        base.setBlock(Material.POLISHED_BLACKSTONE.createBlockData());
        base.setTransformation(new Transformation(
                new Vector3f(-0.45F, 0.0F, -0.45F),
                new org.joml.Quaternionf(),
                new Vector3f(0.9F, 0.35F, 0.9F),
                new org.joml.Quaternionf()
        ));

        BlockDisplay top = center.getWorld().spawn(center.clone().add(0.0D, 0.35D, 0.0D), BlockDisplay.class);
        top.setBlock(Material.REDSTONE_BLOCK.createBlockData());
        top.setTransformation(new Transformation(
                new Vector3f(-0.3F, 0.0F, -0.3F),
                new org.joml.Quaternionf(),
                new Vector3f(0.6F, 0.55F, 0.6F),
                new org.joml.Quaternionf()
        ));

        Interaction interaction = center.getWorld().spawn(center.clone().add(0.0D, 0.35D, 0.0D), Interaction.class);
        interaction.setInteractionWidth(1.8F);
        interaction.setInteractionHeight(1.8F);
        DisplayTextService.ManagedTextDisplay promptDisplay = displayTextService.create(
                DisplayAnchor.fixed(center.clone().add(0.0D, 2.25D, 0.0D)),
                DisplayTextOptions.defaults("&cボス挑戦操作\n&eドロップ&fで中止GUIを開く")
                        .withLineWidth(260)
                        .withViewRange(48.0F)
                        .withShadowed(true)
        );
        return new BossChallengeCancelController(challengeId, center, base, top, interaction, promptDisplay);
    }

    /**
     * 挑戦 ID を返します。
     *
     * @return 挑戦 ID
     */
    public @NotNull UUID challengeId() {
        return challengeId;
    }

    /**
     * 操作判定用 Interaction エンティティを返します。
     *
     * @return Interaction エンティティ
     */
    public @NotNull Interaction interaction() {
        return interaction;
    }

    /**
     * プレイヤーが装置の近くにいるか判定します。
     *
     * @param player 判定対象
     * @return 同一ワールドかつ操作距離内なら true
     */
    public boolean isNear(@NotNull Player player) {
        return player.getWorld().getUID().equals(center.getWorld().getUID())
                && player.getLocation().distanceSquared(center) <= INTERACTION_DISTANCE_SQUARED;
    }

    /**
     * 装置のエンティティを破棄します。
     */
    public void destroy() {
        promptDisplay.destroy();
        for (Entity entity : List.of(baseDisplay, topDisplay, interaction)) {
            if (!entity.isDead()) {
                entity.remove();
            }
        }
    }
}
