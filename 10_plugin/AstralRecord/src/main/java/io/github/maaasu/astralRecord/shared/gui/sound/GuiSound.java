package io.github.maaasu.astralRecord.shared.gui.sound;

import io.github.maaasu.astralRecord.feature.resourcepack.service.GameplaySound;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * メニューとインベントリGUIで共通利用する操作音です。
 */
public enum GuiSound {
    OPEN("gui.open", Sound.BLOCK_CHEST_OPEN, 0.6f, 1.28f),
    SELECT("gui.select", Sound.UI_BUTTON_CLICK, 0.6f, 1.4f),
    PAGE("gui.page", Sound.ITEM_BOOK_PAGE_TURN, 0.55f, 1.3f),
    TOGGLE("gui.toggle", Sound.BLOCK_LEVER_CLICK, 0.55f, 1.15f),
    CONFIRM("gui.confirm", Sound.BLOCK_NOTE_BLOCK_PLING, 0.65f, 1.35f),
    SUCCESS("gui.success", Sound.ENTITY_PLAYER_LEVELUP, 0.65f, 1.15f),
    PURCHASE("gui.purchase", Sound.ENTITY_VILLAGER_TRADE, 0.75f, 1.1f),
    SELL(Sound.ENTITY_VILLAGER_TRADE, 0.65f, 0.85f),
    REWARD(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f),
    ITEM_RECEIVE(Sound.ENTITY_ITEM_PICKUP, 0.45f, 1.35f),
    UPGRADE(Sound.BLOCK_ANVIL_USE, 0.75f, 1.2f),
    REPAIR(Sound.BLOCK_ANVIL_USE, 0.65f, 0.95f),
    TRADE(Sound.ENTITY_VILLAGER_TRADE, 0.75f, 1.25f),
    TELEPORT(Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.15f),
    SKILL_LEARN(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.25f),
    SKILL_RELOCK(Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.55f, 1.2f),
    SKILL_CONFIRM(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.3f),
    LOGIN_BONUS_REWARD(Sound.ENTITY_PLAYER_LEVELUP, 0.65f, 1.0f),
    RING_OPEN(Sound.BLOCK_BEACON_ACTIVATE, 0.45f, 1.65f),
    RING_SWITCH("gui.ring_switch", Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.7f),
    RING_SELECT(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.25f),
    RING_CAST(Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.55f, 1.35f),
    GUIDE_STEP(Sound.BLOCK_NOTE_BLOCK_CHIME, 0.65f, 1.45f),
    GUIDE_COMPLETE(Sound.ENTITY_PLAYER_LEVELUP, 0.70f, 1.15f),
    TRASH_DISPOSE(Sound.ENTITY_ITEM_BREAK, 0.7f, 0.9f),
    CLOSE("gui.close", Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.16f),
    DUMMY("gui.dummy", Sound.BLOCK_NOTE_BLOCK_HAT, 0.28f, 0.85f),
    DENY("gui.deny", Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f),
    EQUIP(Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.0f),
    UNEQUIP(Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 0.9f);

    private final @Nullable String eventPath;
    private final Sound fallbackSound;
    private final float volume;
    private final float fallbackPitch;

    GuiSound(@NotNull Sound fallbackSound, float volume, float fallbackPitch) {
        this(null, fallbackSound, volume, fallbackPitch);
    }

    GuiSound(@Nullable String eventPath, @NotNull Sound fallbackSound, float volume, float fallbackPitch) {
        this.eventPath = eventPath;
        this.fallbackSound = fallbackSound;
        this.volume = volume;
        this.fallbackPitch = fallbackPitch;
    }

    /**
     * 対応パックの有無に応じた操作音を、操作したプレイヤーに再生します。
     *
     * @param player メインスレッド上の再生対象
     */
    public void play(@NotNull Player player) {
        GuiSound cue = GuiClickSoundFeedback.resolve(player, this);
        if (cue != null) {
            cue.playResolved(player, 1.0F, cue.fallbackPitch);
        }
    }

    /**
     * 指定した音程で操作音を再生します。装飾枠には専用の既定音程を使います。
     *
     * @param player メインスレッド上の再生対象
     * @param pitch 再生音程
     */
    public void play(@NotNull Player player, float pitch) {
        GuiSound cue = GuiClickSoundFeedback.resolve(player, this);
        if (cue != null) {
            cue.playResolved(player, cue == this ? pitch : 1.0F, cue == this ? pitch : cue.fallbackPitch);
        }
    }

    /** 解決済みの操作音を一度送信し、対応パックがなければ標準音を使います。 */
    private void playResolved(@NotNull Player player, float pitch, float vanillaPitch) {
        Location location = player.getLocation();
        if (eventPath != null && GameplaySound.tryPlay(player, location, eventPath, volume, pitch)) {
            return;
        }
        player.playSound(location, fallbackSound, SoundCategory.PLAYERS, volume, vanillaPitch);
        if (this == PURCHASE) {
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.85F, 1.5F);
        }
    }
}
