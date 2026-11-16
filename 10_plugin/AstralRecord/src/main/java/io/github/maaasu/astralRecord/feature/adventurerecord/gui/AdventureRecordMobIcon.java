package io.github.maaasu.astralRecord.feature.adventurerecord.gui;

import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import io.github.maaasu.astralRecord.shared.gui.HeadTextureItemStackSupport;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 冒険記録で一覧と詳細に共通する Mob の頭アイコンを構築します。 */
final class AdventureRecordMobIcon {
    private static final String ROLE_PREFIX = "mob_head_";
    private static final Map<EntityType, Material> VANILLA_HEADS = Map.of(
        EntityType.ZOMBIE, Material.ZOMBIE_HEAD,
        EntityType.SKELETON, Material.SKELETON_SKULL,
        EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SKULL,
        EntityType.CREEPER, Material.CREEPER_HEAD,
        EntityType.ENDER_DRAGON, Material.DRAGON_HEAD,
        EntityType.PIGLIN, Material.PIGLIN_HEAD
    );
    private static final Map<String, String> BOSS_HEAD_ROLES = Map.of(
        "midgard_savanna_sunbird", "mob_head_sunbird",
        "selenea_eclipse_witch", "mob_head_selenea",
        "orrery_heart_nyxeron", "mob_head_nyxeron"
    );

    private AdventureRecordMobIcon() {
    }

    /**
     * マスタ指定のプレイヤーヘッド、パックの頭モデル、バニラの頭を優先します。
     * マスタに頭アイコンの指定がない場合は、従来どおり Mob の外見から頭を選びます。
     *
     * @param template 表示する有効レベルの Mob テンプレート
     * @param name GUI アイテムの表示名
     * @param lore GUI アイテムの説明
     * @return 新しい頭アイコンまたは従来アイコンの ItemStack
     */
    static @NotNull ItemStack create(
        @NotNull MobTemplate template,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        String icon = template.icon();
        Material configured = icon == null || icon.isBlank() ? null : Material.matchMaterial(icon.trim());
        if (configured == Material.PLAYER_HEAD && HeadTextureItemStackSupport.isValid(template.iconTexture())) {
            ItemStack item = GuiItems.create(Material.PLAYER_HEAD, name, lore);
            HeadTextureItemStackSupport.apply(item, template.iconTexture());
            return item;
        }

        String configuredRole = template.iconModel();
        if (configuredRole != null && configuredRole.startsWith(ROLE_PREFIX)
            && GuiResourcePackIcons.hasIcon(configuredRole, Material.PAPER)) {
            return GuiResourcePackIcons.apply(GuiItems.create(Material.PAPER, name, lore), configuredRole);
        }
        if (configured != null && VANILLA_HEADS.containsValue(configured)) {
            return GuiItems.create(configured, name, lore);
        }

        Material vanillaHead = VANILLA_HEADS.get(template.entityType());
        if (vanillaHead != null) {
            return GuiItems.create(vanillaHead, name, lore);
        }

        String specialRole = BOSS_HEAD_ROLES.get(template.id());
        if (specialRole != null && GuiResourcePackIcons.hasIcon(specialRole, Material.PAPER)) {
            return GuiResourcePackIcons.apply(GuiItems.create(Material.PAPER, name, lore), specialRole);
        }

        String variantRole = variantRole(template);
        if (variantRole != null && GuiResourcePackIcons.hasIcon(variantRole, Material.PAPER)) {
            return GuiResourcePackIcons.apply(GuiItems.create(Material.PAPER, name, lore), variantRole);
        }

        String entityRole = ROLE_PREFIX + template.entityType().name().toLowerCase(Locale.ROOT);
        if (GuiResourcePackIcons.hasIcon(entityRole, Material.PAPER)) {
            return GuiResourcePackIcons.apply(GuiItems.create(Material.PAPER, name, lore), entityRole);
        }
        return GuiItems.create(configured == null ? Material.ZOMBIE_HEAD : configured, name, lore);
    }

    /**
     * 現行マスタで頭の色や模様が変わる Mob のモデル役割を返します。
     *
     * @param template 表示対象の Mob テンプレート
     * @return 特別な見た目の役割名。共通モデルを使う場合は {@code null}
     */
    private static String variantRole(@NotNull MobTemplate template) {
        String kind = template.variant().kind();
        return switch (template.entityType()) {
            case MOOSHROOM -> "BROWN".equals(kind) ? ROLE_PREFIX + "mooshroom_brown" : null;
            case PARROT -> switch (kind == null ? "" : kind) {
                case "GREEN" -> ROLE_PREFIX + "parrot_green";
                case "GRAY" -> ROLE_PREFIX + "parrot_gray";
                default -> null;
            };
            case RABBIT -> "BROWN".equals(kind) ? ROLE_PREFIX + "rabbit_brown" : null;
            default -> null;
        };
    }
}
