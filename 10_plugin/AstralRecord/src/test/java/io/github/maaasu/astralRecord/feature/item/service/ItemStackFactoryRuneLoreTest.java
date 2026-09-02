package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactoryRuneLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### マスタItemStack生成
     * 検証契約: ルーンは対象スロット・対象装備タグ・必要強化・equipmentと同じ形式の固定ステータスをプレイヤー向け名称で表示する。
     */
    @Test
    void runeMasterLoreShowsEquipmentStyleConditionsAndStats() throws ReflectiveOperationException {
        ItemRune rune = new ItemRune(
                List.of("WEAPON", "CHEST"),
                3,
                List.of(
                        new ItemEquipmentStat("MELEE_ATTACK", ItemEquipmentStatType.FLAT, 5.0D, 5.0D),
                        new ItemEquipmentStat("CRITICAL_RATE", ItemEquipmentStatType.FLAT, 0.05D, 0.05D)),
                List.of("SWORD"));

        List<String> lore = invokeBuildLore(model(rune));
        List<String> plainLore = lore.stream().map(this::toPlain).toList();

        assertTrue(plainLore.contains("❖ ルーン効果"));
        assertTrue(plainLore.contains(" ▸ 対象スロット: 武器 / 胴"));
        assertTrue(plainLore.contains(" ▸ 対象種別: 剣"));
        assertTrue(plainLore.contains(" ▸ 必要強化: +3"));
        assertTrue(plainLore.contains(" ▸ ステータス補正"));
        assertTrue(plainLore.stream().anyMatch(line -> line.contains("近接攻撃力 : +5")));
        assertTrue(plainLore.stream().anyMatch(line -> line.contains("会心率 : +0.05%")));
        assertFalse(plainLore.stream().anyMatch(line -> line.contains("└")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### マスタItemStack生成
     * 検証契約: `ANY` を含む対象スロットは「全スロット」、単独の未登録対象スロットは「不明な装備枠」へ変換し、内部IDをLoreへ出さない。
     */
    @Test
    void runeTargetSlotLoreUsesSafeDisplayFallbacks() throws ReflectiveOperationException {
        ItemRune rune = new ItemRune(List.of("ANY", "INTERNAL_SLOT"), 0, List.of());

        List<String> plainLore = invokeBuildLore(model(rune)).stream().map(this::toPlain).toList();

        assertTrue(plainLore.contains(" ▸ 対象スロット: 全スロット"));
        assertFalse(plainLore.stream().anyMatch(line -> line.contains("INTERNAL_SLOT")));

        ItemRune unknownRune = new ItemRune(List.of("INTERNAL_SLOT"), 0, List.of());
        List<String> unknownPlainLore = invokeBuildLore(model(unknownRune)).stream()
                .map(this::toPlain)
                .toList();

        assertTrue(unknownPlainLore.contains(" ▸ 対象スロット: 不明な装備枠"));
        assertFalse(unknownPlainLore.stream().anyMatch(line -> line.contains("INTERNAL_SLOT")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### マスタItemStack生成
     * 検証契約: 解決できないルーンのステータスは「未登録のステータス」と表示し、内部IDをLoreへ出さない。
     */
    @Test
    void runeStatusLoreUsesSafeDisplayFallbacks() throws ReflectiveOperationException {
        ItemRune rune = new ItemRune(
                List.of("WEAPON"),
                0,
                List.of(new ItemEquipmentStat(
                        "INTERNAL_STATUS", ItemEquipmentStatType.FLAT, 1.0D, 1.0D)));

        List<String> plainLore = invokeBuildLore(model(rune)).stream().map(this::toPlain).toList();

        assertTrue(plainLore.stream().anyMatch(line -> line.contains("未登録のステータス : +1")));
        assertFalse(plainLore.stream().anyMatch(line -> line.contains("INTERNAL_STATUS")));
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeBuildLore(ItemModel model) throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        Method method = ItemStackFactory.class.getDeclaredMethod("buildLore", ItemModel.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model);
    }

    private ItemModel model(ItemRune rune) {
        return new ItemModel(
                1,
                "rune-lore-test",
                ItemCategory.RUNE.getApiValue(),
                "テストルーン",
                "REDSTONE",
                "COMMON",
                64,
                10,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                null,
                rune,
                null,
                null,
                null
        );
    }

    private String toPlain(String line) {
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(
                        ColorCodeUtil.translateAlternateColorCodes(line)));
    }
}
