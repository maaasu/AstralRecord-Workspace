package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.view.PacketMobView;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mob テンプレートのキャッシュ・インスタンス管理・パケット表示連携を担うサービス。
 *
 * <p>本サービスはメインスレッドからの呼び出しを前提とする。
 * API 経由のテンプレート取得は同期 HTTP のため、スポーン時の遅延ロードが発生する可能性に注意する。</p>
 */
public class MobService {

    /** 仮想 Entity ID の採番開始値（プロセス内一意）。 */
    private static final int ENTITY_ID_BASE = 2_000_000;

    /** 視認距離（ブロック単位）。本距離を超えるプレイヤーには破棄パケットを送る。 */
    private static final double DEFAULT_VIEW_DISTANCE = 64.0;
    private static final double DEFAULT_VIEW_DISTANCE_SQ = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;

    private static final AtomicInteger ENTITY_ID_SEQUENCE = new AtomicInteger(ENTITY_ID_BASE);

    private final Plugin plugin;
    private final MobRepository repository;
    private final PacketMobView view;

    private final Map<String, MobTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, MobInstance> instances = new LinkedHashMap<>();
    /** インスタンスごとに、現在表示中（spawn パケットを送出済み）のプレイヤー UUID 集合を保持。 */
    private final Map<UUID, Set<UUID>> viewers = new LinkedHashMap<>();

    /**
     * サービスを初期化します。
     *
     * @param plugin     プラグイン本体
     * @param repository Mob リポジトリ
     */
    public MobService(@NotNull Plugin plugin, @NotNull MobRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.view = new PacketMobView(plugin);
    }

    /**
     * AstralRecord API から Mob テンプレートを一括ロードし、キャッシュを置換します。
     *
     * @return ロードしたテンプレート数
     */
    public int loadAll() {
        templates.clear();
        for (MobTemplate template : repository.findAll()) {
            templates.put(template.id(), template);
        }
        Logger.log(LogId.I_5700, templates.size());
        return templates.size();
    }

    /**
     * テンプレートをキャッシュから取得します。未ヒット時は API から遅延ロードします。
     *
     * @param mobId テンプレート ID
     * @return テンプレート。取得できない場合は {@code null}
     */
    @Nullable
    public MobTemplate findTemplate(@NotNull String mobId) {
        MobTemplate cached = templates.get(mobId);
        if (cached != null) return cached;

        MobTemplate loaded = repository.findById(mobId);
        if (loaded != null) {
            templates.put(mobId, loaded);
        }
        return loaded;
    }

    /**
     * ロード済みテンプレートの ID 一覧を返します。
     *
     * @return ID 集合（変更不可）
     */
    @NotNull
    public Collection<String> getLoadedMobIds() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * 全インスタンスを取得します。
     *
     * @return Mob インスタンスのコレクション（変更不可）
     */
    @NotNull
    public Collection<MobInstance> getInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    /**
     * 指定インスタンスを取得します。
     *
     * @param instanceId インスタンス ID
     * @return Mob インスタンス。未登録なら {@code null}
     */
    @Nullable
    public MobInstance getInstance(@NotNull UUID instanceId) {
        return instances.get(instanceId);
    }

    /**
     * Mob をスポーンします。
     *
     * @param templateId テンプレート ID
     * @param location   スポーン位置
     * @return 生成した Mob インスタンス。テンプレート未取得時は {@code null}
     */
    @Nullable
    public MobInstance spawn(@NotNull String templateId, @NotNull Location location) {
        MobTemplate template = findTemplate(templateId);
        if (template == null) {
            Logger.log(LogId.W_5701, templateId);
            return null;
        }

        UUID instanceId = UUID.randomUUID();
        int entityId = ENTITY_ID_SEQUENCE.incrementAndGet();
        MobInstance instance = new MobInstance(instanceId, entityId, template, location);
        instances.put(instanceId, instance);
        viewers.put(instanceId, new HashSet<>());

        // 範囲内プレイヤーへ初回送出
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(player, instance.currentLocation())) {
                view.spawn(player, instance);
                viewers.get(instanceId).add(player.getUniqueId());
            }
        }

        Logger.log(LogId.D_5701, templateId, instanceId);
        return instance;
    }

    /**
     * 指定インスタンスを破棄します。
     *
     * @param instanceId インスタンス ID
     * @return 破棄したかどうか
     */
    public boolean destroy(@NotNull UUID instanceId) {
        MobInstance instance = instances.remove(instanceId);
        if (instance == null) return false;

        Set<UUID> shown = viewers.remove(instanceId);
        if (shown != null) {
            for (UUID playerId : shown) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    view.destroy(player, instance);
                }
            }
        }
        // 範囲外プレイヤー側にも念のため破棄を送る（オンラインのうち shown に含まれていないプレイヤー）
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shown == null || !shown.contains(player.getUniqueId())) {
                view.destroy(player, instance);
            }
        }
        Logger.log(LogId.D_5702, instanceId);
        return true;
    }

    /**
     * すべての Mob インスタンスを破棄します。
     *
     * @return 破棄した件数
     */
    public int destroyAll() {
        int count = instances.size();
        for (MobInstance instance : instances.values()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                view.destroy(player, instance);
            }
        }
        instances.clear();
        viewers.clear();
        Logger.log(LogId.I_5701, count);
        return count;
    }

    /**
     * すべてのインスタンスについて、現在表示中のプレイヤー集合を更新します。
     * 新規プレイヤーへは spawn パケット、範囲外プレイヤーへは destroy パケットを送出します。
     * 既存表示中プレイヤーへは差分の位置パケットを送出します。
     */
    public void updateViewers() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (MobInstance instance : instances.values()) {
            Set<UUID> currentViewers = viewers.computeIfAbsent(instance.instanceId(), id -> new HashSet<>());
            Location loc = instance.currentLocation();

            for (Player player : online) {
                UUID playerId = player.getUniqueId();
                boolean inRange = canSee(player, loc);
                boolean shown = currentViewers.contains(playerId);

                if (inRange && !shown) {
                    view.spawn(player, instance);
                    currentViewers.add(playerId);
                } else if (!inRange && shown) {
                    view.destroy(player, instance);
                    currentViewers.remove(playerId);
                } else if (inRange) {
                    view.move(player, instance);
                }
            }

            // オフラインになったプレイヤーは viewers から除去
            currentViewers.removeIf(id -> Bukkit.getPlayer(id) == null);
        }
    }

    /**
     * 指定プレイヤーが指定座標の Mob を視認できる距離内か判定します。
     *
     * @param player プレイヤー
     * @param loc    対象座標
     * @return 視認可能なら {@code true}
     */
    public boolean canSee(@NotNull Player player, @NotNull Location loc) {
        if (player.getWorld() != loc.getWorld()) return false;
        return player.getLocation().distanceSquared(loc) <= DEFAULT_VIEW_DISTANCE_SQ;
    }

    /** プラグイン本体を返します（共通基盤用）。 */
    @NotNull
    public Plugin plugin() {
        return plugin;
    }

    /** パケットビューを返します（共通基盤用）。 */
    @NotNull
    public PacketMobView view() {
        return view;
    }
}
