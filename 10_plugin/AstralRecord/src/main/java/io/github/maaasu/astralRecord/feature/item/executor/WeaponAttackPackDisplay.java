package io.github.maaasu.astralRecord.feature.item.executor;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/** 通常攻撃の発動地点に、閲覧者専用の短いリソースパック演出を送信します。 */
public final class WeaponAttackPackDisplay {
    // Geyser 拡張の WeaponAttackEntity と共有するマーカー。UUID 下位 1 byte にスタイルを入れる。
    private static final long BEDROCK_MARKER = 0x4153545241544b31L;
    private static final Key FONT = Key.key("astralrecord", "weapon_attack");
    private static final String GLYPH_TRANSLATION = "astralrecord.weapon_attack.glyph";
    private static final String FRAME_TRANSLATION = "astralrecord.weapon_attack.frame";
    private static final String PROJECTILE_TRANSLATION = "astralrecord.weapon_attack.projectile";
    private static final int FIRST_GLYPH = 0xe100;
    private static final int GLYPH_COLUMNS = 6;
    private static final int FIRST_PROJECTILE_GLYPH = 0xe12a;
    private static final int FRAME_COUNT = 6;
    private static final double VIEW_DISTANCE = 24.0D;
    private static final double PROJECTILE_VIEW_DISTANCE = 64.0D;
    private static final double RELATIVE_MOVE_UNITS = 4096.0D;
    private static final int DISPLAY_MOVE_DURATION_INDEX = 10;
    private static final int DISPLAY_TRANSLATION_INDEX = 11;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int TEXT_INDEX = 23;
    private static final int BACKGROUND_INDEX = 25;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    private final Plugin plugin;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final ResourcePackService resourcePacks;
    private final PlayerSettingService playerSettings;
    private final ParticleDisplayService particles;
    private final Map<Integer, AttackDisplay> activeDisplays = new HashMap<>();
    private final Map<Style, List<WrappedDataValue>> glyphFrames = new HashMap<>();

    /**
     * 通常攻撃演出の送信を準備します。
     * @param plugin 演出の終了タスクを登録するプラグイン
     * @param resourcePacks 閲覧者の Java パック適用状態を管理するサービス
     * @param playerSettings 閲覧者の粒子密度を管理するサービス
     * @param particles Java パック未適用時の粒子を表示するサービス
     */
    public WeaponAttackPackDisplay(Plugin plugin, ResourcePackService resourcePacks,
                                   PlayerSettingService playerSettings, ParticleDisplayService particles) {
        this.plugin = plugin;
        this.resourcePacks = resourcePacks;
        this.playerSettings = playerSettings;
        this.particles = particles;
    }

