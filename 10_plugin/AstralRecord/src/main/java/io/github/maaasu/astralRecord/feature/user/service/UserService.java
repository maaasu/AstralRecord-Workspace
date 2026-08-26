package io.github.maaasu.astralRecord.feature.user.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ユーザー機能のビジネスロジックを担うサービスクラス。
 * プレイヤーのログイン・初回登録処理を管理します。
 */
public class UserService {

    private final UserRepository userRepository;
    private final AccountService accountService;

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
    public void onAsyncPreLogin(UUID uuid, String mcid, String globalIp) {
        UserModel existing;
        try {
            // 初参加チェックのため 404 が正常系となる findByUuidSilent を使用する
            // findByUuid は「存在すべきユーザーが見つからない」場面向けであり WARN が出るため使用しないこと
            existing = userRepository.findByUuidSilent(uuid);
        } catch (Exception e) {
            Logger.log(LogId.W_5051, mcid, e.getMessage());
            return;
        }

        if (existing == null) {
            try {
                registerNewUser(uuid, mcid, globalIp);
            } catch (Exception e) {
                Logger.log(LogId.W_5051, mcid, e.getMessage());
            }
        } else {
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

    public UserModel setPermission(UUID uuid, int permission, UUID updatedBy) {
        userRepository.updatePermission(uuid, permission, updatedBy);
        return getUser(uuid);
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
