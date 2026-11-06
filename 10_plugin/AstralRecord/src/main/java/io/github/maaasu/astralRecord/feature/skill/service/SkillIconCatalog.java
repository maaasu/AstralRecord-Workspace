package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** アクションリングとアイテム表示で、パックと同じスキルアイコンを使います。 */
public final class SkillIconCatalog {
    public static final Material ITEM_MATERIAL = Material.PAPER;
    private static final int MODEL_DATA_BASE = 920000;
    private static final String RESOURCE = "/action-ring-icons.json";
    private static final SkillIconCatalog INSTANCE = load();

    private final Map<String, Integer> icons;
    private final String revision;
    private final String hudRevision;
    private final String bedrockHudRevision;

    private SkillIconCatalog(Map<String, Integer> icons, String revision, String hudRevision, String bedrockHudRevision) {
        this.icons = icons;
        this.revision = revision;
        this.hudRevision = hudRevision;
        this.bedrockHudRevision = bedrockHudRevision;
    }

    static int sprite(@Nullable String skillId) {
        return skillId == null ? 0 : INSTANCE.icons.getOrDefault(skillId, 0);
    }

    /**
     * スキルのアイテム表示に使うリソースパックのモデル番号を取得します。
     *
     * @param skillId スキル定義ID、または通常攻撃などの予約ID
     * @return 台帳未登録の場合は null
     */
    public static @Nullable Integer modelData(@Nullable String skillId) {
        int sprite = sprite(skillId);
        return sprite == 0 ? null : MODEL_DATA_BASE + sprite;
    }

    /**
     * スキルを表す表示アイテムに、リングと共通の絵柄を適用します。
     *
     * @param fallback 表示用アイテム。名前・説明・識別用のPDCなどを引き継ぎます
     * @param skillId スキル定義ID、または通常攻撃などの予約ID
     * @return モデル適用済みのコピー。台帳未登録の場合は元のアイテム
     */
    public static @NotNull ItemStack apply(@NotNull ItemStack fallback, @Nullable String skillId) {
        Integer modelData = modelData(skillId);
        if (modelData == null) {
            return fallback;
        }
        ItemStack icon = fallback.withType(ITEM_MATERIAL);
        ItemMeta meta = icon.getItemMeta();
        // 鍛冶型などの表示用 ITEM_MODEL を残すと、紙のモデル番号の振り分けが使われません。
        meta.setItemModel(ITEM_MATERIAL.getKey());
        CustomModelDataComponentUtil.writeFromInt(meta, modelData);
        icon.setItemMeta(meta);
        return icon;
    }

    static String revision() {
        return INSTANCE.revision;
    }

    /** 固定 HUD 字形の契約とスプライト台帳を合わせた互換性識別子を返します。 */
    static String hudRevision() {
        return INSTANCE.hudRevision;
    }

    /** 統合版の HUD データ形式とスプライト台帳を合わせた互換性識別子を返します。 */
    static String bedrockHudRevision() {
        return INSTANCE.bedrockHudRevision;
    }

    private static SkillIconCatalog load() {
        try (var stream = SkillIconCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing action ring sprite catalog");
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            Map<String, Integer> icons = new HashMap<>();
            JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(entry ->
                icons.put(entry.getKey(), entry.getValue().getAsInt())
            );
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String revision = HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
            String hudRevision = HexFormat.of().formatHex(digest.digest(("action-ring-hud-v1\n" + json)
                .getBytes(StandardCharsets.UTF_8)));
            String bedrockHudRevision = HexFormat.of().formatHex(digest.digest(("action-ring-hud-bedrock-v2\n" + json)
                .getBytes(StandardCharsets.UTF_8)));
            return new SkillIconCatalog(Map.copyOf(icons), revision, hudRevision, bedrockHudRevision);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot read action ring sprite catalog", exception);
        }
    }
}
