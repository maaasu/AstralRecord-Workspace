package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * プレイヤー詳細情報を表示する GUI です。
 */
public final class PlayerDetailGui extends BaseMenuScreenView {
    public static final int HEAD_SLOT = 4;
    public static final int USER_SLOT = 10;
    public static final int ACCOUNT_SLOT = 12;
    public static final int CLASS_SLOT = 14;
    public static final int ECONOMY_SLOT = 16;
    public static final int RESOURCE_SLOT = 28;
    public static final int PRIMARY_SLOT = 29;
    public static final int OFFENSE_SLOT = 30;
    public static final int DEFENSE_SLOT = 31;
    public static final int UTILITY_SLOT = 32;
    public static final int BUFF_SLOT = 34;
    public static final int TRADE_SLOT = 38;
    public static final int PARTY_INVITE_SLOT = 42;

    private static final String SEPARATOR = "◇════════════════◇";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * 詳細 GUI を開きます。
     *
     * @param viewer      閲覧者
     * @param target      表示対象
     * @param snapshot    表示用ステータス
     * @param backTarget  戻り先
     * @param returnPage  戻り先一覧ページ
     */
    public void open(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        @NotNull PlayerListBackTarget backTarget,
        int returnPage
    ) {
        open(viewer, target, snapshot, 0L, target.getClassId(), 0.0, 0L, backTarget, returnPage);
    }

