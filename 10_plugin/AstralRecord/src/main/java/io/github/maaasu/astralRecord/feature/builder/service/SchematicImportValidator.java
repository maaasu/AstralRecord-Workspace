package io.github.maaasu.astralRecord.feature.builder.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.maaasu.astralRecord.feature.builder.service.SchematicImportNbt.Compound;
import static io.github.maaasu.astralRecord.feature.builder.service.SchematicImportNbt.TagList;

/** 外部から受け取った Sponge Schematic v3 を WorldEdit に渡す前の検証。 */
public final class SchematicImportValidator {
    public static final int MAX_COMPRESSED_BYTES = 1024 * 1024;

    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final int MAX_VERTICAL_COORDINATE = 4_096;
    private static final int MAX_RELATIVE_COORDINATE = 4_096;
    private static final int MAX_BLOCK_STATE_LENGTH = 512;
    private static final int FIRST_FLATTENED_DATA_VERSION = 1519;
    private static final Pattern BLOCK_STATE = Pattern.compile(
            "(?:minecraft:)?([a-z][a-z0-9_]*)(?:\\[([a-z0-9_]+=[a-z0-9_]+(?:,[a-z0-9_]+=[a-z0-9_]+)*)\\])?");
    private static final Set<String> WORLD_EDIT_BLOCK_SHORTCUTS = Set.of("hand", "offhand", "pos1", "pos2");
    private static final Set<String> FORBIDDEN_BLOCKS = Set.of(
            "command_block", "repeating_command_block", "chain_command_block",
            "spawner", "mob_spawner", "trial_spawner", "vault", "jigsaw",
            "structure_block", "structure_void", "end_gateway", "end_portal", "nether_portal", "moving_piston");
    private static final Set<String> EMPTY_CONTAINERS = Set.of(
            "barrel", "chest", "trapped_chest", "dispenser", "dropper", "shulker_box");
    private static final Set<String> COLORS = Set.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Set<String> SIGN_WOODS = Set.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo",
            "crimson", "warped", "pale_oak");

    private SchematicImportValidator() {
    }

    /**
     * 圧縮サイズ、NBT 構造、ブロック配列、追加データを検証する。
     *
     * @param compressed gzip 圧縮済みのアップロード内容
     * @param maxBlocks 選択範囲の最大体積
     * @return 保存元 Minecraft のデータ版と、パレットに記載された名前空間付きブロック ID
     * @throws IOException 読めないデータまたは許可範囲外のデータが含まれる場合
     */
    public static Validation validate(byte[] compressed, int maxBlocks) throws IOException {
        if (compressed == null || compressed.length > MAX_COMPRESSED_BYTES || maxBlocks < 1) {
            throw new IOException("Schematic input exceeds its limits");
        }
        Compound root = SchematicImportNbt.read(compressed);
        root.requireOnly(Set.of("Schematic"));
        Compound schematic = root.required("Schematic", Compound.class);
        schematic.requireOnly(Set.of("Version", "DataVersion", "Width", "Height", "Length",
                "Offset", "Metadata", "Blocks", "Entities"));
        if (schematic.required("Version", Integer.class) != 3) {
            throw new IOException("Unsupported schematic format");
        }
        int dataVersion = schematic.required("DataVersion", Integer.class);
        if (dataVersion < FIRST_FLATTENED_DATA_VERSION) {
            throw new IOException("Unsupported Minecraft data version");
        }

        Dimensions dimensions = readDimensions(schematic, maxBlocks);
        validateCoordinates(schematic, dimensions);
        TagList entities = schematic.optional("Entities", TagList.class);
        if (entities != null && !entities.isEmptyCompoundList()) {
            throw new IOException("Schematic entities are unsupported");
        }

        Compound blocks = schematic.required("Blocks", Compound.class);
        blocks.requireOnly(Set.of("Palette", "Data", "BlockEntities"));
        String[] palette = readPalette(blocks.required("Palette", Compound.class), dimensions.volume());
        TagList blockEntities = blocks.optional("BlockEntities", TagList.class);
        Map<Integer, String> blockEntitiesByIndex = readBlockEntities(blockEntities, dimensions);
        validateBlockData(blocks.required("Data", byte[].class), palette, dimensions.volume(), blockEntitiesByIndex);
        Set<String> blockIds = new HashSet<>();
        for (String id : palette) {
            blockIds.add("minecraft:" + id);
        }
        return new Validation(dataVersion, Set.copyOf(blockIds));
    }

    /** 符号なし short の寸法を取り出し、体積が設定範囲内か調べる。 */
    private static Dimensions readDimensions(Compound schematic, int maxBlocks) throws IOException {
        int width = Short.toUnsignedInt(schematic.required("Width", Short.class));
        int height = Short.toUnsignedInt(schematic.required("Height", Short.class));
        int length = Short.toUnsignedInt(schematic.required("Length", Short.class));
        long volume = (long) width * height * length;
        if (volume < 1 || volume > maxBlocks) {
            throw new IOException("Schematic volume is outside its limits");
        }
        return new Dimensions(width, height, length, (int) volume);
    }

    /** ペースト原点からの距離と、WorldEdit が読み込む保存元座標を制限する。 */
    private static void validateCoordinates(Compound schematic, Dimensions dimensions) throws IOException {
        int[] offset = optionalVector(schematic, "Offset");
        int[] origin = new int[3];
        Compound metadata = schematic.optional("Metadata", Compound.class);
        if (metadata != null) {
            TagList requiredMods = metadata.optional("RequiredMods", TagList.class);
            if (requiredMods != null && !requiredMods.isEmptyStringList()) {
                throw new IOException("Schematic requires unsupported mods");
            }
            Compound worldEdit = metadata.optional("WorldEdit", Compound.class);
            if (worldEdit != null) {
                origin = optionalVector(worldEdit, "Origin");
            }
        }
        int[] sizes = {dimensions.width(), dimensions.height(), dimensions.length()};
        for (int axis = 0; axis < 3; axis++) {
            long relativeMax = (long) offset[axis] + sizes[axis] - 1;
            int absoluteLimit = axis == 1 ? MAX_VERTICAL_COORDINATE : MAX_HORIZONTAL_COORDINATE;
            if (!inCoordinateRange(offset[axis], MAX_RELATIVE_COORDINATE)
                    || !inCoordinateRange(relativeMax, MAX_RELATIVE_COORDINATE)
                    || !inCoordinateRange(origin[axis], absoluteLimit)
                    || !inCoordinateRange((long) origin[axis] + offset[axis], absoluteLimit)
                    || !inCoordinateRange(origin[axis] + relativeMax, absoluteLimit)) {
                throw new IOException("Schematic coordinates are outside their limits");
            }
        }
    }

    /** 省略時にゼロを使う三次元座標を取得する。 */
    private static int[] optionalVector(Compound compound, String name) throws IOException {
        int[] vector = compound.optional(name, int[].class);
        if (vector == null) {
            return new int[3];
        }
        requireVector(vector);
        return vector;
    }

    /** 三次元座標の要素数を検証する。 */
    private static void requireVector(int[] vector) throws IOException {
        if (vector.length != 3) {
            throw new IOException("Invalid schematic coordinate vector");
        }
    }

    /** 軸上の座標が正負の上限内にあるか判定する。 */
    private static boolean inCoordinateRange(long value, int limit) {
        return value >= -limit && value <= limit;
    }

    /** パレットの連続 ID と、各ブロック状態の識別子・プロパティを検証する。 */
    private static String[] readPalette(Compound palette, int volume) throws IOException {
        if (palette.values().isEmpty() || palette.values().size() > volume) {
            throw new IOException("Invalid schematic palette size");
        }
        String[] blockIds = new String[palette.values().size()];
        for (Map.Entry<String, Object> entry : palette.values().entrySet()) {
            if (!(entry.getValue() instanceof Integer id) || id < 0 || id >= blockIds.length
                    || blockIds[id] != null) {
                throw new IOException("Invalid schematic palette index");
            }
            blockIds[id] = readBlockState(entry.getKey());
        }
        return blockIds;
    }

    /** WorldEdit の入力構文へ展開されないバニラのブロック状態から ID を取り出す。 */
    private static String readBlockState(String state) throws IOException {
        if (state.length() > MAX_BLOCK_STATE_LENGTH) {
            throw new IOException("Invalid schematic block state");
        }
        Matcher match = BLOCK_STATE.matcher(state);
        if (!match.matches() || FORBIDDEN_BLOCKS.contains(match.group(1))
                || WORLD_EDIT_BLOCK_SHORTCUTS.contains(match.group(1))) {
            throw new IOException("Unsupported schematic block state");
        }
        String properties = match.group(2);
        if (properties != null) {
            Set<String> names = new HashSet<>();
            for (String property : properties.split(",")) {
                String name = property.substring(0, property.indexOf('='));
                if (!names.add(name)) {
                    throw new IOException("Duplicate schematic block property");
                }
                if (match.group(1).equals("lectern") && property.equals("has_book=true")) {
                    throw new IOException("Schematic lectern has a book");
                }
            }
        }
        return match.group(1);
    }

    /** ブロックエンティティの位置、型、安全な追加データを検証する。 */
    private static Map<Integer, String> readBlockEntities(TagList entities, Dimensions dimensions) throws IOException {
        if (entities == null || entities.isEmptyCompoundList()) {
            return Map.of();
        }
        Map<Integer, String> blockEntities = new HashMap<>();
        if (!entities.isCompoundList() || entities.values().size() > dimensions.volume()) {
            throw new IOException("Invalid schematic block entity list");
        }
        for (Object value : entities.values()) {
            Compound entity = (Compound) value;
            entity.requireOnly(Set.of("Id", "Pos", "Data"));
            int[] pos = entity.required("Pos", int[].class);
            requireVector(pos);
            if (pos[0] < 0 || pos[0] >= dimensions.width() || pos[1] < 0 || pos[1] >= dimensions.height()
                    || pos[2] < 0 || pos[2] >= dimensions.length()) {
                throw new IOException("Schematic block entity is outside the selection");
            }
            int index = pos[0] + (pos[2] + pos[1] * dimensions.length()) * dimensions.width();
            String id = entity.required("Id", String.class);
            if (blockEntities.putIfAbsent(index, id) != null) {
                throw new IOException("Duplicate schematic block entity position");
            }
            validateBlockEntityData(id, entity.optional("Data", Compound.class));
        }
        return blockEntities;
    }

    /** 空の装飾・容器に必要な既定フィールドだけを通す。 */
    private static void validateBlockEntityData(String id, Compound data) throws IOException {
        if (data == null) {
            return;
        }
        if (id.startsWith("minecraft:") && EMPTY_CONTAINERS.contains(id.substring("minecraft:".length()))) {
            data.requireOnly(Set.of("id", "components", "Items"));
            TagList items = data.optional("Items", TagList.class);
            if (items != null && !items.isEmptyCompoundList()) {
                throw new IOException("Schematic container has items");
            }
        } else {
            data.requireOnly(Set.of("id", "components"));
        }
        String nestedId = data.optional("id", String.class);
        Compound components = data.optional("components", Compound.class);
        if ((nestedId != null && !id.equals(nestedId)) || (components != null && !components.values().isEmpty())) {
            throw new IOException("Invalid schematic block entity data");
        }
    }

    /** パレット参照を一つずつ読み、格納数とブロックエンティティの対応を検証する。 */
    private static void validateBlockData(byte[] data, String[] palette, int volume,
                                          Map<Integer, String> blockEntities) throws IOException {
        if (data.length < volume || data.length > 5L * volume) {
            throw new IOException("Invalid schematic block data length");
        }
        int position = 0;
        for (int index = 0; index < volume; index++) {
            long value = 0;
            int octet;
            int shift = 0;
            do {
                if (position == data.length || shift == 35) {
                    throw new IOException("Invalid schematic palette VarInt");
                }
                octet = Byte.toUnsignedInt(data[position++]);
                value |= (long) (octet & 0x7F) << shift;
                shift += 7;
            } while ((octet & 0x80) != 0);
            if ((shift > 7 && octet == 0) || value >= palette.length) {
                throw new IOException("Invalid schematic palette reference");
            }
            String block = palette[(int) value];
            String blockEntity = blockEntities.get(index);
            if (blockEntity != null && !matchesBlockEntity(block, blockEntity)) {
                throw new IOException("Unsupported schematic block entity");
            }
        }
        if (position != data.length) {
            throw new IOException("Schematic block count does not match its dimensions");
        }
    }

    /** 許可するブロックエンティティの ID が対応ブロックと一致するか判定する。 */
    private static boolean matchesBlockEntity(String block, String blockEntity) {
        if (!blockEntity.startsWith("minecraft:")) {
            return false;
        }
        return switch (blockEntity.substring("minecraft:".length())) {
            case "lectern", "ender_chest", "barrel", "chest", "trapped_chest", "dispenser", "dropper" ->
                    blockEntity.equals("minecraft:" + block);
            case "shulker_box" -> block.equals("shulker_box") || hasPrefixIn(block, "_shulker_box", COLORS);
            case "bed" -> hasPrefixIn(block, "_bed", COLORS);
            case "banner" -> hasPrefixIn(block, "_banner", COLORS) || hasPrefixIn(block, "_wall_banner", COLORS);
            case "sign" -> hasPrefixIn(block, "_sign", SIGN_WOODS) || hasPrefixIn(block, "_wall_sign", SIGN_WOODS);
            case "hanging_sign" -> hasPrefixIn(block, "_hanging_sign", SIGN_WOODS)
                    || hasPrefixIn(block, "_wall_hanging_sign", SIGN_WOODS);
            default -> false;
        };
    }

    /** 色や樹種とブロック接尾辞からなる ID を照合する。 */
    private static boolean hasPrefixIn(String block, String suffix, Set<String> prefixes) {
        return block.endsWith(suffix) && prefixes.contains(block.substring(0, block.length() - suffix.length()));
    }

    /** WorldEdit の解釈前に検証した、Minecraft データ版と名前空間付きブロック ID。 */
    public record Validation(int dataVersion, Set<String> blockIds) {
    }

    private record Dimensions(int width, int height, int length, int volume) {
    }
}
