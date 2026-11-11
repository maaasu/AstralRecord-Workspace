package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.hud.HudMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCooldownHudEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCooldownHudEntry.State;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 読み込み済みの装備・習得・使用許可とCTから、Java HUDの一覧を作ります。 */
public final class SkillCooldownHudService {
    private final SkillBindPresetService presets;
    private final SkillService skills;
    private final SkillOwnershipService ownership;
    private final SkillPermissionService permissions;
    private final ItemWeaponAttackService weapons;

    /**
     * @param presets 選択済みプリセットと解放枠の参照先
     * @param skills スキル解決・共有CT・一般的な発動条件の参照先
     * @param ownership 読み込み済み習得個体の参照先
     * @param permissions 現在の使用許可の参照先
     * @param weapons 主手武器の使用条件の参照先
     */
    public SkillCooldownHudService(SkillBindPresetService presets, SkillService skills,
            SkillOwnershipService ownership, SkillPermissionService permissions, ItemWeaponAttackService weapons) {
        this.presets = presets;
        this.skills = skills;
        this.ownership = ownership;
        this.permissions = permissions;
        this.weapons = weapons;
    }

    /**
     * メインスレッドで現在装備を読みます。通信・DB読込・定期タスク・独自キャッシュは追加しません。
     * @param player 表示対象。非プレイ状態と未読込プリセットは空一覧になります
     * @return 最大13件。空枠・通常攻撃・パッシブは省略し、左クリックの重複は1件へまとめます
     */
    public @NotNull List<SkillCooldownHudEntry> snapshot(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        if (!player.getAccount().getMode().shouldProcessGameplay() || !presets.hasLoadedPresets(accountId)) {
            return List.of();
        }
        int selected = presets.selectedPresetIndex(accountId);
        SkillBindPreset preset = presets.getPresets(accountId).stream()
                .filter(candidate -> candidate.isUnlocked() && candidate.getPresetIndex() == selected)
                .findFirst().orElse(null);
        if (preset == null) return List.of();

        int count = Math.min(SkillBindPreset.ACTIVE_SLOT_COUNT,
                Math.min(presets.activeSkillSlotCount(accountId), preset.getActiveSkillSlots().size()));
        Map<String, LearnedSkillInstance> learned = new HashMap<>();
        for (LearnedSkillInstance instance : ownership.learnedSkills(player)) {
            learned.put(instance.getLearnedSkillId().toString(), instance);
        }
        Set<String> permitted = permissions.permittedSkillIds(player);
        boolean weaponReady = weapons.hasUsableMainHandWeapon(player) && !player.getBukkit().isDead();
        PlayerSkillCaster caster = new PlayerSkillCaster(player);
        List<SkillCooldownHudEntry> entries = new ArrayList<>(SkillCooldownHudEntry.MAX_ENTRIES);
        Set<String> displayed = new HashSet<>();
        for (int slot = 0; slot < count; slot++) {
            String binding = preset.getActiveSkillSlots().get(slot);
            add(entries, slot + 1, binding, caster, learned, permitted, weaponReady);
            if (binding != null) displayed.add(binding.toLowerCase(Locale.ROOT));
        }
        String leftClick = preset.getLeftClickSkillId();
        if (leftClick != null && !displayed.contains(leftClick.toLowerCase(Locale.ROOT))) {
            add(entries, 0, leftClick, caster, learned, permitted, weaponReady);
        }
        return List.copyOf(entries);
    }

    private void add(List<SkillCooldownHudEntry> entries, int slot, String binding, PlayerSkillCaster caster,
            Map<String, LearnedSkillInstance> learned, Set<String> permitted, boolean weaponReady) {
        if (binding == null || binding.isBlank() || SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(binding)) return;
        LearnedSkillInstance instance = learned.get(binding.toLowerCase(Locale.ROOT));
        var resolved = instance == null ? null : skills.resolveLearnedSkill(instance);
        var definition = resolved == null ? null : resolved.definition();
        if (definition != null && definition.getKind() != SkillKind.ACTIVE) return;
        String name = SkillPresentationUtil.plainName(definition, PlayerMsgResource.getMessage(HudMsgId.P_5673.getId()));
        if (definition == null) {
            entries.add(new SkillCooldownHudEntry(slot, name, 0, State.BLOCKED));
            return;
        }
        long remaining = Math.max(0L, skills.getRemainingCooldownTicks(caster, skills.resolveCooldownId(definition.getId())));
        State state;
        if (!weaponReady || !permitted.contains(instance.getSkillId().toLowerCase(Locale.ROOT))) {
            state = State.BLOCKED;
        } else if (remaining > 0) {
            state = State.COOLDOWN;
        } else {
            var result = skills.canCast(caster, resolved);
            state = result.success() ? State.READY : result.messageId() == PlayerMsgId.P_5801 ? State.MANA
                    : result.messageId() == PlayerMsgId.P_5806 ? State.ENERGY : State.BLOCKED;
        }
        entries.add(new SkillCooldownHudEntry(slot, name, remaining, state));
    }
}
