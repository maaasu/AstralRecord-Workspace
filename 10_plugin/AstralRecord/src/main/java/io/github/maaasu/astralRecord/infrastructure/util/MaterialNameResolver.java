package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Minecraft バージョン更新で改名された Material を含め、マスタ上の Material 名を解決します。
 */
public final class MaterialNameResolver {
    private static final Map<String, Material> LEGACY_ALIASES = Map.of(
            "CHAIN", Material.IRON_CHAIN
    );

    private MaterialNameResolver() {
    }

    /**
     * Material 名を現在の Paper API の Material へ解決します。
     *
     * @param rawName マスタまたは設定上の Material 名
     * @return 解決した Material。空値または不明な名前の場合は {@code null}
     */
    @Nullable
    public static Material match(@Nullable String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String normalized = rawName.trim().toUpperCase(Locale.ROOT);
        Material alias = LEGACY_ALIASES.get(normalized);
        return alias == null ? Material.matchMaterial(normalized) : alias;
    }
}
