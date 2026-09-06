package io.github.maaasu.astralRecord.feature.mutation.service;

import io.github.maaasu.astralRecord.feature.mutation.model.LocalMutationCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Plugin側で確定したmutationを、必要な差分だけバイナリで保持する軽量なローカルoutboxです。
 * <p>
 * ファイルには未完了操作だけを保存します。API応答、master、表示文言、inventory snapshotは保存せず、
 * 成功確認後に該当レコードを削除してファイルを再圧縮します。同一アカウントの送信は一件ずつに
 * 制限し、強化やレベルアップの順序を維持します。
 */
public final class LocalMutationOutbox implements AutoCloseable {
    private static final int MAGIC = 0x41524D4F; // ARMO
    private static final int VERSION = 4;
    private static final byte EQUIPMENT_ORB = 1;
    private static final byte SKILL_LEVEL_UP = 2;
    private static final int MAX_RECORDS = 4096;
    private static final int MAX_LIST_SIZE = 256;
    private static final int MAX_STRING_BYTES = 16_384;
    private static final long RETRY_INITIAL_MILLIS = 1_000L;
    private static final long RETRY_MAX_MILLIS = 30_000L;

    private final Path file;
    private final Path temporaryFile;
    private final Executor executor;
    private final Object lock = new Object();
    private final LinkedHashMap<UUID, LocalMutationCommand> pending = new LinkedHashMap<>();
    private final Map<UUID, Integer> attempts = new LinkedHashMap<>();
    private final Map<UUID, Boolean> inFlightByAccount = new LinkedHashMap<>();
    private final Map<UUID, Boolean> retryScheduled = new LinkedHashMap<>();
    private Dispatcher dispatcher;
    private boolean started;
    private boolean closing;

    public LocalMutationOutbox(@NotNull Path pluginDataFolder, @NotNull Executor executor) {
        this.file = pluginDataFolder.resolve("pending-mutations.bin");
        this.temporaryFile = pluginDataFolder.resolve("pending-mutations.bin.tmp");
        this.executor = executor;
    }

    /** API送信処理を接続します。接続後に保存済み操作の送信を開始します。 */
    public void setDispatcher(@NotNull Dispatcher dispatcher) {
        synchronized (lock) {
            this.dispatcher = dispatcher;
            if (!started) {
                loadLocked();
                started = true;
            }
        }
        dispatch();
    }

    /** ローカルファイルへ追加します。ローカル状態反映後の送信開始は {@link #dispatch()} で行います。 */
    public void enqueue(@NotNull LocalMutationCommand command) {
        synchronized (lock) {
            if (closing) {
                throw new IllegalStateException("Local mutation outbox is closing.");
            }
            if (!started) {
                loadLocked();
                started = true;
            }
            if (pending.containsKey(command.operationId())) {
                return;
            }
            if (pending.size() >= MAX_RECORDS) {
                throw new IllegalStateException("Local mutation outbox is full.");
            }
            pending.put(command.operationId(), command);
            try {
                persistLocked();
            } catch (RuntimeException failure) {
                pending.remove(command.operationId());
                throw failure;
            }
        }
    }

