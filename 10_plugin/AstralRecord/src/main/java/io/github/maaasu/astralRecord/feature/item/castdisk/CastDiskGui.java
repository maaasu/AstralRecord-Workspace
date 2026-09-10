package io.github.maaasu.astralRecord.feature.item.castdisk;

import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** スキルキャストディスクの3行設定 GUI を描画します。 */
public final class CastDiskGui {
    public static final int SIZE = 27;
    public static final int ACTION_SLOT_START = 0;
    public static final int WEAPON_SLOT_START = 18;

    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final SkillService skillService;

    public CastDiskGui(
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull SkillService skillService
    ) {
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.skillService = skillService;
    }

    public @NotNull Inventory create(@NotNull Player player, @NotNull String equipmentInstanceId, @NotNull CastDiskSettings settings) {
        Inventory inventory = Bukkit.createInventory(
            new CastDiskInventoryHolder(equipmentInstanceId), SIZE,
            Component.text("スキルキャストディスク設定", NamedTextColor.AQUA)
        );
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        List<String> actionSlots = activeSlots(player);
        for (int index = 0; index < CastDiskSettings.ACTION_SLOT_COUNT; index++) {
            String skillId = index < actionSlots.size() ? actionSlots.get(index) : null;
            inventory.setItem(ACTION_SLOT_START + index, actionItem(player, index, skillId, settings.actionSlotIndex() == index));
        }
        inventory.setItem(13, item(Material.COMPARATOR, "設定内容", List.of(
            Component.text(settings.actionSlotIndex() < 0 ? "スキル枠: 未設定" : "スキル枠: " + (settings.actionSlotIndex() + 1), NamedTextColor.YELLOW),
            Component.text(settings.weaponHotbarSlot() < 0 ? "武器枠: 未設定" : "武器枠: " + (settings.weaponHotbarSlot() + 1), NamedTextColor.YELLOW),
            Component.text("上段でスキル枠、下段で武器枠を選択", NamedTextColor.GRAY)
        )));
        for (int index = 0; index < CastDiskSettings.WEAPON_HOTBAR_SLOT_COUNT; index++) {
            inventory.setItem(WEAPON_SLOT_START + index, weaponItem(player, index, settings.weaponHotbarSlot() == index));
        }
        return inventory;
    }

    private @NotNull List<String> activeSlots(@NotNull Player player) {
        var astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (astPlayer == null) return List.of();
        int selected = presetService.selectedPresetIndex(astPlayer.getAccount().getUuid());
        return presetService.getPresets(astPlayer.getAccount().getUuid()).stream()
            .filter(preset -> preset.isUnlocked() && preset.getPresetIndex() == selected)
            .findFirst().map(SkillBindPreset::getActiveSkillSlots).orElse(List.of());
    }

    private @NotNull ItemStack actionItem(@NotNull Player player, int index, String skillId, boolean selected) {
        Material material = Material.BARRIER;
        String name = "未設定";
        String iconTexture = null;
        if (skillId != null && !skillId.isBlank()) {
            var astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
            LearnedSkillInstance learned = astPlayer == null ? null : ownershipService.findInstance(astPlayer, skillId);
            SkillDefinition definition = learned == null ? null : skillService.registry().getDefinition(learned.getSkillId());
            if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
                material = Material.IRON_SWORD;
                name = "武器通常攻撃";
            } else if (definition != null) {
                Material resolvedMaterial = MaterialNameResolver.match(definition.getIcon());
                material = resolvedMaterial == null ? Material.AMETHYST_SHARD : resolvedMaterial;
                name = SkillPresentationUtil.plainName(definition, "未定義スキル");
                iconTexture = definition.getIconTexture();
            } else {
                name = "未習得スキル";
            }
        }
        ItemStack itemStack = item(material, "アクション枠 " + (index + 1) + ": " + name, List.of(
            Component.text(selected ? "選択中" : "クリックして設定", selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
        ));
        io.github.maaasu.astralRecord.shared.gui.HeadTextureItemStackSupport.apply(itemStack, iconTexture);
        return itemStack;
    }

    private @NotNull ItemStack weaponItem(@NotNull Player player, int index, boolean selected) {
        ItemStack held = player.getInventory().getItem(index);
        Material material = held == null || held.getType() == Material.AIR ? Material.BARRIER : held.getType();
        return item(material, "武器ホットバー " + (index + 1), List.of(
            Component.text(selected ? "選択中" : "クリックして設定", selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
        ));
    }

    private @NotNull ItemStack item(@NotNull Material material, @NotNull String name, @NotNull List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(new ArrayList<>(lore));
        stack.setItemMeta(meta);
        return stack;
    }
}
