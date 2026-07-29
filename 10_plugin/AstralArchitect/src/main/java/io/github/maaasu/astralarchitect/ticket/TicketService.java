package io.github.maaasu.astralarchitect.ticket;

import io.github.maaasu.astralarchitect.config.ArchitectConfig;
import io.github.maaasu.astralarchitect.worldedit.CandidateAnalysis;
import io.github.maaasu.astralarchitect.worldedit.SchematicService;
import io.github.maaasu.astralarchitect.worldedit.WorldEditSelection;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * チケットの状態遷移とSchematic操作を単一の非同期キューで直列化します。
 */
public final class TicketService implements AutoCloseable {

    private static final DateTimeFormatter TICKET_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final TicketRepository repository;
    private final SchematicService schematicService;
    private final AtomicReference<ArchitectConfig> config;
    private final ExecutorService executor;
    private final Logger logger;

    /**
     * チケットサービスを作成します。
     *
     * @param repository チケット永続化先
     * @param schematicService Schematic操作サービス
     * @param initialConfig 初期設定
     * @param logger プラグインロガー
     */
    public TicketService(
            TicketRepository repository,
            SchematicService schematicService,
            ArchitectConfig initialConfig,
            Logger logger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.schematicService = Objects.requireNonNull(schematicService, "schematicService");
        this.config = new AtomicReference<>(Objects.requireNonNull(initialConfig, "initialConfig"));
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AstralArchitect-TicketWorker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 以降の処理で使用する設定を原子的に更新します。
     *
     * @param newConfig 検証済みの新設定
     */
    public void updateConfig(ArchitectConfig newConfig) {
        config.set(Objects.requireNonNull(newConfig, "newConfig"));
    }

    /**
     * 選択範囲から新しいチケットを作成します。
     *
     * @param selection メインスレッドで確定済みの選択情報
     * @param name チケット表示名
     * @param ownerUuid 所有プレイヤーUUID
     * @param ownerName 所有プレイヤー名
     * @param minecraftVersion Minecraftバージョン
     * @param faweVersion FAWEバージョン
     * @return 作成結果Future
     */
    public CompletableFuture<TicketOperationResult> create(
            WorldEditSelection selection,
            String name,
            UUID ownerUuid,
            String ownerName,
            String minecraftVersion,
            String faweVersion) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String normalizedName = validateTicketName(name);
        String capturedOwnerName = Objects.requireNonNull(ownerName, "ownerName");
        String capturedMinecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        String capturedFaweVersion = Objects.requireNonNull(faweVersion, "faweVersion");

        return submit(() -> {
            Instant now = Instant.now();
            String ticketId = createTicketId(now);
            Path ticketPath = repository.createTicketDirectory(ticketId);
            try {
                TicketMetadata creating = TicketMetadata.creating(
                        ticketId,
                        normalizedName,
                        ownerUuid.toString(),
                        capturedOwnerName,
                        selection.worldUuid(),
                        selection.worldName(),
                        selection.bounds(),
                        selection.anchor(),
                        selection.anchorBlockState(),
                        selection.bounds().volume(),
                        capturedMinecraftVersion,
                        capturedFaweVersion,
                        now.toString());
                repository.write(ticketPath, creating);
                Path source = ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC);
                Path candidate = ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC);
                ensureWorkerActive();
                schematicService.writeSelection(selection, source);
                ensureWorkerActive();
                repository.initializeCandidate(ticketPath);
                String sourceHash = repository.sha256(source);
                String candidateHash = repository.sha256(candidate);
                TicketMetadata created = creating.created(sourceHash, candidateHash, Instant.now().toString());
                repository.write(ticketPath, created);
                return new TicketOperationResult(created, created.blockCount());
            } catch (Exception exception) {
                try {
                    repository.deleteFailedTicket(ticketPath);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
                throw exception;
            }
        });
    }

    /**
     * 操作中チケットを一覧取得します。
     *
     * @return チケット一覧Future
     */
    public CompletableFuture<List<TicketMetadata>> list() {
        return submit(repository::list);
    }

    /**
     * 操作中チケットのメタデータを取得します。
     *
     * @param ticketId チケットID
     * @return メタデータFuture
     */
    public CompletableFuture<TicketMetadata> read(String ticketId) {
        return submit(() -> repository.read(ticketId));
    }

    /**
     * trash内の最新チケットメタデータを取得します。
     *
     * @param ticketId チケットID
     * @return メタデータFuture
     */
    public CompletableFuture<TicketMetadata> readLatestTrash(String ticketId) {
        return submit(() -> repository.readLatestTrash(ticketId));
    }

    /**
     * AI候補を検証しREADY状態へ更新します。
     *
     * @param ticketId チケットID
     * @return 検証結果Future
     */
    public CompletableFuture<TicketOperationResult> validate(String ticketId) {
        return submit(() -> {
            ArchitectConfig currentConfig = config.get();
            Path ticketPath = repository.requireTicketPath(ticketId);
            try (TicketFileLock candidateLock = repository.acquireCandidateLock(ticketPath)) {
                candidateLock.requireHeld();
                TicketMetadata metadata = repository.read(ticketId);
                if (!metadata.state().canValidate()) {
                    throw new IllegalStateException("現在の状態では検証できません: " + metadata.state());
                }
                Path source = ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC);
                Path candidate = ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC);
                requireSourceHash(metadata, source);
                String candidateHashBefore = repository.sha256(candidate);
                CandidateAnalysis analysis = schematicService.analyze(
                        source,
                        candidate,
                        metadata,
                        currentConfig.maxChangedBlockCount(),
                        currentConfig.forbiddenBlockTypes());
                requireStableHash("source.schem", metadata.sourceSha256(), repository.sha256(source));
                requireStableHash("candidate.schem", candidateHashBefore, repository.sha256(candidate));
                ensureWorkerActive();
                TicketMetadata ready = metadata.ready(
                        candidateHashBefore,
                        analysis.changedBlockCount(),
                        Instant.now().toString());
                repository.write(ticketPath, ready);
                return new TicketOperationResult(ready, analysis.changedBlockCount());
            }
        });
    }

    /**
     * 検証済み候補を対象ワールドへ差分適用します。
     *
     * @param ticketId チケットID
     * @param world 対象ワールド
     * @return 適用結果Future
     */
    public CompletableFuture<TicketOperationResult> apply(String ticketId, World world) {
        Objects.requireNonNull(world, "world");
        String suppliedWorldUuid = world.getUID().toString();
        return submit(() -> {
            ArchitectConfig currentConfig = config.get();
            Path ticketPath = repository.requireTicketPath(ticketId);
            try (TicketFileLock candidateLock = repository.acquireCandidateLock(ticketPath)) {
                candidateLock.requireHeld();
                TicketMetadata metadata = repository.read(ticketId);
                if (metadata.state() != TicketState.READY && metadata.state() != TicketState.APPLYING) {
                    throw new IllegalStateException(
                            "適用には状態READYまたはAPPLYINGが必要です。現在: " + metadata.state());
                }
                requireWorld(metadata, suppliedWorldUuid);

                Path source = ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC);
                requireSourceHash(metadata, source);
                Path appliedPath = ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC);
                final String candidateHash;
                final CandidateAnalysis analysis;
                if (metadata.state() == TicketState.READY) {
                    Path candidate = ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC);
                    candidateHash = repository.sha256(candidate);
                    requireStableHash("candidate.schem", metadata.candidateSha256(), candidateHash);
                    analysis = schematicService.analyze(
                            source,
                            candidate,
                            metadata,
                            currentConfig.maxChangedBlockCount(),
                            currentConfig.forbiddenBlockTypes());
                    requireExpectedChangeCount(metadata, analysis);
                    requireStableHash("source.schem", metadata.sourceSha256(), repository.sha256(source));
                    requireStableHash("candidate.schem", candidateHash, repository.sha256(candidate));
                    try {
                        repository.snapshotAppliedCandidate(ticketPath);
                        requireStableHash("applied.schem", candidateHash, repository.sha256(appliedPath));
                        schematicService.verifyExpectedWorld(world, metadata.bounds(), analysis, false);
                        ensureWorkerActive();
                        metadata = metadata.applying(candidateHash, Instant.now().toString());
                        repository.write(ticketPath, metadata);
                    } catch (Exception exception) {
                        deleteAppliedSnapshotQuietly(ticketPath, exception);
                        throw exception;
                    }
                } else {
                    if (metadata.appliedCandidateSha256() == null) {
                        throw new IllegalStateException("再開用applied.schemのSHA-256が記録されていません。");
                    }
                    candidateHash = repository.sha256(appliedPath);
                    requireStableHash("applied.schem", metadata.appliedCandidateSha256(), candidateHash);
                    analysis = schematicService.analyze(
                            source,
                            appliedPath,
                            metadata,
                            metadata.blockCount(),
                            Set.of());
                    requireExpectedChangeCount(metadata, analysis);
                }
                requireStableHash("source.schem", metadata.sourceSha256(), repository.sha256(source));
                requireStableHash("applied.schem", candidateHash, repository.sha256(appliedPath));
                ensureWorkerActive();
                int affected = schematicService.converge(world, metadata.bounds(), analysis, false);
                ensureWorkerActive();
                TicketMetadata applied = metadata.applied(candidateHash, Instant.now().toString());
                repository.write(ticketPath, applied);
                return new TicketOperationResult(applied, affected);
            }
        });
    }

    /**
     * 適用時に固定した候補との差分をsourceへ戻します。
     *
     * @param ticketId チケットID
     * @param world 対象ワールド
     * @return ロールバック結果Future
     */
    public CompletableFuture<TicketOperationResult> rollback(String ticketId, World world) {
        Objects.requireNonNull(world, "world");
        String suppliedWorldUuid = world.getUID().toString();
        return submit(() -> {
            Path ticketPath = repository.requireTicketPath(ticketId);
            try (TicketFileLock candidateLock = repository.acquireCandidateLock(ticketPath)) {
                candidateLock.requireHeld();
                TicketMetadata metadata = repository.read(ticketId);
                if (metadata.state() != TicketState.APPLIED && metadata.state() != TicketState.ROLLING_BACK) {
                    throw new IllegalStateException(
                            "ロールバックには状態APPLIEDまたはROLLING_BACKが必要です。現在: " + metadata.state());
                }
                requireWorld(metadata, suppliedWorldUuid);
                if (metadata.appliedCandidateSha256() == null) {
                    throw new IllegalStateException("適用済み候補のSHA-256が記録されていません。");
                }

                Path source = ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC);
                Path applied = ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC);
                requireSourceHash(metadata, source);
                String appliedHash = repository.sha256(applied);
                requireStableHash("applied.schem", metadata.appliedCandidateSha256(), appliedHash);
                CandidateAnalysis analysis = schematicService.analyze(
                        source,
                        applied,
                        metadata,
                        metadata.blockCount(),
                        Set.of());
                requireExpectedChangeCount(metadata, analysis);
                requireStableHash("source.schem", metadata.sourceSha256(), repository.sha256(source));
                requireStableHash("applied.schem", appliedHash, repository.sha256(applied));

                if (metadata.state() == TicketState.APPLIED) {
                    schematicService.verifyExpectedWorld(world, metadata.bounds(), analysis, true);
                    ensureWorkerActive();
                    metadata = metadata.rollingBack(Instant.now().toString());
                    repository.write(ticketPath, metadata);
                }
                ensureWorkerActive();
                int affected = schematicService.converge(world, metadata.bounds(), analysis, true);
                ensureWorkerActive();
                TicketMetadata rolledBack = metadata.rolledBack(Instant.now().toString());
                repository.write(ticketPath, rolledBack);
                try {
                    repository.deleteAppliedSnapshot(ticketPath);
                } catch (IOException exception) {
                    logger.log(
                            Level.WARNING,
                            "ロールバック済みapplied.schemの削除に失敗しました: " + ticketId,
                            exception);
                }
                return new TicketOperationResult(rolledBack, affected);
            }
        });
    }

    /**
     * チケットを復元可能なtrashへ移動します。
     *
     * @param ticketId チケットID
     * @return 削除結果Future
     */
    public CompletableFuture<TicketOperationResult> trash(String ticketId) {
        return submit(() -> {
            Path ticketPath = repository.requireTicketPath(ticketId);
            try (TicketFileLock candidateLock = repository.acquireCandidateLock(ticketPath)) {
                candidateLock.requireHeld();
                TicketMetadata current = repository.read(ticketId);
                if (current.state() == TicketState.APPLYING || current.state() == TicketState.ROLLING_BACK) {
                    throw new IllegalStateException("中断中の処理を同じapply/rollbackコマンドで完了してから削除してください。");
                }
                Instant now = Instant.now();
                repository.moveToTrash(ticketId, now);
                return new TicketOperationResult(current.trashed(now.toString()), 0L);
            }
        });
    }

    /**
     * trash内の最新チケットを操作中ディレクトリへ復元します。
     *
     * @param ticketId チケットID
     * @return 復元結果Future
     */
    public CompletableFuture<TicketOperationResult> restore(String ticketId) {
        return submit(() -> {
            TicketMetadata restored = repository.restoreFromTrash(ticketId, Instant.now());
            return new TicketOperationResult(restored, 0L);
        });
    }

    /**
     * 現在設定された保持期間を過ぎたtrashを削除します。
     *
     * @return 完全削除件数Future
     */
    public CompletableFuture<Integer> purgeExpiredTrash() {
        return submit(() -> repository.purgeExpiredTrash(config.get().trashRetention(), Instant.now()));
    }

    /**
     * 新しい処理受付を停止し、実行中キューの終了を待ちます。
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                    logger.severe("チケットworkerが停止要求後も終了しませんでした。次回起動時に中間状態を確認してください。");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try (TicketFileLock workerLock = repository.acquireWorkerLock()) {
                workerLock.requireHeld();
                return action.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    private static String validateTicketName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("チケット名を指定してください。");
        }
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("チケット名は1文字以上64文字以下で指定してください。");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("チケット名に制御文字は使用できません。");
        }
        return normalized;
    }

    private static String createTicketId(Instant now) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return TICKET_TIME.format(now) + "-" + random;
    }

    private void requireSourceHash(TicketMetadata metadata, Path source) throws IOException {
        if (metadata.sourceSha256() == null) {
            throw new IllegalStateException("source.schemのSHA-256が記録されていません。");
        }
        requireStableHash("source.schem", metadata.sourceSha256(), repository.sha256(source));
    }

    private static void requireStableHash(String fileName, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(fileName + "のSHA-256が一致しません。ファイルが処理中に変更された可能性があります。");
        }
    }

    private static void requireWorld(TicketMetadata metadata, String suppliedWorldUuid) {
        if (!Objects.equals(metadata.worldUuid(), suppliedWorldUuid)) {
            throw new IllegalStateException("チケット作成元と異なるワールドには適用できません。");
        }
    }

    private static void requireExpectedChangeCount(TicketMetadata metadata, CandidateAnalysis analysis) {
        if (metadata.changedBlockCount() != analysis.changedBlockCount()) {
            throw new IllegalStateException("検証時と変更ブロック数が一致しません。再度validateしてください。");
        }
    }

    private static void ensureWorkerActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("チケット処理がプラグイン停止要求により中断されました。");
        }
    }

    private void deleteAppliedSnapshotQuietly(Path ticketPath, Exception original) {
        try {
            repository.deleteAppliedSnapshot(ticketPath);
        } catch (IOException cleanupException) {
            original.addSuppressed(cleanupException);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
