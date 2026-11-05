package io.github.maaasu.astralRecord.infrastructure.util;

import io.github.maaasu.astralRecord.feature.item.service.EquipmentAssetSupport;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@SuppressWarnings("UnstableApiUsage")
class CustomModelDataComponentUtilTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: Bedrock防具の表示状態をflag0へ明示し、切替時も他flagとモデルID・追加データを維持する。
     */
    @Test
    void armorHiddenFlagIsExplicitAndRetainsUnrelatedCustomModelData() {
        ItemMeta meta = mock(ItemMeta.class);
        CustomModelDataComponent component = mock(CustomModelDataComponent.class);
        AtomicReference<List<Boolean>> flags = new AtomicReference<>(List.of());
        when(meta.getCustomModelDataComponent()).thenReturn(component);
        when(component.getFlags()).thenAnswer(call -> flags.get());
        doAnswer(call -> { flags.set(List.copyOf(call.getArgument(0))); return null; })
                .when(component).setFlags(anyList());

        assertTrue(CustomModelDataComponentUtil.updateArmorHiddenFlag(meta, false));
        assertEquals(List.of(false), flags.get());
        flags.set(List.of(false, true, false));
        assertTrue(CustomModelDataComponentUtil.updateArmorHiddenFlag(meta, true));
        assertEquals(List.of(true, true, false), flags.get());
        assertFalse(CustomModelDataComponentUtil.updateArmorHiddenFlag(meta, true));
        assertTrue(CustomModelDataComponentUtil.updateArmorHiddenFlag(meta, false));
        assertEquals(List.of(false, true, false), flags.get());
        verify(component, never()).setFloats(anyList());
        verify(component, never()).setStrings(anyList());
        verify(component, never()).setColors(anyList());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: 非表示flagの対象は4部位の標準防具素材だけで、馬鎧・頭装飾・武器・紙へ広げない。
     */
    @Test
    void hiddenFlagTargetsOnlyNativeBodyArmorMaterials() {
        ItemStack item = mock(ItemStack.class);
        for (Material type : List.of(Material.LEATHER_HELMET, Material.IRON_CHESTPLATE,
                Material.COPPER_LEGGINGS, Material.NETHERITE_BOOTS, Material.TURTLE_HELMET)) {
            when(item.getType()).thenReturn(type);
            assertTrue(EquipmentAssetSupport.isNativeBodyArmor(item), type.name());
        }
        for (Material type : List.of(Material.IRON_HORSE_ARMOR, Material.PLAYER_HEAD,
                Material.CARVED_PUMPKIN, Material.IRON_SWORD, Material.PAPER)) {
            when(item.getType()).thenReturn(type);
            assertFalse(EquipmentAssetSupport.isNativeBodyArmor(item), type.name());
        }
    }
}
