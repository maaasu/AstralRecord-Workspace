package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network有効時のチャンネル接続・debugロール照会を集約します。 */
public final class NetworkChannelAccessService {
    private static final NetworkChannelAccessService INSTANCE = new NetworkChannelAccessService();

    private final NetworkChannelAccessClient client = new NetworkChannelAccessClient();
    private final AtomicBoolean warningLogged = new AtomicBoolean();

    private NetworkChannelAccessService() {
    }

    /**
     * 共有のチャンネルロール照会サービスを返します。
     *
     * @return 共有サービス
     */
    public static NetworkChannelAccessService getInstance() {
        return INSTANCE;
    }

    /**
     * Network管理が有効か返します。
     *
     * @return Network管理が有効なら{@code true}
     */
    public boolean isManaged() {
        return ConfigProperties.getInstance().isNetworkEnabled();
    }

    /**
     * 接続前にNetwork APIへ問い合わせ、成功したロールだけをキャッシュします。
     * API失敗時はキャッシュやローカルconfigから許可を補完しません。
     *
     * @param userUuid 接続するプレイヤーUUID
     * @return Network APIのロール。失敗時は{@code null}
     */
    public NetworkChannelAccess resolveForLogin(UUID userUuid) {
        return resolve(userUuid);
    }

    /**
     * オンラインプレイヤーのロールを更新します。
     *
     * @param userUuid 更新対象のプレイヤーUUID
     * @return API応答。取得失敗時は{@code null}
     */
    public NetworkChannelAccess refresh(UUID userUuid) {
        return resolve(userUuid);
    }

    private NetworkChannelAccess resolve(UUID userUuid) {
        if (!isManaged() || userUuid == null) return null;
        try {
            NetworkChannelAccess access = client.getAccess(
                userUuid,
                ConfigProperties.getInstance().getNetworkChannelName()
            );
            NetworkChannelAccessRegistry.replace(access);
            warningLogged.set(false);
            return access;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logWarningOnce(exception);
            return null;
        } catch (IOException | RuntimeException exception) {
            logWarningOnce(exception);
            return null;
        }
    }

    private void logWarningOnce(Throwable failure) {
        if (warningLogged.compareAndSet(false, true)) {
            Logger.log(LogId.W_7121, failure, failure.getClass().getSimpleName());
        }
    }
}
