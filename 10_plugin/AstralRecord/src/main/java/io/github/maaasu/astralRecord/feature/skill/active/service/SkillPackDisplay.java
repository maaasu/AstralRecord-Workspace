package io.github.maaasu.astralRecord.feature.skill.active.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService.SkillEffectsClient;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** スキル固有の短いアニメーションを、互換パックを使用する閲覧者に送ります。 */
public final class SkillPackDisplay {
    private static final long BEDROCK_MARKER = 0x41535452534b4c31L; // ASTRSKL1
    private static final Key FONT = Key.key("astralrecord", "skill_effect");
    private static final String GLYPH_TRANSLATION = "astralrecord.skill_effect.glyph";
    private static final int FIRST_GLYPH = 0xe400;
    private static final int FRAME_COUNT = 6;
    private static final int MAX_SLOTS = 170;
    private static final int MAX_ACTIVE = 128;
    private static final int MAX_VIEWERS = 32;
    private static final int MAX_CANDIDATES = 64;
    private static final double VIEW_DISTANCE = 64.0D;
    private static final int DISPLAY_TRANSLATION_INDEX = 11;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int TEXT_INDEX = 23;
    private static final int BACKGROUND_INDEX = 25;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** アトラスの順序に対応する演出段階です。 */
    public enum Phase { CAST, TRAIL, IMPACT, AREA }

    private final Plugin plugin;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final ResourcePackService resourcePacks;
    private final PlayerSettingService playerSettings;
    private final Map<String, Integer> slots;
    private final Map<Integer, List<WrappedDataValue>> glyphs = new HashMap<>();
    private final List<Sprite> active = new ArrayList<>();
    private @Nullable BukkitTask animation;

    /**
     * パックと同じ順序のスキル台帳を読み込みます。
     * @param plugin 描画の後始末を登録するプラグイン
     * @param resourcePacks 閲覧者の互換性を判定するサービス
     * @param playerSettings 閲覧者の表示密度を取得するサービス
     * @throws IllegalStateException 組み込み台帳が欠損または不正な場合
     */
    public SkillPackDisplay(Plugin plugin, ResourcePackService resourcePacks, PlayerSettingService playerSettings) {
        this.plugin = plugin;
        this.resourcePacks = resourcePacks;
        this.playerSettings = playerSettings;
        this.slots = loadSlots(plugin);
    }

