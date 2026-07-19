package io.github.maaasu.astralRecord.feature.resourcepack.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * プレイヤー名 prefix による Bedrock Edition 接続判定を共通化します。
 */
public final class BedrockPlayerDetector {
    private BedrockPlayerDetector() {
    }

    /**
     * 指定名が設定済み Bedrock prefix のいずれかに一致するか判定します。
     *
     * @param playerName プレイヤー名
     * @param prefixes Bedrock 判定用 prefix
     * @return Bedrock Edition 接続として扱う場合は {@code true}
     */
    public static boolean isBedrock(@NotNull String playerName, @Nullable List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        return prefixes.stream()
            .filter(prefix -> prefix != null && !prefix.isBlank())
            .anyMatch(playerName::startsWith);
    }
}
