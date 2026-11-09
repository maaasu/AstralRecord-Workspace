package io.github.maaasu.astralRecord.feature.item.view;

import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** 適用済み Java パックに対応する閲覧者だけに、副手の矢筒を背負っているように表示します。 */
final class BackQuiverPresentation {
    static final long UPDATE_TICKS = 2L;
    private static final String ITEM_ID = "20a00040";
    private static final NamespacedKey WORN_MODEL = new NamespacedKey("astralrecord", "venom_quiver_worn");
    private static final double VIEW_RANGE_SQUARED = 64.0 * 64.0;
    private static final float AVATAR_SCALE = 0.9375F;
    private static final double TORSO_PIVOT_Y = 1.501;
    private static final double CROUCH_TORSO_OFFSET_Y = 3.2 / 16.0;
    private static final double CROUCH_AVATAR_OFFSET_Y = 2.0 / 16.0;
    private static final float CROUCH_PITCH = (float) Math.toDegrees(0.5);

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final Predicate<Player> isBedrock;
    private final BiConsumer<Player, Player> refreshOffHand;
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<Integer, Set<UUID>> projectedViewers = new ConcurrentHashMap<>();

    /**
     * @param plugin 表示エンティティの所有者
     * @param packs 受信者のパック適用状態
     * @param isBedrock メインスレッドで受信者が統合版か判定する処理
     * @param refreshOffHand 閲覧者と装備者に対応する副手の表示再送
     */
    BackQuiverPresentation(Plugin plugin, ResourcePackService packs, Predicate<Player> isBedrock,
                           BiConsumer<Player, Player> refreshOffHand) {
        this.plugin = plugin;
        this.packs = packs;
        this.isBedrock = isBedrock;
        this.refreshOffHand = refreshOffHand;
    }

    /**
     * 閲覧者が背負い表示を追跡しているかをパケットスレッドから確認します。
     *
     * @param viewerId 閲覧者のUUID
     * @param wearerEntityId 装備者のエンティティID
     * @return 対応パックを適用済みで背負い表示を追跡している場合はtrue
     */
    boolean isProjected(UUID viewerId, int wearerEntityId) {
        return packs.hasJavaBackQuiver(viewerId)
            && projectedViewers.getOrDefault(wearerEntityId, Set.of()).contains(viewerId);
    }

    /**
     * 副手の送信コピーへ手持ち非表示用のモデルを設定します。
     *
     * @param original 元アイテム
     * @param replaced 変換済みの送信コピー。未変換の場合はnull
     * @return 矢筒は着用モデルに切り替えたコピー、その他は入力された送信コピー
     */
    static @Nullable ItemStack projectOffHand(ItemStack original, @Nullable ItemStack replaced) {
        if (!isQuiver(original)) return replaced;
        ItemStack result = replaced == null ? original.clone() : replaced;
        result.setData(DataComponentTypes.ITEM_MODEL, WORN_MODEL);
        return result;
    }

    /**
     * クリエイティブ操作で返送された矢筒から着用表示用のモデル指定を取り除きます。
     *
     * @param cursor クライアントが送ったアイテム
     * @return 表示用指定を外したコピー。対象外ならnull
     */
    static @Nullable ItemStack restoreCreativeProjection(ItemStack cursor) {
        if (!isQuiver(cursor) || !WORN_MODEL.equals(cursor.getData(DataComponentTypes.ITEM_MODEL))) return null;
        ItemStack restored = cursor.clone();
        restored.resetData(DataComponentTypes.ITEM_MODEL);
        return restored;
    }

    /**
     * 変更された装備者と閲覧者を検出し、胴体位置と副手表示をメインスレッドで更新します。
     */
    void update() {
        var players = plugin.getServer().getOnlinePlayers();
        if (players.stream().noneMatch(this::canView)) {
            clear();
            return;
        }
        for (Body body : List.copyOf(bodies.values())) {
            if (!body.wearer.isOnline() || !body.display.isValid()
                || body.wearer.getWorld() != body.display.getWorld() || !canWear(body.wearer)) {
                remove(body);
            }
        }
        for (Player wearer : players) {
            if (canWear(wearer)) updateWearer(wearer);
        }
    }

