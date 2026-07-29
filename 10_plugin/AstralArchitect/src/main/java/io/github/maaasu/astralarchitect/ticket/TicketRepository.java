package io.github.maaasu.astralarchitect.ticket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * チケットディレクトリとticket.jsonの永続化を担当します。
 */
public final class TicketRepository {

    private static final Pattern SAFE_TICKET_ID = Pattern.compile("[a-z0-9][a-z0-9-]{7,79}");
    private static final DateTimeFormatter TRASH_SUFFIX = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final Path ticketsRoot;
    private final Path trashRoot;
    private final Path locksRoot;
    private final Gson gson;

    /**
     * リポジトリを作成します。
     *
     * @param dataFolder プラグインデータフォルダ
     */
    public TicketRepository(Path dataFolder) {
        Path normalizedDataFolder = dataFolder.toAbsolutePath().normalize();
        this.ticketsRoot = normalizedDataFolder.resolve("tickets");
        this.trashRoot = normalizedDataFolder.resolve("trash");
        this.locksRoot = normalizedDataFolder.resolve(TicketFiles.LOCKS_DIRECTORY);
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    /**
     * 必要な永続化ディレクトリを作成します。
     *
     * @throws IOException ディレクトリ作成に失敗した場合
     */
    public void initialize() throws IOException {
        Files.createDirectories(ticketsRoot);
        Files.createDirectories(trashRoot);
        Files.createDirectories(locksRoot);
        if (Files.isSymbolicLink(locksRoot) || !Files.isDirectory(locksRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lock directory must be a regular non-link directory: " + locksRoot);
        }
        ensureLockFile(locksRoot.resolve(TicketFiles.WORKER_LOCK));
    }

    /**
     * チケットIDの安全性を検証します。
     *
     * @param ticketId チケットID
     * @return 安全なIDの場合はtrue
     */
    public static boolean isSafeTicketId(String ticketId) {
        return ticketId != null && SAFE_TICKET_ID.matcher(ticketId).matches();
    }

    /**
     * 新しいチケットディレクトリを作成します。
     *
     * @param ticketId チケットID
     * @return 作成した絶対パス
     * @throws IOException 作成に失敗した場合
     */
    public Path createTicketDirectory(String ticketId) throws IOException {
        Path ticketPath = resolveTicketPath(ticketId);
        Files.createDirectory(ticketPath);
        Path lockPath = resolveLockPath(ticketId);
        try {
            Files.createDirectory(ticketPath.resolve(TicketFiles.ATTACHMENTS));
            createLockFile(lockPath);
            return ticketPath;
        } catch (IOException | RuntimeException exception) {
            try {
                deleteTree(ticketPath);
                Files.deleteIfExists(lockPath);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    /**
     * チケットIDから既存パスを取得します。
     *
     * @param ticketId チケットID
     * @return チケットパス
     * @throws IOException IDが不正またはチケットが存在しない場合
     */
    public Path requireTicketPath(String ticketId) throws IOException {
        Path path = resolveTicketPath(ticketId);
        if (!Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            throw new IOException("Ticket does not exist: " + ticketId);
        }
        return path;
    }

    /**
     * candidate.schemに関係する処理のプロセス間排他を取得します。
     * Python CLIも同じ固定ファイルの先頭1バイトをロックします。
     *
     * @param ticketPath チケットディレクトリ
     * @return 解放時にcloseするロック
     * @throws IOException 他プロセスが編集中、またはロック取得に失敗した場合
     */
    TicketFileLock acquireCandidateLock(Path ticketPath) throws IOException {
        Path normalized = requireOwnedDirectory(ticketPath, ticketsRoot);
        Path lockPath = resolveLockPath(normalized.getFileName().toString());
        return acquireManagedLock(lockPath, "candidate.schem is being edited by another process");
    }

    /**
     * プラグイン再読込をまたいだworker処理を直列化します。
     *
     * @return 解放時にcloseするロック
     * @throws IOException 別workerが処理中、またはロック取得に失敗した場合
     */
    TicketFileLock acquireWorkerLock() throws IOException {
        return acquireManagedLock(
                locksRoot.resolve(TicketFiles.WORKER_LOCK),
                "another AstralArchitect worker is still processing a ticket");
    }

    private static TicketFileLock acquireManagedLock(Path lockPath, String busyMessage) throws IOException {
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockPath)) {
            throw new IOException("Managed lock is missing or is not a regular file: " + lockPath);
        }
        FileChannel channel = FileChannel.open(lockPath, WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.size() < 1L) {
                throw new IOException("Managed lock file is invalid: " + lockPath);
            }
            FileLock lock = channel.tryLock(0L, 1L, false);
            if (lock == null) {
                throw new IOException(busyMessage);
            }
            return new TicketFileLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IOException(busyMessage, exception);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    /**
     * チケットのメタデータを読み込みます。
     *
     * @param ticketId チケットID
     * @return メタデータ
     * @throws IOException 読み込みまたは契約検証に失敗した場合
     */
    public TicketMetadata read(String ticketId) throws IOException {
        TicketMetadata metadata = readFromDirectory(requireTicketPath(ticketId));
        if (!ticketId.equals(metadata.id())) {
            throw new IOException("Ticket id does not match its directory: " + ticketId);
        }
        return metadata;
    }

    /**
     * 操作中チケットを列挙します。
     *
     * @return ID順のメタデータ一覧
     * @throws IOException 列挙に失敗した場合
     */
    public List<TicketMetadata> list() throws IOException {
        List<TicketMetadata> tickets = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ticketsRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                try {
                    TicketMetadata metadata = readFromDirectory(entry);
                    if (entry.getFileName().toString().equals(metadata.id())) {
                        tickets.add(metadata);
                    }
                } catch (IOException ignored) {
                    // 壊れたチケットはinfoで個別確認できるよう、一覧表示だけから除外します。
                }
            }
        }
        tickets.sort(Comparator.comparing(TicketMetadata::id));
        return List.copyOf(tickets);
    }

    /**
     * メタデータを一時ファイル経由で原子的に保存します。
     *
     * @param ticketPath チケットディレクトリ
     * @param metadata 保存するメタデータ
     * @throws IOException 書き込みに失敗した場合
     */
    public void write(Path ticketPath, TicketMetadata metadata) throws IOException {
        Path normalizedTicketPath = requireOwnedDirectory(ticketPath, ticketsRoot, trashRoot);
        Path destination = normalizedTicketPath.resolve(TicketFiles.METADATA);
        Path temporary = Files.createTempFile(normalizedTicketPath, ".ticket-json-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    WRITE,
                    TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                gson.toJson(metadata, writer);
            }
            moveReplacing(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * source.schemをcandidate.schemへ複製します。
     *
     * @param ticketPath チケットディレクトリ
     * @throws IOException 複製に失敗した場合
     */
    public void initializeCandidate(Path ticketPath) throws IOException {
        Path source = ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC);
        requireRegularFile(source, TicketFiles.SOURCE_SCHEMATIC);
        Files.copy(
                source,
                ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC),
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * 検証済みcandidateをロールバック用applied.schemへ原子的に固定します。
     *
     * @param ticketPath チケットディレクトリ
     * @throws IOException 複製に失敗した場合
     */
    public void snapshotAppliedCandidate(Path ticketPath) throws IOException {
        Path source = ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC);
        requireRegularFile(source, TicketFiles.CANDIDATE_SCHEMATIC);
        Path temporary = Files.createTempFile(ticketPath, ".applied-schem-", ".tmp");
        Path destination = ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC);
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 未完了の適用スナップショットを削除します。
     *
     * @param ticketPath チケットディレクトリ
     * @throws IOException 削除に失敗した場合
     */
    public void deleteAppliedSnapshot(Path ticketPath) throws IOException {
        Files.deleteIfExists(ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC));
        Files.deleteIfExists(ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC + ".tmp"));
    }

    /**
     * ファイルのSHA-256を計算します。
     *
     * @param path 対象ファイル
     * @return 小文字16進表現
     * @throws IOException 読み込みに失敗した場合
     */
    public String sha256(Path path) throws IOException {
        requireRegularFile(path, path.getFileName().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * チケットをtrashへ移動します。
     *
     * @param ticketId チケットID
     * @param now 操作時刻
     * @return trash内の移動先
     * @throws IOException 移動に失敗した場合
     */
    public Path moveToTrash(String ticketId, Instant now) throws IOException {
        Path source = requireTicketPath(ticketId);
        TicketMetadata original = read(ticketId);
        TicketMetadata trashed = original.trashed(now.toString());
        if (trashed != original) {
            write(source, trashed);
        }
        Path destination = trashRoot.resolve(ticketId + "--" + TRASH_SUFFIX.format(now));
        if (Files.exists(destination)) {
            destination = trashRoot.resolve(ticketId + "--" + TRASH_SUFFIX.format(now)
                    + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        try {
            return Files.move(source, destination);
        } catch (IOException exception) {
            try {
                write(source, original);
            } catch (IOException compensationException) {
                exception.addSuppressed(compensationException);
            }
            throw exception;
        }
    }

    /**
     * trashにある最新の同一IDチケットを復元します。
     *
     * @param ticketId チケットID
     * @param now 復元時刻
     * @return 復元したメタデータ
     * @throws IOException 対象不在または復元失敗の場合
     */
    public TicketMetadata restoreFromTrash(String ticketId, Instant now) throws IOException {
        if (!isSafeTicketId(ticketId)) {
            throw new IOException("Unsafe ticket id: " + ticketId);
        }
        if (Files.exists(resolveTicketPath(ticketId))) {
            throw new IOException("Active ticket already exists: " + ticketId);
        }
        Path trashedPath = findLatestTrashPath(ticketId);
        TicketMetadata trashed = readFromDirectory(trashedPath);
        requireMetadataId(ticketId, trashed, trashedPath);
        TicketMetadata restored = trashed.restored(now.toString());
        if (restored != trashed) {
            write(trashedPath, restored);
        }
        try {
            Files.move(trashedPath, resolveTicketPath(ticketId));
        } catch (IOException exception) {
            try {
                write(trashedPath, trashed);
            } catch (IOException compensationException) {
                exception.addSuppressed(compensationException);
            }
            throw exception;
        }
        return restored;
    }

    /**
     * trashにある最新の同一IDチケットを読み込みます。
     *
     * @param ticketId チケットID
     * @return trash内メタデータ
     * @throws IOException 対象不在または読み込み失敗の場合
     */
    public TicketMetadata readLatestTrash(String ticketId) throws IOException {
        Path trashPath = findLatestTrashPath(ticketId);
        TicketMetadata metadata = readFromDirectory(trashPath);
        requireMetadataId(ticketId, metadata, trashPath);
        return metadata;
    }

    /**
     * 保持期間を過ぎたtrashディレクトリを削除します。
     *
     * @param retention 保持期間
     * @param now 基準時刻
     * @return 削除件数
     * @throws IOException 列挙または削除に失敗した場合
     */
    public int purgeExpiredTrash(Duration retention, Instant now) throws IOException {
        int deleted = 0;
        Instant threshold = now.minus(retention);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trashRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                Instant deletedAt = deletedAtOrLastModified(entry);
                if (!deletedAt.isAfter(threshold)) {
                    String trashDirectoryName = entry.getFileName().toString();
                    deleteTree(entry);
                    deleteUnusedLock(trashDirectoryName);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * 作成失敗したチケットだけを安全に削除します。
     *
     * @param ticketPath 削除対象
     * @throws IOException 削除に失敗した場合
     */
    public void deleteFailedTicket(Path ticketPath) throws IOException {
        Path normalized = ticketPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(ticketsRoot) || normalized.equals(ticketsRoot)) {
            throw new IOException("Refusing to delete path outside tickets root: " + normalized);
        }
        String ticketId = normalized.getFileName().toString();
        if (!isSafeTicketId(ticketId)) {
            throw new IOException("Refusing to delete lock for unsafe ticket id: " + ticketId);
        }
        deleteTree(normalized);
        Files.deleteIfExists(resolveLockPath(ticketId));
    }

    private TicketMetadata readFromDirectory(Path ticketPath) throws IOException {
        Path metadataPath = ticketPath.resolve(TicketFiles.METADATA);
        requireRegularFile(metadataPath, TicketFiles.METADATA);
        if (Files.size(metadataPath) > 1024L * 1024L) {
            throw new IOException("ticket.json exceeds 1 MiB: " + metadataPath);
        }
        try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
            TicketMetadata metadata = gson.fromJson(reader, TicketMetadata.class);
            if (metadata == null || !isSafeTicketId(metadata.id())) {
                throw new IOException("Invalid ticket metadata: " + metadataPath);
            }
            if (metadata.schemaVersion() != TicketMetadata.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported ticket schema version: " + metadata.schemaVersion());
            }
            validateMetadata(metadata, metadataPath);
            return metadata;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid ticket metadata: " + metadataPath, exception);
        }
    }

    private Path resolveTicketPath(String ticketId) throws IOException {
        if (!isSafeTicketId(ticketId)) {
            throw new IOException("Unsafe ticket id: " + ticketId);
        }
        Path resolved = ticketsRoot.resolve(ticketId).normalize();
        if (!resolved.startsWith(ticketsRoot)) {
            throw new IOException("Ticket path escaped root: " + ticketId);
        }
        return resolved;
    }

    private Path resolveLockPath(String ticketId) throws IOException {
        if (!isSafeTicketId(ticketId)) {
            throw new IOException("Unsafe ticket id: " + ticketId);
        }
        Path resolved = locksRoot.resolve(ticketId + ".lock").normalize();
        if (!resolved.startsWith(locksRoot) || resolved.equals(locksRoot)) {
            throw new IOException("Lock path escaped root: " + ticketId);
        }
        return resolved;
    }

    private static void createLockFile(Path lockPath) throws IOException {
        try (FileChannel channel = FileChannel.open(
                lockPath,
                CREATE_NEW,
                WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            int written = channel.write(ByteBuffer.wrap(new byte[]{0}), 0L);
            if (written != 1) {
                throw new IOException("Failed to initialize candidate lock: " + lockPath);
            }
            channel.force(true);
        }
    }

    private static void ensureLockFile(Path lockPath) throws IOException {
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createLockFile(lockPath);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another plugin instance completed initialization first.
            }
        }
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(lockPath)
                || Files.size(lockPath) < 1L) {
            throw new IOException("Managed lock file is invalid: " + lockPath);
        }
    }

    private Path findLatestTrashPath(String ticketId) throws IOException {
        if (!isSafeTicketId(ticketId)) {
            throw new IOException("Unsafe ticket id: " + ticketId);
        }
        Optional<Path> latest;
        try (Stream<Path> entries = Files.list(trashRoot)) {
            latest = entries
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().startsWith(ticketId + "--"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
        }
        Path path = latest.orElseThrow(() -> new IOException("Trashed ticket does not exist: " + ticketId));
        return requireOwnedDirectory(path, trashRoot);
    }

    private void deleteUnusedLock(String trashDirectoryName) throws IOException {
        int separator = trashDirectoryName.indexOf("--");
        if (separator <= 0) {
            return;
        }
        String ticketId = trashDirectoryName.substring(0, separator);
        if (!isSafeTicketId(ticketId) || Files.exists(resolveTicketPath(ticketId))) {
            return;
        }
        try (Stream<Path> entries = Files.list(trashRoot)) {
            if (entries.anyMatch(path -> path.getFileName().toString().startsWith(ticketId + "--"))) {
                return;
            }
        }
        Files.deleteIfExists(resolveLockPath(ticketId));
    }

    private static Path requireOwnedDirectory(Path path, Path... roots) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root) && !normalized.equals(root)) {
                if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
                    throw new IOException("Ticket directory does not exist: " + normalized);
                }
                return normalized;
            }
        }
        throw new IOException("Path is outside repository roots: " + normalized);
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(label + " must be a regular non-link file: " + path);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Instant deletedAtOrLastModified(Path ticketPath) throws IOException {
        try {
            TicketMetadata metadata = readFromDirectory(ticketPath);
            if (metadata.deletedAt() != null) {
                return Instant.parse(metadata.deletedAt());
            }
        } catch (IOException | RuntimeException ignored) {
            // 壊れた時刻だけを理由にtrash全体の定期整理を止めないため、mtimeへフォールバックします。
        }
        FileTime lastModified = Files.getLastModifiedTime(ticketPath);
        return lastModified.toInstant();
    }

