package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 繝代・繝・ぅ繧ｯ繝ｫ騾∽ｿ｡驥上ｒ繝励Λ繧ｰ繧､繝ｳ險ｭ螳壹・蟇・ｺｦ縺ｧ陬懈ｭ｣縺励※陦ｨ遉ｺ縺吶ｋ繧ｵ繝ｼ繝薙せ縲・ * 蟆・擂逧・↑繝励Ξ繧､繝､繝ｼ蛻･蟇・ｺｦ險ｭ螳壹↓蛯吶∴縲√・繝ｬ繧､繝､繝ｼ蜊倅ｽ阪・陬懈ｭ｣菫よ焚繧ょ女縺大叙繧後ｋ縲・ */
public class ParticleDisplayService {

    private static final double PLUGIN_PARTICLE_DENSITY_SCALE = 1.0D;

    private final PlayerSettingService playerSettingService;

    public ParticleDisplayService() {
        this(null);
    }

    /**
     * 繝励Ξ繧､繝､繝ｼ險ｭ螳壹し繝ｼ繝薙せ繧貞盾辣ｧ縺励※繝代・繝・ぅ繧ｯ繝ｫ陦ｨ遉ｺ繧ｵ繝ｼ繝薙せ繧呈ｧ狗ｯ峨＠縺ｾ縺吶・     *
     * @param playerSettingService 繝励Ξ繧､繝､繝ｼ險ｭ螳壹し繝ｼ繝薙せ縲よ悴蛻晄悄蛹匁凾縺ｯ讓呎ｺ門ｯ・ｺｦ繧剃ｽｿ逕ｨ縺励∪縺吶・     */
    public ParticleDisplayService(@Nullable PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnWorld(
            astPlayer,
            world,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        spawnWorld(
            world,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    /**
     * 蟇ｾ雎｡繝励Ξ繧､繝､繝ｼ縺ｮ險ｭ螳壼ｯ・ｺｦ繧貞渚譏縺励※繝ｯ繝ｼ繝ｫ繝牙髄縺代↓繝代・繝・ぅ繧ｯ繝ｫ繧定｡ｨ遉ｺ縺励∪縺吶・     *
     * @param astPlayer 蟇・ｺｦ險ｭ螳壹・蜿ら・蜈・・繝ｬ繧､繝､繝ｼ
     * @param world 繝ｯ繝ｼ繝ｫ繝・     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     */
    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnWorld(astPlayer, world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 蟇ｾ雎｡繝励Ξ繧､繝､繝ｼ縺ｮ險ｭ螳壼ｯ・ｺｦ繧貞渚譏縺励※縲∬ｿｽ蜉繝・・繧ｿ莉倥″繝代・繝・ぅ繧ｯ繝ｫ繧偵Ρ繝ｼ繝ｫ繝牙髄縺代↓陦ｨ遉ｺ縺励∪縺吶・     *
     * @param astPlayer 蟇ｾ雎｡蟇・ｺｦ險ｭ螳壹・蜿ら・蜈・・繝ｬ繧､繝､繝ｼ
     * @param world 繝ｯ繝ｼ繝ｫ繝・     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param data 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ縲ゆｸ崎ｦ√↑蝣ｴ蜷医・ null
     * @param <T> 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ蝙・     */
    public <T> void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnWorld(world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(astPlayer), data);
    }

    /**
     * 繝ｯ繝ｼ繝ｫ繝牙髄縺代↓繝代・繝・ぅ繧ｯ繝ｫ繧定｡ｨ遉ｺ縺吶ｋ縲・     *
     * @param world 繝ｯ繝ｼ繝ｫ繝・     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param playerDensityScale 繝励Ξ繧､繝､繝ｼ蛟句挨縺ｮ蟇・ｺｦ蛟咲紫・域悴險ｭ螳壽凾縺ｯ 1.0・・     */
    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnWorld(world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    /**
     * 繝ｯ繝ｼ繝ｫ繝牙髄縺代↓霑ｽ蜉繝・・繧ｿ莉倥″繝代・繝・ぅ繧ｯ繝ｫ繧定｡ｨ遉ｺ縺吶ｋ縲・     *
     * @param world 繝ｯ繝ｼ繝ｫ繝・     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param playerDensityScale 繝励Ξ繧､繝､繝ｼ蛟句挨縺ｮ蟇・ｺｦ菫よ焚・域悴險ｭ螳壽凾縺ｯ 1.0・・     * @param data 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ縲ゆｸ崎ｦ√↑蝣ｴ蜷医・ null
     * @param <T> 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ蝙・     */
    public <T> void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        int count = resolveCount(baseCount, playerDensityScale);
        if (count <= 0) {
            return;
        }
        world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
    }

    /**
     * 謖・ｮ壹・繝ｬ繧､繝､繝ｼ縺ｫ縺ｮ縺ｿ繝代・繝・ぅ繧ｯ繝ｫ繧帝∽ｿ｡縺吶ｋ縲・     *
     * @param viewer 騾∽ｿ｡蜈医・繝ｬ繧､繝､繝ｼ
     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     */
    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    /**
     * 謖・ｮ壹・繝ｬ繧､繝､繝ｼ縺ｫ縺ｮ縺ｿ霑ｽ蜉繝・・繧ｿ莉倥″繝代・繝・ぅ繧ｯ繝ｫ繧帝∽ｿ｡縺吶ｋ縲・     *
     * @param viewer 騾∽ｿ｡蜈医・繝ｬ繧､繝､繝ｼ
     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param data 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ縲ゆｸ崎ｦ√↑蝣ｴ蜷医・ null
     * @param <T> 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ蝙・     */
    public <T> void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewer(viewer.getBukkit(), location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(viewer), data);
    }

    /**
     * 謖・ｮ壹・繝励Ξ繧､繝､繝ｼ縺ｫ縺ｮ縺ｿ繝代・繝・ぅ繧ｯ繝ｫ繧帝∽ｿ｡縺吶ｋ縲・     *
     * @param viewer 騾∽ｿ｡蜈医・繝ｬ繧､繝､繝ｼ
     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param playerDensityScale 繝励Ξ繧､繝､繝ｼ蛟句挨縺ｮ蟇・ｺｦ蛟咲紫・域悴險ｭ螳壽凾縺ｯ 1.0・・     */
    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    /**
     * 謖・ｮ壹・繝励Ξ繧､繝､繝ｼ縺ｫ縺ｮ縺ｿ霑ｽ蜉繝・・繧ｿ莉倥″繝代・繝・ぅ繧ｯ繝ｫ繧帝∽ｿ｡縺吶ｋ縲・     *
     * @param viewer 騾∽ｿ｡蜈医・繝ｬ繧､繝､繝ｼ
     * @param location 陦ｨ遉ｺ蠎ｧ讓・     * @param particle 繝代・繝・ぅ繧ｯ繝ｫ遞ｮ蛻･
     * @param baseCount 蝓ｺ貅門区焚
     * @param offsetX X諡｡謨｣
     * @param offsetY Y諡｡謨｣
     * @param offsetZ Z諡｡謨｣
     * @param extra 霑ｽ蜉繝代Λ繝｡繝ｼ繧ｿ
     * @param playerDensityScale 繝励Ξ繧､繝､繝ｼ蛟句挨縺ｮ蟇・ｺｦ菫よ焚・域悴險ｭ螳壽凾縺ｯ 1.0・・     * @param data 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ縲ゆｸ崎ｦ√↑蝣ｴ蜷医・ null
     * @param <T> 繝代・繝・ぅ繧ｯ繝ｫ霑ｽ蜉繝・・繧ｿ蝙・     */
    public <T> void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        int count = resolveCount(baseCount, playerDensityScale);
        if (count <= 0) {
            return;
        }
        viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
    }

    private double resolvePlayerDensityScale(@NotNull AstPlayer astPlayer) {
        if (playerSettingService == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return playerSettingService.getParticleDensityScale(astPlayer.getUser().getUuid());
    }

    private int resolveCount(int baseCount, double playerDensityScale) {
        if (baseCount <= 0) {
            return 0;
        }
        double pluginDensity = Math.max(0.0D, PLUGIN_PARTICLE_DENSITY_SCALE);
        double effectiveDensity = pluginDensity * Math.max(0.0D, playerDensityScale);
        return Math.max(0, (int) Math.round(baseCount * effectiveDensity));
    }
}
