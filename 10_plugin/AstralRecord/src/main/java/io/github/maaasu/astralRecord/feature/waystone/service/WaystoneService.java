package io.github.maaasu.astralRecord.feature.waystone.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.waystone.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.waystone.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.waystone.repository.WaystoneUnlockRepository;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ウェイストーンの配置、開放、GUI、テレポートを扱うサービスです。
 */
public final class WaystoneService {
    private static final int GUI_SIZE = 54;
    private static final String GUI_TITLE = "Waystone";

    private final AstralRecord plugin;
    private final WaystoneDefinitionRepository definitionRepository;
    private final WaystoneUnlockRepository unlockRepository;
    private InventoryService inventoryService;
    private final Map<String, WaystoneDefinition> definitionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> unlockedCache = new ConcurrentHashMap<>();
    private WaystoneVisualizer visualizer;

    /**
     * service を初期化します。
     *
     * @param plugin プラグイン本体
     * @param definitionRepository ウェイストーン定義repository
     * @param unlockRepository 開放状態repository
     */
    public WaystoneService(
        @NotNull AstralRecord plugin,
        @NotNull WaystoneDefinitionRepository definitionRepository,
        @NotNull WaystoneUnlockRepository unlockRepository
    ) {
        this.plugin = plugin;
        this.definitionRepository = definitionRepository;
        this.unlockRepository = unlockRepository;
    }

    /**
     * 開放コストの消費に使うインベントリサービスを差し込みます。
     *
     * @param inventoryService インベントリサービス
     */
    public void setInventoryService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * 表示サービスを差し込みます。
     *
     * @param visualizer 表示サービス
     */
    public void setVisualizer(@NotNull WaystoneVisualizer visualizer) {
        this.visualizer = visualizer;
    }

    /**
     * 定義ファイルを再読込します。
     *
     * @return 読み込んだ定義数
     */
    public int loadAll() {
        definitionsById.clear();
        for (WaystoneDefinition definition : definitionRepository.loadAll()) {
            definitionsById.put(definition.id(), definition);
        }
        return definitionsById.size();
    }

    /**
     * 表示タスクを開始します。
     */
    public void start() {
        if (visualizer != null) {
            visualizer.start();
        }
    }

    /**
     * 表示タスクを停止します。
     */
    public void stop() {
        if (visualizer != null) {
            visualizer.stop();
        }
    }

    /**
     * 現在地にウェイストーンを作成します。
     *
     * @param name 表示名
     * @param location 配置位置
     * @param alwaysUnlocked 常時開放する場合はtrue
     * @param unlockGoldCost 初回開放コスト
     * @return 作成した定義
     * @throws IOException 保存に失敗した場合
     */
    public @NotNull WaystoneDefinition create(
        @NotNull String name,
        @NotNull Location location,
        boolean alwaysUnlocked,
        long unlockGoldCost
    ) throws IOException {
        WaystoneDefinition definition = definitionRepository.create(name, location, alwaysUnlocked, unlockGoldCost);
        definitionsById.put(definition.id(), definition);
        return definition;
    }

    /**
     * すべての定義を返します。
     *
     * @return ウェイストーン定義一覧
     */
    public @NotNull List<WaystoneDefinition> definitions() {
        return definitionsById.values().stream()
            .sorted(Comparator.comparing(WaystoneDefinition::worldName).thenComparing(WaystoneDefinition::name))
            .toList();
    }

    /**
     * IDから定義を取得します。
     *
     * @param waystoneId ウェイストーンID
     * @return 定義。存在しない場合はnull
     */
    public @Nullable WaystoneDefinition find(@NotNull String waystoneId) {
        return definitionsById.get(waystoneId);
    }

    /**
     * プレイヤーに対してウェイストーンが開放済みか判定します。
     *
     * @param player プレイヤー
     * @param definition 定義
     * @return 開放済みならtrue
     */
    public boolean isUnlocked(@NotNull Player player, @NotNull WaystoneDefinition definition) {
        if (definition.alwaysUnlocked()) {
            return true;
        }
        AstPlayer astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        Set<String> ids = unlockedCache.get(astPlayer.getAccount().getUuid());
        return ids != null && ids.contains(definition.id());
    }

