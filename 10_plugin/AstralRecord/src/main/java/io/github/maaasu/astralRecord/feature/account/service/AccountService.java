package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * アカウント機能のビジネスロジックを担うサービスクラス。
 * アカウントの作成・選択・一覧取得を管理します。
 */
public class AccountService {

    private final Plugin plugin;
    private final AccountRepository accountRepository;
    private final Executor asyncExecutor;
    private final Map<UUID, CompletableFuture<Void>> experienceUpdateChains = new ConcurrentHashMap<>();

    public AccountService(@NotNull Plugin plugin, @NotNull AccountRepository accountRepository) {
        this.plugin = plugin;
        this.accountRepository = accountRepository;
        this.asyncExecutor = command -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, command);
    }

    /**
     * 指定プレイヤーのアカウント一覧を取得します。
     *
     * @param userId プレイヤー UUID
     * @return アカウントモデルのリスト
     */
    public List<AccountModel> getAccounts(UUID userId) {
        return accountRepository.findByUserId(userId);
    }

    /**
     * 指定プレイヤーの選択中アカウントを取得します。
     * user.accountId を優先し、不整合時は isActive=true のアカウントへフォールバックします。
     *
     * @param userId            プレイヤー UUID
     * @param selectedAccountId ユーザーに保存されている選択中アカウント UUID（null 可）
     * @return 選択中アカウント、見つからない場合は null
     */
    public AccountModel getSelectedAccount(UUID userId, UUID selectedAccountId) {
        List<AccountModel> accounts = accountRepository.findByUserId(userId);
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
     * @return アカウントモデル、存在しない場合は null
     */
    public AccountModel getAccount(UUID accountUuid) {
        return accountRepository.findByUuid(accountUuid);
    }

    /**
     * 新規アカウントを作成します。
     * スロット番号の重複チェックを行い、問題なければ登録します。
     *
     * @param userId      プレイヤー UUID
     * @param accountName アカウント名（キャラクター名）
     * @param slotIndex   スロット番号（0始まり）
     * @param createdBy   登録者 UUID（新規ユーザー登録フローでは SystemUser、自己作成時はプレイヤー UUID）
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
            existing.isEmpty(),     // 初めてのアカウントであれば自動で is_active = true
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
     * @param userId      プレイヤー UUID
     * @param accountUuid 選択するアカウント UUID
     */
    public void switchAccount(UUID userId, UUID accountUuid) {
        accountRepository.switchActiveAccount(userId, accountUuid, userId);
        Logger.log(LogId.I_5101, accountUuid, userId);
    }

    /**
     * 指定アカウントのモードを更新します。
     *
     * @param accountUuid 更新対象アカウント UUID
     * @param mode        新しいアカウントモード
     * @param updatedBy   更新者 UUID
     * @return 更新後のアカウントモデル
     */
    public AccountModel setMode(UUID accountUuid, AccountMode mode, UUID updatedBy) {
        AccountModel updated = accountRepository.updateMode(accountUuid, mode, updatedBy);
        Logger.log(LogId.I_5102, accountUuid, mode.getValue(), updatedBy);
        return updated;
    }

    /**
     * 指定アカウントへ経験値を加算し、必要に応じてレベルアップさせます。
     *
     * @param accountUuid 対象アカウント UUID
     * @param experience 加算経験値
     * @param updatedBy 更新者 UUID
     * @return 経験値加算結果
     * @throws IllegalArgumentException 対象アカウントが存在しない場合
     */
    public AccountExperienceResult grantExperience(UUID accountUuid, int experience, UUID updatedBy) {
        AccountModel current = accountRepository.findByUuid(accountUuid);
        if (current == null) {
            throw new IllegalArgumentException("Account not found: " + accountUuid);
        }
        if (experience <= 0) {
            return new AccountExperienceResult(current, current, 0, 0);
        }

        long totalExperience = current.getTotalExperience() + experience;
        int level = Math.max(1, current.getLevel());
        while (totalExperience >= totalRequiredExperienceForLevel(accountUuid, level + 1)) {
            level++;
        }

        AccountModel updated = accountRepository.updateProgress(accountUuid, level, totalExperience, updatedBy);
        int levelUps = Math.max(0, updated.getLevel() - current.getLevel());
        if (levelUps > 0) {
            Logger.log(LogId.I_5103, accountUuid, updated.getLevel(), updated.getTotalExperience());
        }
        return new AccountExperienceResult(current, updated, experience, levelUps);
    }

    /**
     * 経験値加算をアカウント単位で順序保証しながら非同期実行します。
     * <p>Mob 討伐のように短時間で連続実行される経路でも、同一 account への更新順が崩れないよう
     * 前回リクエストの完了後に次の API 更新を開始します。</p>
     *
     * @param accountUuid 対象アカウント UUID
     * @param experience 加算経験値
     * @param updatedBy 更新者 UUID
     * @return 非同期で完了する経験値加算結果
     */
    public CompletableFuture<AccountExperienceResult> grantExperienceAsync(
        @NotNull UUID accountUuid,
        int experience,
        @NotNull UUID updatedBy
    ) {
        CompletableFuture<AccountExperienceResult> result = new CompletableFuture<>();
        experienceUpdateChains.compute(accountUuid, (ignored, previousChain) -> {
            CompletableFuture<Void> base = previousChain == null
                ? CompletableFuture.completedFuture(null)
                : previousChain.handle((unused, ex) -> null);
            CompletableFuture<Void> nextChain = base.thenRunAsync(() -> {
                try {
                    result.complete(grantExperience(accountUuid, experience, updatedBy));
                } catch (Throwable ex) {
                    result.completeExceptionally(ex);
                }
            }, asyncExecutor);
            nextChain.whenComplete((unused, ex) -> experienceUpdateChains.remove(accountUuid, nextChain));
            return nextChain;
        });
        return result;
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
}

