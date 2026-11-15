package io.github.maaasu.astralRecord.feature.textdisplay.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * /textdisplay コマンドです。
 */
public final class TextDisplayCommand extends AstCommand {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern COMMAND_UNSAFE_CHARACTERS = Pattern.compile("[\\p{javaWhitespace}\\p{Cntrl}\\u00A0§]+");
    private static final String AUTO_ID_PREFIX = "textdisplay-";
    // 256文字のチャット入力上限に、名前空間付きコマンドの入力も収める。
    private static final int MAX_REMOVAL_TARGET_LENGTH = 220;

    private final TextDisplayPlacementService placementService;

    /**
     * TextDisplayCommand を初期化します。
     *
     * @param placementService 固定 TextDisplay 配置サービス
     */
    public TextDisplayCommand(@NotNull TextDisplayPlacementService placementService) {
        super("textdisplay", "固定テキスト表示を管理します。", "/textdisplay <place [id] <text...>|remove <id|補完候補>|list|reload>",
                true, UserPermission.ADMIN.getValue());
        this.placementService = placementService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> handlePlace(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "reload" -> handleReload(player);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handlePlace(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        String id;
        int textStartIndex;
        if (args.length == 2) {
            id = nextAutomaticId();
            textStartIndex = 1;
        } else {
            id = args[1];
            textStartIndex = 2;
            if (!ID_PATTERN.matcher(id).matches()) {
                sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5774.getId()));
                return;
            }
        }

        String text = joinArgs(args, textStartIndex).replace("\\n", "\n");
        TextDisplayPlacement placement = placementService.place(id, text, player.getBukkit().getLocation());
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5770.getId(),
                placement.id(),
                placement.worldName(),
                String.format(Locale.ROOT, "%.2f", placement.x()),
                String.format(Locale.ROOT, "%.2f", placement.y()),
                String.format(Locale.ROOT, "%.2f", placement.z())
        ));
    }

    private @NotNull String nextAutomaticId() {
        Set<String> ids = new HashSet<>();
        for (TextDisplayPlacement placement : placementService.getPlacements()) {
            ids.add(placement.id());
        }

        int sequence = 1;
        String id;
        do {
            id = AUTO_ID_PREFIX + sequence++;
        } while (ids.contains(id));
        return id;
    }

    private void handleRemove(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        String target = joinArgs(args, 1);
        String id = target;
        if (args.length > 2) {
            List<String> matches = placementService.getPlacements().stream()
                    .filter(placement -> removalTarget(placement).equals(target))
                    .map(TextDisplayPlacement::id)
                    .limit(2)
                    .toList();
            id = matches.size() == 1 ? matches.getFirst() : null;
        }
        if (id == null || !placementService.remove(id)) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5775.getId(), target));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5771.getId(), target));
    }

    /**
     * 表示本文と配置 ID から、削除コマンドに挿入できる補完候補を作ります。
     *
     * @param placement 表示の配置情報
     * @return 色コードと改行を除いた本文のプレビューと識別用 ID
     */
    static @NotNull String removalTarget(@NotNull TextDisplayPlacement placement) {
        String plain = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(placement.displayText()));
        String label = normalizeRemovalInput(plain).stripTrailing();
        // 手編集された YAML の ID に空白や角括弧が含まれていても、補完の末尾で一意に表す。
        String id = URLEncoder.encode(placement.id(), StandardCharsets.UTF_8);
        if (id.length() > 64) {
            id = "@" + UUID.nameUUIDFromBytes(placement.id().getBytes(StandardCharsets.UTF_8));
        }
        String suffix = " [" + id + "]";
        int maxLabelLength = Math.max(1, MAX_REMOVAL_TARGET_LENGTH - suffix.length());
        if (label.length() > maxLabelLength) {
            int end = maxLabelLength - 1;
            if (end > 0 && Character.isHighSurrogate(label.charAt(end - 1))) {
                end--;
            }
            label = label.substring(0, end).stripTrailing() + "…";
        }
        return (label.isEmpty() ? "…" : label) + suffix;
    }

    /**
     * チャット入力で一つの空白になる文字を、補完候補と検索入力で揃えます。
     *
     * @param text 本文または入力中の引数
     * @return 先頭の空白を除いた照合用テキスト
     */
    static @NotNull String normalizeRemovalInput(@NotNull String text) {
        return COMMAND_UNSAFE_CHARACTERS.matcher(text).replaceAll(" ").stripLeading();
    }

    private void handleList(@NotNull AstPlayer player) {
        var placements = placementService.getPlacements();
        if (placements.isEmpty()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5776.getId()));
            return;
        }

        String texts = placements.stream()
                .map(placement -> placement.displayText() + ColorCodeUtil.RESET)
                .collect(Collectors.joining("\n"));
        String message = PlayerMsgResource.format(PlayerMsgId.P_5772.getId(), placements.size(), texts);
        PlayerMessageService.getInstance().sendComponent(player.getBukkit(),
                LegacyComponentSerializer.legacySection().deserialize(message));
    }

    private void handleReload(@NotNull AstPlayer player) {
        int count = placementService.loadAll();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5773.getId(), count));
    }
}
