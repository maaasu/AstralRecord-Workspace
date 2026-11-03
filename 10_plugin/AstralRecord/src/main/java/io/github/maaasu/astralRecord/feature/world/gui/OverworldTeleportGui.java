package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldAdventureGuide;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 拠点ワールドからオーバーワールドを選ぶ GUI です。
 */
public final class OverworldTeleportGui {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    private final OverworldTeleportService teleportService;

    /**
     * 転送条件の表示に使うサービスを設定します。
     * @param teleportService 所持条件を転送処理と共用するサービス
     */
    public OverworldTeleportGui(@NotNull OverworldTeleportService teleportService) {
        this.teleportService = teleportService;
    }

    /**
     * GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param destinations 表示するワールド一覧
     */
    public void open(@NotNull Player player, @NotNull List<WorldMasterData> destinations) {
        open(player, destinations, () -> {
        }, () -> {
        });
    }

    /**
     * GUI を開き、表示結果に応じた処理を実行します。
     *
     * @param player 表示対象プレイヤー
     * @param destinations 表示するワールド一覧
     * @param onOpened GUI の表示に成功した場合の処理
     * @param onCancelled GUI の表示が取り消された場合の処理
     */
    public void open(
            @NotNull Player player,
            @NotNull List<WorldMasterData> destinations,
            @NotNull Runnable onOpened,
            @NotNull Runnable onCancelled
    ) {
        Map<Integer, String> worldIdsBySlot = worldIdsBySlot(destinations);
        Inventory inventory = Bukkit.createInventory(
                new Holder(worldIdsBySlot),
                SIZE,
                Component.text("オーバーワールド転送", NamedTextColor.AQUA, TextDecoration.BOLD)
        );
        render(inventory, destinations, worldIdsBySlot, AstPlayerCache.get(player));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory, onOpened, onCancelled);
    }

    /**
     * この GUI かを返します。
     *
     * @param inventory 対象 Inventory
     * @return この GUI の場合は {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * Holder を取り出します。
     *
     * @param inventory 対象 Inventory
     * @return GUI Holder。対象外なら {@code null}
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder;
        }
        return null;
    }

    /**
     * 転送先を定義済みスロットへ配置し、閲覧者の必要アイテム所持状態を表示します。
     * @param inventory 描画する GUI。既存の内容を置き換える
     * @param destinations 表示する転送先
     * @param worldIdsBySlot 重複排除済みのスロットとワールド ID
     * @param astPlayer 所持数の表示対象。未ロード時は所持数0
     */
    private void render(
            @NotNull Inventory inventory,
            @NotNull List<WorldMasterData> destinations,
            @NotNull Map<Integer, String> worldIdsBySlot,
            @Nullable AstPlayer astPlayer
    ) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }

        for (WorldMasterData destination : destinations) {
            if (destination.overworldTeleportGui() != null
                    && destination.overworldTeleportGui().hasValidSlot()
                    && destination.id().equals(worldIdsBySlot.get(destination.overworldTeleportGui().slot()))) {
                inventory.setItem(destination.overworldTeleportGui().slot(), destinationItem(destination, astPlayer));
            }
        }

        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
    }

    /**
     * 冒険ガイドと、ショップと同じ必要数・所持数の併記で入場条件を表示します。
     * @param world 転送先
     * @param astPlayer 所持数の表示対象。未ロード時は所持数0
     * @return 閲覧者の転送可否を反映したアイコン
     */
    private @NotNull ItemStack destinationItem(@NotNull WorldMasterData world, @Nullable AstPlayer astPlayer) {
        List<Component> lore = new ArrayList<>();
        if (!world.description().isBlank()) {
            for (String line : world.description().split("\\R", -1)) {
                lore.add(legacy(line, ""));
            }
        }

        WorldAdventureGuide guide = world.adventureGuide();
        if (guide != null) {
            if (guide.hasRecommendedLevel()) {
                lore.add(Component.text("推奨レベル: ", NamedTextColor.GRAY)
                        .append(Component.text(formatRange("Lv.", guide.recommendedLevelMin(), guide.recommendedLevelMax()), NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (guide.hasRecommendedPartySize()) {
                lore.add(Component.text("推奨人数: ", NamedTextColor.GRAY)
                        .append(Component.text(formatRange("", guide.recommendedPartySizeMin(), guide.recommendedPartySizeMax()) + "人", NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false));
            }
            for (String note : guide.notes()) {
                lore.add(Component.text("・", NamedTextColor.DARK_GRAY)
                        .append(legacy(note, note))
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        var requirement = teleportService.previewRequiredItem(astPlayer, world);
        if (requirement.required()) {
            lore.add(Component.empty());
            lore.add(Component.text("◆ 必要アイテム ◆", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            NamedTextColor statusColor = requirement.canTeleport() ? NamedTextColor.GREEN : NamedTextColor.RED;
            lore.add(Component.text("• ", NamedTextColor.AQUA)
                    .append(legacy(requirement.displayName(), "未登録のアイテム"))
                    .append(Component.text(" ×1", NamedTextColor.AQUA))
                    .append(Component.text(" （所持: " + requirement.ownedAmount() + "）", statusColor))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(requirement.consumed() ? "転送時に1個消費します" : "消費しません（所持のみ）",
                    requirement.consumed() ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(!requirement.valid() ? "✘ 入場条件を確認できません"
                            : requirement.canTeleport() ? "✔ 所持済み" : "✘ 必要アイテムが不足しています",
                    statusColor, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text(requirement.canTeleport() ? "クリックで移動" : "このワールドへ移動できません",
                requirement.canTeleport() ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        return GuiItems.create(
                resolveIcon(world.guiIconMaterial()),
                legacy(world.displayName(), world.worldType().getRegionDisplayName()).decorate(TextDecoration.BOLD),
                lore
        );
    }

    private @NotNull Map<Integer, String> worldIdsBySlot(@NotNull List<WorldMasterData> destinations) {
        Map<Integer, String> ids = new LinkedHashMap<>();
        for (WorldMasterData destination : destinations) {
            if (destination.overworldTeleportGui() != null
                    && destination.overworldTeleportGui().hasValidSlot()) {
                ids.putIfAbsent(destination.overworldTeleportGui().slot(), destination.id());
            }
        }
        return Map.copyOf(ids);
    }

    private @NotNull Component legacy(@NotNull String text, @NotNull String fallback) {
        return GuiItems.noItalic(LEGACY.deserialize(ColorCodeUtil.toLegacyText(text, fallback)));
    }

    private @NotNull Material resolveIcon(@Nullable String materialName) {
        if (materialName != null && !materialName.isBlank()) {
            Material resolved = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
            if (resolved != null && resolved.isItem()) {
                return resolved;
            }
        }
        return Material.GRASS_BLOCK;
    }

    private @NotNull String formatRange(@NotNull String prefix, @Nullable Integer min, @Nullable Integer max) {
        if (min != null && max != null) {
            return prefix + min + "-" + max;
        }
        if (min != null) {
            return prefix + min + "+";
        }
        if (max != null) {
            return prefix + "1-" + max;
        }
        return prefix + "-";
    }

    /**
     * GUI のスロットとワールド ID の対応を保持します。
     *
     * @param worldIdsBySlot GUI スロットをキーとする表示ワールド ID
     */
    public record Holder(@NotNull Map<Integer, String> worldIdsBySlot) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
