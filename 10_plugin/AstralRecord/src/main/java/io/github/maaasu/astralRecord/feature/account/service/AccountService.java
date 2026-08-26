package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountDeleteResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountLevelSetResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
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

/**
 * アカウントのビジネスロジックを扱うサービスクラスです。
 * モブ討伐時の経験値はメモリ上で先に進め、一定間隔で API へ flush します。
 */
public class AccountService {

    private static final long EXPERIENCE_FLUSH_INTERVAL_TICKS = 40L;
    private static final int MAX_STOP_FLUSH_ATTEMPTS = 3;
    public static final int MAX_PLAYER_LEVEL = 100;

    private final Plugin plugin;
    private final AccountRepository accountRepository;
    private final Map<UUID, PendingExperienceUpdate> pendingExperienceUpdates = new ConcurrentHashMap<>();
    private final Map<UUID, PendingClassProgressUpdate> pendingClassProgressUpdates = new ConcurrentHashMap<>();
    private final BukkitTask flushTask;

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
     * プレイヤーのアカウント一覧を取得します。
     *
     * @param userId プレイヤー UUID
     * @return アカウントモデルのリスト
     */
    public List<AccountModel> getAccounts(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
            .map(this::overlayPendingProgress)
            .toList();
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
        AccountModel account = accountRepository.findByUuid(accountUuid);
        return account == null ? null : overlayPendingProgress(account);
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
        Logger.log(LogId.I_5100, accountName, slotIndex, userId);
        return created;
    }

    /**
     * 選択中アカウントを切り替えます。
     *
     * @param userId プレイヤー UUID
     * @param accountUuid 選択するアカウント UUID
     */
    public void switchAccount(UUID userId, UUID accountUuid) {
        accountRepository.switchActiveAccount(userId, accountUuid, userId);
        Logger.log(LogId.I_5101, accountUuid, userId);
    }

    /**
     * アカウントモードを更新します。
     *
     * @param accountUuid 更新対象アカウント UUID
     * @param mode 新しいアカウントモード
     * @param updatedBy 更新者 UUID
     * @return 更新後のアカウントモデル
     */
    public AccountModel setMode(UUID accountUuid, AccountMode mode, UUID updatedBy) {
        AccountModel updated = accountRepository.updateMode(accountUuid, mode, updatedBy);
        Logger.log(LogId.I_5102, accountUuid, mode.getValue(), updatedBy);
        return updated;
    }

