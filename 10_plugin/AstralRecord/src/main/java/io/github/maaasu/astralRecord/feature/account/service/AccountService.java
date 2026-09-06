package io.github.maaasu.astralRecord.feature.account.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountDeleteResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountLevelSetResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * アカウントのビジネスロジックを扱うサービスクラスです。
 * モブ討伐時の経験値はメモリ上で先に進め、一定間隔で API へ flush します。
 */
public class AccountService {

    private static final long EXPERIENCE_FLUSH_INTERVAL_TICKS = 40L;
    public static final int MAX_PLAYER_LEVEL = 100;

    private final Plugin plugin;
    private final AccountRepository accountRepository;
    private final Map<UUID, PendingExperienceUpdate> pendingExperienceUpdates = new ConcurrentHashMap<>();
    private final Map<UUID, PendingClassProgressUpdate> pendingClassProgressUpdates = new ConcurrentHashMap<>();
    private final Map<UUID, PendingModeUpdate> pendingModeUpdates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingProgressRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> acknowledgedProgressVersions = new ConcurrentHashMap<>();
    private final Map<UUID, AccountModel> persistedOfflineModes = new ConcurrentHashMap<>();
    /**
     * account進行の read-modify-write と snapshot取得を直列化するガードです。
     * InventoryService の state lock を取った呼出元では、必ずその内側で取得します。
     */
    private final Map<UUID, Object> progressLocks = new ConcurrentHashMap<>();
    private final Map<UUID, List<Integer>> accountSlotIndexes = new ConcurrentHashMap<>();
    private final BukkitTask flushTask;
    private final AtomicLong revisionSequence = new AtomicLong();
    private volatile Consumer<UUID> localPlayerSaveRequester = ignored -> { };