    /** ローカル状態の反映後に、保存済みレコードの非同期送信を開始します。 */
    public void dispatch() {
        try {
            executor.execute(this::dispatchAvailable);
        } catch (Throwable schedulingFailure) {
            // レコードはファイルへ残っているため、次回dispatch/setDispatcher時に再送できる。
            Logger.warn(LogId.W_5252, file.toString(), failureReason(schedulingFailure));
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /**
     * 旧形式からの移行時、未確定操作があるアカウントの新規変更を保留します。
     * @param accountId 対象アカウント
     * @return 旧形式の未受領操作が残る場合true
     */
    public boolean hasPending(@NotNull UUID accountId) {
        synchronized (lock) {
            return pending.values().stream().anyMatch(command -> command.accountId().equals(accountId));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closing = true;
        }
    }

    private void dispatchAvailable() {
        List<LocalMutationCommand> dispatches = new ArrayList<>();
        synchronized (lock) {
            if (!started || closing || dispatcher == null) {
                return;
            }
            java.util.Set<UUID> encounteredAccounts = new java.util.HashSet<>();
            for (LocalMutationCommand command : pending.values()) {
                UUID accountId = command.accountId();
                if (!encounteredAccounts.add(accountId) || inFlightByAccount.containsKey(accountId)
                    || retryScheduled.containsKey(command.operationId())) {
                    continue;
                }
                inFlightByAccount.put(accountId, true);
                dispatches.add(command);
            }
        }
        for (LocalMutationCommand command : dispatches) {
            CompletionStage<Delivery> result;
            try {
                result = dispatcher.dispatch(command);
            } catch (Throwable failure) {
                result = CompletableFuture.failedFuture(failure);
            }
            if (result == null) {
                complete(
                    command,
                    Delivery.RETRY,
                    new IllegalStateException("Local mutation dispatcher returned null.")
                );
            } else {
                result.whenComplete((delivery, failure) -> complete(command, delivery, failure));
            }
        }
    }

    private void complete(
        @NotNull LocalMutationCommand command,
        Delivery delivery,
        Throwable failure
    ) {
        boolean retry = failure != null || delivery != Delivery.ACK;
        long retryDelay = 0L;
        synchronized (lock) {
            inFlightByAccount.remove(command.accountId());
            if (!pending.containsKey(command.operationId())) {
                return;
            }
            if (!retry) {
                pending.remove(command.operationId());
                attempts.remove(command.operationId());
                try {
                    persistLocked();
                } catch (RuntimeException persistFailure) {
                    // メモリ上で削除した操作を戻し、次回の再送対象として残す。
                    LinkedHashMap<UUID, LocalMutationCommand> retained = new LinkedHashMap<>();
                    retained.put(command.operationId(), command);
                    retained.putAll(pending);
                    pending.clear();
                    pending.putAll(retained);
                    Logger.warn(LogId.W_5252, command.accountId(), failureReason(persistFailure));
                    retry = true;
                }
            }
            if (retry) {
                int attempt = attempts.merge(command.operationId(), 1, Integer::sum);
                retryDelay = Math.min(
                    RETRY_MAX_MILLIS,
                    RETRY_INITIAL_MILLIS << Math.min(5, Math.max(0, attempt - 1))
                );
                retryScheduled.put(command.operationId(), true);
            }
        }
        if (retry) {
            final long delay = retryDelay;
            try {
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor).execute(() -> {
                    synchronized (lock) {
                        retryScheduled.remove(command.operationId());
                    }
                    dispatchAvailable();
                });
            } catch (Throwable schedulingFailure) {
                synchronized (lock) {
                    retryScheduled.remove(command.operationId());
                }
                Logger.warn(LogId.W_5252, command.accountId(), failureReason(schedulingFailure));
            }
        }
        // ACKが同期完了するdispatcherでも、同一accountの連続処理を再帰呼び出しにしない。
        scheduleDispatch();
    }

    private void scheduleDispatch() {
        try {
            CompletableFuture.delayedExecutor(0L, TimeUnit.MILLISECONDS, executor)
                .execute(this::dispatchAvailable);
        } catch (Throwable schedulingFailure) {
            Logger.warn(LogId.W_5252, file.toString(), failureReason(schedulingFailure));
        }
    }

    private void loadLocked() {
        Path source = file;
        if (!Files.exists(source) && Files.exists(temporaryFile)) {
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
                source = file;
            } catch (IOException recoveryFailure) {
                // moveできない環境では一時ファイルを直接読み、次回のpersistで正規化する。
                source = temporaryFile;
                Logger.warn(LogId.W_5252, temporaryFile.toString(), failureReason(recoveryFailure));
            }
        }
        if (!Files.exists(source)) {
            return;
        }
        try (InputStream input = Files.newInputStream(source);
             DataInputStream data = new DataInputStream(input)) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                throw new IOException("Unsupported local mutation outbox header.");
            }
            int count = data.readInt();
            if (count < 0 || count > MAX_RECORDS) {
                throw new IOException("Invalid local mutation outbox record count: " + count);
            }
            for (int index = 0; index < count; index++) {
                LocalMutationCommand command = readCommand(data);
                if (pending.put(command.operationId(), command) != null) {
                    throw new IOException("Duplicate local mutation operation ID.");
                }
            }
        } catch (RuntimeException | IOException failure) {
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt");
            try {
                Files.move(source, corrupt, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                Logger.warn(LogId.W_5252, source.toString(), failureReason(moveFailure));
            }
            pending.clear();
            Logger.warn(LogId.W_5252, source.toString(), failureReason(failure));
        }
    }

    private void persistLocked() {
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(temporaryFile);
                return;
            }
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
                OutputStream output = Channels.newOutputStream(channel);
                DataOutputStream data = new DataOutputStream(output);
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeInt(pending.size());
                for (LocalMutationCommand command : pending.values()) {
                    writeCommand(data, command);
                }
                data.flush();
                channel.force(false);
            }
            try {
                Files.move(
                    temporaryFile,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to persist local mutation outbox.", failure);
        }
    }

    private static void writeCommand(
        @NotNull DataOutputStream data,
        @NotNull LocalMutationCommand command
    ) throws IOException {
        if (command instanceof LocalMutationCommand.EquipmentOrb equipment) {
            data.writeByte(EQUIPMENT_ORB);
            writeUuid(data, equipment.operationId());
            writeUuid(data, equipment.accountId());
            writeUuid(data, equipment.equipmentInstanceId());
            writeUuid(data, equipment.orbInventoryEntryId());
            writeString(data, equipment.orbItemId());
            data.writeInt(equipment.baseEnhanceLevel());
            data.writeInt(equipment.baseTranscendenceRank());
            data.writeInt(equipment.enhanceLevel());
            data.writeBoolean(equipment.enhancementSucceeded());
            return;
        }
        if (command instanceof LocalMutationCommand.SkillLevelUp skill) {
            data.writeByte(SKILL_LEVEL_UP);
            writeUuid(data, skill.operationId());
            writeUuid(data, skill.accountId());
            writeUuid(data, skill.learnedSkillId());
            writeUuid(data, skill.updatedBy());
            data.writeInt(skill.expectedLevel());
            data.writeInt(skill.targetLevel());
            data.writeInt(skill.expectedVersion());
            data.writeInt(skill.targetVersion());
            if (skill.payments().size() > MAX_LIST_SIZE) {
                throw new IOException("Too many skill payment records.");
            }
            data.writeInt(skill.payments().size());
            for (LocalMutationCommand.Payment payment : skill.payments()) {
                writeUuid(data, payment.inventoryEntryId());
                data.writeLong(payment.amount());
            }
            return;
        }
        throw new IOException("Unknown local mutation command type.");
    }

    private static @NotNull LocalMutationCommand readCommand(@NotNull DataInputStream data) throws IOException {
        return switch (data.readByte()) {
            case EQUIPMENT_ORB -> readEquipmentOrb(data);
            case SKILL_LEVEL_UP -> readSkillLevelUp(data);
            default -> throw new IOException("Unknown local mutation command tag.");
        };
    }

    private static @NotNull LocalMutationCommand.EquipmentOrb readEquipmentOrb(
        @NotNull DataInputStream data
    ) throws IOException {
        UUID operationId = readUuid(data);
        UUID accountId = readUuid(data);
        UUID equipmentId = readUuid(data);
        UUID orbEntryId = readUuid(data);
        String orbItemId = readString(data);
        int baseEnhanceLevel = data.readInt();
        int baseTranscendenceRank = data.readInt();
        int enhanceLevel = data.readInt();
        boolean enhancementSucceeded = data.readBoolean();
        return new LocalMutationCommand.EquipmentOrb(
            operationId, accountId, equipmentId, orbEntryId, orbItemId,
            baseEnhanceLevel, baseTranscendenceRank, enhanceLevel,
            enhancementSucceeded);
    }

    private static @NotNull LocalMutationCommand.SkillLevelUp readSkillLevelUp(
        @NotNull DataInputStream data
    ) throws IOException {
        UUID operationId = readUuid(data);
        UUID accountId = readUuid(data);
        UUID learnedSkillId = readUuid(data);
        UUID updatedBy = readUuid(data);
        int expectedLevel = data.readInt();
        int targetLevel = data.readInt();
        int expectedVersion = data.readInt();
        int targetVersion = data.readInt();
        int count = readListSize(data);
        List<LocalMutationCommand.Payment> payments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            payments.add(new LocalMutationCommand.Payment(readUuid(data), data.readLong()));
        }
        return new LocalMutationCommand.SkillLevelUp(
            operationId, accountId, learnedSkillId, updatedBy,
            expectedLevel, targetLevel, expectedVersion, targetVersion, payments);
    }

    private static int readListSize(@NotNull DataInputStream data) throws IOException {
        int count = data.readInt();
        if (count < 0 || count > MAX_LIST_SIZE) {
            throw new IOException("Invalid local mutation list size: " + count);
        }
        return count;
    }

    private static void writeUuid(@NotNull DataOutputStream data, @NotNull UUID value) throws IOException {
        data.writeLong(value.getMostSignificantBits());
        data.writeLong(value.getLeastSignificantBits());
    }

    private static @NotNull UUID readUuid(@NotNull DataInputStream data) throws IOException {
        return new UUID(data.readLong(), data.readLong());
    }

    private static void writeString(@NotNull DataOutputStream data, @NotNull String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Local mutation string is too long.");
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static @NotNull String readString(@NotNull DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid local mutation string length.");
        byte[] bytes = data.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Unexpected end of local mutation string.");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static @NotNull String failureReason(@NotNull Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public enum Delivery {
        ACK,
        RETRY,
    }

    @FunctionalInterface
    public interface Dispatcher {
        @NotNull CompletionStage<Delivery> dispatch(@NotNull LocalMutationCommand command);
    }
}
