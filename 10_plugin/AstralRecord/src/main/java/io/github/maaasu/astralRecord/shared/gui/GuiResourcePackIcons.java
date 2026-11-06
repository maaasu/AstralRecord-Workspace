package io.github.maaasu.astralRecord.shared.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 配布パックと共通の台帳から、GUI 専用コントロールの表示モデルを選びます。 */
public final class GuiResourcePackIcons {
    private static final Map<IconKey, Integer> ICONS = loadIcons();
    private static final Set<Float> GUI_MODEL_IDS = Set.copyOf(ICONS.values().stream().map(Integer::floatValue).toList());

    private GuiResourcePackIcons() {
    }

    /**
     * 空白の仕切りだけへ共通パネルのモデルを設定します。
     * 名前や説明を持つ素材カードとプレイヤーヘッドは変更しません。
     * @param itemStack 新しく生成した GUI アイテム。呼出スレッドでのみ変更すること
     * @return 元と同じ ItemStack。素材、数量、表示名、説明、PDC は維持します
     */
    public static @NotNull ItemStack applyControl(@NotNull ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || (meta.hasLore() && meta.lore() != null && !meta.lore().isEmpty())
            || (meta.displayName() != null
                && !PlainTextComponentSerializer.plainText().serialize(meta.displayName()).isBlank())) {
            return itemStack;
        }
        return switch (itemStack.getType()) {
            case BLACK_STAINED_GLASS_PANE -> apply(itemStack, "frame");
            case GRAY_STAINED_GLASS_PANE -> apply(itemStack, "panel");
            default -> itemStack;
        };
    }

    /**
     * 役割と現在の素材に対応する専用モデルを、新しく生成した GUI コントロールへ適用します。
     * 既存のマスター装備モデルは上書きせず、追加 float・flag・string・color も維持します。
     * @param itemStack GUI 用として生成したアイテム。実所持品のインスタンスを渡さないこと
     * @param role 台帳の役割名。未登録の役割と素材の組み合わせでは変更しません
     * @return 元と同じ ItemStack。素材、数量、プロファイル、PDC と他のメタデータは維持します
     */
    @SuppressWarnings("UnstableApiUsage")
    public static @NotNull ItemStack apply(@NotNull ItemStack itemStack, @NotNull String role) {
        Integer modelId = ICONS.get(new IconKey(role, itemStack.getType()));
        ItemMeta meta = itemStack.getItemMeta();
        if (modelId == null || meta == null) {
            return itemStack;
        }
        var floats = meta.getCustomModelDataComponent().getFloats();
        if (!floats.isEmpty() && floats.getFirst() != 0.0F
            && !GUI_MODEL_IDS.contains(floats.getFirst())) {
            return itemStack;
        }
        CustomModelDataComponentUtil.writeFromInt(meta, modelId);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    /** 同梱台帳を一度だけ読み込み、重複や不正な登録は起動時に検出します。 */
    private static Map<IconKey, Integer> loadIcons() {
        try (InputStream stream = GuiResourcePackIcons.class.getResourceAsStream("/gui-icons.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled gui-icons.json");
            }
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format_version").getAsInt() != 1) {
                throw new IllegalStateException("Unsupported GUI icon registry format");
            }
            Map<IconKey, Integer> result = new HashMap<>();
            Set<Integer> modelIds = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray("icons")) {
                JsonObject icon = element.getAsJsonObject();
                String role = icon.get("role").getAsString();
                Material material = Material.matchMaterial(icon.get("base_item").getAsString());
                int modelId = icon.get("custom_model_data").getAsInt();
                if (role.isBlank() || material == null || !material.isItem()
                    || modelId < 1 || modelId > 16777215 || !modelIds.add(modelId)
                    || result.putIfAbsent(new IconKey(role, material), modelId) != null) {
                    throw new IllegalStateException("Invalid or duplicate bundled GUI icon registration");
                }
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bundled GUI icon registry", exception);
        }
    }

    private record IconKey(String role, Material material) {
    }
}