    /**
     * 組み込み武器の発動演出を閲覧者へ送ります。
     * @param attacker 発動者
     * @param skillId 組み込み通常攻撃のID
     * @param location 攻撃判定の起点
     * @param direction 発動時の単位方向ベクトル
     * @param hitRange 攻撃の射程
     * @param hitRadius 攻撃判定の半径
     * @param fallback Java管理パック未適用時に送る標準粒子
     * @return 組み込み武器の演出を受け持った場合はtrue
     */
    boolean show(Player attacker, String skillId, Location location, Vector direction,
                 double hitRange, double hitRadius, Particle fallback) {
        Style style = Style.forSkill(skillId);
        World world = location.getWorld();
        if (style == null || world == null) {
            return false;
        }
        try {
            showBurst(attacker, location, world, style, VIEW_DISTANCE,
                    meleePath(style, location, direction, hitRange, hitRadius),
                    (viewer, density) -> particles.spawnForViewer(viewer, location, fallback, 1, 0, 0, 0, 0, density));
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9012, exception, world.getName());
            return false;
        }
        return true;
    }

    /**
     * 通常攻撃の飛翔区間または終端に、閲覧者のパックに応じた演出を送ります。
     * @param attacker 発射者
     * @param skillId 組み込み通常攻撃のID
     * @param location 軌跡の始点または終端
     * @param motion 軌跡の移動量
     * @param impact 終端を表示する場合はtrue
     * @param fallback 未対応の閲覧者に同期して粒子を送る処理。既存の粒子がない場合はnull
     */
    void showProjectile(Player attacker, String skillId, Location location, Vector motion, boolean impact,
                        @Nullable ProjectileFallback fallback) {
        Style release = Style.forSkill(skillId);
        Style style = switch (release) {
            case BOW -> impact ? Style.BOW_IMPACT : Style.BOW_TRAIL;
            case WAND -> impact ? Style.WAND_IMPACT : Style.WAND_TRAIL;
            case STAFF -> impact ? Style.STAFF_IMPACT : Style.STAFF_TRAIL;
            case null, default -> null;
        };
        World world = location.getWorld();
        if (style == null || world == null) {
            return;
        }
        try {
            IntFunction<Location> path = impact ? null : frame -> location.clone()
                    .add(motion.clone().multiply(Math.min(1.0D, frame / 3.0D)));
            showBurst(attacker, location, world, style, PROJECTILE_VIEW_DISTANCE, path, fallback);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9012, exception, world.getName());
        }
    }

    /**
     * 攻撃方向と判定の幅・射程を基に、近接武器のコマごとの座標を返します。
     * @param style 表示スタイル
     * @param start 攻撃判定の起点
     * @param direction 発動時の単位方向ベクトル
     * @param range 攻撃の射程
     * @param radius 攻撃判定の半径
     * @return コマから座標を計算する関数。移動のないスタイルの場合はnull
     */
    private static @Nullable IntFunction<Location> meleePath(Style style, Location start, Vector direction,
                                                            double range, double radius) {
        return switch (style) {
            case SWORD -> {
                Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
                if (side.lengthSquared() > 1.0E-6D) {
                    side.normalize();
                }
                yield frame -> {
                    double sweep = Math.min(1.0D, frame / 4.0D);
                    return start.clone()
                            .add(direction.clone().multiply(range * (0.16D + 0.44D * Math.sin(sweep * Math.PI))))
                            .add(side.clone().multiply(radius * (2.0D * sweep - 1.0D)));
                };
            }
            case HAMMER -> frame -> start.clone()
                    .add(direction.clone().multiply(range * 0.25D * Math.min(1.0D, frame / 2.0D)))
                    .subtract(0.0D, 0.75D * Math.min(1.0D, frame / 3.0D), 0.0D);
            case SPEAR -> frame -> start.clone().add(direction.clone().multiply(range * switch (frame) {
                case 0 -> 0.0D;
                case 1 -> 0.20D;
                case 2 -> 0.50D;
                case 3 -> 0.82D;
                default -> 1.0D;
            }));
            default -> null;
        };
    }

    /**
     * 閲覧者を選び、アニメーションの初回パケットと再生タスクを登録します。
     * @param attacker 発動者
     * @param location 閲覧範囲の中心
     * @param world 表示先のワールド
     * @param style 表示スタイル
     * @param viewDistance 最大閲覧距離
     * @param path コマごとの移動先。静止時はnull
     * @param fallback 代替粒子の送信処理。代替粒子がない場合はnull
     */
    private void showBurst(Player attacker, Location location, World world, Style style,
                           double viewDistance, @Nullable IntFunction<Location> path,
                           @Nullable ProjectileFallback fallback) {
        List<Player> javaViewers = new ArrayList<>();
        List<Player> bedrockViewers = new ArrayList<>();
        for (Player viewer : world.getNearbyPlayers(location, viewDistance)) {
            double distanceSquared = viewer.getLocation().distanceSquared(location);
            if (!viewer.canSee(attacker) || distanceSquared > viewDistance * viewDistance) {
                continue;
            }
            var player = AstPlayerCache.get(viewer);
            double density = player == null ? ParticleDensity.NORMAL.getDensityScale()
                    : playerSettings.getCachedParticleDensityScale(player.getUser().getUuid());
            if (density <= 0.0D) {
                continue;
            }
            if (style.ordinal() >= GLYPH_COLUMNS && !resourcePacks.hasWeaponAttackProjectiles(viewer)) {
                if (fallback != null) fallback.show(viewer, density);
                continue;
            }
            if (player == null ? resourcePacks.isBedrock(viewer.getName()) : player.isBedrock()) {
                if (shouldShowBurst(density)) bedrockViewers.add(viewer);
            } else if (resourcePacks.hasManagedJavaPack(viewer.getUniqueId())) {
                if (shouldShowBurst(density)) javaViewers.add(viewer);
            } else if (fallback != null) {
                fallback.show(viewer, density);
            }
        }
        if (javaViewers.isEmpty() && bedrockViewers.isEmpty()) {
            return;
        }

        int entityId = PacketEntityIdAllocator.nextEntityId();
        Location initialPosition = path == null ? location.clone() : path.apply(0);
        PacketContainer spawn = spawnPacket(entityId, style.ordinal(), initialPosition);
        PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        destroy.getIntLists().writeSafely(0, List.of(entityId));
        destroy.getIntegerArrays().writeSafely(0, new int[]{entityId});

        PacketContainer javaSpawn = null;
        List<WrappedDataValue> frames = javaViewers.isEmpty()
                ? List.of() : glyphFrames.computeIfAbsent(style, this::glyphFrames);
        if (!frames.isEmpty()) {
            float scale = style.width / 0.4f;
            PacketContainer metadata = metadataPacket(entityId, List.of(
                    // 16 px の字形の中心をビルボードの原点へ合わせる。
                    vector(DISPLAY_TRANSLATION_INDEX, new Vector3f(0f, -style.width / 2f, 0f)),
                    vector(DISPLAY_SCALE_INDEX, new Vector3f(scale)),
                    value(DISPLAY_MOVE_DURATION_INDEX, Integer.class, 1),
                    value(DISPLAY_BILLBOARD_INDEX, Byte.class, (byte) Display.Billboard.CENTER.ordinal()),
                    value(DISPLAY_BRIGHTNESS_INDEX, Integer.class, FULL_BRIGHT),
                    frames.getFirst(), value(BACKGROUND_INDEX, Integer.class, 0)
            ));
            javaSpawn = bundle(spawn, metadata);
        }
        BukkitTask task = animate(entityId, world, javaViewers, bedrockViewers, initialPosition, path, frames);
        List<Player> viewers = new ArrayList<>(javaViewers);
        viewers.addAll(bedrockViewers);
        activeDisplays.put(entityId, new AttackDisplay(viewers, destroy, task));
        for (Player viewer : javaViewers) {
            send(viewer, javaSpawn);
        }
        for (Player viewer : bedrockViewers) {
            send(viewer, spawn);
        }
    }

    /**
     * 後続コマの座標と字形を同期送信し、再生終了時に表示を破棄します。
     * @param entityId 仮想表示のエンティティID
     * @param world 表示元のワールド
     * @param javaViewers Java版の閲覧者
     * @param bedrockViewers 統合版の閲覧者
     * @param position 最後に送信した座標
     * @param path コマごとの移動先。静止時はnull
     * @param frames Java版の字形。Java版閲覧者がいなければ空
     * @return 停止時にキャンセルするタスク
     */
    private BukkitTask animate(int entityId, World world, List<Player> javaViewers, List<Player> bedrockViewers,
                               Location position, @Nullable IntFunction<Location> path, List<WrappedDataValue> frames) {
        if (path == null && frames.isEmpty()) {
            return plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> remove(activeDisplays.remove(entityId)), FRAME_COUNT);
        }
        return new BukkitRunnable() {
            private int frame = 1;

            @Override
            public void run() {
                if (frame == FRAME_COUNT) {
                    cancel();
                    remove(activeDisplays.remove(entityId));
                    return;
                }
                try {
                    PacketContainer move = path == null ? null : movePacket(entityId, position, path.apply(frame));
                    if (move != null) {
                        sendToViewers(bedrockViewers, world, move);
                    }
                    if (!frames.isEmpty()) {
                        PacketContainer metadata = metadataPacket(entityId, List.of(frames.get(frame)));
                        sendToViewers(javaViewers, world, move == null ? metadata : bundle(move, metadata));
                    }
                    frame++;
                } catch (RuntimeException exception) {
                    cancel();
                    remove(activeDisplays.remove(entityId));
                    Logger.log(LogId.W_9012, exception, world.getName());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * 同じワールドに残っているオンライン閲覧者へ後続パケットを送ります。
     * @param viewers 発動時の閲覧者
     * @param world 表示元のワールド
     * @param packet 後続パケット
     */
    private void sendToViewers(List<Player> viewers, World world, PacketContainer packet) {
        for (Player viewer : viewers) {
            if (viewer.isOnline() && viewer.getWorld().equals(world)) {
                send(viewer, packet);
            }
        }
    }

    /**
     * Java版とGeyserで扱える相対移動を作り、送信済み位置を進めます。
     * @param entityId 仮想表示のエンティティID
     * @param position 最後に送信した座標。相対移動分を加算する
     * @param target このコマの目標座標
     * @return 相対移動パケット。量子化後の移動がない場合はnull
     */
    private @Nullable PacketContainer movePacket(int entityId, Location position, Location target) {
        short dx = relativeDelta(position.getX(), target.getX());
        short dy = relativeDelta(position.getY(), target.getY());
        short dz = relativeDelta(position.getZ(), target.getZ());
        if (dx == 0 && dy == 0 && dz == 0) {
            return null;
        }
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.REL_ENTITY_MOVE);
        packet.getIntegers().write(0, entityId);
        packet.getShorts().write(0, dx).write(1, dy).write(2, dz);
        packet.getBooleans().write(0, false);
        position.add(dx / RELATIVE_MOVE_UNITS, dy / RELATIVE_MOVE_UNITS, dz / RELATIVE_MOVE_UNITS);
        return packet;
    }

    /**
     * 座標差を相対移動パケットの範囲に収まる単位へ変換します。
     * @param from 送信済み座標
     * @param to 目標座標
     * @return 量子化した差
     */
    private static short relativeDelta(double from, double to) {
        long delta = Math.round(to * RELATIVE_MOVE_UNITS) - Math.round(from * RELATIVE_MOVE_UNITS);
        return (short) Math.clamp(delta, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Java版で同じコマのパケットをまとめて適用します。
     * @param first 生成または移動のパケット
     * @param second 字形のパケット
     * @return 順に適用するバンドル
     */
    private PacketContainer bundle(PacketContainer first, PacketContainer second) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.BUNDLE);
        packet.getPacketBundles().write(0, List.of(first, second));
        return packet;
    }

    private static boolean shouldShowBurst(double density) {
        return density >= 1.0D || ThreadLocalRandom.current().nextDouble() < density;
    }

    private PacketContainer spawnPacket(int entityId, int style, Location location) {
        long markerData = (ThreadLocalRandom.current().nextLong() & ~0xffL) | style;
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getUUIDs().writeSafely(0, new UUID(BEDROCK_MARKER, markerData));
        packet.getEntityTypeModifier().writeSafely(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        return packet;
    }

    private PacketContainer metadataPacket(int entityId, List<WrappedDataValue> values) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getDataValueCollectionModifier().write(0, values);
        return packet;
    }

    /**
     * フォントの行と列からJava版のコマごとの本文を組み立てます。
     * @param style 表示スタイル
     * @return 再生順の本文メタデータ
     */
    private List<WrappedDataValue> glyphFrames(Style style) {
        int index = style.ordinal();
        var serializer = WrappedDataWatcher.Registry.getChatComponentSerializer(false);
        List<WrappedDataValue> frames = new ArrayList<>(FRAME_COUNT);
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            Component display;
            if (index < GLYPH_COLUMNS) {
                int glyph = FIRST_GLYPH + (frame + 1) * GLYPH_COLUMNS + index;
                Component animated = Component.translatable(FRAME_TRANSLATION, Character.toString(FIRST_GLYPH + index),
                        Component.text(Character.toString(glyph))).font(FONT);
                display = Component.translatable(GLYPH_TRANSLATION, "★", animated);
            } else {
                // 旧パックは追加字形を持たないため、弾芯だけを表示する。
                int glyph = FIRST_PROJECTILE_GLYPH + frame * GLYPH_COLUMNS + index - GLYPH_COLUMNS;
                display = Component.translatable(PROJECTILE_TRANSLATION, " ",
                        Component.text(Character.toString(glyph)).font(FONT));
            }
            WrappedChatComponent text = WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(display));
            frames.add(WrappedDataValue.fromWrappedValue(TEXT_INDEX, serializer, text));
        }
        return frames;
    }

    private static WrappedDataValue vector(int index, Vector3f vector) {
        try {
            return value(index, Vector3f.class, vector);
        } catch (IllegalArgumentException ignored) {
            return value(index, Vector3fc.class, vector);
        }
    }

    private static WrappedDataValue value(int index, Type type, Object value) {
        return WrappedDataValue.fromWrappedValue(index, WrappedDataWatcher.Registry.get(type), value);
    }

    void stop() {
        for (AttackDisplay display : activeDisplays.values()) {
            display.task().cancel();
            remove(display);
        }
        activeDisplays.clear();
    }

    private void remove(AttackDisplay display) {
        for (Player viewer : display.viewers()) {
            if (viewer.isOnline()) send(viewer, display.destroy());
        }
    }

    private void send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9012, exception, viewer.getWorld().getName());
        }
    }

    private record AttackDisplay(List<Player> viewers, PacketContainer destroy, BukkitTask task) {}

    @FunctionalInterface
    interface ProjectileFallback {
        /**
         * 対応パックを持たない閲覧者に、従来の粒子を同期送信します。
         * @param viewer 送信先
         * @param density 閲覧者の粒子密度
         */
        void show(Player viewer, double density);
    }

    // Java の字形順と Bedrock の style プロパティに対応。
    private enum Style {
        SWORD(1.55f), HAMMER(1.9f), SPEAR(1.0f), BOW(1.1f), WAND(1.15f), STAFF(1.45f),
        BOW_TRAIL(1.0f), WAND_TRAIL(1.0f), STAFF_TRAIL(1.25f),
        BOW_IMPACT(1.35f), WAND_IMPACT(1.45f), STAFF_IMPACT(1.8f);

        private final float width;

        Style(float width) {
            this.width = width;
        }

        /**
         * 組み込み通常攻撃IDの発動スタイルを返します。
         * @param skillId 通常攻撃ID
         * @return 発動スタイル。対象外の場合はnull
         */
        static @Nullable Style forSkill(String skillId) {
            return switch (skillId) {
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE -> SWORD;
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER -> HAMMER;
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR -> SPEAR;
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW,
                     BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW,
                     BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW -> BOW;
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND -> WAND;
                case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC -> STAFF;
                default -> null;
            };
        }
    }
}
