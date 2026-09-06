package io.github.maaasu.astralRecord.feature.mutation.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 未受領の完成状態をアカウント・ユーザーごとの圧縮バイナリに保存します。
 * 全メソッドは専用の非同期保存処理から呼び、Bukkitメインスレッドから呼びません。
 */
public final class PendingStateStore {
    private static final int MAGIC = 0x41525331;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private final Path root;

    /** @param root この保存機構専用のローカルディレクトリ */
    public PendingStateStore(@NotNull Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 対象の未同期状態を置き換えます。別ユーザーのファイルは書き直しません。
     * @param subjectId 所有者UUID
     * @param json 送信内容が固定されたJSON
     * @throws UncheckedIOException 保存に失敗した場合
     */
    public synchronized void write(@NotNull UUID subjectId, @NotNull String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Player state exceeds storage limit");
        Path file = file(subjectId);
        Path temporary = root.resolve(subjectId + ".bin.tmp");
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            Files.createDirectories(root);
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary));
                 DataOutputStream data = new DataOutputStream(output)) {
                data.writeInt(MAGIC);
                data.writeInt(bytes.length);
                DeflaterOutputStream compressed = new DeflaterOutputStream(data, deflater);
                compressed.write(bytes);
                compressed.finish();
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            deflater.end();
        }
    }

    /**
     * 保存した未同期状態を読みます。壊れたファイルを成功扱いで削除しません。
     * @param subjectId 所有者UUID
     * @return 未同期JSON。保存がない場合null
     * @throws UncheckedIOException 読み込みまたは検証に失敗した場合
     */
    public synchronized @Nullable String read(@NotNull UUID subjectId) {
        Path file = file(subjectId);
        if (!Files.exists(file)) return null;
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (data.readInt() != MAGIC) throw new IOException("Unsupported player state header");
            int length = data.readInt();
            if (length < 0 || length > MAX_BYTES) throw new IOException("Invalid player state length");
            try (InflaterInputStream compressed = new InflaterInputStream(data)) {
                byte[] bytes = compressed.readNBytes(length);
                if (bytes.length != length || compressed.read() != -1) throw new IOException("Invalid player state content");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * API受領済みの未同期ファイルだけを削除します。
     * @param subjectId 受領確認した所有者UUID
     * @throws UncheckedIOException 削除失敗時
     */
    public synchronized void delete(@NotNull UUID subjectId) {
        try {
            Files.deleteIfExists(file(subjectId));
            Files.deleteIfExists(root.resolve(subjectId + ".bin.tmp"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * 起動時の再送対象を取得します。
     * @return この保存機構が作成したUUIDファイルの所有者一覧
     * @throws UncheckedIOException 列挙失敗時
     */
    public synchronized @NotNull List<UUID> subjects() {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                .filter(name -> name.matches("[0-9a-fA-F-]{36}\\.bin"))
                .map(name -> UUID.fromString(name.substring(0, 36))).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path file(UUID subjectId) {
        return root.resolve(subjectId + ".bin");
    }
}
