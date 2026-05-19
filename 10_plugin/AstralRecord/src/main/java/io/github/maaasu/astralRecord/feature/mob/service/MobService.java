package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.view.PacketMobView;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 論理 Mob のテンプレートロードと packet 表示を管理します。
 */
public class MobService {

    private static final double DEFAULT_VIEW_DISTANCE = 64.0;
    private static final AtomicInteger ENTITY_ID_SEQUENCE = new AtomicInteger(2_000_000);

    private final Plugin plugin;
    private final MobRepository repository;
    private final PacketMobView view;
    private final Map<String, MobTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, MobInstance> instances = new LinkedHashMap<>();

    /**
     * MobService を初期化します。
     *
     * @param plugin     プラグイン
     * @param repository Mob リポジトリ
     */
    public MobService(@NotNull Plugin plugin, @NotNull MobRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.view = new PacketMobView(plugin);
    }

    /**
     * YAML からすべての Mob テンプレートを読み込み直します。
     *
     * @return 読み込んだ件数
     */
    public int loadAll() {
        templates.clear();
        templates.putAll(repository.findAll());
        return templates.size();
    }

    /**
     * 指定 ID の Mob テンプレートを取得します。
     *
     * @param id テンプレート ID
     * @return Mob テンプレート
     */
    @Nullable
    public MobTemplate findTemplate(@NotNull String id) {
        return templates.get(id);
    }

    /**
     * 読み込み済み Mob ID の一覧を返します。
     *
     * @return Mob ID 一覧
     */
    @NotNull
    public Collection<String> getLoadedMobIds() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * Mob を指定位置へ生成し、周囲プレイヤーへ表示します。
     *
     * @param templateId テンプレート ID
     * @param location   生成位置
     * @return 生成した Mob インスタンス
     */
    @Nullable
    public MobInstance spawn(@NotNull String templateId, @NotNull Location location) {
        MobTemplate template = findTemplate(templateId);
        if (template == null) {
            return null;
        }

        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                ENTITY_ID_SEQUENCE.incrementAndGet(),
                template,
                location.clone()
        );
        instances.put(instance.instanceId(), instance);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(player, location)) {
                view.spawn(player, instance);
            }
        }
        return instance;
    }

    /**
     * 表示中の Mob をすべて削除します。
     */
    public void destroyAll() {
        for (MobInstance instance : instances.values()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                view.destroy(player, instance);
            }
        }
        instances.clear();
    }

    private boolean canSee(@NotNull Player player, @NotNull Location location) {
        if (player.getWorld() != location.getWorld()) {
            return false;
        }
        return player.getLocation().distanceSquared(location) <= DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
    }
}
