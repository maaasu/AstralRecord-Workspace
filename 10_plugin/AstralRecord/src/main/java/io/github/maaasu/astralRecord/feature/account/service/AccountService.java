package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * アカウントのビジネスロジックを扱うサービスクラスです。
 * モブ討伐時の経験値はメモリ上で先に進め、一定間隔で API へ flush します。
 */
public class AccountService {

    private static final long EXPERIENCE_FLUSH_INTERVAL_TICKS = 40L;
    private static final int MAX_STOP_FLUSH_ATTEMPTS = 3;

    private final Plugin plugin;
    private final AccountRepository accountRepository;
    private final Map<UUID, PendingExperienceUpdate> pendingExperienceUpdates = new ConcurrentHashMap<>();
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
            .map(this::overlayPendingExperience)
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
        PendingExperienceUpdate pending = pendingExperienceUpdates.get(accountUuid);
        if (pending != null) {
            return pending.account();
        }
        return accountRepository.findByUuid(accountUuid);
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
        AccountModel previous = overlayPendingExperience(currentAccount);
        if (experience <= 0) {
            return new AccountExperienceResult(previous, previous, 0, 0);
        }

        long totalExperience = previous.getTotalExperience() + experience;
        int level = Math.max(1, previous.getLevel());
        while (totalExperience >= totalRequiredExperienceForLevel(previous.getUuid(), level + 1)) {
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

    public double experienceProgress(UUID accountUuid, int level, long totalExperience) {
        int normalizedLevel = Math.max(1, level);
        long currentLevelRequiredExperience = totalRequiredExperienceForLevel(accountUuid, normalizedLevel);
        long nextLevelRequiredExperience = totalRequiredExperienceForLevel(accountUuid, normalizedLevel + 1);
        long levelRange = nextLevelRequiredExperience - currentLevelRequiredExperience;
        if (levelRange <= 0L) {
            return 0.0D;
        }
        long levelProgress = Math.max(0L, totalExperience - currentLevelRequiredExperience);
        return Math.clamp((double) levelProgress / (double) levelRange, 0.0D, 1.0D);
    }

    public void stop() {
        flushTask.cancel();
        for (int attempt = 0; attempt < MAX_STOP_FLUSH_ATTEMPTS; attempt++) {
            if (pendingExperienceUpdates.isEmpty()) {
                return;
            }
            flushPendingExperienceNow();
        }
        if (!pendingExperienceUpdates.isEmpty()) {
            Logger.log(LogId.E_5155, "experience flush unfinished", pendingExperienceUpdates.size());
        }
    }

    private void flushPendingExperienceAsync() {
        if (pendingExperienceUpdates.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingExperienceUpdate> entry : List.copyOf(pendingExperienceUpdates.entrySet())) {
            flushPendingExperience(entry.getKey(), entry.getValue());
        }
    }

    private void flushPendingExperienceNow() {
        for (Map.Entry<UUID, PendingExperienceUpdate> entry : List.copyOf(pendingExperienceUpdates.entrySet())) {
            flushPendingExperience(entry.getKey(), entry.getValue());
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
                LogId.E_5155,
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

    private @NotNull AccountModel overlayPendingExperience(@NotNull AccountModel account) {
        PendingExperienceUpdate pending = pendingExperienceUpdates.get(account.getUuid());
        return pending == null ? account : pending.account();
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
            totalExperience
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
        int base = 60 + (currentLevel * currentLevel * 5);
        int tierBonus = (currentLevel / 10) * 25;
        int[] wavePattern = {0, 8, 3, 13, 5, 17, 9, 21};
        int waveBonus = wavePattern[Math.floorMod(currentLevel - 1, wavePattern.length)];
        int milestoneBonus = currentLevel % 5 == 0 ? 18 + (currentLevel * 2) : 0;
        int hashModulo = 6 + (currentLevel / 8);
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
}
