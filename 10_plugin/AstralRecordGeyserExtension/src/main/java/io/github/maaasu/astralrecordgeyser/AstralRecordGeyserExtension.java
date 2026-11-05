package io.github.maaasu.astralrecordgeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent.SkullTextureType;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.Identifier;
import java.io.IOException;
import java.util.LinkedHashSet;

/** AstralRecord のヘッドと Bedrock 向け専用モデルを Geyser へ登録します。 */
public final class AstralRecordGeyserExtension implements Extension {
    private static final Identifier TEXT_DISPLAY = Identifier.of("minecraft", "text_display");
    private static final Identifier DROPPED_ITEM = Identifier.of("minecraft", "item");
    private final DamagePackDistribution damagePacks = new DamagePackDistribution(this);
    private CustomEntityDefinition damageNumberDefinition;
    private DamageNumberEntity damageNumberProperties;
    private CustomEntityDefinition actionRingDefinition;
    private ActionRingEntity actionRingProperties;

    @Subscribe
    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        var definition = DamageNumberEntity.definition();
        event.register(definition);
        damageNumberDefinition = definition;
        var ringDefinition = ActionRingEntity.definition();
        event.register(ringDefinition);
        actionRingDefinition = ringDefinition;
    }

    @Subscribe
    public void onDefineEntityProperties(GeyserDefineEntityPropertiesEvent event) {
        if (damageNumberDefinition != null) {
            damageNumberProperties = DamageNumberEntity.registerProperties(event);
        }
        if (actionRingDefinition != null) {
            actionRingProperties = ActionRingEntity.registerProperties(event);
        }
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        if (damageNumberProperties != null || actionRingProperties != null) damagePacks.start();
    }

    @Subscribe
    public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        damagePacks.register(event);
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        damagePacks.onLogin(event.connection());
    }

    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        damagePacks.disconnect(event.connection());
    }

    @Subscribe(ignoreCancelled = true)
    public void onServerSpawn(ServerSpawnEntityEvent event) {
        if (event.entityType().is(DROPPED_ITEM) && ActionRingFrame.isIcon(event.uuid())) {
            event.preSpawnConsumer(entity -> {
                entity.override(GeyserEntityDataTypes.WIDTH, 0f);
                entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
            });
            return;
        }
        if (!event.entityType().is(TEXT_DISPLAY)) return;
        ActionRingFrame frame = ActionRingFrame.decode(event.uuid(), event.entityId());
        if (frame != null) {
            onActionRingFrame(event, frame);
            return;
        }
        DamageNumber number = DamageNumber.decode(event.uuid(), event.entityId());
        if (number == null) return;

        var properties = damageNumberProperties;
        boolean usePack = properties != null && damagePacks.ready(event.connection());
        if (usePack != number.sidecar()) {
            event.setCancelled(true);
        } else if (usePack) {
            event.definition(damageNumberDefinition);
            event.preSpawnConsumer(entity -> properties.initialize(entity, number));
        }
    }

    private void onActionRingFrame(ServerSpawnEntityEvent event, ActionRingFrame frame) {
        var entities = event.connection().entities();
        for (int slot = 0; slot < 8; slot++) {
            var icon = entities.byUuid(frame.iconUuid(slot));
            if (icon != null) {
                float scale = frame.iconScale(slot);
                if (!Float.valueOf(scale).equals(icon.override(GeyserEntityDataTypes.SCALE))) {
                    icon.override(GeyserEntityDataTypes.SCALE, scale);
                }
            }
        }
        var properties = actionRingProperties;
        if (properties == null || !damagePacks.actionRingReady(event.connection())) {
            event.setCancelled(true);
            return;
        }
        if (!frame.update()) {
            event.definition(actionRingDefinition);
            event.preSpawnConsumer(entity -> properties.initialize(entity, frame));
            return;
        }
        event.setCancelled(true);
        var ring = entities.byJavaId(frame.targetId());
        if (ring != null && actionRingDefinition.equals(ring.definition())) {
            properties.apply(ring, frame, true);
        }
    }

    /**
     * Geyser起動時に全ヘッドを登録する。API失敗時も組込みヘッドは有効で、失敗を明示する。
     * @param event 起動時限定の登録イベント。戻り後の非同期追加は行わない。
     */
    @Subscribe
    public void onDefineCustomSkulls(GeyserDefineCustomSkullsEvent event) {
        LinkedHashSet<String> textures = new LinkedHashSet<>(BuiltinHeadTextures.ALL);
        for (String texture : textures) event.register(texture, SkullTextureType.PROFILE);
        HeadCatalog catalog;
        try {
            HeadApiConfig config = HeadApiConfig.load(dataFolder());
            if (config.allowInsecureTls) {
                logger().warning("AstralRecord head API TLS certificate and hostname verification are disabled; use only in a trusted local or private network.");
            }
            catalog = HeadApiClient.fetch(config);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger().error("AstralRecord head API loading interrupted; only built-in heads were registered. Restart the proxy after recovery.");
            return;
        } catch (IOException | RuntimeException exception) {
            logger().error("AstralRecord head API loading failed (" + exception.getClass().getSimpleName()
                + "); only built-in heads are guaranteed. Check config.yml, API availability, credentials and TLS trust, then restart the proxy.");
            return;
        }
        for (String texture : catalog.textures()) {
            if (textures.add(texture)) event.register(texture, SkullTextureType.PROFILE);
        }
        try (var mojang = new MojangSkinClient()) {
            var playerSkins = PlayerHeadCache.resolve(dataFolder().resolve("player-head-skins.json"),
                catalog.playerUuids(), mojang::fetch, logger()::warning);
            var distinctHashes = new LinkedHashSet<>(playerSkins.values());
            for (String hash : distinctHashes) event.register(hash, SkullTextureType.SKIN_HASH);
            logger().info("Registered AstralRecord heads: textures=" + textures.size()
                + ", player UUIDs resolved=" + playerSkins.size() + "/" + catalog.playerUuids().size()
                + ", distinct player skins=" + distinctHashes.size());
        } catch (RuntimeException exception) {
            logger().error("AstralRecord player head registration failed (" + exception.getClass().getSimpleName()
                + "); built-in and master heads were registered.");
        }
    }
}
