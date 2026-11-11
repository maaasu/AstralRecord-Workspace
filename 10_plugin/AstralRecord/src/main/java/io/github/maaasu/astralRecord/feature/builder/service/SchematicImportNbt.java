package io.github.maaasu.astralRecord.feature.builder.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/** アップロード用のサイズと構造の上限を設けた gzip / Java NBT 読み取り。 */
final class SchematicImportNbt {
    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;

    private static final int MAX_INFLATED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_TAGS = 150_000;
    private static final int MAX_STRING_BYTES = 4_096;

    private static final int GZIP_HEADER_BYTES = 10;
    private static final int GZIP_TRAILER_BYTES = 8;
    private static final int GZIP_HEADER_CRC = 2;
    private static final int GZIP_EXTRA_FIELD = 4;
    private static final int GZIP_FILE_NAME = 8;
    private static final int GZIP_COMMENT = 16;

    private final DataInputStream input;
    private int tagsRemaining = MAX_TAGS;

    private SchematicImportNbt(byte[] nbt) {
        input = new DataInputStream(new ByteArrayInputStream(nbt));
    }

    /** 単一 gzip メンバー内にある無名のルート Compound 全体を読む。 */
    static Compound read(byte[] compressed) throws IOException {
        return new SchematicImportNbt(inflate(compressed)).readRoot();
    }

