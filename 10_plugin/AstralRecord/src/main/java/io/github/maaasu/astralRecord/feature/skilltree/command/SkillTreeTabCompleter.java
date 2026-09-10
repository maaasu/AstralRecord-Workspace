package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** /skilltree の固定タブ補完です。 */
public class SkillTreeTabCompleter extends AstTabCompleter {
    public SkillTreeTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("back", "help", "node");
        }
        if (args.length == 2 && "node".equalsIgnoreCase(args[0])) {
            return List.of("highlight", "filter");
        }
        if (args.length == 3 && "node".equalsIgnoreCase(args[0])) {
            if ("highlight".equalsIgnoreCase(args[1])) {
                return getBooleanCompletions();
            }
            if ("filter".equalsIgnoreCase(args[1])) {
                return statusFilterCompletions(args[2]);
            }
        }
        return List.of();
    }

    private @NotNull List<String> statusFilterCompletions(@NotNull String rawValue) {
        String[] segments = rawValue.split(":", -1);
        String prefix = segments.length == 1
                ? ""
                : String.join(":", java.util.Arrays.copyOf(segments, segments.length - 1)) + ":";
        Set<String> selected = java.util.Arrays.stream(segments)
                .limit(Math.max(0, segments.length - 1))
                .map(this::resolveStatusType)
                .filter(java.util.Objects::nonNull)
                .map(StatusType::getId)
                .collect(Collectors.toSet());
        List<String> completions = new java.util.ArrayList<>();
        if (segments.length == 1) {
            completions.add("off");
            completions.add("clear");
        }
        for (StatusType statusType : StatusType.values()) {
            String id = statusType.getId().toLowerCase(Locale.ROOT);
            if (!selected.contains(statusType.getId())) {
                completions.add(prefix + statusType.getDisplayName());
                completions.add(prefix + id);
            }
        }
        return completions;
    }

    /**
     * 英語IDまたは日本語表示名からステータス種別を解決します。
     *
     * @param rawStatus コマンド引数のステータス表記
     * @return 解決したステータス種別。空文字または未定義ならnull
     */
    private StatusType resolveStatusType(@NotNull String rawStatus) {
        String normalized = rawStatus.trim();
        if (normalized.isBlank()) {
            return null;
        }
        StatusType byId = StatusType.fromId(normalized.replace('-', '_').toUpperCase(Locale.ROOT));
        if (byId != null) {
            return byId;
        }
        return java.util.Arrays.stream(StatusType.values())
                .filter(statusType -> statusType.getDisplayName().equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
