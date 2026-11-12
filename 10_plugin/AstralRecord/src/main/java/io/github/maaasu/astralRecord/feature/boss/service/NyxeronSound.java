package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * ニクセロンの効果音。対応パックの接続には {@code astralrecord:boss.nyxeron.*} を、それ以外には近い標準音を送ります。
 * 遠くの心臓からの音も聞き取れるよう、聞き手の位置で再生します。
 */
enum NyxeronSound {
    INTRO("intro", Sound.BLOCK_BELL_RESONATE, 0.6F),
    PHASE("phase", Sound.BLOCK_BELL_USE, 0.5F),
    CUE_BAND("cue_band", Sound.BLOCK_BEACON_ACTIVATE, 0.6F),
    CUE_SPOKE("cue_spoke", Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2F),
    CUE_HEART("cue_heart", Sound.ENTITY_WARDEN_HEARTBEAT, 0.8F),
    CUE_BRAND("cue_brand", Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7F),
    CUE_GAZE("cue_gaze", Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7F),
    CUE_METEOR("cue_meteor", Sound.ENTITY_BLAZE_SHOOT, 0.6F),
    CUE_COMBO("cue_combo", Sound.BLOCK_END_PORTAL_SPAWN, 1.4F),
    CUE_NOVA("cue_nova", Sound.BLOCK_BEACON_POWER_SELECT, 1.6F),
    CUE_SPIRAL("cue_spiral", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6F),
    CUE_SHOCK("cue_shock", Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0F),
    CUE_SWEEP("cue_sweep", Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8F),
    CUE_STARFALL("cue_starfall", Sound.BLOCK_BEACON_ACTIVATE, 1.5F),
    COUNT("count", Sound.BLOCK_NOTE_BLOCK_HAT, 1.6F),
    COUNT_FINAL("count_final", Sound.BLOCK_NOTE_BLOCK_BELL, 1.8F),
    IMPACT_BAND("impact_band", Sound.ENTITY_GENERIC_EXPLODE, 0.7F),
    IMPACT_SPOKE("impact_spoke", Sound.ENTITY_GUARDIAN_ATTACK, 1.4F),
    IMPACT_HEART("impact_heart", Sound.ENTITY_WARDEN_HEARTBEAT, 0.6F),
    IMPACT_BRAND("impact_brand", Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6F),
    IMPACT_GAZE("impact_gaze", Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2F),
    IMPACT_METEOR("impact_meteor", Sound.ENTITY_GENERIC_EXPLODE, 0.9F),
    BULLET("bullet", Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.8F),
    NOVA_BURST("nova_burst", Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.4F),
    SHOCK_LOW("shock_low", Sound.ENTITY_BREEZE_WIND_BURST, 0.6F),
    SHOCK_HIGH("shock_high", Sound.ENTITY_BREEZE_WIND_BURST, 1.6F),
    SWEEP_REVERSE("sweep_reverse", Sound.BLOCK_NOTE_BLOCK_PLING, 0.5F),
    FRAGMENT_DROP("fragment_drop", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4F),
    FRAGMENT_PICKUP("fragment_pickup", Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2F),
    SUPERNOVA_CHARGE("supernova_charge", Sound.BLOCK_BEACON_AMBIENT, 0.5F),
    SUPERNOVA_BLAST("supernova_blast", Sound.ENTITY_GENERIC_EXPLODE, 0.5F),
    STAGGER("stagger", Sound.BLOCK_GLASS_BREAK, 0.7F),
    RITUAL_START("ritual_start", Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.5F),
    SEAL_DONE("seal_done", Sound.BLOCK_BEACON_POWER_SELECT, 1.4F),
    RITUAL_SUCCESS("ritual_success", Sound.BLOCK_BEACON_DEACTIVATE, 0.8F),
    RITUAL_FAIL("ritual_fail", Sound.ENTITY_GENERIC_EXPLODE, 0.5F);

    private static final String NAMESPACE = "astralrecord:boss.nyxeron.";

    private final String event;
    private final Sound fallback;
    private final float fallbackPitch;

    NyxeronSound(String event, Sound fallback, float fallbackPitch) {
        this.event = NAMESPACE + event;
        this.fallback = fallback;
        this.fallbackPitch = fallbackPitch;
    }

    /**
     * @param players 聞き手 @param pack 専用音を再生できる接続か @param volume 音量 @param pitch 専用音の音程
     */
    void play(Collection<Player> players, Predicate<UUID> pack, float volume, float pitch) {
        for (Player player : players) {
            if (pack.test(player.getUniqueId())) {
                player.playSound(player.getLocation(), event, SoundCategory.HOSTILE, volume, pitch);
            } else {
                player.playSound(player.getLocation(), fallback, SoundCategory.HOSTILE, volume, fallbackPitch * pitch);
            }
        }
    }
}