    /** Offset を省略した検証済み v3 に既定の [0, 0, 0] を補う。 */
    static byte[] withDefaultOffset(byte[] validated) throws IOException {
        byte[] nbt = inflate(validated);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(validated.length);
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(buffer))) {
            // 末尾の Schematic と無名ルートの終端は入力検証で確認済み。
            output.write(nbt, 0, nbt.length - 2);
            output.writeByte(INT_ARRAY);
            output.writeUTF("Offset");
            output.writeInt(3);
            output.write(new byte[3 * Integer.BYTES]);
            output.writeByte(END);
            output.writeByte(END);
        }
        return buffer.toByteArray();
    }

    /** gzip のフッターを照合し、解凍サイズと後続データを検証する。 */
    private static byte[] inflate(byte[] compressed) throws IOException {
        int start = gzipPayloadOffset(compressed);
        int end = compressed.length - GZIP_TRAILER_BYTES;
        CRC32 crc = new CRC32();
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed, start, compressed.length - start);
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && !inflater.finished()) {
                    throw new IOException("Incomplete schematic gzip payload");
                }
                if (count > MAX_INFLATED_BYTES - output.size()) {
                    throw new IOException("Schematic exceeds the decompressed size limit");
                }
                crc.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != GZIP_TRAILER_BYTES
                    || littleEndianUnsignedInt(compressed, end) != crc.getValue()
                    || littleEndianUnsignedInt(compressed, end + 4) != output.size()) {
                throw new IOException("Invalid schematic gzip trailer");
            }
            return output.toByteArray();
        } catch (DataFormatException exception) {
            throw new IOException("Invalid schematic gzip payload", exception);
        } finally {
            inflater.end();
        }
    }

    /** gzip ヘッダーの任意フィールドとチェックサムを読み、圧縮データの先頭を返す。 */
    private static int gzipPayloadOffset(byte[] compressed) throws IOException {
        int limit = compressed.length - GZIP_TRAILER_BYTES;
        if (limit <= GZIP_HEADER_BYTES || (compressed[0] & 0xFF) != 0x1F
                || (compressed[1] & 0xFF) != 0x8B || compressed[2] != 8 || (compressed[3] & 0xE0) != 0) {
            throw new IOException("Invalid schematic gzip header");
        }
        int flags = compressed[3];
        int offset = GZIP_HEADER_BYTES;
        if ((flags & GZIP_EXTRA_FIELD) != 0) {
            if (offset + 2 > limit) {
                throw new IOException("Invalid schematic gzip extra field");
            }
            int extraLength = littleEndianUnsignedShort(compressed, offset);
            offset += 2 + extraLength;
            if (offset > limit) {
                throw new IOException("Invalid schematic gzip extra field");
            }
        }
        if ((flags & GZIP_FILE_NAME) != 0) {
            offset = skipGzipString(compressed, offset, limit);
        }
        if ((flags & GZIP_COMMENT) != 0) {
            offset = skipGzipString(compressed, offset, limit);
        }
        if ((flags & GZIP_HEADER_CRC) != 0) {
            CRC32 crc = new CRC32();
            crc.update(compressed, 0, offset);
            if (offset + 2 > limit || littleEndianUnsignedShort(compressed, offset) != (crc.getValue() & 0xFFFF)) {
                throw new IOException("Invalid schematic gzip header checksum");
            }
            offset += 2;
        }
        if (offset >= limit) {
            throw new IOException("Missing schematic gzip payload");
        }
        return offset;
    }

    /** gzip のゼロ終端文字列を読み飛ばす。 */
    private static int skipGzipString(byte[] compressed, int offset, int limit) throws IOException {
        for (int index = offset; index < limit; index++) {
            if (compressed[index] == 0) {
                return index + 1;
            }
        }
        throw new IOException("Unterminated schematic gzip header field");
    }

    /** gzip の 16 ビット符号なし数を読み取る。 */
    private static int littleEndianUnsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    /** gzip の 32 ビット符号なし数を読み取る。 */
    private static long littleEndianUnsignedInt(byte[] data, int offset) {
        return littleEndianUnsignedShort(data, offset) | (long) littleEndianUnsignedShort(data, offset + 2) << 16;
    }

    /** ルート名と型を検証し、NBT に後続データがないことを確認する。 */
    private Compound readRoot() throws IOException {
        if (input.readUnsignedByte() != COMPOUND || !readString().isEmpty()) {
            throw new IOException("Invalid schematic NBT root");
        }
        Compound root = (Compound) readPayload(COMPOUND, 1);
        if (input.read() != -1) {
            throw new IOException("Unexpected data after schematic NBT");
        }
        return root;
    }

    /** NBT タグの値を構造上限内で読み取る。 */
    private Object readPayload(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH || --tagsRemaining < 0) {
            throw new IOException("Schematic NBT exceeds its structure limits");
        }
        return switch (type) {
            case BYTE -> input.readByte();
            case SHORT -> input.readShort();
            case INT -> input.readInt();
            case LONG -> input.readLong();
            case FLOAT -> input.readFloat();
            case DOUBLE -> input.readDouble();
            case BYTE_ARRAY -> input.readNBytes(readLength(Byte.BYTES));
            case STRING -> readString();
            case LIST -> readList(depth);
            case COMPOUND -> readCompound(depth);
            case INT_ARRAY -> readInts();
            case LONG_ARRAY -> readLongs();
            default -> throw new IOException("Invalid schematic NBT tag");
        };
    }

    /** Compound を読み取り、同名のタグを拒否する。 */
    private Compound readCompound(int depth) throws IOException {
        Map<String, Object> values = new HashMap<>();
        int type;
        while ((type = input.readUnsignedByte()) != END) {
            String name = readString();
            if (values.putIfAbsent(name, readPayload(type, depth + 1)) != null) {
                throw new IOException("Duplicate schematic NBT tag");
            }
        }
        return new Compound(values);
    }

    /** リスト要素の型と件数を検証してから領域を確保する。 */
    private TagList readList(int depth) throws IOException {
        int type = input.readUnsignedByte();
        int length = readLength(1);
        if (type > LONG_ARRAY || length > tagsRemaining || (type == END && length != 0)) {
            throw new IOException("Invalid schematic NBT list");
        }
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(readPayload(type, depth + 1));
        }
        return new TagList(type, values);
    }

    /** 整数配列のサイズを検証してから読み取る。 */
    private int[] readInts() throws IOException {
        int[] values = new int[readLength(Integer.BYTES)];
        for (int index = 0; index < values.length; index++) {
            values[index] = input.readInt();
        }
        return values;
    }

    /** 64 ビット整数配列のサイズを検証してから読み取る。 */
    private long[] readLongs() throws IOException {
        long[] values = new long[readLength(Long.BYTES)];
        for (int index = 0; index < values.length; index++) {
            values[index] = input.readLong();
        }
        return values;
    }

    /** 配列またはリストの件数が残りバイト数に収まるか確認する。 */
    private int readLength(int minElementBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || (long) length * minElementBytes > input.available()) {
            throw new IOException("Invalid schematic NBT length");
        }
        return length;
    }

    /** Java NBT の Modified UTF-8 文字列を上限付きで読む。 */
    private String readString() throws IOException {
        input.mark(Short.BYTES);
        int bytes = input.readUnsignedShort();
        if (bytes > MAX_STRING_BYTES || bytes > input.available()) {
            throw new IOException("Invalid schematic NBT string length");
        }
        input.reset();
        return input.readUTF();
    }

    /** 解析した Compound とスキーマ検証用の型付きアクセサー。 */
    record Compound(Map<String, Object> values) {
        /** スキーマ外のタグがあれば拒否する。 */
        void requireOnly(Set<String> allowed) throws IOException {
            if (!allowed.containsAll(values.keySet())) {
                throw new IOException("Unsupported schematic NBT field");
            }
        }

        /** 必須タグを指定型で取得する。 */
        <T> T required(String key, Class<T> type) throws IOException {
            T value = optional(key, type);
            if (value == null) {
                throw new IOException("Missing schematic NBT field");
            }
            return value;
        }

        /** 任意タグを指定型で取得し、存在する場合は型を照合する。 */
        <T> T optional(String key, Class<T> type) throws IOException {
            Object value = values.get(key);
            if (value != null && !type.isInstance(value)) {
                throw new IOException("Invalid schematic NBT field type");
            }
            return type.cast(value);
        }
    }

    /** NBT リストの宣言型と各要素。 */
    record TagList(int elementType, List<Object> values) {
        /** Compound 型のリストかを返す。 */
        boolean isCompoundList() {
            return elementType == COMPOUND;
        }

        /** Compound または終端型の空リストかを返す。 */
        boolean isEmptyCompoundList() {
            return values.isEmpty() && (isCompoundList() || elementType == END);
        }

        /** 文字列または終端型の空リストかを返す。 */
        boolean isEmptyStringList() {
            return values.isEmpty() && (elementType == STRING || elementType == END);
        }
    }
}
