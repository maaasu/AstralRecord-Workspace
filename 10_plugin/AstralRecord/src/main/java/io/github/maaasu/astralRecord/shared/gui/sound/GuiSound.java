package io.github.maaasu.astralRecord.shared.gui.sound;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Shared UI sound definitions used by menu/inventory screens.
 */
public enum GuiSound {
    OPEN(Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f),
    SELECT(Sound.UI_BUTTON_CLICK, 0.6f, 1.4f),
    CLOSE(Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.0f),
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
}