    public AccountService(@NotNull Plugin plugin, @NotNull AccountRepository accountRepository) {
        this.plugin = plugin;
        this.accountRepository = accountRepository;
        this.flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::flushPendingExperienceAsync,
            EXPERIENCE_FLUSH_INTERVAL_TICKS,
            EXPERIENCE_FLUSH_INTERVAL_TICKS
        );
    }

    /**
     * account進行のwrite-behindを共通プレイヤー保存へ委譲する送信要求先を設定します。
     *
     * @param requester account ID ごとの共通保存要求。呼出先は非同期 API 送信を開始するだけであり、同期通信してはなりません
     */
    public void setLocalPlayerSaveRequester(@NotNull Consumer<UUID> requester) {
        this.localPlayerSaveRequester = requester;
    }

    /**
     * プレイヤーのアカウント一覧を取得します。
     *
     * @param userId プレイヤー UUID
     * @return アカウントモデルのリスト
     */
    public List<AccountModel> getAccounts(UUID userId) {
        List<AccountModel> accounts = accountRepository.findByUserId(userId).stream()
            .map(this::overlayPendingProgress)
            .toList();
        accounts.forEach(account -> acknowledgedProgressVersions.putIfAbsent(
            account.getUuid(), account.getProgressVersion()
        ));
        cacheAccountSlotIndexes(userId, accounts);
        return accounts;
    }

    /**
     * アカウント一覧取得済み時点のスロット番号を返します。
     * <p>
     * コマンド補完から API の同期通信を発生させないため、このメソッドはメモリ上のキャッシュだけを参照します。
     * 未取得の場合は空のリストを返します。
     *
     * @param userId プレイヤー UUID
     * @return 作成済みアカウントのスロット番号
     */
    public List<Integer> getCachedSlotIndexes(@NotNull UUID userId) {
        return accountSlotIndexes.getOrDefault(userId, List.of());
    }

    /**
     * プレイヤーの選択中アカウントを取得します。
     * user.accountId を優先し、無ければ isActive=true のアカウントへフォールバックします。
     *
     * @param userId プレイヤー UUID
     * @param selectedAccountId ユーザーに設定されている選択中アカウント UUID（null 可）
     * @return 選択中アカウント。見つからない場合は null
     */
    public AccountModel getSelectedAccount(UUID userId, UUID selectedAccountId) {
        List<AccountModel> accounts = getAccounts(userId);
        if (selectedAccountId != null) {
            AccountModel selectedAccount = accounts.stream()
                .filter(account -> account.getUuid().equals(selectedAccountId))
                .findFirst()
                .orElse(null);
            if (selectedAccount != null) {
                return selectedAccount;
            }
        }

        return accounts.stream()
            .filter(AccountModel::isActive)
            .findFirst()
            .orElse(null);
    }

    /**
     * アカウント UUID でアカウントを取得します。
     *
     * @param accountUuid アカウント UUID
     * @return アカウントモデル。存在しない場合は null
     */
    public AccountModel getAccount(UUID accountUuid) {
        return withProgressLock(accountUuid, () -> {
            AccountModel account = accountRepository.findByUuid(accountUuid);
            if (account != null) {
                acknowledgedProgressVersions.merge(account.getUuid(), account.getProgressVersion(), Math::max);
            }
            return account == null ? null : overlayPendingProgress(account);
        });
    }

    /**
     * 未保存進行のないofflineアカウントのmodeをAPIへ保存します。管理コマンドの非同期処理専用です。
     * 初期読込と同じaccountガードで直列化し、成功後だけ進行版とmodeの読込overlayを更新します。
     *
     * @param accountId 対象アカウント
     * @param mode 保存するmode
     * @param updatedBy 管理操作の実行者
     * @return APIが保存したアカウント
     * @throws IllegalStateException 未ACK進行がある場合
     */
    public @NotNull AccountModel saveOfflineMode(
        @NotNull UUID accountId, @NotNull AccountMode mode, @NotNull UUID updatedBy
    ) {
        return withProgressLock(accountId, () -> {
            if (pendingProgressRevisions.containsKey(accountId)) {
                throw new IllegalStateException("Offline account has pending player state: " + accountId);
            }
            AccountModel saved = accountRepository.updateMode(accountId, mode, updatedBy);
            acknowledgedProgressVersions.merge(accountId, saved.getProgressVersion(), Math::max);
            persistedOfflineModes.put(accountId, saved);
            Logger.log(LogId.I_5102, accountId, mode.getValue(), updatedBy);
            return saved;
        });
    }

    /**
     * offline API待機中にログインした対象へ、現在の進行を保持して保存済みmodeだけを反映します。
     * @param current 現在のオンライン状態
     * @param saved API保存済み結果
     * @return 最新pending進行とmodeを重ねた状態
     */
    public @NotNull AccountModel mergeSavedOfflineMode(@NotNull AccountModel current, @NotNull AccountModel saved) {
        return withProgressLock(current.getUuid(), () -> overlayPendingProgress(
            withMode(current, saved.getMode(), saved.getUpdatedBy())
        ));
    }

    /**
     * 新規アカウントを作成します。
     * スロット重複の事前チェックを行い、問題なければ登録します。
     *
     * @param userId プレイヤー UUID
     * @param accountName アカウント名（キャラクター名）
     * @param slotIndex スロット番号（0 始まり）
     * @param createdBy 作成者 UUID
     * @return 作成したアカウントモデル
     * @throws IllegalArgumentException スロット番号が既に使用中の場合
     */
    public AccountModel createAccount(UUID userId, String accountName, int slotIndex, UUID createdBy) {
        List<AccountModel> existing = accountRepository.findByUserId(userId);
        boolean slotUsed = existing.stream().anyMatch(a -> a.getSlotIndex() == slotIndex);
        if (slotUsed) {
            throw new IllegalArgumentException("Slot " + slotIndex + " is already in use for user: " + userId);
        }

        LocalDateTime now = LocalDateTime.now();
        AccountModel model = new AccountModel(
            UUID.randomUUID(),
            userId,
            accountName,
            slotIndex,
            existing.isEmpty(),
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            createdBy,
            createdBy,
            false
        );
        AccountModel created = accountRepository.insert(model);
        List<AccountModel> cachedAccounts = new ArrayList<>(existing);
        cachedAccounts.add(created);
        cacheAccountSlotIndexes(userId, cachedAccounts);
        Logger.log(LogId.I_5100, created.getAccountName(), slotIndex, userId);
        return created;
    }

    /**
     * アカウント名を変更します。
     *
     * @param accountUuid 変更対象アカウント UUID
     * @param accountName 新しいアカウント名
     * @param updatedBy 更新者 UUID
     * @return API が確定した更新後アカウント
     */
    public AccountModel renameAccount(
        @NotNull UUID accountUuid,
        @NotNull String accountName,
        @NotNull UUID updatedBy
    ) {
        return accountRepository.updateName(accountUuid, accountName, updatedBy);
    }

    /**
     * 選択中アカウントを切り替えます。
     *
     * @param userId プレイヤー UUID
     * @param accountUuid 選択するアカウント UUID
     * @return 切替後のアカウントモデル
     */
    public AccountModel switchAccount(UUID userId, UUID accountUuid) {
        return switchAccount(userId, accountUuid, userId);
    }

    /**
     * 指定ユーザーの選択中アカウントを、指定した実行者として切り替えます。
     *
     * @param userId 対象ユーザー UUID
     * @param accountUuid 選択するアカウント UUID
     * @param updatedBy 更新者 UUID
     * @return 切替後のアカウントモデル
     */
    public AccountModel switchAccount(UUID userId, UUID accountUuid, UUID updatedBy) {
        AccountModel switched = accountRepository.switchActiveAccount(userId, accountUuid, updatedBy);
        Logger.log(LogId.I_5101, accountUuid, userId);
        return overlayPendingProgress(switched);
    }

    /**
     * アカウントモードをローカルstateへ即時反映します。
     * 共通snapshot保存の要求は、Inventory の state lock を解放した呼出元が行います。
     *
     * @param currentAccount 現在のローカルアカウント状態
     * @param mode 新しいアカウントモード
     * @param updatedBy 更新者 UUID
     * @return API応答を待たず反映した更新後のアカウントモデル
     */
    public AccountModel setMode(@NotNull AccountModel currentAccount, @NotNull AccountMode mode, @NotNull UUID updatedBy) {
        AccountModel updated = withProgressLock(currentAccount.getUuid(), () -> {
            AccountModel next = withMode(overlayPendingProgress(currentAccount), mode, updatedBy);
            pendingModeUpdates.put(next.getUuid(), new PendingModeUpdate(next, updatedBy));
            markProgressDirty(next.getUuid());
            return next;
        });
        Logger.log(LogId.I_5102, updated.getUuid(), mode.getValue(), updatedBy);
        return updated;
    }

    /**
     * 共通プレイヤー保存を要求します。state mutation の lock を保持しない呼出元だけが実行します。
     *
     * @param accountId 保存対象account ID
     */
    public void requestLocalPlayerSave(@NotNull UUID accountId) {
        localPlayerSaveRequester.accept(accountId);
    }

    /**
     * 指定アカウントを削除し、削除済みアカウントの保留中進行度を破棄します。
     *
     * @param accountUuid 削除対象アカウント UUID
     * @param deletedBy 削除実行者 UUID
     * @return 削除結果。対象が存在しない場合は {@code null}
     */
    public @Nullable AccountDeleteResult deleteAccount(@NotNull UUID accountUuid, @NotNull UUID deletedBy) {
        withProgressLock(accountUuid, () -> {
            pendingExperienceUpdates.remove(accountUuid);
            pendingClassProgressUpdates.remove(accountUuid);
            pendingModeUpdates.remove(accountUuid);
            pendingProgressRevisions.remove(accountUuid);
            acknowledgedProgressVersions.remove(accountUuid);
            return null;
        });
        AccountDeleteResult result = accountRepository.delete(accountUuid, deletedBy);
        if (result != null) {
            accountSlotIndexes.computeIfPresent(result.getUserId(), (ignored, slots) -> {
                List<Integer> updatedSlots = new ArrayList<>(slots.stream()
                    .filter(slot -> slot != result.getDeletedSlotIndex())
                    .toList());
                if (result.getCreatedReplacement()) {
                    updatedSlots.add(result.getDeletedSlotIndex());
                }
                return updatedSlots.stream().distinct().sorted().toList();
            });
            Logger.log(LogId.I_5104, accountUuid, result.getDeletedSlotIndex(), deletedBy);
        }
        return result;
    }

    /**
     * 経験値をメモリ上で先に加算します。
     * API 更新は定期 flush へ回し、呼び出し元には即時のレベル結果を返します。
     *
     * @param currentAccount 現在のアカウント状態
     * @param experience 加算経験値
     * @param updatedBy 更新者 UUID
     * @return 経験値加算結果
     */
    public @NotNull AccountExperienceResult grantExperienceCached(
        @NotNull AccountModel currentAccount,
        int experience,
        @NotNull UUID updatedBy
    ) {
        AccountExperienceResult result = withProgressLock(currentAccount.getUuid(), () -> {
            AccountModel previous = overlayPendingProgress(currentAccount);
            if (experience <= 0) {
                return new AccountExperienceResult(previous, previous, 0, 0);
            }

            long totalExperience = previous.getTotalExperience() + experience;
            int level = Math.max(1, previous.getLevel());
            while (level < MAX_PLAYER_LEVEL && totalExperience >= totalRequiredExperienceForLevel(previous.getUuid(), level + 1)) {
                level++;
            }

            AccountModel updated = withProgress(previous, level, totalExperience, updatedBy);
            registerPendingExperience(updated, updatedBy);
            int levelUps = Math.max(0, updated.getLevel() - previous.getLevel());
            return new AccountExperienceResult(previous, updated, experience, levelUps);
        });
        int levelUps = result.levelUps();
        if (levelUps > 0) {
            AccountModel updated = result.updatedAccount();
            Logger.log(LogId.I_5103, updated.getUuid(), updated.getLevel(), updated.getTotalExperience());
        }
        return result;
    }

    /**
     * プレイヤーレベルを設定し、設定レベルの開始累計経験値を pending 状態へ登録します。
     *
     * @param currentAccount 現在のアカウント状態
     * @param requestedLevel 設定要求レベル。1 未満は 1、上限超過は最大レベルへ補正します
     * @param updatedBy 更新者 UUID
     * @return 設定前後のレベルと最大レベル
     */
    public @NotNull AccountLevelSetResult setPlayerLevelCached(
        @NotNull AccountModel currentAccount,
        long requestedLevel,
        @NotNull UUID updatedBy
    ) {
        return withProgressLock(currentAccount.getUuid(), () -> {
            AccountModel previous = overlayPendingProgress(currentAccount);
            int previousLevel = Math.clamp(previous.getLevel(), 1, MAX_PLAYER_LEVEL);
            int currentLevel = (int) Math.clamp(requestedLevel, 1L, (long) MAX_PLAYER_LEVEL);
            long totalExperience = totalRequiredExperienceForLevel(previous.getUuid(), currentLevel);
            AccountModel updated = withProgress(previous, currentLevel, totalExperience, updatedBy);
            registerPendingExperience(updated, updatedBy);
            return new AccountLevelSetResult(previousLevel, currentLevel, MAX_PLAYER_LEVEL, updated);
        });
    }

    /**
     * 複数機能にまたがる処理の補償時に、指定したアカウント進捗を pending 状態へ戻します。
     * 次回 flush ではこのスナップショットが正本として保存されます。
     *
     * @param snapshot 復元するアカウント進捗
     * @param updatedBy 更新者 UUID
     */
    public void restoreCachedProgress(
        @NotNull AccountModel snapshot,
        @NotNull UUID updatedBy
    ) {
        withProgressLock(snapshot.getUuid(), () -> {
            registerPendingExperience(snapshot, updatedBy);
            registerPendingClassProgress(snapshot, updatedBy);
            return null;
        });
    }

    public double experienceProgress(UUID accountUuid, int level, long totalExperience) {
        int normalizedLevel = Math.max(1, level);
        if (normalizedLevel >= MAX_PLAYER_LEVEL) {
            return 1.0D;
        }
        long currentLevelRequiredExperience = totalRequiredExperienceForLevel(accountUuid, normalizedLevel);
        long nextLevelRequiredExperience = totalRequiredExperienceForLevel(accountUuid, normalizedLevel + 1);
        long levelRange = nextLevelRequiredExperience - currentLevelRequiredExperience;
        if (levelRange <= 0L) {
            return 0.0D;
        }
        long levelProgress = Math.max(0L, totalExperience - currentLevelRequiredExperience);
        return Math.clamp((double) levelProgress / (double) levelRange, 0.0D, 1.0D);
    }

    /**
     * 現在レベル内で獲得済みの経験値から指定割合を減算します。
     * レベル開始時点の総経験値を下限にするため、レベルダウンは発生しません。
     *
     * @param currentAccount 現在のアカウント状態
     * @param percent 減算割合。0 以下は無視し、100 を上限に扱います
     * @param updatedBy 更新者 UUID
     * @return 進捗が変化した場合の更新後アカウント
     */
    public @NotNull Optional<AccountModel> loseCurrentLevelExperiencePercentCached(
        @NotNull AccountModel currentAccount,
        int percent,
        @NotNull UUID updatedBy
    ) {
        return withProgressLock(currentAccount.getUuid(), () -> {
            AccountModel previous = overlayPendingProgress(currentAccount);
            int normalizedPercent = Math.clamp(percent, 0, 100);
            if (normalizedPercent <= 0) {
                return Optional.empty();
            }

            int level = Math.max(1, previous.getLevel());
            long currentLevelRequiredExperience = totalRequiredExperienceForLevel(previous.getUuid(), level);
            long levelProgress = Math.max(0L, previous.getTotalExperience() - currentLevelRequiredExperience);
            if (levelProgress <= 0L) {
                return Optional.empty();
            }

            long lostExperience = Math.max(1L, (levelProgress * normalizedPercent) / 100L);
            long totalExperience = Math.max(currentLevelRequiredExperience, previous.getTotalExperience() - lostExperience);
            if (totalExperience == previous.getTotalExperience()) {
                return Optional.empty();
            }

            AccountModel updated = withProgress(previous, level, totalExperience, updatedBy);
            registerPendingExperience(updated, updatedBy);
            return Optional.of(updated);
        });
    }

    public void stop() {
        flushTask.cancel();
        flushPendingExperienceAsync();
    }

    private void flushPendingExperienceAsync() {
        for (UUID accountId : pendingProgressRevisions.keySet()) {
            localPlayerSaveRequester.accept(accountId);
        }
    }

    /**
     * クラス進行度を定期 flush 対象として登録します。
     *
     * @param currentAccount 現在のアカウント状態
     * @param classId 現在のクラス ID
     * @param classLevel 現在のクラスレベル
     * @param classExperience 現在クラスの累計経験値
     * @param updatedBy 更新者 UUID
     * @return pending を反映したアカウント状態
     */
    public @NotNull AccountModel updateClassProgressCached(
        @NotNull AccountModel currentAccount,
        @NotNull String classId,
        int classLevel,
        long classExperience,
        @NotNull UUID updatedBy
    ) {
        return withProgressLock(currentAccount.getUuid(), () -> {
            AccountModel previous = overlayPendingProgress(currentAccount);
            AccountModel updated = withClassProgress(previous, classId, classLevel, classExperience, updatedBy);
            registerPendingClassProgress(updated, updatedBy);
            return updated;
        });
    }

    /**
     * プレイヤーの現在クラス進行度を共通保存キューへ登録します。
     *
     * @param player 保存対象プレイヤー
     */
    public void saveClassProgressNow(@NotNull AstPlayer player) {
        updateClassProgressCached(
            player.getAccount(),
            player.getClassId(),
            player.getClassLevel(),
            player.getClassExperience(),
            player.getUser().getUuid()
        );
        localPlayerSaveRequester.accept(player.getAccount().getUuid());
    }

    /**
     * 指定アカウントのクラス進行度保存が保留されているかを返します。
     *
     * @param accountUuid アカウント UUID
     * @return API 保存待ちのクラス進行度がある場合は {@code true}
     */
    public boolean hasPendingClassProgress(@NotNull UUID accountUuid) {
        return pendingClassProgressUpdates.containsKey(accountUuid);
    }

    /**
     * dirty な account進行を共通プレイヤー状態snapshotの section として返します。
     * API ACK は同名sectionの {@code clientRevision} を返し、取得時点より新しいローカル更新を
     * 消さない場合だけ dirty を解除します。
     *
     * @param accountId 対象account ID
     * @return dirty な進行がなければ {@code null}
     */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        CapturedProgressSnapshot captured = withProgressLock(accountId, () -> {
            Long revision = pendingProgressRevisions.get(accountId);
            if (revision == null) {
                return null;
            }
            PendingExperienceUpdate experience = pendingExperienceUpdates.get(accountId);
            PendingClassProgressUpdate classProgress = pendingClassProgressUpdates.get(accountId);
            PendingModeUpdate mode = pendingModeUpdates.get(accountId);
            AccountModel progress = snapshotPendingProgress(experience, classProgress, mode);
            return progress == null ? null : new CapturedProgressSnapshot(
                revision,
                acknowledgedProgressVersions.getOrDefault(accountId, progress.getProgressVersion()),
                experience,
                classProgress,
                mode,
                progress
            );
        });
        if (captured == null) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", captured.revision());
        payload.addProperty("expectedProgressVersion", captured.expectedProgressVersion());
        payload.addProperty("updatedBy", captured.progress().getUpdatedBy().toString());
        payload.addProperty("level", captured.progress().getLevel());
        payload.addProperty("totalExperience", captured.progress().getTotalExperience());
        payload.addProperty("classId", captured.progress().getClassId());
        payload.addProperty("classLevel", captured.progress().getClassLevel());
        payload.addProperty("classExperience", captured.progress().getClassExperience());
        if (captured.mode() != null) {
            payload.addProperty("mode", captured.progress().getMode().getValue());
        }
        JsonArray classProgresses = new JsonArray();
        for (ClassProgressModel classProgress : captured.progress().getClassProgresses()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("classId", classProgress.getClassId());
            entry.addProperty("level", classProgress.getLevel());
            entry.addProperty("experience", classProgress.getExperience());
            classProgresses.add(entry);
        }
        payload.add("classProgresses", classProgresses);
        return new PlayerStateSection("accountProgress", payload, acknowledgement ->
            acknowledgeSnapshot(
                accountId,
                captured.revision(),
                captured.experience(),
                captured.classProgress(),
                captured.mode(),
                acknowledgement
            )
        );
    }

    private void acknowledgeSnapshot(
        @NotNull UUID accountId,
        long capturedRevision,
        @Nullable PendingExperienceUpdate capturedExperience,
        @Nullable PendingClassProgressUpdate capturedClassProgress,
        @Nullable PendingModeUpdate capturedMode,
        @NotNull JsonElement acknowledgement
    ) {
        if (!acknowledgement.isJsonObject()) {
            throw new IllegalArgumentException("accountProgress acknowledgement must be an object");
        }
        JsonObject acknowledged = acknowledgement.getAsJsonObject();
        JsonElement acknowledgedRevision = acknowledged.get("clientRevision");
        if (acknowledgedRevision == null || !acknowledgedRevision.isJsonPrimitive()
            || acknowledgedRevision.getAsLong() != capturedRevision) {
            throw new IllegalArgumentException("accountProgress acknowledgement revision did not match snapshot");
        }
        JsonElement progressVersion = acknowledged.get("progressVersion");
        if (progressVersion == null || !progressVersion.isJsonPrimitive()) {
            throw new IllegalArgumentException("accountProgress acknowledgement must contain progressVersion");
        }
        final int acknowledgedProgressVersion;
        try {
            acknowledgedProgressVersion = progressVersion.getAsInt();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("accountProgress acknowledgement progressVersion must be an integer", exception);
        }
        withProgressLock(accountId, () -> {
            // snapshot世代が古くても、API成功で得た専用versionは次の送信のbaseとして必ず採用する。
            acknowledgedProgressVersions.merge(accountId, acknowledgedProgressVersion, Math::max);
            if (pendingProgressRevisions.remove(accountId, capturedRevision)) {
                if (capturedExperience != null) {
                    pendingExperienceUpdates.remove(accountId, capturedExperience);
                }
                if (capturedClassProgress != null) {
                    pendingClassProgressUpdates.remove(accountId, capturedClassProgress);
                }
                if (capturedMode != null) {
                    pendingModeUpdates.remove(accountId, capturedMode);
                }
            }
            return null;
        });
    }

    private @Nullable AccountModel snapshotPendingProgress(
        @Nullable PendingExperienceUpdate experience,
        @Nullable PendingClassProgressUpdate classProgress,
        @Nullable PendingModeUpdate mode
    ) {
        if (experience == null && classProgress == null && mode == null) {
            return null;
        }
        AccountModel base = experience != null ? experience.account()
            : classProgress != null ? classProgress.account() : mode.account();
        AccountModel merged = classProgress == null ? base : withPendingClassProgress(base, classProgress.account());
        return mode == null ? merged : withMode(merged, mode.account().getMode(), mode.updatedBy());
    }

    private void registerPendingExperience(@NotNull AccountModel account, @NotNull UUID updatedBy) {
        pendingExperienceUpdates.put(account.getUuid(), new PendingExperienceUpdate(account, updatedBy));
        markProgressDirty(account.getUuid());
    }

    private void registerPendingClassProgress(@NotNull AccountModel account, @NotNull UUID updatedBy) {
        pendingClassProgressUpdates.put(account.getUuid(), new PendingClassProgressUpdate(account, updatedBy));
        markProgressDirty(account.getUuid());
    }

    private void markProgressDirty(@NotNull UUID accountId) {
        pendingProgressRevisions.put(accountId, revisionSequence.incrementAndGet());
    }

    private @NotNull AccountModel overlayPendingProgress(@NotNull AccountModel account) {
        return withProgressLock(account.getUuid(), () -> overlayPendingProgressLocked(account));
    }

    private @NotNull AccountModel overlayPendingProgressLocked(@NotNull AccountModel account) {
        AccountModel savedMode = persistedOfflineModes.get(account.getUuid());
        if (savedMode != null && account.getProgressVersion() < savedMode.getProgressVersion()) {
            account = withMode(account, savedMode.getMode(), savedMode.getUpdatedBy());
        }
        PendingExperienceUpdate pending = pendingExperienceUpdates.get(account.getUuid());
        AccountModel overlaid = pending == null ? account : pending.account();
        PendingClassProgressUpdate classPending = pendingClassProgressUpdates.get(account.getUuid());
        AccountModel withClass = classPending == null ? overlaid : withPendingClassProgress(overlaid, classPending.account());
        PendingModeUpdate modePending = pendingModeUpdates.get(account.getUuid());
        return modePending == null ? withClass : withMode(withClass, modePending.account().getMode(), modePending.updatedBy());
    }

    private <T> T withProgressLock(@NotNull UUID accountId, @NotNull Supplier<T> action) {
        Object lock = progressLocks.computeIfAbsent(accountId, ignored -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    private void cacheAccountSlotIndexes(@NotNull UUID userId, @NotNull List<AccountModel> accounts) {
        accountSlotIndexes.put(
            userId,
            accounts.stream()
                .map(AccountModel::getSlotIndex)
                .distinct()
                .sorted()
                .toList()
        );
    }

    /**
     * 最新のプレイヤー進行へ、独立して保留中のクラス進行だけを重ねます。
     *
     * @param playerProgress 最新のプレイヤーレベル・経験値を持つ状態
     * @param classProgress 最新のクラス進行を持つ状態
     * @return 両方の pending を合成したアカウント状態
     */
    private @NotNull AccountModel withPendingClassProgress(
        @NotNull AccountModel playerProgress,
        @NotNull AccountModel classProgress
    ) {
        return new AccountModel(
            playerProgress.getUuid(),
            playerProgress.getUserId(),
            playerProgress.getAccountName(),
            playerProgress.getSlotIndex(),
            playerProgress.isActive(),
            playerProgress.getMode(),
            playerProgress.getMenuShortcutsJson(),
            playerProgress.getCreatedAt(),
            classProgress.getUpdatedAt(),
            playerProgress.getCreatedBy(),
            classProgress.getUpdatedBy(),
            playerProgress.isDeleted(),
            playerProgress.getLevel(),
            playerProgress.getTotalExperience(),
            classProgress.getClassId(),
            classProgress.getClassLevel(),
            classProgress.getClassExperience(),
            classProgress.getClassProgresses(),
            playerProgress.getProgressVersion()
        );
    }

    private @NotNull AccountModel withProgress(
        @NotNull AccountModel account,
        int level,
        long totalExperience,
        @NotNull UUID updatedBy
    ) {
        return new AccountModel(
            account.getUuid(),
            account.getUserId(),
            account.getAccountName(),
            account.getSlotIndex(),
            account.isActive(),
            account.getMode(),
            account.getMenuShortcutsJson(),
            account.getCreatedAt(),
            LocalDateTime.now(),
            account.getCreatedBy(),
            updatedBy,
            account.isDeleted(),
            level,
            totalExperience,
            account.getClassId(),
            account.getClassLevel(),
            account.getClassExperience(),
            account.getClassProgresses(),
            account.getProgressVersion()
        );
    }

    private @NotNull AccountModel withClassProgress(
        @NotNull AccountModel account,
        @NotNull String classId,
        int classLevel,
        long classExperience,
        @NotNull UUID updatedBy
    ) {
        String normalizedClassId = classId.isBlank() ? "adventurer" : classId.trim();
        List<ClassProgressModel> classProgresses = new ArrayList<>(account.getClassProgresses());
        classProgresses.removeIf(progress -> progress.getClassId().equalsIgnoreCase(normalizedClassId));
        classProgresses.add(new ClassProgressModel(
            normalizedClassId,
            Math.max(1, classLevel),
            Math.max(0L, classExperience)
        ));
        return new AccountModel(
            account.getUuid(),
            account.getUserId(),
            account.getAccountName(),
            account.getSlotIndex(),
            account.isActive(),
            account.getMode(),
            account.getMenuShortcutsJson(),
            account.getCreatedAt(),
            LocalDateTime.now(),
            account.getCreatedBy(),
            updatedBy,
            account.isDeleted(),
            account.getLevel(),
            account.getTotalExperience(),
            normalizedClassId,
            Math.max(1, classLevel),
            Math.max(0L, classExperience),
            classProgresses,
            account.getProgressVersion()
        );
    }

    private @NotNull AccountModel withMode(
        @NotNull AccountModel account,
        @NotNull AccountMode mode,
        @NotNull UUID updatedBy
    ) {
        return new AccountModel(
            account.getUuid(), account.getUserId(), account.getAccountName(), account.getSlotIndex(),
            account.isActive(), mode, account.getMenuShortcutsJson(), account.getCreatedAt(), LocalDateTime.now(),
            account.getCreatedBy(), updatedBy, account.isDeleted(), account.getLevel(), account.getTotalExperience(),
            account.getClassId(), account.getClassLevel(), account.getClassExperience(), account.getClassProgresses(),
            account.getProgressVersion()
        );
    }

    private long totalRequiredExperienceForLevel(UUID accountUuid, int targetLevel) {
        long total = 0L;
        for (int level = 1; level < targetLevel; level++) {
            total += requiredExperienceForNextLevel(accountUuid, level);
        }
        return total;
    }

    private int requiredExperienceForNextLevel(UUID accountUuid, int currentLevel) {
        int base = 500 + (currentLevel * currentLevel * 120);
        int tierBonus = (currentLevel / 10) * 850;
        int[] wavePattern = {0, 90, 35, 140, 60, 185, 95, 230};
        int waveBonus = wavePattern[Math.floorMod(currentLevel - 1, wavePattern.length)];
        int milestoneBonus = currentLevel % 5 == 0 ? 600 + (currentLevel * 80) : 0;
        int hashModulo = 90 + (currentLevel * 4);
        int hashBonus = stableHash(accountUuid, currentLevel) % hashModulo;
        return base + tierBonus + waveBonus + milestoneBonus + hashBonus;
    }

    private int stableHash(UUID accountUuid, int level) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((accountUuid + ":" + level).getBytes(StandardCharsets.UTF_8));
            int value = 0;
            for (int index = 0; index < Integer.BYTES; index++) {
                value = (value << Byte.SIZE) | (bytes[index] & 0xFF);
            }
            return Math.floorMod(value, Integer.MAX_VALUE);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record CapturedProgressSnapshot(
        long revision,
        int expectedProgressVersion,
        @Nullable PendingExperienceUpdate experience,
        @Nullable PendingClassProgressUpdate classProgress,
        @Nullable PendingModeUpdate mode,
        @NotNull AccountModel progress
    ) {
    }

    private record PendingExperienceUpdate(@NotNull AccountModel account, @NotNull UUID updatedBy) {
    }

    private record PendingClassProgressUpdate(@NotNull AccountModel account, @NotNull UUID updatedBy) {
    }

    private record PendingModeUpdate(@NotNull AccountModel account, @NotNull UUID updatedBy) {
    }
}
