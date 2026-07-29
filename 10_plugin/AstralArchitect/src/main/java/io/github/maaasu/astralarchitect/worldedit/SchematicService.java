package io.github.maaasu.astralarchitect.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import io.github.maaasu.astralarchitect.ticket.BlockPosition;
import io.github.maaasu.astralarchitect.ticket.TicketBounds;
import io.github.maaasu.astralarchitect.ticket.TicketMetadata;
import org.bukkit.World;
import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinRootEntry;
import org.enginehub.linbus.tree.LinTagType;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Sponge Schematic v3の作成、比較、FAWE差分適用を担当します。
 */
public final class SchematicService {

    private static final Pattern BLOCK_STATE_PATTERN = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_]+=[a-z0-9_.-]+"
                    + "(?:,[a-z0-9_]+=[a-z0-9_.-]+)*])?");
    private static final long MIN_NBT_LIMIT = 16L * 1024L * 1024L;
    private static final long MAX_NBT_LIMIT = 512L * 1024L * 1024L;

    /**
     * WorldEdit選択をSponge Schematic v3として保存します。
     * EntityとBiomeはコピーしません。Block Entityは元データ保全のため保存されますが、変更は禁止されます。
     *
     * @param selection 保存する選択
     * @param destination 出力ファイル
     * @throws IOException ファイル入出力に失敗した場合
     * @throws WorldEditException WorldEditのコピーに失敗した場合
     */
    public void writeSelection(WorldEditSelection selection, Path destination)
            throws IOException, WorldEditException {
        BlockArrayClipboard clipboard = new BlockArrayClipboard(selection.region());
        clipboard.setOrigin(toVector(selection.anchor()));
        try {
            ForwardExtentCopy copy = new ForwardExtentCopy(
                    selection.world(),
                    selection.region(),
                    clipboard,
                    selection.region().getMinimumPoint());
            copy.setCopyingEntities(false);
            copy.setCopyingBiomes(false);
            Operations.complete(copy);
            clipboard.flush();

            try (OutputStream output = Files.newOutputStream(destination);
                 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output)) {
                writer.write(clipboard);
            }
        } finally {
            clipboard.close();
        }
    }

    /**
     * sourceとcandidateを比較し、初期版の安全条件を満たす差分を返します。
     *
     * @param sourcePath 不変な元Schematic
     * @param candidatePath AI候補Schematic
     * @param metadata チケット契約
     * @param maxChangedBlockCount 最大変更数
     * @param forbiddenBlockTypes 禁止ブロックID
     * @return 検証済み差分
     * @throws IOException 読み込みに失敗した場合
     * @throws CandidateValidationException 形式または変更内容が不正な場合
     */
    public CandidateAnalysis analyze(
            Path sourcePath,
            Path candidatePath,
            TicketMetadata metadata,
            long maxChangedBlockCount,
            Set<String> forbiddenBlockTypes) throws IOException, CandidateValidationException {
        int sourceDataVersion = validateRawSchematic(sourcePath, metadata);
        int candidateDataVersion = validateRawSchematic(candidatePath, metadata);
        if (sourceDataVersion != candidateDataVersion) {
            throw new CandidateValidationException(
                    "candidate.schemのDataVersionがsource.schemと一致しません。");
        }
        try (Clipboard source = read(sourcePath);
             Clipboard candidate = read(candidatePath)) {
            validateDimensions(source, candidate, metadata.bounds());
            if (!source.getRegion().getMinimumPoint().equals(candidate.getRegion().getMinimumPoint())
                    || !source.getRegion().getMaximumPoint().equals(candidate.getRegion().getMaximumPoint())) {
                throw new CandidateValidationException("candidate.schemの座標範囲がsource.schemと一致しません。");
            }
            if (!source.getOrigin().equals(toVector(metadata.anchor()))) {
                throw new CandidateValidationException("source.schemの基準点がticket.jsonと一致しません。");
            }
            if (!source.getOrigin().equals(candidate.getOrigin())) {
                throw new CandidateValidationException("candidate.schemの基準点がsource.schemと一致しません。");
            }
            if (candidate.hasBiomes()) {
                throw new CandidateValidationException("初期版ではBiomeを含む候補を適用できません。");
            }
            if (!candidate.getEntities().isEmpty()) {
                throw new CandidateValidationException("初期版ではEntityを含む候補を適用できません。");
            }

            BlockVector3 sourceMin = source.getRegion().getMinimumPoint();
            BlockVector3 candidateMin = candidate.getRegion().getMinimumPoint();
            int width = metadata.bounds().width();
            int height = metadata.bounds().height();
            int length = metadata.bounds().length();
            List<SchematicChange> changes = new ArrayList<>();

            for (int y = 0; y < height; y++) {
                ensureNotInterrupted();
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        BlockVector3 sourcePosition = sourceMin.add(x, y, z);
                        BlockVector3 candidatePosition = candidateMin.add(x, y, z);
                        BaseBlock sourceBlock = source.getFullBlock(sourcePosition);
                        BaseBlock candidateBlock = candidate.getFullBlock(candidatePosition);
                        BlockState sourceState = sourceBlock.toImmutableState();
                        BlockState candidateState = candidateBlock.toImmutableState();

                        if (sourceState.equals(candidateState)) {
                            if (!sourceBlock.equals(candidateBlock)) {
                                throw new CandidateValidationException(
                                        "Block Entityデータの変更は初期版では使用できません: "
                                                + formatRelative(x, y, z));
                            }
                            continue;
                        }
                        if (sourceState.getMaterial().isTile() || candidateState.getMaterial().isTile()) {
                            throw new CandidateValidationException(
                                    "Block Entityを持つブロックの変更は初期版では使用できません: "
                                            + formatRelative(x, y, z));
                        }
                        if (sourceBlock.getNbtReference() != null || candidateBlock.getNbtReference() != null) {
                            throw new CandidateValidationException(
                                    "NBTデータを持つブロックの変更は初期版では使用できません: "
                                            + formatRelative(x, y, z));
                        }
                        String candidateType = candidateState.getBlockType().id().toLowerCase(Locale.ROOT);
                        if (forbiddenBlockTypes.contains(candidateType)) {
                            throw new CandidateValidationException(
                                    "禁止ブロックが候補に含まれています: " + candidateType + " "
                                            + formatRelative(x, y, z));
                        }
                        changes.add(new SchematicChange(
                                new BlockPosition(x, y, z),
                                sourceState,
                                candidateState));
                        if (changes.size() > maxChangedBlockCount) {
                            throw new CandidateValidationException(
                                    "変更ブロック数が上限" + maxChangedBlockCount + "を超えました。");
                        }
                    }
                }
            }
            if (changes.isEmpty()) {
                throw new CandidateValidationException("candidate.schemにブロック変更がありません。");
            }
            return new CandidateAnalysis(List.copyOf(changes));
        }
    }

    /**
     * 初回の適用またはロールバック前に、全差分座標が開始状態と一致するか検証します。
     *
     * @param bukkitWorld 対象Bukkitワールド
     * @param bounds チケット範囲
     * @param analysis 検証済み差分
     * @param rollback trueならcandidateからsourceへ戻す前提で検証する
     * @throws WorldConflictException 現在ブロックが開始状態と異なる場合
     */
    public void verifyExpectedWorld(
            World bukkitWorld,
            TicketBounds bounds,
            CandidateAnalysis analysis,
            boolean rollback) throws WorldConflictException {
        com.sk89q.worldedit.world.World world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
        try (EditSession editSession = createEditSession(world, bounds, analysis)) {
            for (SchematicChange change : analysis.changes()) {
                ensureWorldEditNotInterrupted();
                BlockVector3 worldPosition = absolutePosition(bounds.min(), change.relativePosition());
                BlockState expected = rollback ? change.candidateState() : change.sourceState();
                BlockState current = editSession.getBlock(worldPosition);
                if (!Objects.equals(current, expected)) {
                    throw conflict(worldPosition, expected, current);
                }
            }
        }
    }

    /**
     * 中断再開可能な形で差分を完成状態へ収束させます。
     * 各座標は開始状態または完成状態だけを許可し、第三の状態が一つでもあれば変更しません。
     *
     * @param bukkitWorld 対象Bukkitワールド
     * @param bounds チケット範囲
     * @param analysis 検証済み差分
     * @param rollback trueならcandidateからsourceへ収束させる
     * @return 差分全体のブロック数
     * @throws WorldEditException FAWE編集に失敗した場合
     * @throws WorldConflictException 現在ブロックが開始・完成状態のどちらでもない場合
     */
    public int converge(World bukkitWorld, TicketBounds bounds, CandidateAnalysis analysis, boolean rollback)
            throws WorldEditException, WorldConflictException {
        com.sk89q.worldedit.world.World world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
        try (EditSession editSession = createEditSession(world, bounds, analysis)) {
            for (SchematicChange change : analysis.changes()) {
                ensureWorldEditNotInterrupted();
                BlockVector3 worldPosition = absolutePosition(bounds.min(), change.relativePosition());
                BlockState expected = rollback ? change.candidateState() : change.sourceState();
                BlockState replacement = rollback ? change.sourceState() : change.candidateState();
                BlockState current = editSession.getBlock(worldPosition);
                if (!Objects.equals(current, expected) && !Objects.equals(current, replacement)) {
                    throw conflict(worldPosition, expected, current);
                }
            }
            List<SchematicChange> completed = new ArrayList<>();
            try {
                for (SchematicChange change : analysis.changes()) {
                    ensureWorldEditNotInterrupted();
                    BlockVector3 worldPosition = absolutePosition(bounds.min(), change.relativePosition());
                    BlockState expected = rollback ? change.candidateState() : change.sourceState();
                    BlockState replacement = rollback ? change.sourceState() : change.candidateState();
                    BlockState current = editSession.getBlock(worldPosition);
                    if (Objects.equals(current, replacement)) {
                        continue;
                    }
                    if (!Objects.equals(current, expected)) {
                        throw conflict(worldPosition, expected, current);
                    }
                    boolean changed = editSession.setBlock(
                            worldPosition.x(),
                            worldPosition.y(),
                            worldPosition.z(),
                            replacement);
                    if (!changed) {
                        throw new WorldConflictException(
                                "FAWEがブロック変更を受理しませんでした: " + worldPosition);
                    }
                    completed.add(change);
                }
            } catch (WorldConflictException exception) {
                compensatePartialEdit(editSession, bounds, completed, rollback, exception);
                throw exception;
            } catch (RuntimeException exception) {
                compensatePartialEdit(editSession, bounds, completed, rollback, exception);
                throw exception;
            }
        }
        verifyExpectedWorld(bukkitWorld, bounds, analysis, !rollback);
        return analysis.changes().size();
    }

    private static EditSession createEditSession(
            com.sk89q.worldedit.world.World world,
            TicketBounds bounds,
            CandidateAnalysis analysis) {
        CuboidRegion allowedRegion = new CuboidRegion(
                world,
                toVector(bounds.min()),
                toVector(bounds.max()));
        long editsWithCompensation = Math.max(1L, analysis.changes().size() * 2L);
        int maxBlocks = (int) Math.min(Integer.MAX_VALUE, editsWithCompensation);
        return WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(world)
                .allowedRegions(new Region[]{allowedRegion})
                .maxBlocks(maxBlocks)
                .fastMode(false)
                .build();
    }

    private static WorldConflictException conflict(
            BlockVector3 worldPosition,
            BlockState expected,
            BlockState current) {
        return new WorldConflictException(
                "ワールドに競合する変更があります: " + worldPosition
                        + " expected=" + expected.getAsString()
                        + " actual=" + current.getAsString());
    }

    private static Clipboard read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             ClipboardReader reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input)) {
            return reader.read();
        }
    }

    private static int validateRawSchematic(Path path, TicketMetadata metadata)
            throws IOException, CandidateValidationException {
        long nbtLimit = calculateNbtLimit(metadata.blockCount());
        try (InputStream fileInput = Files.newInputStream(path);
             InputStream gzipInput = new GZIPInputStream(fileInput);
             DataInputStream dataInput = new DataInputStream(new LimitedInputStream(gzipInput, nbtLimit))) {
            LinRootEntry rootEntry = LinBinaryIO.readUsing(dataInput, LinRootEntry::readFrom);
            LinCompoundTag schematic = rootEntry.value().getTag("Schematic", LinTagType.compoundTag());
            int version = schematic.getTag("Version", LinTagType.intTag()).valueAsInt();
            if (version != 3) {
                throw new CandidateValidationException(path.getFileName() + "はSponge Schematic v3ではありません。");
            }
            validateRawGeometry(path, schematic, metadata);
            int dataVersion = schematic.getTag("DataVersion", LinTagType.intTag()).valueAsInt();
            if (dataVersion < 0) {
                throw new CandidateValidationException(path.getFileName() + "のDataVersionが不正です。");
            }
            LinCompoundTag blocks = schematic.getTag("Blocks", LinTagType.compoundTag());
            LinCompoundTag palette = blocks.getTag("Palette", LinTagType.compoundTag());
            Set<Integer> paletteIds = validatePalette(path, palette);
            byte[] blockData = blocks.getTag("Data", LinTagType.byteArrayTag()).value();
            validateBlockData(path, blockData, metadata.blockCount(), paletteIds);
            return dataVersion;
        } catch (CandidateValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CandidateValidationException(
                    path.getFileName() + "のNBTまたはPalette契約が不正です: "
                            + safeMessage(exception));
        }
    }

    private static Set<Integer> validatePalette(Path path, LinCompoundTag palette)
            throws CandidateValidationException {
        ParserContext parserContext = new ParserContext();
        parserContext.setRestricted(false);
        parserContext.setTryLegacy(false);
        parserContext.setPreferringWildcard(false);
        Set<Integer> paletteIds = new HashSet<>();
        for (var entry : palette.value().entrySet()) {
            String blockState = entry.getKey();
            if (!BLOCK_STATE_PATTERN.matcher(blockState).matches()) {
                throw new CandidateValidationException(
                        path.getFileName() + "のPaletteに不正なブロック状態があります: "
                                + abbreviate(blockState));
            }
            if (!(entry.getValue() instanceof LinIntTag idTag)
                    || idTag.valueAsInt() < 0
                    || !paletteIds.add(idTag.valueAsInt())) {
                throw new CandidateValidationException(
                        path.getFileName() + "のPalette IDが不正または重複しています。");
            }
            try {
                WorldEdit.getInstance().getBlockFactory().parseFromInput(blockState, parserContext);
            } catch (InputParseException exception) {
                throw new CandidateValidationException(
                        path.getFileName() + "に存在しないブロック状態があります: "
                                + abbreviate(blockState));
            }
        }
        if (paletteIds.isEmpty()) {
            throw new CandidateValidationException(path.getFileName() + "のPaletteが空です。");
        }
        int maximumId = paletteIds.stream().mapToInt(Integer::intValue).max().orElseThrow();
        if (maximumId > Character.MAX_VALUE || maximumId + 1 != paletteIds.size()) {
            throw new CandidateValidationException(
                    path.getFileName() + "のPalette IDは0から連続する65535以下の値である必要があります。");
        }
        return Set.copyOf(paletteIds);
    }

    private static void validateRawGeometry(
            Path path,
            LinCompoundTag schematic,
            TicketMetadata metadata) throws CandidateValidationException {
        int width = Short.toUnsignedInt(schematic.getTag("Width", LinTagType.shortTag()).valueAsShort());
        int height = Short.toUnsignedInt(schematic.getTag("Height", LinTagType.shortTag()).valueAsShort());
        int length = Short.toUnsignedInt(schematic.getTag("Length", LinTagType.shortTag()).valueAsShort());
        if (width != metadata.bounds().width()
                || height != metadata.bounds().height()
                || length != metadata.bounds().length()) {
            throw new CandidateValidationException(
                    path.getFileName() + "の寸法がticket.jsonと一致しません。");
        }
        long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact((long) width, height), length);
        } catch (ArithmeticException exception) {
            throw new CandidateValidationException(path.getFileName() + "の寸法が大きすぎます。");
        }
        if (volume != metadata.blockCount()) {
            throw new CandidateValidationException(
                    path.getFileName() + "の総ブロック数がticket.jsonと一致しません。");
        }

        int[] offset = schematic.getTag("Offset", LinTagType.intArrayTag()).value();
        LinCompoundTag schematicMetadata = schematic.getTag("Metadata", LinTagType.compoundTag());
        LinCompoundTag worldEditMetadata = schematicMetadata.getTag("WorldEdit", LinTagType.compoundTag());
        int[] origin = worldEditMetadata.getTag("Origin", LinTagType.intArrayTag()).value();
        if (offset.length != 3 || origin.length != 3) {
            throw new CandidateValidationException(
                    path.getFileName() + "のOffsetまたはWorldEdit Originが不正です。");
        }
        BlockPosition minimum = metadata.bounds().min();
        BlockPosition anchor = metadata.anchor();
        if (origin[0] != anchor.x() || origin[1] != anchor.y() || origin[2] != anchor.z()
                || offset[0] != Math.subtractExact(minimum.x(), anchor.x())
                || offset[1] != Math.subtractExact(minimum.y(), anchor.y())
                || offset[2] != Math.subtractExact(minimum.z(), anchor.z())) {
            throw new CandidateValidationException(
                    path.getFileName() + "の基準座標がticket.jsonと一致しません。");
        }
    }

    static void validateBlockData(
            Path path,
            byte[] blockData,
            long expectedBlockCount,
            Set<Integer> paletteIds) throws CandidateValidationException {
        long decodedCount = 0L;
        int paletteId = 0;
        int shift = 0;
        for (byte rawByte : blockData) {
            int current = Byte.toUnsignedInt(rawByte);
            int payload = current & 0x7F;
            if (shift == 28 && payload > 0x07) {
                throw invalidBlockData(path, "Palette IDが32bit符号付き整数の範囲を超えています。");
            }
            paletteId |= payload << shift;
            if ((current & 0x80) != 0) {
                shift += 7;
                if (shift > 28) {
                    throw invalidBlockData(path, "VarIntが長すぎます。");
                }
                continue;
            }
            if (!paletteIds.contains(paletteId)) {
                throw invalidBlockData(path, "存在しないPalette IDを参照しています: " + paletteId);
            }
            decodedCount++;
            if (decodedCount > expectedBlockCount) {
                throw invalidBlockData(path, "寸法より多くのブロックが含まれています。");
            }
            paletteId = 0;
            shift = 0;
        }
        if (shift != 0) {
            throw invalidBlockData(path, "終端されていないVarIntがあります。");
        }
        if (decodedCount != expectedBlockCount) {
            throw invalidBlockData(path, "ブロック数が寸法と一致しません。");
        }
    }

    private static CandidateValidationException invalidBlockData(Path path, String detail) {
        return new CandidateValidationException(path.getFileName() + "のBlocks.Dataが不正です: " + detail);
    }

    private static long calculateNbtLimit(long blockCount) {
        try {
            long estimated = Math.addExact(MIN_NBT_LIMIT, Math.multiplyExact(blockCount, 128L));
            return Math.min(MAX_NBT_LIMIT, Math.max(MIN_NBT_LIMIT, estimated));
        } catch (ArithmeticException exception) {
            return MAX_NBT_LIMIT;
        }
    }

    private static void ensureNotInterrupted() throws CandidateValidationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CandidateValidationException("Schematic処理がプラグイン停止要求により中断されました。");
        }
    }

    private static void ensureWorldEditNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("WorldEdit処理がプラグイン停止要求により中断されました。");
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : abbreviate(message);
    }

    private static void validateDimensions(Clipboard source, Clipboard candidate, TicketBounds bounds)
            throws CandidateValidationException {
        BlockVector3 expected = BlockVector3.at(bounds.width(), bounds.height(), bounds.length());
        if (!source.getDimensions().equals(expected)) {
            throw new CandidateValidationException("source.schemの寸法がticket.jsonと一致しません。");
        }
        if (!candidate.getDimensions().equals(expected)) {
            throw new CandidateValidationException("candidate.schemの寸法がticket.jsonと一致しません。");
        }
    }

    private static BlockVector3 absolutePosition(BlockPosition minimum, BlockPosition relative) {
        return BlockVector3.at(
                Math.addExact(minimum.x(), relative.x()),
                Math.addExact(minimum.y(), relative.y()),
                Math.addExact(minimum.z(), relative.z()));
    }

    private static BlockVector3 toVector(BlockPosition position) {
        return BlockVector3.at(position.x(), position.y(), position.z());
    }

    private static String formatRelative(int x, int y, int z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    private static void compensatePartialEdit(
            EditSession editSession,
            TicketBounds bounds,
            List<SchematicChange> completed,
            boolean rollback,
            Exception original) {
        for (int index = completed.size() - 1; index >= 0; index--) {
            SchematicChange change = completed.get(index);
            BlockVector3 worldPosition = absolutePosition(bounds.min(), change.relativePosition());
            BlockState previous = rollback ? change.candidateState() : change.sourceState();
            BlockState replacement = rollback ? change.sourceState() : change.candidateState();
            try {
                BlockState current = editSession.getBlock(worldPosition);
                if (Objects.equals(current, replacement)) {
                    boolean restored = editSession.setBlock(
                            worldPosition.x(),
                            worldPosition.y(),
                            worldPosition.z(),
                            previous);
                    if (!restored) {
                        original.addSuppressed(new IllegalStateException(
                                "FAWEが補償変更を受理しませんでした: " + worldPosition));
                    }
                } else if (!Objects.equals(current, previous)) {
                    original.addSuppressed(new WorldConflictException(
                            "補償中に第三者の変更を検出したため上書きしませんでした: " + worldPosition));
                }
            } catch (RuntimeException compensationException) {
                original.addSuppressed(compensationException);
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0L) {
                throw new IOException("Decompressed NBT exceeds the safety limit");
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining <= 0L) {
                throw new IOException("Decompressed NBT exceeds the safety limit");
            }
            int allowed = (int) Math.min(remaining, length);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }
    }
}
