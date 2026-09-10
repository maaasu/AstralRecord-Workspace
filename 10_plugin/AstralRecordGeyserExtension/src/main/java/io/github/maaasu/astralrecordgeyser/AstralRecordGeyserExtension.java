package io.github.maaasu.astralrecordgeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent.SkullTextureType;
import org.geysermc.geyser.api.extension.Extension;
import java.io.IOException;
import java.util.LinkedHashSet;

/** AstralRecordのマスター・プレイヤー・固定GUIヘッドをGeyserへ登録する。 */
public final class AstralRecordGeyserExtension implements Extension {
    /**
     * Geyser起動時に全ヘッドを登録する。API失敗時も組込みヘッドは有効で、失敗を明示する。
     * @param event 起動時限定の登録イベント。戻り後の非同期追加は行わない。
     */
    @Subscribe
    public void onDefineCustomSkulls(GeyserDefineCustomSkullsEvent event) {
        LinkedHashSet<String> textures = new LinkedHashSet<>(BuiltinHeadTextures.ALL);
        for (String texture : textures) event.register(texture, SkullTextureType.PROFILE);
        try {
            HeadApiConfig config = HeadApiConfig.load(dataFolder());
            if (config.allowInsecureTls) {
                logger().warning("AstralRecord head API TLS certificate and hostname verification are disabled; use only in a trusted local or private network.");
            }
            HeadCatalog catalog = HeadApiClient.fetch(config);
            for (String texture : catalog.textures()) {
                if (textures.add(texture)) event.register(texture, SkullTextureType.PROFILE);
            }
            for (var uuid : catalog.playerUuids()) event.register(uuid.toString(), SkullTextureType.UUID);
            logger().info("Registered AstralRecord heads: textures=" + textures.size()
                + ", player UUIDs=" + catalog.playerUuids().size());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger().error("AstralRecord head API loading interrupted; only built-in heads were registered. Restart the proxy after recovery.");
        } catch (IOException | RuntimeException exception) {
            logger().error("AstralRecord head API loading failed (" + exception.getClass().getSimpleName()
                + "); only built-in heads are guaranteed. Check config.yml, API availability, credentials and TLS trust, then restart the proxy.");
        }
    }
}