    /**
     * メインスレッドで装備者の表示を破棄し、追跡中の閲覧者の副手表示を戻します。
     *
     * @param wearer 退出した装備者
     */
    void forget(Player wearer) {
        Body body = bodies.get(wearer.getUniqueId());
        if (body != null) remove(body);
    }

    /** メインスレッドで全表示を破棄し、追跡中の閲覧者の副手表示を戻します。 */
    void clear() {
        for (Body body : List.copyOf(bodies.values())) remove(body);
    }

    /**
     * @param wearer 副手を確認する装備者
     * @return 胴体の位置を合わせられる姿勢で矢筒を装備している場合はtrue
     */
    private static boolean canWear(Player wearer) {
        Pose pose = wearer.getPose();
        return (pose == Pose.STANDING || pose == Pose.SNEAKING)
            && !wearer.isDead() && !wearer.isInvisible() && wearer.getGameMode() != GameMode.SPECTATOR
            && isQuiver(wearer.getInventory().getItemInOffHand());
    }

    /**
     * @param item 装備者の副手または副手へ送信するアイテム
     * @return ヴェノムの矢筒ならtrue
     */
    private static boolean isQuiver(ItemStack item) {
        return !item.isEmpty() && ITEM_ID.equals(ItemStackFactory.getAstralItemId(item));
    }

    /**
     * @param viewer 背負い表示を確認する閲覧者
     * @return 対応パックを適用済みの Java 閲覧者ならtrue
     */
    private boolean canView(Player viewer) {
        return packs.hasJavaBackQuiver(viewer.getUniqueId()) && !isBedrock.test(viewer);
    }

    /**
     * @param wearer 背負い表示を更新する装備者
     */
    private void updateWearer(Player wearer) {
        Location pivot = wearer.getLocation();
        Set<Player> eligible = new HashSet<>();
        if (canView(wearer)) eligible.add(wearer);
        for (Player viewer : wearer.getTrackedBy()) {
            if (viewer.getWorld() == pivot.getWorld() && canView(viewer)
                && viewer.getLocation().distanceSquared(pivot) <= VIEW_RANGE_SQUARED) {
                eligible.add(viewer);
            }
        }
        Body body = bodies.get(wearer.getUniqueId());
        if (eligible.isEmpty()) {
            if (body != null) remove(body);
            return;
        }

        var scaleAttribute = wearer.getAttribute(Attribute.SCALE);
        float scale = scaleAttribute == null ? 1.0F : (float) scaleAttribute.getValue();
        float modelScale = scale * AVATAR_SCALE;
        boolean crouching = wearer.getPose() == Pose.SNEAKING;
        double torsoY = crouching ? TORSO_PIVOT_Y - CROUCH_TORSO_OFFSET_Y : TORSO_PIVOT_Y;
        pivot.add(0.0, torsoY * modelScale - (crouching ? CROUCH_AVATAR_OFFSET_Y * scale : 0.0), 0.0);
        // ItemDisplay の固定描画はアイテムを Y 軸で半回転し、モデルの +Z が装備者の背側になります。
        pivot.setYaw(wearer.getBodyYaw());
        pivot.setPitch(crouching ? CROUCH_PITCH : 0.0F);
        if (body == null) {
            ItemDisplay display = pivot.getWorld().spawn(pivot, ItemDisplay.class, entity -> {
                ItemStack model = new ItemStack(Material.FLOWER_POT);
                model.setData(DataComponentTypes.ITEM_MODEL, WORN_MODEL);
                entity.setItemStack(model);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setTransformation(transformation(modelScale));
                entity.setPersistent(false);
                entity.setVisibleByDefault(false);
                entity.setViewRange(2.0F);
            });
            if (!display.isValid()) return;
            body = new Body(wearer, display, modelScale);
            bodies.put(wearer.getUniqueId(), body);
        } else if (!move(body, pivot, modelScale)) {
            remove(body);
            return;
        }
        synchronizeViewers(body, eligible);
    }