    /**
     * 詳細 GUI を表示用サマリ付きで開きます。
     *
     * @param viewer                  閲覧者
     * @param target                  表示対象
     * @param snapshot                表示用ステータス
     * @param goldAmount              対象アカウントの所持 Gold
     * @param classDisplayName        対象クラスの表示名
     * @param classExperienceProgress 現在クラスレベル内の経験値進捗率
     * @param classExperienceRemaining 次のクラスレベルまでの残り経験値
     * @param backTarget              戻り先
     * @param returnPage              戻り先一覧ページ
     */
    public void open(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        long goldAmount,
        @NotNull String classDisplayName,
        double classExperienceProgress,
        long classExperienceRemaining,
        @NotNull PlayerListBackTarget backTarget,
        int returnPage
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(target.getBukkit().getUniqueId(), backTarget, Math.max(0, returnPage)),
            SIZE,
            Component.text("プロフィール: " + target.getBukkit().getName(), NamedTextColor.GOLD)
        );
        render(inventory, viewer, target, snapshot, goldAmount, classDisplayName, classExperienceProgress, classExperienceRemaining);
        viewer.openInventory(inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable UUID getTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.targetId();
        }
        return null;
    }

    public @Nullable PlayerListBackTarget getBackTarget(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.backTarget();
        }
        return null;
    }

    public int getReturnPage(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.returnPage();
        }
        return 0;
    }

    private void render(
        @NotNull Inventory inventory,
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        long goldAmount,
        @NotNull String classDisplayName,
        double classExperienceProgress,
        long classExperienceRemaining
    ) {
        boolean self = viewer.getUniqueId().equals(target.getBukkit().getUniqueId());
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(HEAD_SLOT, playerHead(target, classDisplayName));
        inventory.setItem(USER_SLOT, profileItem(target));
        inventory.setItem(ACCOUNT_SLOT, accountItem(target));
        inventory.setItem(CLASS_SLOT, classItem(target, classDisplayName, classExperienceProgress, classExperienceRemaining));
        inventory.setItem(ECONOMY_SLOT, economyItem(target, goldAmount));
        inventory.setItem(RESOURCE_SLOT, categoryItem(Material.GOLDEN_APPLE, "◆", StatusType.Category.RESOURCE, NamedTextColor.GOLD, snapshot));
        inventory.setItem(PRIMARY_SLOT, categoryItem(Material.DIAMOND, "◇", StatusType.Category.PRIMARY, NamedTextColor.YELLOW, snapshot));
        inventory.setItem(OFFENSE_SLOT, categoryItem(Material.NETHERITE_SWORD, "⚔", StatusType.Category.OFFENSE, NamedTextColor.RED, snapshot));
        inventory.setItem(DEFENSE_SLOT, categoryItem(Material.SHIELD, "✚", StatusType.Category.DEFENSE, NamedTextColor.BLUE, snapshot));
        inventory.setItem(UTILITY_SLOT, categoryItem(Material.FEATHER, "✦", StatusType.Category.UTILITY, NamedTextColor.GREEN, snapshot));
        inventory.setItem(BUFF_SLOT, createItem(
            Material.POTION,
            noItalic(Component.text("現在のバフ", NamedTextColor.AQUA)),
            buildBuffLore(target)
        ));
        inventory.setItem(TRADE_SLOT, actionItem(
            Material.EMERALD,
            "トレード申請",
            self,
            "対象プレイヤーへトレード申請を送ります",
            "自分自身へは申請できません"
        ));
        inventory.setItem(PARTY_INVITE_SLOT, actionItem(
            Material.WRITABLE_BOOK,
            "パーティー招待",
            self,
            "対象プレイヤーをパーティーへ招待します",
            "自分自身へは招待できません"
        ));
    }

    private @NotNull ItemStack playerHead(@NotNull AstPlayer target, @NotNull String classDisplayName) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(target.getBukkit().getUniqueId());
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(noItalic(Component.text(target.getBukkit().getName(), NamedTextColor.WHITE, TextDecoration.BOLD)));
            skullMeta.lore(List.of(
                noItalic(Component.text("Lv." + target.getAccount().getLevel(), NamedTextColor.YELLOW)
                    .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Class Lv." + target.getClassLevel(), NamedTextColor.AQUA))),
                noItalic(Component.text("クラス: ", NamedTextColor.GRAY).append(legacy(classDisplayName)))
            ));
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private @NotNull ItemStack profileItem(@NotNull AstPlayer target) {
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("名前: " + target.getBukkit().getName(), NamedTextColor.WHITE)));
        lore.add(noItalic(Component.text("UUID: " + target.getBukkit().getUniqueId(), NamedTextColor.DARK_GRAY)));
        lore.add(noItalic(Component.text("ワールド: " + displayWorldName(target.getBukkit()), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("ゲームモード: " + target.getBukkit().getGameMode().name(), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("権限: " + target.getUser().getPermission(), NamedTextColor.YELLOW)));
        lore.add(separatorLine());
        return createItem(
            Material.NAME_TAG,
            noItalic(Component.text("基本情報", NamedTextColor.AQUA, TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull ItemStack accountItem(@NotNull AstPlayer target) {
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("アカウント名: " + target.getAccount().getAccountName(), NamedTextColor.WHITE)));
        lore.add(noItalic(Component.text("スロット: " + target.getAccount().getSlotIndex(), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("モード: " + target.getAccount().getMode().getDisplayName(), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("プレイヤー Lv: " + target.getAccount().getLevel(), NamedTextColor.YELLOW)));
        lore.add(noItalic(Component.text("累計 EXP: " + formatInt(target.getAccount().getTotalExperience()), NamedTextColor.YELLOW)));
        lore.add(Component.empty());
        lore.add(noItalic(Component.text("初ログイン: " + formatDateTime(target.getUser().getJoinDate()), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("最終ログイン: " + formatDateTime(target.getUser().getLastJoinDate()), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("作成日: " + formatDateTime(target.getAccount().getCreatedAt()), NamedTextColor.DARK_GRAY)));
        lore.add(separatorLine());
        return createItem(
            Material.BOOK,
            noItalic(Component.text("アカウント", NamedTextColor.GREEN, TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull ItemStack classItem(
        @NotNull AstPlayer target,
        @NotNull String classDisplayName,
        double classExperienceProgress,
        long classExperienceRemaining
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("クラス: ", NamedTextColor.GRAY).append(legacy(classDisplayName))));
        lore.add(noItalic(Component.text("classId: " + target.getClassId(), NamedTextColor.DARK_GRAY)));
        lore.add(noItalic(Component.text("クラス Lv: " + target.getClassLevel(), NamedTextColor.AQUA)));
        lore.add(noItalic(Component.text("クラス累計 EXP: " + formatInt(target.getClassExperience()), NamedTextColor.YELLOW)));
        lore.add(noItalic(Component.text("現在 Lv 進捗: " + formatPercent(classExperienceProgress), NamedTextColor.GREEN)));
        lore.add(noItalic(Component.text(
            classExperienceRemaining <= 0 ? "次のクラス Lv: 最大レベル" : "次のクラス Lv まで: " + formatInt(classExperienceRemaining) + " EXP",
            NamedTextColor.AQUA
        )));
        lore.add(separatorLine());
        return createItem(
            Material.COMPASS,
            noItalic(Component.text("クラス詳細", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull ItemStack economyItem(@NotNull AstPlayer target, long goldAmount) {
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("所持 Gold: " + formatInt(goldAmount), NamedTextColor.GOLD, TextDecoration.BOLD)));
        lore.add(noItalic(Component.text("プレイ時間: " + formatPlayTime(target), NamedTextColor.YELLOW)));
        lore.add(noItalic(Component.text("現在位置: " + formatLocation(target.getBukkit()), NamedTextColor.GRAY)));
        lore.add(separatorLine());
        return createItem(
            Material.RAW_GOLD,
            noItalic(Component.text("資産と活動", NamedTextColor.GOLD, TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull ItemStack categoryItem(
        @NotNull Material material,
        @NotNull String icon,
        @NotNull StatusType.Category category,
        @NotNull TextColor color,
        @NotNull StatusSnapshot snapshot
    ) {
        Component name = noItalic(Component.empty()
            .append(Component.text(icon + " ", color))
            .append(Component.text(category.getDisplayName(), color, TextDecoration.BOLD))
            .append(Component.text(" " + icon, color)));

        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        for (StatusType type : StatusType.byCategory(category)) {
            StatusValue value = snapshot.getValue(type);
            if (value != null) {
                lore.add(statLine(type, value, color));
            }
        }
        if (lore.size() == 1) {
            lore.add(noItalic(Component.text("算出済みステータスがありません", NamedTextColor.DARK_GRAY)));
        }
        lore.add(separatorLine());
        return createItem(material, name, lore);
    }

    private @NotNull Component statLine(
        @NotNull StatusType type,
        @NotNull StatusValue value,
        @NotNull TextColor accent
    ) {
        Component line = Component.empty()
            .append(Component.text(" ▸ ", accent))
            .append(Component.text(type.getDisplayName(), type.namedColor()))
            .append(Component.text("  "))
            .append(Component.text(
                type.formatRange(value.getMinValue(), value.getMaxValue()),
                NamedTextColor.WHITE,
                TextDecoration.BOLD
            ));
        double bonusMin = value.getBonusMinValue();
        double bonusMax = value.getBonusMaxValue();
        if (bonusMin != 0.0 || bonusMax != 0.0) {
            NamedTextColor bonusColor = bonusMin >= 0.0 && bonusMax >= 0.0
                ? NamedTextColor.GREEN
                : NamedTextColor.RED;
            line = line
                .append(Component.text("  基礎 ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                    type.formatRange(value.getBaseMinValue(), value.getBaseMaxValue()),
                    NamedTextColor.GRAY
                ))
                .append(Component.text(" / 補正 ", NamedTextColor.DARK_GRAY))
                .append(Component.text(type.formatSignedRange(bonusMin, bonusMax), bonusColor));
        }
        return noItalic(line);
    }

    private @NotNull ItemStack actionItem(
        @NotNull Material material,
        @NotNull String title,
        boolean disabled,
        @NotNull String enabledLine,
        @NotNull String disabledLine
    ) {
        NamedTextColor color = disabled ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN;
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text(disabled ? disabledLine : enabledLine, disabled ? NamedTextColor.GRAY : NamedTextColor.WHITE)));
        lore.add(noItalic(Component.text(disabled ? "利用不可" : "クリックで実行", color, TextDecoration.BOLD)));
        lore.add(separatorLine());
        return createItem(material, noItalic(Component.text(title, color, TextDecoration.BOLD)), lore);
    }

    private @NotNull List<Component> buildBuffLore(@NotNull AstPlayer target) {
        if (target.getActiveBuffs().isEmpty()) {
            return List.of(noItalic(Component.text("現在有効なバフはありません", NamedTextColor.GRAY)));
        }
        List<Component> lore = new ArrayList<>();
        for (var activeBuff : target.getActiveBuffs()) {
            String displayName = ColorCodeUtil.toPlainText(
                activeBuff.getType().getDisplayName(),
                activeBuff.getType().getId()
            );
            lore.add(noItalic(Component.text("- " + displayName, NamedTextColor.WHITE)));
        }
        return lore;
    }

    protected @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull Component legacy(@NotNull String text) {
        return noItalic(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(text)));
    }

    private @NotNull Component separatorLine() {
        return noItalic(Component.text(SEPARATOR, NamedTextColor.DARK_GRAY));
    }

    private @NotNull String displayWorldName(@NotNull Player target) {
        String normalizedName = target.getWorld().getName()
            .replace('\\', '/')
            .replaceAll("/{2,}", "/");
        String leafName = new File(normalizedName).getName();
        return leafName.isBlank() ? normalizedName : leafName;
    }

    private @NotNull String formatLocation(@NotNull Player target) {
        return String.format(
            Locale.US,
            "%s  %.1f, %.1f, %.1f",
            displayWorldName(target),
            target.getLocation().getX(),
            target.getLocation().getY(),
            target.getLocation().getZ()
        );
    }

    private @NotNull String formatDateTime(@NotNull LocalDateTime value) {
        return value.format(DATE_TIME_FORMATTER);
    }

    private @NotNull String formatPlayTime(@NotNull AstPlayer target) {
        long totalTicks = Math.max(0L, (long) target.getBukkit().getStatistic(Statistic.PLAY_ONE_MINUTE));
        long totalSeconds = totalTicks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d時間%02d分", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.US, "%d分%02d秒", minutes, seconds);
        }
        return String.format(Locale.US, "%d秒", seconds);
    }

    private @NotNull String formatPercent(double ratio) {
        return String.format(Locale.US, "%.1f%%", Math.max(0.0, Math.min(1.0, ratio)) * 100.0);
    }

    private @NotNull String formatInt(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private @NotNull String formatInt(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }

    private record Holder(
        @NotNull UUID targetId,
        @NotNull PlayerListBackTarget backTarget,
        int returnPage
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
