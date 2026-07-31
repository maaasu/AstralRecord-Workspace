package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * プレイヤーの詠唱中に表示する ActionBar と進捗サウンドを構築します。
 */
final class SkillCastFeedback {
    static final long SOUND_INTERVAL_TICKS = 10L;

    private static final int BAR_LENGTH = 24;
    private static final String BAR_SEGMENT = "█";
    private static final float SOUND_VOLUME = 0.35F;
    private static final float SOUND_MIN_PITCH = 0.8F;
    private static final float SOUND_PITCH_RANGE = 0.8F;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    /**
     * 詠唱名、残り秒数、進捗バーを含む ActionBar を構築します。
     * 完了部分には legacy color code の難読化装飾を適用し、文字が周期的に揺らぐ表示にします。
     *
     * @param definition 対象スキル定義
     * @param totalTicks 詠唱の総 tick 数
     * @param remainingTicks 残り tick 数
     * @return 詠唱中 ActionBar
     */
    @NotNull Component createActionBar(
            @NotNull SkillDefinition definition,
            long totalTicks,
            long remainingTicks
    ) {
        long safeTotalTicks = Math.max(1L, totalTicks);
        long safeRemainingTicks = Math.clamp(remainingTicks, 0L, safeTotalTicks);
        double elapsedRatio = 1.0D - (double) safeRemainingTicks / (double) safeTotalTicks;
        int filledLength = (int) Math.round(elapsedRatio * BAR_LENGTH);
        double seconds = safeRemainingTicks / 20.0D;

        String label = PlayerMsgResource.format(
                PlayerMsgId.P_5811.getId(),
                SkillPresentationUtil.legacyName(definition, definition.getId()),
                String.format(Locale.ROOT, "%.1f", seconds)
        );
        String bar = " "
                + ColorCodeUtil.DARK_GRAY + "["
                + ColorCodeUtil.GREEN + ColorCodeUtil.BOLD + ColorCodeUtil.OBFUSCATED
                + BAR_SEGMENT.repeat(filledLength)
                + ColorCodeUtil.RESET + ColorCodeUtil.GRAY + ColorCodeUtil.BOLD
                + BAR_SEGMENT.repeat(BAR_LENGTH - filledLength)
                + ColorCodeUtil.RESET + ColorCodeUtil.DARK_GRAY + "]";
        return LEGACY_SERIALIZER.deserialize(label + bar);
    }

    /**
     * 指定 tick が詠唱中サウンドの再生タイミングか判定します。
     *
     * @param elapsedTicks 詠唱開始からの経過 tick 数
     * @return 再生タイミングなら {@code true}
     */
    boolean shouldPlaySound(long elapsedTicks) {
        return elapsedTicks >= 0L && elapsedTicks % SOUND_INTERVAL_TICKS == 0L;
    }

    /**
     * 詠唱進捗に応じて音程が上がる短いサウンドを対象プレイヤーだけへ再生します。
     *
     * @param player 対象プレイヤー
     * @param elapsedTicks 詠唱開始からの経過 tick 数
     * @param totalTicks 詠唱の総 tick 数
     */
    void playSound(@NotNull Player player, long elapsedTicks, long totalTicks) {
        double progress = Math.clamp((double) elapsedTicks / (double) Math.max(1L, totalTicks), 0.0D, 1.0D);
        float pitch = SOUND_MIN_PITCH + (float) progress * SOUND_PITCH_RANGE;
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS,
                SOUND_VOLUME,
                pitch
        );
    }
}
