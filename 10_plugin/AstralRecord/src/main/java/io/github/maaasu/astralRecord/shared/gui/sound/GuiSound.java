package io.github.maaasu.astralRecord.shared.gui.sound;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Shared UI sound definitions used by menu/inventory screens.
 */
public enum GuiSound {
    OPEN(Sound.BLOCK_CHEST_OPEN, 0.6f, 1.28f),
    SELECT(Sound.UI_BUTTON_CLICK, 0.6f, 1.4f),
    PAGE(Sound.ITEM_BOOK_PAGE_TURN, 0.55f, 1.3f),
    TOGGLE(Sound.BLOCK_LEVER_CLICK, 0.55f, 1.15f),
    CONFIRM(Sound.BLOCK_NOTE_BLOCK_PLING, 0.65f, 1.35f),
    SUCCESS(Sound.ENTITY_PLAYER_LEVELUP, 0.65f, 1.15f),
    PURCHASE(Sound.ENTITY_VILLAGER_TRADE, 0.75f, 1.1f),
    SELL(Sound.ENTITY_VILLAGER_TRADE, 0.65f, 0.85f),
    REWARD(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f),
    UPGRADE(Sound.BLOCK_ANVIL_USE, 0.75f, 1.2f),
    REPAIR(Sound.BLOCK_ANVIL_USE, 0.65f, 0.95f),
    TRADE(Sound.ENTITY_VILLAGER_TRADE, 0.75f, 1.25f),
    TELEPORT(Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.15f),
    SKILL_LEARN(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.25f),
    SKILL_RELOCK(Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.55f, 1.2f),
    SKILL_CONFIRM(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.3f),
    LOGIN_BONUS_REWARD(Sound.ENTITY_PLAYER_LEVELUP, 0.65f, 1.0f),
    RING_OPEN(Sound.BLOCK_BEACON_ACTIVATE, 0.45f, 1.65f),
    RING_SWITCH(Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.7f),
    RING_SELECT(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.25f),
    RING_CAST(Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.55f, 1.35f),
    GUIDE_STEP(Sound.BLOCK_NOTE_BLOCK_CHIME, 0.65f, 1.45f),
    GUIDE_COMPLETE(Sound.ENTITY_PLAYER_LEVELUP, 0.70f, 1.15f),
    TRASH_DISPOSE(Sound.ENTITY_ITEM_BREAK, 0.7f, 0.9f),
    CLOSE(Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.16f),
    DENY(Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f),
    EQUIP(Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.0f),
    UNEQUIP(Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 0.9f);

    private final Sound sound;
    private final float volume;
    private final float pitch;

    GuiSound(@NotNull Sound sound, float volume, float pitch) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public void play(@NotNull Player player) {
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    /**
     * 定義済み音量のまま音程だけを変えて再生します。
     *
     * @param player 対象プレイヤー
     * @param pitch 再生音程
     */
    public void play(@NotNull Player player, float pitch) {
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