    /** 配布用パックと同じ連番を読み込み、IDの重複と範囲外を拒否します。 */
    private static Map<String, Integer> loadSlots(Plugin plugin) {
        var source = plugin.getResource("skill-effect-slots.txt");
        if (source == null) {
            throw new IllegalStateException("Missing skill-effect-slots.txt");
        }
        try (var reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            Map<String, Integer> result = new HashMap<>();
            String id;
            while ((id = reader.readLine()) != null) {
                if (!id.matches("[a-z][a-z0-9_]*") || result.size() == MAX_SLOTS
                        || result.putIfAbsent(id, result.size()) != null) {
                    throw new IllegalStateException("Invalid skill effect slot: " + id);
                }
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read skill effect slots", exception);
        }
    }

    /**
     * 一地点で6コマの演出を再生します。サーバーのエンティティや当たり判定は生成しません。
     * @param skillId 演出を持つスキル ID
     * @param phase 発動、軌跡、着弾、範囲の段階
     * @param location 判定または演出の実座標
     * @param diameter スプライトの直径（ブロック）
     * @param source 発動者。発動者の非表示設定を判定しない場合はnull
     * @return パック演出で処理した閲覧者。既存粒子へのフォールバックから除外できる
     */
    public Set<Player> show(String skillId, Phase phase, Location location, float diameter, @Nullable Player source) {
        return show(skillId, phase, location, diameter, source, null, null);
    }

    /**
     * 解決済みの閲覧者がある場合は、その範囲内だけでスプライトを再生します。
     * @param skillId 演出を持つスキル ID
     * @param phase 発動、軌跡、着弾、範囲の段階
     * @param location 判定または演出の実座標
     * @param diameter スプライトの直径（ブロック）
     * @param source 発動者。発動者を持たない場合はnull
     * @param audience 元の演出が使用する閲覧者。nullなら近傍の閲覧者を検索
     * @param edition 対象の版。null の場合は両版
     * @return パック演出で処理した閲覧者
     */
    Set<Player> show(String skillId, Phase phase, Location location, float diameter, @Nullable Player source,
                     @Nullable List<Player> audience, @Nullable SkillEffectsClient edition) {
        Integer slot = slots.get(skillId);
        World world = location.getWorld();
        if (slot == null || world == null || !Bukkit.isPrimaryThread() || active.size() >= MAX_ACTIVE) {
            return Set.of();
        }
        try {
            int style = slot * Phase.values().length + phase.ordinal();
            int size = Math.clamp(Math.round(diameter * 16.0F), 1, 255);
            List<Player> java = new ArrayList<>();
            List<Player> bedrock = new ArrayList<>();
            Set<Player> handled = new HashSet<>();
            int checked = 0;
            if (audience == null && source != null) {
                collect(source, source, location, phase, java, bedrock, handled, edition);
            }
            var candidates = audience == null ? world.getNearbyPlayers(location, VIEW_DISTANCE) : audience;
            for (Player viewer : candidates) {
                if (checked++ >= MAX_CANDIDATES || handled.size() >= MAX_VIEWERS) break;
                if (audience != null || viewer != source) {
                    collect(viewer, source, location, phase, java, bedrock, handled, edition);
                }
            }
            if (java.isEmpty() && bedrock.isEmpty()) return handled;


            int id = PacketEntityIdAllocator.nextEntityId();
            PacketContainer spawn = spawnPacket(id, style, size, location);
            PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().writeSafely(0, List.of(id));
            destroy.getIntegerArrays().writeSafely(0, new int[]{id});

            List<WrappedDataValue> frames = java.isEmpty() ? List.of() : glyphs.computeIfAbsent(style, this::glyphFrames);
            PacketContainer javaSpawn = frames.isEmpty() ? null : spawnWithGlyph(spawn, id, size, frames.getFirst());
            if (animation == null) {
                animation = plugin.getServer().getScheduler().runTaskTimer(plugin, this::animate, 1L, 1L);
            }
            active.add(new Sprite(id, world, source, java, bedrock, destroy, frames, Bukkit.getCurrentTick()));
            if (javaSpawn != null) {
                for (Player viewer : java) {
                    if (!send(viewer, javaSpawn)) handled.remove(viewer);
                }
            }
            for (Player viewer : bedrock) {
                if (!send(viewer, spawn)) handled.remove(viewer);
            }
            return handled;
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9013, exception, world.getName());
            return Set.of();
        }
    }

    /** 範囲、発動者の可視性、パック、粒子密度の順に閲覧者を絞ります。 */
    private void collect(Player viewer, @Nullable Player source, Location location, Phase phase,
                         List<Player> java, List<Player> bedrock, Set<Player> handled,
                         @Nullable SkillEffectsClient edition) {
        if (!viewer.isOnline() || viewer.getWorld() != location.getWorld()
                || viewer.getLocation().distanceSquared(location) > VIEW_DISTANCE * VIEW_DISTANCE
                || (source != null && viewer != source && !viewer.canSee(source))) return;
        var client = resourcePacks.skillEffectsClient(viewer);
        if (client == SkillEffectsClient.NONE || (edition != null && client != edition)) return;
        handled.add(viewer);
        double density = density(viewer);
        if (density <= 0.0D || (client == SkillEffectsClient.JAVA
                && phase == Phase.TRAIL && density < 1.0D
                && ThreadLocalRandom.current().nextDouble() >= density)) return;
        (client == SkillEffectsClient.JAVA ? java : bedrock).add(viewer);
    }

    /** 設定キャッシュを使用し、読み込み前の閲覧者には標準密度を使用します。 */
    private double density(Player viewer) {
        var ast = AstPlayerCache.get(viewer);
        return ast == null ? ParticleDensity.NORMAL.getDensityScale()
                : playerSettings.getCachedParticleDensityScale(ast.getUser().getUuid());
    }

    /** BedrockのスタイルとサイズをUUIDに載せ、Javaと同じ座標に仮想エンティティを生成します。 */
    private PacketContainer spawnPacket(int id, int style, int size, Location location) {
        long data = (ThreadLocalRandom.current().nextLong() & ~0x3ffffL) | ((long) size << 10) | style;
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, id);
        packet.getUUIDs().writeSafely(0, new UUID(BEDROCK_MARKER, data));
        packet.getEntityTypeModifier().writeSafely(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        return packet;
    }

