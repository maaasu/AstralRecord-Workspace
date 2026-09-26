package io.github.maaasu.astralRecord.feature.vip.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** 未開始の優先消費を保存し、停止・再起動をまたぐ返却を可能にする書込先行記録です。 */
public final class PendingPriorityJournal {
    private final Path path;
    private final Map<UUID, UUID> accountsByOperation = new LinkedHashMap<>();

    /** 永続記録を読み込みます。破損時は消費を始めず例外を返します。 */
    public PendingPriorityJournal(Path path) {
        this.path = path;
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames())
                accountsByOperation.put(UUID.fromString(key), UUID.fromString(properties.getProperty(key)));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** API消費より前にoperation/accountを保存します。保存失敗時は状態を変更しません。 */
    public synchronized void add(UUID operationId, UUID accountId) {
        Map<UUID, UUID> next = new LinkedHashMap<>(accountsByOperation);
        UUID previous = next.putIfAbsent(operationId, accountId);
        if (previous != null && !previous.equals(accountId)) throw new IllegalArgumentException("Operation account mismatch");
        write(next);
    }

    /** 開始成功またはAPI返却確定後だけ記録を除去します。 */
    public synchronized void complete(UUID operationId) {
        if (!accountsByOperation.containsKey(operationId)) return;
        Map<UUID, UUID> next = new LinkedHashMap<>(accountsByOperation);
        next.remove(operationId);
        write(next);
    }

    /** 起動時に返却すべき操作の変更不能スナップショットを返します。 */
    public synchronized Map<UUID, UUID> pending() { return Map.copyOf(accountsByOperation); }

    /** 同じディレクトリの一時ファイルをfsync後に原子的置換します。 */
    private void write(Map<UUID, UUID> next) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Properties properties = new Properties();
            next.forEach((operation, account) -> properties.setProperty(operation.toString(), account.toString()));
            try (var output = Files.newOutputStream(temporary)) { properties.store(output, "Pending instance priority refunds"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            accountsByOperation.clear();
            accountsByOperation.putAll(next);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
