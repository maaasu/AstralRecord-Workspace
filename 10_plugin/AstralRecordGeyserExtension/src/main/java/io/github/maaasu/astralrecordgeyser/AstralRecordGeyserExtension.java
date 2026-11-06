package io.github.maaasu.astralrecordgeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent.SkullTextureType;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.Identifier;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.UUID;

/** AstralRecord のヘッド、アイテム、Bedrock 向け専用エンティティを Geyser へ登録します。 */
public final class AstralRecordGeyserExtension implements Extension {
    private static final Identifier TEXT_DISPLAY = Identifier.of("minecraft", "text_display");
    private static final Identifier DROPPED_ITEM = Identifier.of("minecraft", "item");
    private static final long COMBAT_RESULT_TAG = 0xa57ac2e570000001L;
    private static final CustomEntityDefinition COMBAT_RESULT =
        CustomEntityDefinition.of(Identifier.of("astralrecord", "combat_result"));
    private final DamagePackDistribution damagePacks = new DamagePackDistribution(this);
    private CustomEntityDefinition damageNumberDefinition;
    private DamageNumberEntity damageNumberProperties;
    private CustomEntityDefinition actionRingDefinition;
    private ActionRingEntity actionRingProperties;
    private CustomEntityDefinition actionRingPanelDefinition;
    private ActionRingPanelEntity actionRingPanelProperties;
    private CustomEntityDefinition weaponAttackDefinition;
    private WeaponAttackEntity weaponAttackProperties;
    private CustomEntityDefinition skillEffectDefinition;
    private SkillEffectEntity skillEffectProperties;
    // Paper のボス用 UUID マーカー。並びは本体・技・モーション。
    private final BossDisplay sunbird = new BossDisplay("sunbird", "parrot",
        0x41535453554e4231L, 0x41535453554e4631L, 0x41535453554e4d31L, 5, 3,
        DamagePack::sunbird, DamagePack::sunbirdMotion);
    private final BossDisplay selenea = new BossDisplay("selenea", "witch",
        0x41535453454c4231L, 0x41535453454c4631L, 0x41535453454c4d31L, 10, 4,
        DamagePack::selenea, DamagePack::selenea);
    private WorldDisplayEntity worldDisplays;
    private HeldWeaponModelDisplay heldWeaponModels;

    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        damagePacks.defineItems(event);
    }

    @Subscribe
    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        var definition = DamageNumberEntity.definition();
        event.register(definition);
        damageNumberDefinition = definition;
        var ringDefinition = ActionRingEntity.definition();
        event.register(ringDefinition);
        actionRingDefinition = ringDefinition;
        var panelDefinition = ActionRingPanelEntity.definition();
        event.register(panelDefinition);
        actionRingPanelDefinition = panelDefinition;
        var attackDefinition = WeaponAttackEntity.definition();
        event.register(attackDefinition);
        weaponAttackDefinition = attackDefinition;
        var effectDefinition = SkillEffectEntity.definition();
        event.register(effectDefinition);
        skillEffectDefinition = effectDefinition;
        event.register(COMBAT_RESULT);
        sunbird.register(event);
        event.register(WorldDisplayEntity.DEFINITION);
        selenea.register(event);
    }

    @Subscribe
    public void onDefineEntityProperties(GeyserDefineEntityPropertiesEvent event) {
        if (damageNumberDefinition != null) {
            damageNumberProperties = DamageNumberEntity.registerProperties(event);
        }
        if (actionRingDefinition != null) {
            actionRingProperties = ActionRingEntity.registerProperties(event);
        }
        if (actionRingPanelDefinition != null) {
            actionRingPanelProperties = ActionRingPanelEntity.registerProperties(event);
        }
        if (weaponAttackDefinition != null) {
            weaponAttackProperties = WeaponAttackEntity.registerProperties(event);
        }
        sunbird.registerProperties(event);
        selenea.registerProperties(event);
        if (skillEffectDefinition != null) {
            skillEffectProperties = SkillEffectEntity.registerProperties(event, skillEffectDefinition.identifier());
        }
        worldDisplays = WorldDisplayEntity.registerProperties(event);
    }

    @Subscribe
    public void onDefineHeldWeaponProperty(GeyserDefineEntityPropertiesEvent event) {
        heldWeaponModels = new HeldWeaponModelDisplay(HeldWeaponModel.registerProperty(event));
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        damagePacks.start();
        if (heldWeaponModels != null) heldWeaponModels.start();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (heldWeaponModels != null) heldWeaponModels.shutdown();
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
        if (heldWeaponModels != null) heldWeaponModels.disconnect(event.connection());
    }

    /**
     * Velocity上のAstralRecordProxyから、接続時に配布した効果音の対応状況を照会します。
     *
     * @param playerId 接続中プレイヤーのJava側UUID
     * @return ログインまで完了した接続のパックに専用効果音がある場合はtrue
     */
    public boolean hasGameplaySounds(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection != null && damagePacks.gameplaySoundsReady(connection);
    }

    /**
     * Velocity 上の AstralRecordProxy に通常攻撃の軌跡・着弾の対応状況を返します。
     * @param playerId 接続中プレイヤーのJava側UUID
     * @return 接続時のパックに追加演出が揃い、ログインまで完了していればtrue
     */
    public boolean hasWeaponAttackProjectiles(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return weaponAttackProperties != null && connection != null
            && damagePacks.weaponAttackReady(connection, WeaponAttackEntity.MAX_STYLE);
    }

    /**
     * Velocity 上の AstralRecordProxy にプレイヤースキル演出の対応状況を返します。
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return ログインを完了した接続に対応パックを配布していれば true
     */
    public boolean hasSkillEffects(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return skillEffectProperties != null && connection != null && damagePacks.skillEffectsReady(connection);
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return セレネアの描画に対応するパックを登録し、ログインまで完了した場合は true
     */
    public boolean hasSelenea(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection != null && selenea.ready(damagePacks, connection);
    }

    /**
     * Velocity 上の AstralRecordProxy から、接続時に配布したツールチップの対応状況を照会します。
     *
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return ログインまで完了した接続のパックに対応する字形と枠がある場合は true
     */
    public boolean hasItemTooltips(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection != null && damagePacks.itemTooltipsReady(connection);
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return ログインを完了した接続のアクションリング HUD 互換性識別子。非対応なら null
     */
    public String actionRingHudRevision(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection == null ? null : damagePacks.actionRingHudRevision(connection);
    }

    @Subscribe
    public void onServerTransfer(ServerTransferEvent event) {
        updateHeldWeaponModel(event.connection(), 0);
    }

    @Subscribe(ignoreCancelled = true)
    public void onServerSpawn(ServerSpawnEntityEvent event) {
        if (worldDisplays != null) {
            worldDisplays.onSpawn(event, damagePacks);
            if (event.isCancelled()) return;
        }
        if (sunbird.onSpawn(event, damagePacks) || selenea.onSpawn(event, damagePacks)) return;
        if (event.entityType().is(DROPPED_ITEM) && ActionRingFrame.isIcon(event.uuid())) {
            GeyserEntity panel = panelFor(event, ActionRingPanelEntity.targetId(event.uuid()));
            if (panel != null && ActionRingPanelEntity.replacesFallback(panel, (int) event.uuid().getLeastSignificantBits())) {
                event.setCancelled(true);
                return;
            }
            event.preSpawnConsumer(entity -> {
                entity.override(GeyserEntityDataTypes.WIDTH, 0f);
                entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
            });
            return;
        }
        if (!event.entityType().is(TEXT_DISPLAY)) return;
        if (SkillEffectEntity.isMarker(event.uuid())) {
            var properties = skillEffectProperties;
            var frame = SkillEffectEntity.decode(event.uuid());
            if (frame == null || properties == null || !damagePacks.skillEffectsReady(event.connection())) {
                event.setCancelled(true);
            } else {
                event.definition(skillEffectDefinition);
                event.preSpawnConsumer(entity -> properties.initialize(entity, frame));
            }
            return;
        }
        if (WeaponAttackEntity.isMarker(event.uuid())) {
            var properties = weaponAttackProperties;
            int style = WeaponAttackEntity.styleOf(event.uuid());
            if (style > WeaponAttackEntity.MAX_STYLE || properties == null
                    || !damagePacks.weaponAttackReady(event.connection(), style)) {
                event.setCancelled(true);
            } else {
                event.definition(weaponAttackDefinition);
                event.preSpawnConsumer(entity -> properties.initialize(entity, style));
            }
            return;
        }
        HeldWeaponModel heldWeapon = HeldWeaponModel.decode(event.uuid());
        if (heldWeapon != null) {
            event.setCancelled(true);
            updateHeldWeaponModel(event.connection(), heldWeapon.id());
            return;
        }
        if (event.uuid().getMostSignificantBits() == COMBAT_RESULT_TAG
            && event.uuid().getLeastSignificantBits() == Integer.toUnsignedLong(event.entityId())) {
            if (damagePacks.combatResultReady(event.connection())) {
                // TextDisplayの本文変換を残したまま、透明な防具立てを紋章へ置き換えます。
                event.definition(COMBAT_RESULT);
                event.preSpawnConsumer(entity -> {
                    entity.override(GeyserEntityDataTypes.SCALE, 1f);
                    entity.override(GeyserEntityDataTypes.WIDTH, 0f);
                    entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
                });
            }
            return;
        }
        if (ActionRingPanelEntity.isUpdate(event.uuid())) {
            event.setCancelled(true);
            GeyserEntity panel = panelFor(event, ActionRingPanelEntity.targetId(event.uuid()));
            if (panel != null) {
                actionRingPanelProperties.apply(panel, event.uuid(), damagePacks.actionRingSprites(event.connection()));
            }
            return;
        }
        if (ActionRingPanelEntity.isFallback(event.uuid())) {
            GeyserEntity panel = panelFor(event, ActionRingPanelEntity.targetId(event.uuid()));
            if (panel != null && ActionRingPanelEntity.replacesFallback(panel, 8)) {
                event.setCancelled(true);
            }
            return;
        }
        ActionRingFrame frame = ActionRingFrame.decode(event.uuid(), event.entityId());
        if (frame != null) {
            onActionRingFrame(event, frame);
            return;
        }
        DamageNumber number = DamageNumber.decode(event.uuid(), event.entityId());
        if (number == null) return;

        var properties = damageNumberProperties;
        boolean usePack = properties != null && damagePacks.readyForDamageNumbers(event.connection());
        if (usePack != number.sidecar()) {
            event.setCancelled(true);
        } else if (usePack) {
            event.definition(damageNumberDefinition);
            event.preSpawnConsumer(entity -> properties.initialize(entity, number));
        }
    }

    private void updateHeldWeaponModel(GeyserConnection connection, int requestedModel) {
        if (heldWeaponModels != null) {
            damagePacks.heldWeaponModel(connection, requestedModel)
                .ifPresent(modelId -> heldWeaponModels.update(connection, modelId));
        }
    }

    private GeyserEntity panelFor(ServerSpawnEntityEvent event, int targetId) {
        GeyserEntity ring = event.connection().entities().byJavaId(targetId);
        return ring != null && actionRingPanelProperties != null && actionRingPanelDefinition.equals(ring.definition())
            ? ring : null;
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
        if (!frame.update()) {
            if (actionRingPanelProperties != null && !damagePacks.actionRingSprites(event.connection()).isEmpty()) {
                event.definition(actionRingPanelDefinition);
                event.preSpawnConsumer(entity -> actionRingPanelProperties.pose().initialize(entity, frame));
            } else if (actionRingProperties != null && damagePacks.actionRingReady(event.connection())) {
                event.definition(actionRingDefinition);
                event.preSpawnConsumer(entity -> actionRingProperties.initialize(entity, frame));
            } else {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        var ring = entities.byJavaId(frame.targetId());
        if (ring != null && actionRingPanelProperties != null && actionRingPanelDefinition.equals(ring.definition())) {
            actionRingPanelProperties.pose().apply(ring, frame, true);
        } else if (ring != null && actionRingProperties != null && actionRingDefinition.equals(ring.definition())) {
            actionRingProperties.apply(ring, frame, true);
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
