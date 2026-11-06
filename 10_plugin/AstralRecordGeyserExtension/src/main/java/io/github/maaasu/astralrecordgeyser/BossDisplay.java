package io.github.maaasu.astralrecordgeyser;

import java.util.function.Predicate;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/** ボスの通常エンティティと演出用 TextDisplay を、対応パックの Bedrock モデルへ変換します。 */
final class BossDisplay {
    private static final Identifier TEXT_DISPLAY = Identifier.of("minecraft", "text_display");

    private final Identifier carrier;
    private final CustomEntityDefinition body;
    private final CustomEntityDefinition skill;
    private final long bodyMarker;
    private final long skillMarker;
    private final long motionMarker;
    private final int maxStyle;
    private final int maxMotion;
    private final Predicate<DamagePack> bodyAndSkillAssets;
    private final Predicate<DamagePack> motionAssets;
    private boolean registered;
    private BossSkillEntity skillProperties;
    private BossMotion motionProperties;

    BossDisplay(String id, String carrier, long bodyMarker, long skillMarker, long motionMarker, int maxStyle, int maxMotion,
                Predicate<DamagePack> bodyAndSkillAssets, Predicate<DamagePack> motionAssets) {
        this.carrier = Identifier.of("minecraft", carrier);
        body = CustomEntityDefinition.of(Identifier.of("astralrecord", id));
        skill = CustomEntityDefinition.of(Identifier.of("astralrecord", id + "_skill"));
        this.bodyMarker = bodyMarker;
        this.skillMarker = skillMarker;
        this.motionMarker = motionMarker;
        this.maxStyle = maxStyle;
        this.maxMotion = maxMotion;
        this.bodyAndSkillAssets = bodyAndSkillAssets;
        this.motionAssets = motionAssets;
    }

    void register(GeyserDefineEntitiesEvent event) {
        event.register(body);
        event.register(skill);
        registered = true;
    }

    void registerProperties(GeyserDefineEntityPropertiesEvent event) {
        if (!registered) return;
        skillProperties = BossSkillEntity.registerProperties(event, skill.identifier(), maxStyle);
        motionProperties = BossMotion.registerProperties(event, body.identifier(), maxMotion);
    }

    boolean ready(DamagePackDistribution packs, GeyserConnection connection) {
        return skillProperties != null && motionProperties != null && packs.bossReady(connection, bodyAndSkillAssets);
    }

    boolean onSpawn(ServerSpawnEntityEvent event, DamagePackDistribution packs) {
        long marker = event.uuid().getMostSignificantBits();
        if (marker == bodyMarker && event.entityType().is(carrier)) {
            if (registered && packs.bossReady(event.connection(), bodyAndSkillAssets)) {
                event.definition(body);
            }
            return true;
        }
        if (!event.entityType().is(TEXT_DISPLAY)) return false;
        if (marker == motionMarker) {
            event.setCancelled(true);
            var frame = BossMotion.decode(event.uuid(), motionMarker, maxMotion);
            if (frame != null && motionProperties != null && packs.bossReady(event.connection(), motionAssets)) {
                GeyserEntity target = event.connection().entities().byJavaId(frame.targetId());
                if (target != null && body.equals(target.definition())) {
                    motionProperties.apply(target, frame);
                }
            }
            return true;
        }
        if (marker == skillMarker) {
            var properties = skillProperties;
            var frame = BossSkillEntity.decode(event.uuid(), skillMarker, maxStyle);
            if (frame == null || properties == null || !packs.bossReady(event.connection(), bodyAndSkillAssets)) {
                event.setCancelled(true);
            } else {
                event.definition(skill);
                event.preSpawnConsumer(entity -> properties.initialize(entity, frame));
            }
            return true;
        }
        return false;
    }
}
