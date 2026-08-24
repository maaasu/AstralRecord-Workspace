package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentClassRequirement;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceStatIncrease;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemStackFactoryEquipmentStatLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 強化値は主値の右側、乱数幅は次行の `└` 表記で表示する。
     */
    @Test
    void randomRangeIsShownBeforeEnhancementNoteWithoutEnhancementAddition() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 2.0D, 4.0D, "2~3", "4~5");
        ItemEquipmentEnhance enhance = new ItemEquipmentEnhance(
                1,
                List.of(new ItemEquipmentEnhanceLevel(
                        1,
                        List.of(new ItemEquipmentEnhanceStatIncrease(
                                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 2.0D, 2.0D)),
                        null,
                        1.0D,
                        ItemEquipmentEnhanceFailAction.NONE,
                        null)));

        List<String> lore = buildLore(stat, enhance, 1, "2", "4");
        String line = findStatLine(lore);

        assertTrue(line.contains("+4～+6 [+2]"));
        assertTrue(findLine(lore, "└").contains("2-3～4-5"));
        assertTrue(findRawStatLine(lore).contains(StatusType.MELEE_ATTACK.legacyColor()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: min/max の片側だけが乱数範囲でも、固定値側を維持した括弧注釈を表示する。
     */
    @Test
    void randomRangeAnnotationSupportsSingleRandomEndpoint() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 3.0D, 4.0D, "3", "4~5");

        String line = findStatLine(buildLore(stat, null, 0, "3", "4"));

        assertTrue(line.contains("+3～+4"));
        assertTrue(findLine(buildLore(stat, null, 0, "3", "4"), "└").contains("3～4-5"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 単一の固定範囲は既存の主値表示だけを行い、個別乱数範囲の注釈を追加しない。
     */
    @Test
    void scalarRangeDoesNotShowIndividualRandomRangeAnnotation() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 8.0D, 9.0D, "8", "9");

        String line = findStatLine(buildLore(stat, null, 0, "8", "9"));

        assertTrue(line.contains("+8～+9"));
        assertFalse(line.contains("(8"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### マスタItemStack生成
     * 検証契約: 強化値を持たないマスタ表示でも、個別乱数範囲を灰色注釈として表示する。
     */
    @Test
    void masterLoreShowsRandomRangeWithoutEnhancement() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 2.0D, 4.0D, "2~3", "4~5");

        List<String> lore = buildMasterLore(stat);
        String line = findStatLine(lore);

        assertTrue(line.contains("+2～+4"));
        assertTrue(findLine(lore, "└").contains("2-3～4-5"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 状態変化名とともに、ランク1以上の数値ランクを灰色で表示する。
     */
    @Test
    void transcendenceLoreShowsNumericRankInGray() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 4.0D, 4.0D);
        ItemEquipmentTranscendence transcendence = new ItemEquipmentTranscendence(
                "進化", 1, 0, List.of(), 0, null, null, null);
        ItemModel model = model(stat, null, List.of(transcendence));
        EquipmentInstance instance = new EquipmentInstance(
                "instance-id",
                "account-id",
                model.getId(),
                0,
                0,
                1,
                0,
                0,
                "",
                "",
                List.of(new EquipmentStatRoll("roll-id", stat.getStatus(), "4", "4", 0)),
                List.of(),
                List.of()
        );

        List<String> lore = buildInstanceLore(model, instance);
        String rawLine = lore.stream()
                .filter(line -> line.contains("状態変化"))
                .findFirst()
                .orElseThrow();
        String plainLine = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(rawLine));

        assertTrue(plainLine.contains("状態変化: 【進化】 (ランク1)"));
        assertTrue(rawLine.contains(ColorCodeUtil.GRAY + " (ランク1)"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 乱数指定がないステータスには元範囲注釈を追加しない。
     */
    @Test
    void fixedStatDoesNotShowRandomRangeAnnotation() throws ReflectiveOperationException {
        ItemEquipmentStat stat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 4.0D, 4.0D);

        String line = findStatLine(buildLore(stat, null, 0, "4", "4"));

        assertTrue(line.contains("+4"));
        assertFalse(line.contains("(4"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 同じステータスに FLAT と SCALAR が混在しても、statRoll の sortOrder
     * に対応する補正方式で表示し、数値は小数点以下2桁で切り捨てる。
     */
    @Test
    void duplicateStatusUsesDefinitionOrderAndTruncatesFloatingPointNoise()
            throws ReflectiveOperationException {
        ItemEquipmentStat flat = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.FLAT, 30.0D, 40.0D);
        ItemEquipmentStat scalar = new ItemEquipmentStat(
                "MELEE_ATTACK", ItemEquipmentStatType.SCALAR, 1.10D, 1.10D);
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                null,
                List.of(flat, scalar),
                null,
                null,
                null,
                null,
                List.of());
        ItemModel model = model(equipment);
        EquipmentInstance instance = new EquipmentInstance(
                "instance-id",
                "account-id",
                model.getId(),
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                List.of(
                        new EquipmentStatRoll("flat-roll", "MELEE_ATTACK", "30", "40", 0),
                        new EquipmentStatRoll("scalar-roll", "MELEE_ATTACK", "1.1", "1.1", 1)),
                List.of(),
                List.of());

        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        List<String> lore = invokeBuildLore(factory, model, instance);
        List<String> statLines = findPlainStatLines(lore);

        assertTrue(statLines.get(0).contains("近接攻撃力 : +30～+40"));
        assertFalse(statLines.get(0).contains("×"));
        assertTrue(statLines.get(1).contains("最終近接攻撃力乗数 : ×110%"));
        assertFalse(statLines.get(1).contains("110.00000000000001"));

        Method formatMethod = ItemStackFactory.class.getDeclaredMethod("formatStatValue", double.class);
        formatMethod.setAccessible(true);
        assertEquals("1.23", formatMethod.invoke(factory, 1.239D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 必要クラスはマスタ表示名を使い、クラス表示と同じ `Lv.` 形式で表示する。
     */
    @Test
    void requiredClassesUseDisplayNamesAndExistingLevelStyle() throws ReflectiveOperationException {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                null,
                5,
                List.of(
                        new ItemEquipmentClassRequirement("swordsman", 3),
                        new ItemEquipmentClassRequirement("adventurer", 1)),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                List.of());
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getDisplayName("swordsman")).thenReturn(ColorCodeUtil.WHITE + "ソードマン");
        when(playerClassService.getDisplayName("adventurer")).thenReturn(ColorCodeUtil.WHITE + "冒険者");

        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        factory.setPlayerClassService(playerClassService);
        Method method = ItemStackFactory.class.getDeclaredMethod("formatRequiredClasses", ItemEquipment.class);
        method.setAccessible(true);
        String display = toPlain((String) method.invoke(factory, equipment));

        assertTrue(display.contains("ソードマン Lv.3, 冒険者 Lv.1"));
        assertFalse(display.contains("swordsman"));
        assertFalse(display.contains("adventurer"));
    }

    @SuppressWarnings("unchecked")
    private List<String> buildLore(
            ItemEquipmentStat stat,
            ItemEquipmentEnhance enhance,
            int enhanceLevel,
            String min,
            String max
    ) throws ReflectiveOperationException {
        ItemModel model = model(stat, enhance);
        EquipmentInstance instance = new EquipmentInstance(
                "instance-id",
                "account-id",
                model.getId(),
                enhanceLevel,
                0,
                0,
                0,
                0,
                "",
                "",
                List.of(new EquipmentStatRoll("roll-id", stat.getStatus(), min, max, 0)),
                List.of(),
                List.of()
        );
        return buildInstanceLore(model, instance);
    }

    @SuppressWarnings("unchecked")
    private List<String> buildMasterLore(ItemEquipmentStat stat) throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        Method method = ItemStackFactory.class.getDeclaredMethod("buildLore", ItemModel.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model(stat, null));
    }

    @SuppressWarnings("unchecked")
    private List<String> buildInstanceLore(ItemModel model, EquipmentInstance instance)
            throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        Method method = ItemStackFactory.class.getDeclaredMethod(
                "buildLoreForEquipmentInstance", ItemModel.class, EquipmentInstance.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model, instance);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeBuildLore(
            ItemStackFactory factory, ItemModel model, EquipmentInstance instance)
            throws ReflectiveOperationException {
        Method method = ItemStackFactory.class.getDeclaredMethod(
                "buildLoreForEquipmentInstance", ItemModel.class, EquipmentInstance.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model, instance);
    }

    private ItemModel model(ItemEquipmentStat stat, ItemEquipmentEnhance enhance) {
        return model(stat, enhance, List.of());
    }

    private ItemModel model(
            ItemEquipmentStat stat,
            ItemEquipmentEnhance enhance,
            List<ItemEquipmentTranscendence> transcendence
    ) {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                null,
                List.of(stat),
                null,
                enhance,
                null,
                null,
                transcendence
        );
        return model(equipment);
    }

    private ItemModel model(ItemEquipment equipment) {
        return new ItemModel(
                1,
                "equipment-stat-lore-test",
                ItemCategory.EQUIPMENT.getApiValue(),
                "テスト武器",
                "IRON_SWORD",
                "COMMON",
                1,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                equipment,
                null,
                null,
                null,
                null,
                null
        );
    }

    private String findStatLine(List<String> lore) {
        return lore.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(
                        LegacyComponentSerializer.legacySection().deserialize(line)))
                .filter(line -> line.contains("▹") && line.contains(" : "))
                .findFirst()
                .orElseThrow();
    }

    private String findRawStatLine(List<String> lore) {
        return lore.stream()
                .filter(line -> line.contains("▹") && line.contains(" : "))
                .findFirst()
                .orElseThrow();
    }

    private String findLine(List<String> lore, String label) {
        return lore.stream()
                .map(this::toPlain)
                .filter(line -> line.contains(label))
                .findFirst()
                .orElseThrow();
    }

    private List<String> findPlainStatLines(List<String> lore) {
        return lore.stream()
                .map(this::toPlain)
                .filter(line -> line.contains("▹") && line.contains(" : "))
                .toList();
    }

    private String toPlain(String line) {
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(line));
    }
}