    /**
     * 指定アカウントを削除し、削除済みアカウントの保留中進行度を破棄します。
     *
     * @param accountUuid 削除対象アカウント UUID
     * @param deletedBy 削除実行者 UUID
     * @return 削除結果。対象が存在しない場合は {@code null}
     */
    public @Nullable AccountDeleteResult deleteAccount(@NotNull UUID accountUuid, @NotNull UUID deletedBy) {
        pendingExperienceUpdates.remove(accountUuid);
        pendingClassProgressUpdates.remove(accountUuid);
        AccountDeleteResult result = accountRepository.delete(accountUuid, deletedBy);
        if (result != null) {
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
        pendingExperienceUpdates.put(updated.getUuid(), new PendingExperienceUpdate(updated, updatedBy));

        int levelUps = Math.max(0, updated.getLevel() - previous.getLevel());
        if (levelUps > 0) {
            Logger.log(LogId.I_5103, updated.getUuid(), updated.getLevel(), updated.getTotalExperience());
        }
        return new AccountExperienceResult(previous, updated, experience, levelUps);
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
        AccountModel previous = overlayPendingProgress(currentAccount);
        int previousLevel = Math.clamp(previous.getLevel(), 1, MAX_PLAYER_LEVEL);
        int currentLevel = (int) Math.clamp(requestedLevel, 1L, (long) MAX_PLAYER_LEVEL);
        long totalExperience = totalRequiredExperienceForLevel(previous.getUuid(), currentLevel);
        AccountModel updated = withProgress(previous, currentLevel, totalExperience, updatedBy);
        pendingExperienceUpdates.put(updated.getUuid(), new PendingExperienceUpdate(updated, updatedBy));
        return new AccountLevelSetResult(previousLevel, currentLevel, MAX_PLAYER_LEVEL, updated);
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
        pendingExperienceUpdates.put(
            snapshot.getUuid(),
            new PendingExperienceUpdate(snapshot, updatedBy)
        );
        pendingClassProgressUpdates.put(
            snapshot.getUuid(),
            new PendingClassProgressUpdate(snapshot, updatedBy)
        );
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
        pendingExperienceUpdates.put(updated.getUuid(), new PendingExperienceUpdate(updated, updatedBy));
        return Optional.of(updated);
    }

    public void stop() {
        flushTask.cancel();
        for (int attempt = 0; attempt < MAX_STOP_FLUSH_ATTEMPTS; attempt++) {
            if (pendingExperienceUpdates.isEmpty() && pendingClassProgressUpdates.isEmpty()) {
                return;
            }
            flushPendingExperienceNow();
            flushPendingClassProgressNow();
        }
        if (!pendingExperienceUpdates.isEmpty() || !pendingClassProgressUpdates.isEmpty()) {
            Logger.log(
                LogId.W_5156,
                pendingExperienceUpdates.size(),
                pendingClassProgressUpdates.size()
            );
        }
    }

    private void flushPendingExperienceAsync() {
        if (pendingExperienceUpdates.isEmpty() && pendingClassProgressUpdates.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingExperienceUpdate> entry : List.copyOf(pendingExperienceUpdates.entrySet())) {
            flushPendingExperience(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, PendingClassProgressUpdate> entry : List.copyOf(pendingClassProgressUpdates.entrySet())) {
            flushPendingClassProgress(entry.getKey(), entry.getValue());
        }
    }

    private void flushPendingExperienceNow() {
        for (Map.Entry<UUID, PendingExperienceUpdate> entry : List.copyOf(pendingExperienceUpdates.entrySet())) {
            flushPendingExperience(entry.getKey(), entry.getValue());
        }
    }

    private void flushPendingClassProgressNow() {
        for (Map.Entry<UUID, PendingClassProgressUpdate> entry : List.copyOf(pendingClassProgressUpdates.entrySet())) {
            flushPendingClassProgress(entry.getKey(), entry.getValue());
        }
    }

    private void flushPendingExperience(@NotNull UUID accountUuid, @NotNull PendingExperienceUpdate snapshot) {
        try {
            accountRepository.updateProgress(
                accountUuid,
                snapshot.account().getLevel(),
                snapshot.account().getTotalExperience(),
                snapshot.updatedBy()
            );
            pendingExperienceUpdates.computeIfPresent(accountUuid, (ignored, current) ->
                sameProgressSnapshot(current, snapshot) ? null : current
            );
        } catch (RuntimeException ex) {
            Logger.error(
                LogId.E_5156,
                ex,
                accountUuid,
                snapshot.account().getLevel(),
                snapshot.account().getTotalExperience()
            );
        }
    }

    private boolean sameProgressSnapshot(
        @NotNull PendingExperienceUpdate current,
        @NotNull PendingExperienceUpdate snapshot
    ) {
        return current.account().equals(snapshot.account())
            && current.updatedBy().equals(snapshot.updatedBy());
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
        AccountModel previous = overlayPendingProgress(currentAccount);
        AccountModel updated = withClassProgress(previous, classId, classLevel, classExperience, updatedBy);
        pendingClassProgressUpdates.put(updated.getUuid(), new PendingClassProgressUpdate(updated, updatedBy));
        return updated;
    }

    /**
     * プレイヤーの現在クラス進行度を即時に API へ保存します。
     *
     * @param player 保存対象プレイヤー
     */
    public void saveClassProgressNow(@NotNull AstPlayer player) {
        AccountModel pending = updateClassProgressCached(
            player.getAccount(),
            player.getClassId(),
            player.getClassLevel(),
            player.getClassExperience(),
            player.getUser().getUuid()
        );
        flushPendingClassProgress(pending.getUuid(), pendingClassProgressUpdates.get(pending.getUuid()));
    }

    private void flushPendingClassProgress(@NotNull UUID accountUuid, @Nullable PendingClassProgressUpdate snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            accountRepository.updateClassProgress(
                accountUuid,
                snapshot.account().getClassId(),
                snapshot.account().getClassLevel(),
                snapshot.account().getClassExperience(),
                snapshot.account().getClassProgresses(),
                snapshot.updatedBy()
            );
            pendingClassProgressUpdates.computeIfPresent(accountUuid, (ignored, current) ->
                sameClassProgressSnapshot(current, snapshot) ? null : current
            );
        } catch (RuntimeException ex) {
            Logger.error(
                LogId.E_5157,
                ex,
                accountUuid,
                snapshot.account().getClassId(),
                snapshot.account().getClassLevel(),
                snapshot.account().getClassExperience()
            );
        }
    }

    private boolean sameClassProgressSnapshot(
        @NotNull PendingClassProgressUpdate current,
        @NotNull PendingClassProgressUpdate snapshot
    ) {
        return current.account().equals(snapshot.account())
            && current.updatedBy().equals(snapshot.updatedBy());
    }

    private @NotNull AccountModel overlayPendingProgress(@NotNull AccountModel account) {
        PendingExperienceUpdate pending = pendingExperienceUpdates.get(account.getUuid());
        AccountModel overlaid = pending == null ? account : pending.account();
        PendingClassProgressUpdate classPending = pendingClassProgressUpdates.get(account.getUuid());
        return classPending == null ? overlaid : classPending.account();
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
            account.getClassProgresses()
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
            classProgresses
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

    private record PendingExperienceUpdate(@NotNull AccountModel account, @NotNull UUID updatedBy) {
    }

    private record PendingClassProgressUpdate(@NotNull AccountModel account, @NotNull UUID updatedBy) {
    }
}
