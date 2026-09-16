package io.github.maaasu.astralRecord.feature.item.castdisk;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * ホットバー上のスキルキャストディスクへ、現在の発動対象スキルのアイコンを反映します。
 */
public final class CastDiskHotbarIconService {
    private final SkillBindPresetService presetService;
    private final LearnedSkillService learnedSkillService;
    private final SkillService skillService;

    /**
     * スキルキャストディスク用のホットバー表示サービスを構築します。
     *
     * @param presetService 選択中プリセットとアクションスロットの解決元
     * @param learnedSkillService 所有スキル個体の解決元
     * @param skillService スキル定義の解決元
     */
    public CastDiskHotbarIconService(
        @NotNull SkillBindPresetService presetService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull SkillService skillService
    ) {
        this.presetService = presetService;
        this.learnedSkillService = learnedSkillService;
        this.skillService = skillService;
    }

    /**
     * 対象がスキルキャストディスクであれば、選択中プリセットの発動対象アイコンを反映します。
     * 解決不能な場合はマスタ由来の既存アイコンを維持します。
     *
     * @param itemStack ホットバー表示用 ItemStack
     * @param itemModel ItemStack のマスタ定義
     * @param metadataJson ディスク設定を含む inventory entry metadata
     * @param accountId 表示対象プレイヤーの account ID
     */
    public void apply(
        @NotNull ItemStack itemStack,
        @NotNull ItemModel itemModel,
        @Nullable String metadataJson,
        @NotNull UUID accountId
    ) {
        if (!isSkillCastDisk(itemModel)) {
            return;
        }
        CastDiskSettings settings = CastDiskSettings.read(metadataJson);
        String bindingId = activeBindingId(accountId, settings.actionSlotIndex());
        if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(bindingId)) {
            ItemStackFactory.overrideDisplayIcon(itemStack, Material.IRON_SWORD, null);
            return;
        }
        LearnedSkillInstance learned = learnedSkillService.findInstance(accountId, bindingId);
        SkillDefinition definition = learned == null
            ? null
            : skillService.registry().getDefinition(learned.getSkillId());
        if (definition == null) {
            return;
        }
        Material icon = MaterialNameResolver.match(definition.getIcon());
        if (icon == null) {
            return;
        }
        ItemStackFactory.overrideDisplayIcon(itemStack, icon, definition.getIconTexture());
    }

    private @Nullable String activeBindingId(@NotNull UUID accountId, int actionSlotIndex) {
        if (actionSlotIndex < 0 || actionSlotIndex >= CastDiskSettings.ACTION_SLOT_COUNT) {
            return null;
        }
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        List<String> activeSlots = presetService.getPresets(accountId).stream()
            .filter(SkillBindPreset::isUnlocked)
            .filter(preset -> preset.getPresetIndex() == selectedPresetIndex)
            .findFirst()
            .map(SkillBindPreset::getActiveSkillSlots)
            .orElse(List.of());
        if (actionSlotIndex >= activeSlots.size()) {
            return null;
        }
        String bindingId = activeSlots.get(actionSlotIndex);
        return bindingId == null || bindingId.isBlank() ? null : bindingId;
    }

    private boolean isSkillCastDisk(@NotNull ItemModel itemModel) {
        ItemEquipment equipment = itemModel.getEquipment();
        return equipment != null
            && equipment.getSlot() == ItemEquipmentSlot.TOOL
            && MasterTagIds.Equipment.SKILL_CAST_DISK.equalsIgnoreCase(equipment.getTag());
    }
}
