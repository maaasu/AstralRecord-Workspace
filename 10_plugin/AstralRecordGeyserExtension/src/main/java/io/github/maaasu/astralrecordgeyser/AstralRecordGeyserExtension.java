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
import java.lang.reflect.Method;
import java.util.Optional;

import java.util.LinkedHashSet;
import java.util.UUID;

/** AstralRecord のヘッド、アイテム、Bedrock 向け専用エンティティを Geyser へ登録します。 */
public final class AstralRecordGeyserExtension implements Extension {
    private static final Identifier TEXT_DISPLAY = Identifier.of("minecraft", "text_display");
    private static final Identifier DROPPED_ITEM = Identifier.of("minecraft", "item");
    private static final long COMBAT_RESULT_TAG = 0xa57ac2e570000001L;
    private static final CustomEntityDefinition COMBAT_RESULT =
        CustomEntityDefinition.of(Identifier.of("astralrecord", "combat_result"));
    private static final String QUEST_HUD_KEY = "astralrecord_quest_hud";
    private static final ClassValue<Optional<Method>> JSON_UI_SENDERS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> connectionType) {
            // 公開APIにはTIP送信がないため、Geyser本体のJSON UI送信口がある場合だけ使用します。
            try {
                return Optional.of(connectionType.getMethod("sendJsonUIData", String.class, String.class));
            } catch (NoSuchMethodException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };
    private final LatchedTipDelivery questHudDelivery = new LatchedTipDelivery();
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
    private FishingVisualEntity fishingVisuals;
    private CombatRewardPlaqueEntity combatRewardPlaque;
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
        event.register(FishingVisualEntity.DEFINITION);
        event.register(COMBAT_RESULT);
        event.register(CombatRewardPlaqueEntity.DEFINITION);
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
        fishingVisuals = FishingVisualEntity.registerProperties(event);
        combatRewardPlaque = CombatRewardPlaqueEntity.registerProperties(event);
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
        questHudDelivery.clear();
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
        questHudDelivery.forget(event.connection());
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
     * Velocity 上の AstralRecordProxy に固定UVのプレイヤースキル演出の対応状況を返します。
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return ログインを完了した接続に対応パックを配布していれば true
     */
    public boolean hasSkillEffectsV2(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return skillEffectProperties != null && connection != null && damagePacks.skillEffectsV2Ready(connection);
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

    /** @return 討伐報酬のプレートに対応したパックを登録し、ログインを完了していれば true */
    public boolean hasCombatRewardPlaque(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return combatRewardPlaque != null && connection != null && damagePacks.combatRewardPlaqueReady(connection);
    }

    /**
     * @param playerId 接続中プレイヤーの Java 側 UUID
     * @return ログインを完了した接続のアクションリング HUD 互換性識別子。非対応なら null
     */
    public String actionRingHudRevision(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection == null ? null : damagePacks.actionRingHudRevision(connection);
    }

    /**
     * @param playerId 接続中プレイヤーのJava側UUID
     * @return 対応パックを登録済みで、TIPを送信できる接続の場合はtrue
     */
    public boolean hasQuestHud(UUID playerId) {
        var connection = geyserApi().connectionByUuid(playerId);
        return connection != null && damagePacks.questHudReady(connection) && forcesResourcePacks()
            && JSON_UI_SENDERS.get(connection.getClass()).isPresent();
    }

    /**
     * クエストHUDの文字列をGeyserのJSON UIデータとして送ります。
     * @param playerId 接続中プレイヤーのJava側UUID
     * @param text 表示文字列。空白一文字で消去
     * @return 対応接続へのTIP送信が成功した場合はtrue
     */
    public boolean sendQuestHud(UUID playerId, String text) {
        if (text == null || text.isEmpty() || text.length() > 512) return false;
        var connection = geyserApi().connectionByUuid(playerId);
        if (connection == null) {
            return false;
        }
        var sender = JSON_UI_SENDERS.get(connection.getClass());
        if (sender.isEmpty()) {
            return false;
        }
        boolean verified = damagePacks.questHudReady(connection) && forcesResourcePacks();
        return questHudDelivery.send(connection, " ".equals(text), verified, () -> {
            try {
                sender.get().invoke(connection, QUEST_HUD_KEY, text);
                return true;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // 失敗時はラッチ済み状態を保持し、同じ接続への消去を再試行できるようにする。
                return false;
            }
        });
    }

    /** パック受諾を公開APIで確認できないため、強制配布設定を読めた場合だけ専用TIPを許可する。 */
    private boolean forcesResourcePacks() {
        try {
            Object geyser = geyserApi();
            ClassLoader loader = geyser.getClass().getClassLoader();
            Object config = geyser.getClass().getMethod("config").invoke(geyser);
            Class<?> configType = Class.forName("org.geysermc.geyser.configuration.GeyserConfig", false, loader);
            Object gameplay = configType.getMethod("gameplay").invoke(config);
            Class<?> gameplayType = Class.forName(
                "org.geysermc.geyser.configuration.GeyserConfig$GameplayConfig", false, loader);
            return Boolean.TRUE.equals(gameplayType.getMethod("forceResourcePacks").invoke(gameplay));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
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
        if (FishingVisualEntity.onSpawn(event, damagePacks, fishingVisuals)) return;
        if (SkillEffectEntity.isMarker(event.uuid())) {
            var properties = skillEffectProperties;
            var frame = SkillEffectEntity.decode(event.uuid());
            if (frame == null || properties == null || !damagePacks.skillEffectsV2Ready(event.connection())) {
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
        var plaqueLayout = CombatRewardPlaqueEntity.decode(event.uuid(), event.entityId());
        if (plaqueLayout != null) {
            var properties = combatRewardPlaque;
            if (properties != null && damagePacks.combatRewardPlaqueReady(event.connection())) {
                event.definition(CombatRewardPlaqueEntity.DEFINITION);
                event.preSpawnConsumer(entity -> properties.initialize(entity, plaqueLayout));
            }
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