    /**
     * @param body 既存の背負い表示
     * @param pivot 新しい胴体原点と回転
     * @param modelScale Java プレイヤーモデルと同じ縮尺
     * @return 表示の更新に成功した場合はtrue
     */
    private static boolean move(Body body, Location pivot, float modelScale) {
        Location previous = body.display.getLocation();
        if (!previous.equals(pivot)) {
            body.display.setTeleportDuration(previous.distanceSquared(pivot) > 9.0 ? 0 : (int) UPDATE_TICKS);
            if (!body.display.teleport(pivot)) return false;
        }
        if (body.scale != modelScale) {
            body.display.setTransformation(transformation(modelScale));
            body.scale = modelScale;
        }
        return true;
    }

    /**
     * @param scale Java プレイヤーモデルと同じ縮尺
     * @return 胴体を原点とする矢筒の変換
     */
    private static Transformation transformation(float scale) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale), new Quaternionf());
    }

    /**
     * @param body 背負い表示
     * @param eligible 現在背負い表示を受け取れる閲覧者
     */
    private void synchronizeViewers(Body body, Set<Player> eligible) {
        for (Player viewer : List.copyOf(body.shown)) {
            if (!eligible.contains(viewer)) {
                if (viewer.isOnline()) viewer.hideEntity(plugin, body.display);
                body.shown.remove(viewer);
            }
        }
        for (Player viewer : eligible) {
            if (body.shown.add(viewer)) viewer.showEntity(plugin, body.display);
        }

        Set<Player> tracking = body.display.getTrackedBy();
        Set<UUID> projected = new HashSet<>();
        for (Player viewer : eligible) {
            if (tracking.contains(viewer)) projected.add(viewer.getUniqueId());
        }
        publish(body, Set.copyOf(projected));
    }

    /**
     * @param body 背負い表示
     * @param current 追跡済みの閲覧者UUIDの不変集合
     */
    private void publish(Body body, Set<UUID> current) {
        Set<UUID> previous = projectedViewers.getOrDefault(body.entityId, Set.of());
        if (previous.equals(current)) return;
        if (current.isEmpty()) {
            projectedViewers.remove(body.entityId);
        } else {
            projectedViewers.put(body.entityId, current);
        }
        for (UUID viewer : previous) {
            if (!current.contains(viewer)) refreshViewer(body, viewer);
        }
        for (UUID viewer : current) {
            if (!previous.contains(viewer)) refreshViewer(body, viewer);
        }
    }

    /**
     * @param body 副手の装備者
     * @param viewerId 更新を必要とする閲覧者
     */
    private void refreshViewer(Body body, UUID viewerId) {
        Player viewer = plugin.getServer().getPlayer(viewerId);
        if (viewer != null) refreshOffHand.accept(viewer, body.wearer);
    }

    /**
     * @param body 追跡から外す背負い表示
     */
    private void remove(Body body) {
        bodies.remove(body.wearer.getUniqueId());
        publish(body, Set.of());
        body.display.remove();
    }

    private static final class Body {
        private final Player wearer;
        private final int entityId;
        private final ItemDisplay display;
        private final Set<Player> shown = new HashSet<>();
        private float scale;

        /**
         * @param wearer 矢筒を装備しているプレイヤー
         * @param display 当該プレイヤーの背負い表示
         * @param scale 表示開始時の縮尺
         */
        private Body(Player wearer, ItemDisplay display, float scale) {
            this.wearer = wearer;
            this.entityId = wearer.getEntityId();
            this.display = display;
            this.scale = scale;
        }
    }
}