    private static void validateMetadata(TicketMetadata metadata, Path metadataPath) throws IOException {
        try {
            if (metadata.name() == null || metadata.name().isBlank()
                    || metadata.state() == null
                    || metadata.ownerName() == null || metadata.ownerName().isBlank()
                    || metadata.worldName() == null || metadata.worldName().isBlank()
                    || metadata.bounds() == null
                    || metadata.bounds().min() == null
                    || metadata.bounds().max() == null
                    || metadata.anchor() == null
                    || metadata.anchorBlockState() == null || metadata.anchorBlockState().isBlank()) {
                throw new IllegalArgumentException("required field is missing");
            }
            UUID.fromString(metadata.ownerUuid());
            UUID.fromString(metadata.worldUuid());
            if (metadata.blockCount() != metadata.bounds().volume()) {
                throw new IllegalArgumentException("blockCount does not match bounds");
            }
            if (!metadata.bounds().contains(metadata.anchor())) {
                throw new IllegalArgumentException("anchor is outside bounds");
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid ticket metadata contract: " + metadataPath, exception);
        }
    }

    private static void requireMetadataId(
            String expectedId,
            TicketMetadata metadata,
            Path metadataDirectory) throws IOException {
        if (!expectedId.equals(metadata.id())) {
            throw new IOException("Ticket id does not match trash directory: " + metadataDirectory);
        }
    }
}