    /**
     * 右クリックされたウェイストーンを処理します。
     *
     * @param player Bukkitプレイヤー
     * @param waystoneId ウェイストーンID
     */
    public void handleInteract(@NotNull Player player, @NotNull String waystoneId) {
        WaystoneDefinition source = definitionsById.get(waystoneId);
        AstPlayer astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (source == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> unlocked = loadUnlocked(astPlayer);
            boolean unlockedNow = source.alwaysUnlocked() || unlocked.contains(source.id());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!unlockedNow) {
                    unlockFirstTime(player, astPlayer, source, unlocked);
                    return;
                }
                openGui(player, astPlayer, source);
            });
        });
    }

    /**
     * GUIクリックからテレポート先を選びます。
     *
     * @param player プレイヤー
     * @param waystoneId テレポート先ID
     */
    public void teleportFromGui(@NotNull Player player, @NotNull String waystoneId) {
        WaystoneDefinition destination = definitionsById.get(waystoneId);
        if (destination == null || destination.toLocation() == null) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6506);
            return;
        }
        if (!player.getWorld().getName().equals(destination.worldName())) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6505);
            return;
        }
        player.closeInventory();
        Location target = destination.toLocation().clone().add(0.0D, 0.1D, 0.0D);
        player.teleport(target);
        player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.55F, 1.25F);
        PlayerMessageService.getInstance().send(
            player,
            PlayerMsgId.P_6504,
            ColorCodeUtil.toLegacyText(destination.name(), destination.id())
        );
    }

    private void unlockFirstTime(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull WaystoneDefinition source,
        @NotNull Set<String> unlocked
    ) {
        long cost = Math.max(0L, source.unlockGoldCost());
        if (inventoryService == null) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6507);
            return;
        }
        if (cost > 0L && !inventoryService.consumeGold(astPlayer.getAccount().getUuid(), cost)) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6503, cost);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                unlockRepository.unlock(astPlayer.getAccount().getUuid(), source.id(), astPlayer.getUser().getUuid());
                unlockedCache.compute(astPlayer.getAccount().getUuid(), (ignored, current) -> {
                    Set<String> next = current == null ? new HashSet<>() : new HashSet<>(current);
                    next.add(source.id());
                    return next;
                });
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5F, 1.35F);
                    player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.45F, 1.7F);
                    PlayerMessageService.getInstance().send(
                        player,
                        PlayerMsgId.P_6501,
                        ColorCodeUtil.toLegacyText(source.name(), source.id())
                    );
                });
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    GuiSound.DENY.play(player);
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6507);
                });
            }
        });
    }

    private void openGui(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition source) {
        Set<String> unlocked = unlockedCache.getOrDefault(astPlayer.getAccount().getUuid(), Set.of());
        List<WaystoneDefinition> candidates = definitionsById.values().stream()
            .filter(definition -> definition.worldName().equals(source.worldName()))
            .filter(definition -> definition.alwaysUnlocked() || unlocked.contains(definition.id()))
            .sorted(Comparator.comparing(WaystoneDefinition::name))
            .toList();
        Map<Integer, String> idsBySlot = new HashMap<>();
        WaystoneGuiHolder holder = new WaystoneGuiHolder(idsBySlot);
        var inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text(GUI_TITLE));
        int slot = 0;
        for (WaystoneDefinition definition : candidates) {
            if (slot >= GUI_SIZE) {
                break;
            }
            idsBySlot.put(slot, definition.id());
            inventory.setItem(slot, guiItem(definition, definition.id().equals(source.id())));
            slot++;
        }
        player.openInventory(inventory);
        GuiSound.OPEN.play(player);
    }

    private @NotNull ItemStack guiItem(@NotNull WaystoneDefinition definition, boolean current) {
        ItemStack item = new ItemStack(current ? Material.RESPAWN_ANCHOR : Material.LODESTONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(component((current ? "&b" : "&e") + ColorCodeUtil.toLegacyText(definition.name(), definition.id())));
        List<Component> lore = new ArrayList<>();
        lore.add(component("&7world: &f" + definition.worldName()));
        lore.add(component(current ? "&7現在地" : "&aクリックでテレポート"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private @NotNull Set<String> loadUnlocked(@NotNull AstPlayer astPlayer) {
        return unlockedCache.computeIfAbsent(astPlayer.getAccount().getUuid(), ignored -> {
            try {
                return new HashSet<>(unlockRepository.findUnlockedIds(astPlayer.getAccount().getUuid()));
            } catch (IOException e) {
                return new HashSet<>();
            }
        });
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }
}