    /** Javaの字形の中心とBedrockのスプライト原点を合わせます。 */
    private PacketContainer spawnWithGlyph(PacketContainer spawn, int id, int size, WrappedDataValue firstFrame) {
        float width = size / 16.0F;
        PacketContainer metadata = metadata(id, List.of(
                vector(DISPLAY_TRANSLATION_INDEX, new Vector3f(0.0F, -width / 2.0F, 0.0F)),
                vector(DISPLAY_SCALE_INDEX, new Vector3f(width / 0.4F)),
                value(DISPLAY_BILLBOARD_INDEX, Byte.class, (byte) Display.Billboard.CENTER.ordinal()),
                value(DISPLAY_BRIGHTNESS_INDEX, Integer.class, FULL_BRIGHT),
                firstFrame, value(BACKGROUND_INDEX, Integer.class, 0)
        ));
        PacketContainer bundle = protocol.createPacket(PacketType.Play.Server.BUNDLE);
        bundle.getPacketBundles().write(0, List.of(spawn, metadata));
        return bundle;
    }

    /** 発生中の全スプライトを単一タスクで更新し、6 tick後に削除します。 */
    private void animate() {
        int tick = Bukkit.getCurrentTick();
        for (var iterator = active.iterator(); iterator.hasNext();) {
            Sprite sprite = iterator.next();
            int frame = tick - sprite.startedAt();
            try {
                if (frame >= FRAME_COUNT) {
                    iterator.remove();
                    remove(sprite);
                } else if (frame > 0 && !sprite.frames().isEmpty()) {
                    PacketContainer metadata = metadata(sprite.id(), List.of(sprite.frames().get(frame)));
                    sprite.java().stream().filter(viewer -> isCurrent(viewer, sprite)).forEach(viewer -> send(viewer, metadata));
                }
            } catch (RuntimeException exception) {
                Logger.log(LogId.W_9013, exception, sprite.world().getName());
            }
        }
        if (active.isEmpty() && animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    /** ワールド移動後の閲覧者に前のワールドの更新を送らないようにします。 */
    private boolean isCurrent(Player viewer, Sprite sprite) {
        return viewer.isOnline() && viewer.getWorld() == sprite.world()
                && (sprite.source() == null || viewer == sprite.source() || viewer.canSee(sprite.source()));
    }

    /** プラグイン停止時にアニメーションを取り消し、閲覧者から仮想表示を除去します。 */
    public void stop() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        active.forEach(this::remove);
        active.clear();
    }

    /** 生成時と同じワールドに残っている閲覧者から仮想表示を除去します。 */
    private void remove(Sprite sprite) {
        for (List<Player> viewers : List.of(sprite.java(), sprite.bedrock())) {
            for (Player viewer : viewers) {
                if (viewer.isOnline() && viewer.getWorld() == sprite.world()) send(viewer, sprite.destroy());
            }
        }
    }

    /** 字形フレームをアトラス順に構築します。パックがない場合の翻訳は空白です。 */
    private List<WrappedDataValue> glyphFrames(int style) {
        var serializer = WrappedDataWatcher.Registry.getChatComponentSerializer(false);
        List<WrappedDataValue> frames = new ArrayList<>(FRAME_COUNT);
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            int codepoint = FIRST_GLYPH + style * FRAME_COUNT + frame;
            var glyph = Component.text(Character.toString(codepoint)).font(FONT);
            var component = Component.translatable(GLYPH_TRANSLATION, " ", glyph);
            var text = WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(component));
            frames.add(WrappedDataValue.fromWrappedValue(TEXT_INDEX, serializer, text));
        }
        return frames;
    }

    /** 仮想TextDisplayの指定メタデータを更新するパケットを作ります。 */
    private PacketContainer metadata(int id, List<WrappedDataValue> values) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().writeSafely(0, id);
        packet.getDataValueCollectionModifier().write(0, values);
        return packet;
    }

    /** ProtocolLibが公開する具象型またはインターフェースのベクトルシリアライザを使います。 */
    private static WrappedDataValue vector(int index, Vector3f vector) {
        try {
            return value(index, Vector3f.class, vector);
        } catch (IllegalArgumentException ignored) {
            return value(index, Vector3fc.class, vector);
        }
    }

    /** 指定型のTextDisplayメタデータを組み立てます。 */
    private static WrappedDataValue value(int index, Type type, Object value) {
        return WrappedDataValue.fromWrappedValue(index, WrappedDataWatcher.Registry.get(type), value);
    }

    /** 一人のパケット失敗を他の閲覧者や戦闘判定へ波及させません。 */
    private boolean send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9013, exception, viewer.getWorld().getName());
            return false;
        }
    }

    private record Sprite(int id, World world, @Nullable Player source, List<Player> java, List<Player> bedrock,
                          PacketContainer destroy, List<WrappedDataValue> frames, int startedAt) {}
}
