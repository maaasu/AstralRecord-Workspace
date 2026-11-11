package io.github.maaasu.astralRecord.feature.builder.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchematicImportValidatorTest {
    private static final int DATA_VERSION = 4671;
    private static final TagWriter EMPTY = out -> { };

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 範囲とブロック
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 追加データ
     * 検証契約: 通常ブロックと本のない書見台を含む v3 の体積が上限内なら、データ版と名前空間付き ID を返す。
     */
    @Test
    void acceptsVanillaBlocksAndAnEmptyLectern() throws IOException {
        byte[] file = schematic(2, new byte[]{0, 1}, Map.of(
                        "stone", 0, "minecraft:lectern[facing=west,has_book=false,powered=false]", 1),
                EMPTY, out -> compoundList(out, "BlockEntities", entity("minecraft:lectern", 1, outData -> {
                    stringTag(outData, "id", "minecraft:lectern");
                    compoundTag(outData, "components", EMPTY);
                })));

        SchematicImportValidator.Validation validation = SchematicImportValidator.validate(file, 2);

        assertEquals(DATA_VERSION, validation.dataVersion());
        assertEquals(Set.of("minecraft:stone", "minecraft:lectern"), validation.blockIds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 圧縮と構造
     * 検証契約: 圧縮と展開の上限超過、gzip 破損、後続メンバーや余分なバイトを拒否する。
     */
    @Test
    void rejectsInvalidGzipAndSizeLimitViolations() throws IOException {
        byte[] file = schematic(1, new byte[]{0}, Map.of("minecraft:stone", 0), EMPTY, EMPTY);
        byte[] corruptCrc = file.clone();
        corruptCrc[file.length - 8] ^= 1;

        rejects(corruptCrc);
        rejects(Arrays.copyOf(file, file.length - 1));
        rejects(Arrays.copyOf(file, file.length + 1));
        rejects(concatenate(file, gzip(new byte[0])));
        rejects(new byte[SchematicImportValidator.MAX_COMPRESSED_BYTES + 1]);
        rejects(gzip(new byte[16 * 1024 * 1024 + 1]));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 圧縮と構造
     * 検証契約: ルート終端後のデータ、同名タグ、深さ・タグ数の上限超過、残量を超える配列宣言を拒否する。
     */
    @Test
    void rejectsMalformedAndOverBudgetNbt() throws IOException {
        byte[] nbt = schematicNbt(1, new byte[]{0}, Map.of("stone", 0), EMPTY, EMPTY);
        rejects(gzip(Arrays.copyOf(nbt, nbt.length + 1)));
        rejects(schematic(1, new byte[]{0}, Map.of("stone", 0), out -> intTag(out, "Version", 3), EMPTY));
        rejects(schematic(1, new byte[]{0}, Map.of("stone", 0), out -> compoundTag(out, "Metadata", nested -> {
            for (int index = 0; index < 24; index++) {
                tag(nested, 10, "nested");
            }
            nested.write(new byte[24]);
        }), EMPTY));
        rejects(schematic(1, new byte[]{0}, Map.of("stone", 0), out -> compoundTag(out, "Metadata", nested -> {
            tag(nested, 9, "large");
            nested.writeByte(10);
            nested.writeInt(150_000);
            nested.write(new byte[150_000]);
        }), EMPTY));
        rejects(schematic(1, new byte[]{0}, Map.of("stone", 0), out -> {
            tag(out, 11, "Offset");
            out.writeInt(Integer.MAX_VALUE);
        }, EMPTY));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 範囲とブロック
     * 検証契約: 上限を超えた体積、選択範囲の端が相対上限を超えるオフセット、上限外の保存元原点を拒否する。
     */
    @Test
    void rejectsExcessiveVolumeAndCoordinates() throws IOException {
        Map<String, Integer> palette = Map.of("stone", 0);
        byte[] oversized = schematic(2, new byte[]{0, 0}, palette, EMPTY, EMPTY);
        assertThrows(IOException.class, () -> SchematicImportValidator.validate(oversized, 1));
        rejects(schematic(0, new byte[0], palette, EMPTY, EMPTY));
        rejects(schematic(2, new byte[]{0, 0}, palette, out -> intArrayTag(out, "Offset", 4096, 0, 0), EMPTY));
        rejects(schematic(1, new byte[]{0}, palette, out -> compoundTag(out, "Metadata", metadata ->
                compoundTag(metadata, "WorldEdit", worldEdit ->
                        intArrayTag(worldEdit, "Origin", Integer.MAX_VALUE, 0, 0))), EMPTY));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 範囲とブロック
     * 検証契約: パレット ID の重複・欠番、参照先なし、非正規・途中終端・範囲外の VarInt と体積に一致しない格納数を拒否する。
     */
    @Test
    void rejectsInvalidPaletteReferencesAndBlockCounts() throws IOException {
        rejects(schematic(2, new byte[]{0, 0}, Map.of("stone", 0, "dirt", 0), EMPTY, EMPTY));
        rejects(schematic(2, new byte[]{0, 0}, Map.of("stone", 1), EMPTY, EMPTY));
        Map<String, Integer> palette = Map.of("stone", 0);
        for (byte[] data : new byte[][]{
                {0}, {0, 0, 0}, {0, 1}, {0, (byte) 0x80}, {0, (byte) 0x80, 0},
                {0, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10}
        }) {
            rejects(schematic(2, data, palette, EMPTY, EMPTY));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 範囲とブロック
     * 検証契約: コマンド等を動かすブロック、外部名前空間や WorldEdit 固有構文を使う状態指定を拒否する。
     */
    @Test
    void rejectsActiveBlocksAndExternalStateSyntax() throws IOException {
        for (String block : Set.of("command_block", "minecraft:chain_command_block[conditional=false]",
                "minecraft:repeating_command_block", "minecraft:spawner", "minecraft:trial_spawner",
                "minecraft:jigsaw", "minecraft:structure_block", "external:stone", "minecraft:stone|payload",
                "hand", "pos1", "137")) {
            rejects(schematic(1, new byte[]{0}, Map.of(block, 0), EMPTY, EMPTY));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 追加データ
     * 検証契約: 許可ブロックでも、実体と不一致の型・重複位置・範囲外位置・アイテムや戦利品を持つ追加データを拒否する。
     */
    @Test
    void rejectsUnsupportedBlockEntityDataAndLocations() throws IOException {
        Map<String, Integer> lectern = Map.of("minecraft:lectern[has_book=false]", 0);
        for (TagWriter entity : new TagWriter[]{
                entity("minecraft:command_block", 0, EMPTY), entity("minecraft:lectern", 1, EMPTY),
                entity("minecraft:lectern", 0, out -> compoundTag(out, "Book", EMPTY)),
                entity("minecraft:lectern", 0, out -> stringTag(out, "id", "minecraft:command_block")),
                entity("minecraft:lectern", 0, out -> compoundTag(out, "components",
                        components -> compoundTag(components, "minecraft:custom_data", EMPTY)))
        }) {
            rejects(schematic(1, new byte[]{0}, lectern, EMPTY, out -> compoundList(out, "BlockEntities", entity)));
        }
        TagWriter duplicate = entity("minecraft:lectern", 0, EMPTY);
        rejects(schematic(2, new byte[]{0, 0}, lectern, EMPTY,
                out -> compoundList(out, "BlockEntities", duplicate, duplicate)));

        Map<String, Integer> chest = Map.of("minecraft:chest", 0);
        for (TagWriter data : new TagWriter[]{
                out -> stringTag(out, "LootTable", "minecraft:chests/simple_dungeon"),
                out -> compoundList(out, "Items", item -> stringTag(item, "id", "minecraft:diamond"))
        }) {
            rejects(schematic(1, new byte[]{0}, chest, EMPTY,
                    out -> compoundList(out, "BlockEntities", entity("minecraft:chest", 0, data))));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_4-Schematic入力検証.md
     * 章・見出し: # 38_4-Schematic入力検証 > ## 追加データ
     * 検証契約: ブロックデータに加えてエンティティまたはバイオームを指定したファイルを拒否する。
     */
    @Test
    void rejectsEntitiesAndBiomeData() throws IOException {
        Map<String, Integer> palette = Map.of("stone", 0);
        rejects(schematic(1, new byte[]{0}, palette,
                out -> compoundList(out, "Entities", entity -> stringTag(entity, "Id", "minecraft:pig")), EMPTY));
        rejects(schematic(1, new byte[]{0}, palette, out -> compoundTag(out, "Biomes", EMPTY), EMPTY));
    }

    /** 設定上限内でも入力検証で拒否されることを確認する。 */
    private static void rejects(byte[] file) {
        assertThrows(IOException.class, () -> SchematicImportValidator.validate(file, 100_000));
    }

    /** 最小の Sponge v3 を gzip 圧縮する。 */
    private static byte[] schematic(int width, byte[] data, Map<String, Integer> palette,
                                    TagWriter schematicFields, TagWriter blockFields) throws IOException {
        return gzip(schematicNbt(width, data, palette, schematicFields, blockFields));
    }

    /** テスト対象の追加フィールドを含む Sponge v3 の NBT を組み立てる。 */
    private static byte[] schematicNbt(int width, byte[] data, Map<String, Integer> palette,
                                       TagWriter schematicFields, TagWriter blockFields) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            compoundTag(out, "", root -> compoundTag(root, "Schematic", schematic -> {
                intTag(schematic, "Version", 3);
                intTag(schematic, "DataVersion", DATA_VERSION);
                shortTag(schematic, "Width", width);
                shortTag(schematic, "Height", 1);
                shortTag(schematic, "Length", 1);
                compoundTag(schematic, "Blocks", blocks -> {
                    compoundTag(blocks, "Palette", entries -> {
                        for (var entry : palette.entrySet()) {
                            intTag(entries, entry.getKey(), entry.getValue());
                        }
                    });
                    tag(blocks, 7, "Data");
                    blocks.writeInt(data.length);
                    blocks.write(data);
                    blockFields.write(blocks);
                });
                schematicFields.write(schematic);
            }));
        }
        return buffer.toByteArray();
    }

    /** テスト用のブロックエンティティをリスト要素として書き出す。 */
    private static TagWriter entity(String id, int x, TagWriter data) {
        return out -> {
            stringTag(out, "Id", id);
            intArrayTag(out, "Pos", x, 0, 0);
            compoundTag(out, "Data", data);
        };
    }

    /** Compound リストをタグとして書き出す。 */
    private static void compoundList(DataOutputStream out, String name, TagWriter... elements) throws IOException {
        tag(out, 9, name);
        out.writeByte(10);
        out.writeInt(elements.length);
        for (TagWriter element : elements) {
            element.write(out);
            out.writeByte(0);
        }
    }

    /** Compound タグを終端付きで書き出す。 */
    private static void compoundTag(DataOutputStream out, String name, TagWriter writer) throws IOException {
        tag(out, 10, name);
        writer.write(out);
        out.writeByte(0);
    }

    /** テスト用の整数配列タグを書き出す。 */
    private static void intArrayTag(DataOutputStream out, String name, int... values) throws IOException {
        tag(out, 11, name);
        out.writeInt(values.length);
        for (int value : values) {
            out.writeInt(value);
        }
    }

    /** テスト用の整数タグを書き出す。 */
    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        tag(out, 3, name);
        out.writeInt(value);
    }

    /** テスト用の short タグを書き出す。 */
    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        tag(out, 2, name);
        out.writeShort(value);
    }

    /** テスト用の文字列タグを書き出す。 */
    private static void stringTag(DataOutputStream out, String name, String value) throws IOException {
        tag(out, 8, name);
        out.writeUTF(value);
    }

    /** NBT のタグ型と名前を書き出す。 */
    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }

    /** バイト列を gzip の単一メンバーにする。 */
    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(data);
        }
        return buffer.toByteArray();
    }

    /** 二つの gzip メンバーを一つのファイルに並べる。 */
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @FunctionalInterface
    private interface TagWriter {
        /** NBT ペイロードを出力する。 */
        void write(DataOutputStream out) throws IOException;
    }
}
