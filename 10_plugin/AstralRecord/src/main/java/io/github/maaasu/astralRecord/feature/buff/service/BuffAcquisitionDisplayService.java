package io.github.maaasu.astralRecord.feature.buff.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayAnimationFrame;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * バフ獲得時にプレイヤー視界右下へ短時間の TextDisplay 通知を表示します。
 */
public final class BuffAcquisitionDisplayService {
    private static final long HOLD_TICKS = 54L;
    private static final long FADE_TICKS = 10L;
    private static final double DISTANCE = 1.85D;
    private static final Vector LOWER_RIGHT_OFFSET = new Vector(1.48D, -1.02D, 0.0D);

    private final DisplayTextService displayTextService;
    private final Map<UUID, DisplayTextService.ManagedTextDisplay> activeDisplays = new ConcurrentHashMap<>();

    /**
     * サービスを生成します。
     *
     * @param displayTextService TextDisplay の生成・更新サービス
     */
    public BuffAcquisitionDisplayService(@NotNull DisplayTextService displayTextService) {
        this.displayTextService = displayTextService;
    }

    /**
     * 指定プレイヤーへバフ獲得通知を表示します。
     *
     * @param player 表示対象プレイヤー
     * @param buff   獲得したバフ
     */
    public void show(@NotNull Player player, @NotNull ActiveBuff buff) {
        DisplayTextService.ManagedTextDisplay previous = activeDisplays.remove(player.getUniqueId());
        if (previous != null) {
            previous.destroy();
        }

        String text = buildText(buff);
        DisplayTextOptions options = DisplayTextOptions.defaults(text)
            .withBillboard(Display.Billboard.CENTER)
            .withLineWidth(220)
            .withViewRange(24.0F)
            .withSeeThrough(true)
            .withShadowed(true)
            .withInterpolationDuration(1)
            .withTeleportDuration(1);
        options = new DisplayTextOptions(
            options.text(),
            options.offset(),
            options.billboard(),
            options.lineWidth(),
            options.viewRange(),
            options.textOpacity(),
            options.seeThrough(),
            options.shadowed(),
            false,
            Color.fromARGB(136, 8, 4, 18),
            options.interpolationDuration(),
            options.teleportDuration(),
            options.persistent(),
            15,
            15
        );

        DisplayTextService.ManagedTextDisplay display = displayTextService.create(
            DisplayAnchor.view(player, DISTANCE, LOWER_RIGHT_OFFSET),
            options
        );
        display.playAnimation(frames(text), false, true);
        display.setDynamicText(() -> buildText(buff));
        activeDisplays.put(player.getUniqueId(), display);
    }

    private @NotNull List<DisplayAnimationFrame> frames(@NotNull String text) {
        List<DisplayAnimationFrame> frames = new ArrayList<>();
        frames.add(new DisplayAnimationFrame(text, new Vector(0.08D, 0.0D, 0.0D), 4L));
        frames.add(new DisplayAnimationFrame(text, new Vector(0.0D, 0.0D, 0.0D), HOLD_TICKS));
        frames.add(new DisplayAnimationFrame(text, new Vector(0.0D, -0.05D, 0.0D), FADE_TICKS));
        return frames;
    }

    private @NotNull String buildText(@NotNull ActiveBuff buff) {
        String displayName = buff.getType().getDisplayName();
        if (displayName.isBlank()) {
            displayName = buff.getType().getId();
        }

        StringBuilder builder = new StringBuilder();
        builder.append(ColorCodeUtil.DARK_GRAY)
            .append("◇════ ")
            .append(ColorCodeUtil.AQUA)
            .append("BUFF")
            .append(ColorCodeUtil.DARK_GRAY)
            .append(" ════◇\n")
            .append(ColorCodeUtil.GOLD)
            .append("✦ ")
            .append(ColorCodeUtil.WHITE)
            .append(displayName)
            .append(ColorCodeUtil.GOLD)
            .append(" ✦\n")
            .append(ColorCodeUtil.GRAY)
            .append("⏳ 残り ")
            .append(formatRemaining(buff));

        String modifierSummary = modifierSummary(buff);
        if (!modifierSummary.isBlank()) {
            builder.append('\n').append(ColorCodeUtil.GREEN).append("⚔ ").append(modifierSummary);
        }
        return builder.toString();
    }

    private @NotNull String formatRemaining(@NotNull ActiveBuff buff) {
        long seconds = Math.max(0L, Duration.between(LocalDateTime.now(), buff.getExpiresAt()).toSeconds());
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private @NotNull String modifierSummary(@NotNull ActiveBuff buff) {
        List<BuffModifier> modifiers = buff.getType().getModifiers();
        if (modifiers.isEmpty()) {
            return "";
        }
        BuffModifier modifier = modifiers.get(0);
        StatusType type = modifier.getStatus();
        return type.legacyColor() + type.getDisplayName() + ColorCodeUtil.WHITE + " " + type.formatSignedValue(modifier.getValue());
    }
}
