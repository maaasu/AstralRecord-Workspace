package io.github.maaasu.astralRecord.feature.user.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ユーザー機能のビジネスロジックを担うサービスクラス。
 * プレイヤーのログイン・初回登録処理を管理します。
 */
public class UserService {

    private final UserRepository userRepository;
    private final AccountService accountService;
    private final Map<UUID, Boolean> pendingSameIpUsers = new ConcurrentHashMap<>();

    public UserService(UserRepository userRepository, AccountService accountService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
    }

    /**
     * プレイヤーの非同期ログイン前処理を行います。
     * ユーザーが存在しない場合は新規登録し、アカウントも同時に作成します。
     *
     * @param uuid     プレイヤー UUID
     * @param mcid     Minecraft ID
     * @param globalIp グローバル IP
     */
    public boolean onAsyncPreLogin(UUID uuid, String mcid, String globalIp) {
        UserModel existing;
        try {
            // 初参加チェックのため 404 が正常系となる findByUuidSilent を使用する
            // findByUuid は「存在すべきユーザーが見つからない」場面向けであり WARN が出るため使用しないこと
            existing = userRepository.findByUuidSilent(uuid);
        } catch (Exception e) {
            Logger.log(LogId.W_5051, mcid, e.getMessage());
            return true;
        }

        if (existing == null) {
            try {
                registerNewUser(uuid, mcid, globalIp);
            } catch (Exception e) {
                Logger.log(LogId.W_5051, mcid, e.getMessage());
            }
            pendingSameIpUsers.put(uuid, hasOtherUsersByGlobalIp(uuid, mcid, globalIp));
        } else {
            if (isActiveBan(existing, LocalDateTime.now())) {
                return false;
            }

            pendingSameIpUsers.put(uuid, hasOtherUsersByGlobalIp(uuid, mcid, globalIp));
            // user.accountId を選択状態の正とし、不整合時のみアクティブアカウントへフォールバックする
            AccountModel selectedAccount = accountService.getSelectedAccount(uuid, existing.getAccountId());
            if (selectedAccount != null) {
                try {
                    userRepository.updateJoinInfo(uuid, globalIp, selectedAccount.getUuid(), SystemUser.INSTANCE.getUuid());
                } catch (Exception e) {
                    Logger.log(LogId.W_5051, mcid, e.getMessage());
                }
            }
        }
        return true;
    }

    /**
     * 接続前処理で確認した同一IPの別ユーザー有無を一度だけ取り出します。
     *
     * @param uuid 参加したプレイヤー UUID
     * @return 参加者本人以外の同一IPユーザーが存在する場合は true
     */
    public boolean consumePendingSameIpUser(UUID uuid) {
        return Boolean.TRUE.equals(pendingSameIpUsers.remove(uuid));
    }

    private boolean hasOtherUsersByGlobalIp(UUID uuid, String mcid, String globalIp) {
        try {
            return userRepository.hasOtherByGlobalIp(uuid, globalIp);
        } catch (Exception e) {
            Logger.log(LogId.W_5053, mcid, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 新規ユーザーを登録します。
     * アカウントが存在しない場合は初期アカウントを同時に作成し、そのアカウント UUID を accountId に設定します。
     *
     * @param uuid     プレイヤー UUID
     * @param mcid     Minecraft ID
     * @param globalIp グローバル IP
     */
    public void registerNewUser(UUID uuid, String mcid, String globalIp) {
        LocalDateTime now = LocalDateTime.now();
        UUID systemUuid = SystemUser.INSTANCE.getUuid();

        // 1. user を先に INSERT（account_id は NULL で登録）
        UserModel model = new UserModel(
            uuid,
            mcid,
            now,
            now,
            globalIp,
            null,
            false,
            null,
            true,
            0,
            now,
            now,
            systemUuid,     // createdBy: System User
            systemUuid,     // updatedBy: System User
            false
        );
        userRepository.insert(model);

        // 2. account を INSERT（user が存在するため FK_account_user を満たせる）
        AccountModel account = accountService.createAccount(uuid, mcid, 0, systemUuid);
        UUID accountId = account.getUuid();

        // 3. user.account_id を更新し、新規作成した account を選択状態にする
        userRepository.updateAccountId(uuid, accountId, systemUuid);

        Logger.log(LogId.I_5050, mcid, uuid);
    }

    /**
     * UUID でユーザーを取得します。
     *
     * @param uuid プレイヤー UUID
     * @return ユーザーモデル、存在しない場合は null
     */
    public UserModel getUser(UUID uuid) {
        try {
            return userRepository.findByUuid(uuid);
        } catch (Exception e) {
            Logger.log(LogId.W_5052, uuid, e.getMessage());
            return null;
        }
    }

    /**
     * Minecraft ID でユーザーを取得します。
     * 管理コマンドのオフライン対象解決では、Bukkit のキャッシュではなく API の登録情報を正とします。
     *
     * @param mcid Minecraft ID
     * @return ユーザーモデル。存在しないか API 取得に失敗した場合は null
     */
    public UserModel getUserByMcid(String mcid) {
        try {
            return userRepository.findByMcid(mcid);
        } catch (Exception e) {
            Logger.log(LogId.W_5052, mcid, e.getMessage());
            return null;
        }
    }

    /**
     * Minecraft ID の補完候補を取得します。
     *
     * @param prefix Minecraft ID の前方一致検索文字列
     * @return 参加履歴のある Minecraft ID 一覧
     */
    public List<String> getMcidSuggestions(String prefix) {
        return userRepository.findMcids(prefix);
    }

    public UserModel setPermission(UUID uuid, int permission, UUID updatedBy) {
        userRepository.updatePermission(uuid, permission, updatedBy);
        return getUser(uuid);
    }

    /**
     * ユーザーの BAN 状態を更新します。
     *
     * @param uuid 対象ユーザー UUID
     * @param banIndefinite 無期限 BAN かどうか
     * @param banDate 有期限 BAN の終了日時。無期限の場合は無視されます
     * @param updatedBy 更新者 UUID
     * @return 更新後のユーザーモデル
     */
    public UserModel setBan(UUID uuid, boolean banIndefinite, LocalDateTime banDate, UUID updatedBy) {
        return userRepository.updateBan(
                uuid,
                banIndefinite,
                banIndefinite ? null : banDate,
                updatedBy
        );
    }

    private boolean isActiveBan(UserModel user, LocalDateTime now) {
        return user.getBanIndefinite()
                || user.getBanDate() != null && user.getBanDate().isAfter(now);
    }

    /**
     * ユーザー履歴を登録します。
     * 他 feature から渡された履歴イベントを user feature が所有する履歴 API へ登録します。
     *
     * @param userUuid  対象ユーザー UUID
     * @param eventType 履歴種別
     * @param source    履歴発生元
     * @param message   履歴メッセージ
     * @throws IllegalArgumentException 必須項目が欠落している場合
     */
    public void recordUserHistory(UUID userUuid, String eventType, String source, String message) {
        if (userUuid == null) {
            throw new IllegalArgumentException("userUuid is required");
        }
        if (eventType == null || eventType.isEmpty()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source is required");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message is required");
        }
        userRepository.insertHistory(userUuid, eventType, source, message);
    }
}
