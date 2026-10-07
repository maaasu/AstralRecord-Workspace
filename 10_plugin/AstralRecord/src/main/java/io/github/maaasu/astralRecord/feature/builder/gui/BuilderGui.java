package io.github.maaasu.astralRecord.feature.builder.gui;

import io.github.maaasu.astralRecord.feature.builder.model.BuilderOperation;
import io.github.maaasu.astralRecord.feature.builder.model.BuilderRequest;
import io.github.maaasu.astralRecord.feature.builder.model.BuilderSelectionAction;
import io.github.maaasu.astralRecord.feature.builder.model.BuilderSettings;
import io.github.maaasu.astralRecord.feature.builder.model.WorldEditSnapshot;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** WorldEditを知らなくても範囲・素材・操作の順に進められる建築用GUIです。 */
public final class BuilderGui {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final List<Material> PALETTE = List.of(
            Material.STONE, Material.COBBLESTONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE_BRICKS, Material.TUFF, Material.ANDESITE,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.DARK_OAK_PLANKS,
            Material.CHERRY_PLANKS, Material.BAMBOO_PLANKS, Material.OAK_LOG, Material.SPRUCE_LOG, Material.STRIPPED_OAK_LOG,
            Material.BRICKS, Material.MUD_BRICKS, Material.SANDSTONE, Material.SMOOTH_SANDSTONE,
            Material.RED_SANDSTONE, Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ, Material.QUARTZ_PILLAR, Material.CALCITE,
            Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.BLACK_CONCRETE,
            Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.GLASS, Material.WHITE_STAINED_GLASS, Material.SEA_LANTERN,
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.SAND, Material.GRAVEL,
            Material.PRISMARINE, Material.COPPER_BLOCK, Material.IRON_BLOCK, Material.AIR);
    private static final List<Material> ALL_BLOCKS = Arrays.stream(Material.values())
            .filter(material -> !material.isLegacy() && material.isBlock() && material.isItem())
            .sorted(Comparator.comparing(Material::name)).toList();
    private final BuilderService service;

    /**
     * GUIを生成します。
     * @param service 操作の権限確認・WorldEdit実行を担当するサービス
     */
    public BuilderGui(@NotNull BuilderService service) { this.service = service; }

    /**
     * 利用権限を確認してホームを開きます。メインスレッドで呼び出します。
     * @param player GUIを開くプレイヤー
     */
    public void open(@NotNull Player player) { show(player, Screen.HOME); }

    /**
     * このGUIのインベントリか確認します。
     * @param inventory 判定するインベントリ
     * @return ビルダーGUIならtrue
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * GUI所有者を照合します。
     * @param player 操作するプレイヤー
     * @param inventory 操作先インベントリ
     * @return 所有者が一致するならtrue
     */
    public boolean isOwnedBy(@NotNull Player player, @NotNull Inventory inventory) {
        return inventory.getHolder() instanceof Holder holder && holder.owner.equals(player.getUniqueId());
    }

    /**
     * 次tickへ移した通常クリックを処理します。アイテム自体の移動は行いません。
     * @param player 操作する所有者
     * @param inventory 現在表示中のGUI
     * @param rawSlot ビュー全体でのクリック位置
     * @param clickedMaterial 下側インベントリで選んだ素材。上側クリックはnull
     * @param fast 数値ボタンを最大10単位変更する右クリックか
     */
    public void handleClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot,
                            @Nullable Material clickedMaterial, boolean fast) {
        if (!isOwnedBy(player, inventory) || !service.checkAccess(player)) return;
        Holder holder = (Holder) inventory.getHolder();
        if (holder == null || holder.consumed) return;
        holder.fast = fast;
        if (rawSlot >= SIZE) {
            if (holder.screen == Screen.MATERIAL && clickedMaterial != null
                    && clickedMaterial.isBlock() && clickedMaterial.isItem()) {
                holder.consumed = true;
                chooseMaterial(player, holder, clickedMaterial);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        Consumer<Player> action = holder.actions.get(rawSlot);
        if (action != null) {
            holder.consumed = true;
            GuiSound.SELECT.play(player);
            action.accept(player);
        }
    }

    /** 指定画面を、毎回最新のWorldEdit状態から構築します。 */
    private void show(Player player, Screen screen) {
        show(player, screen, Screen.HOME, false, false, 0, null);
    }

    /** 画面状態と確認要求をholderに保持して、共通の安全な遷移を使います。 */
    private void show(Player player, Screen screen, Screen back, boolean replaceFrom,
                      boolean allBlocks, int page, @Nullable BuilderRequest request) {
        if (!player.isOnline() || !service.checkAccess(player)) return;
        Holder holder = new Holder(player.getUniqueId(), screen, back, replaceFrom, allBlocks, page);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("ビルダー | " + screen.label, NamedTextColor.GOLD));
        holder.inventory = inventory;
        BuilderSettings settings = service.settings(player);
        WorldEditSnapshot snapshot = service.inspect(player);
        switch (screen) {
            case HOME -> home(player, holder, settings, snapshot);
            case SELECTION -> selection(player, holder, settings, snapshot);
            case MATERIAL -> materials(holder, settings);
            case EDIT -> edit(player, holder, settings, snapshot);
            case COPY -> clipboard(player, holder, settings, snapshot);
            case SHAPES -> shapes(player, holder, settings, snapshot);
            case REPEAT -> repeat(player, holder, settings, snapshot);
            case GUIDE -> guide(holder);
            case CONFIRM -> confirmation(holder, settings, request);
        }
        if (screen != Screen.MATERIAL) {
            button(holder, 45, Material.ARROW, "ホームへ戻る", List.of("素材や範囲を見直せます"), p -> show(p, Screen.HOME));
        }
        button(holder, 53, Material.BARRIER, "閉じる", List.of("選択した設定は次回も使えます", "編集は確認画面の実行ボタンで行います"), Player::closeInventory);
        GuiOpenSupport.open(player, inventory);
    }

    /** 最初に行う3段階と、現在の準備状態をホームに表示します。 */
    private void home(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.WRITABLE_BOOK, "はじめての建築：3ステップ",
                "① 範囲：編集する箱の対角の2隅を決める", "② 素材：使うブロックを選ぶ", "③ 編集：操作を選び、内容を確認して実行",
                "クリックで進むだけ。コマンド入力は不要です", "説明はアイコンにカーソルを合わせて読めます");
        button(holder, 10, Material.WOODEN_AXE, "① 編集する範囲を選ぶ", selectionLore(snapshot), p -> show(p, Screen.SELECTION));
        materialButton(holder, 12, settings.material, "② 配置する素材", p -> materialScreen(p, Screen.HOME, false));
        button(holder, 14, Material.BRICKS, "③ 範囲を編集する", List.of("塗りつぶし・置換・壁・削除など", "操作を選んでも、確認するまで編集されません"), p -> show(p, Screen.EDIT));
        button(holder, 16, Material.PAPER, "コピー・貼り付け", clipboardLore(snapshot), p -> show(p, Screen.COPY));
        button(holder, 28, Material.SNOW_BLOCK, "球・円柱を作る", List.of("範囲選択は不要です", "通常は立っている位置を基準に作ります", "確認画面のWorldEdit基準位置を確認してください", "半径や高さをボタンで調整できます"), p -> show(p, Screen.SHAPES));
        button(holder, 30, Material.PISTON, "繰り返す・移動する", List.of("選択した範囲を指定方向へ並べる/移す", "方向・距離/回数をボタンで調整できます"), p -> show(p, Screen.REPEAT));
        button(holder, 32, Material.BOOK, "使い方・困ったとき", List.of("初めての操作例、コピーの基準位置", "取り消し・空気・マスクの注意点"), p -> show(p, Screen.GUIDE));
        operation(player, holder, 38, BuilderOperation.UNDO, snapshot, "直前のWorldEdit編集を1回取り消します", "失敗したときは、まずこのボタンへ");
        operation(player, holder, 42, BuilderOperation.REDO, snapshot, "取り消したWorldEdit編集を1回やり直します");
        status(holder, 49, snapshot);
    }

    /** 対角2隅の指定、選択ツール、範囲の拡縮を説明とともに表示します。 */
    private void selection(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.BOOK, "範囲は「箱の対角の2隅」で決まります",
                "1隅目と2隅目を、反対側の角に指定します", "高さも含まれます。平面なら同じ高さで指定", "足元＝自分が立つ座標。視線＝見ているブロック",
                "指定後は /builder でこの画面へ戻れます", "直方体以外を選択中なら先に直方体へ切替");
        selectionButton(player, holder, 10, Material.STONE, "直方体の範囲に切り替える", BuilderSelectionAction.CUBOID,
                "このGUIの範囲編集は直方体を使用します", "切替後、2隅を指定してください");
        selectionButton(player, holder, 12, Material.WOODEN_AXE, "選択用の木の斧を受け取る", BuilderSelectionAction.WAND,
                "斧で左クリック：1隅目", "斧で右クリック：2隅目", "対象ブロックを壊さずに範囲を選びます");
        selectionButton(player, holder, 14, Material.PAPER, "現在の範囲情報を表示", BuilderSelectionAction.SIZE,
                "大きさやブロック数をチャットで確認します");
        selectionButton(player, holder, 16, Material.GLOWSTONE_DUST, "範囲の見える表示を切り替える", BuilderSelectionAction.DRAW,
                "WorldEditの選択表示を切り替えます", "クライアント/サーバーの対応により表示は異なります");
        selectionButton(player, holder, 19, Material.RED_WOOL, "足元を1隅目にする", BuilderSelectionAction.POS1,
                "現在の自分のブロック座標を指定します", "2隅目へ移動してから再度GUIを開きます");
        selectionButton(player, holder, 21, Material.BLUE_WOOL, "足元を2隅目にする", BuilderSelectionAction.POS2,
                "現在の自分のブロック座標を指定します", "1隅目の反対側の角で使ってください");
        selectionButton(player, holder, 23, Material.RED_DYE, "見ているブロックを1隅目にする", BuilderSelectionAction.HPOS1,
                "GUIを開く前に狙っていたブロックを指定", "空や遠すぎる場所では指定できません");
        selectionButton(player, holder, 25, Material.BLUE_DYE, "見ているブロックを2隅目にする", BuilderSelectionAction.HPOS2,
                "GUIを開く前に狙っていたブロックを指定", "1隅目の反対側の角を狙ってください");
        numberControls(holder, 28, "広げる/縮める量", settings.amount, "ブロック", 1, 64, p -> service.settings(p).amount--,
                p -> service.settings(p).amount++, Screen.SELECTION);
        direction(holder, 32, settings, Screen.SELECTION);
        selectionButton(player, holder, 34, Material.LIME_DYE, "範囲を広げる", BuilderSelectionAction.EXPAND,
                "指定方向へ " + settings.amount + " ブロック広げます", "範囲だけを変更し、ブロックは変更しません");
        selectionButton(player, holder, 35, Material.RED_DYE, "範囲を縮める", BuilderSelectionAction.CONTRACT,
                "指定方向の端を " + settings.amount + " ブロック縮めます", "範囲だけを変更し、ブロックは変更しません");
        status(holder, 40, snapshot);
        button(holder, 49, Material.BRICKS, "次へ：素材と編集", List.of("範囲を確認したら、使う素材と操作を選びます"), p -> show(p, Screen.EDIT));
    }

    /** 権限のない範囲操作は、実行できない理由を同じ場所に表示します。 */
    private void selectionButton(Player player, Holder holder, int slot, Material icon, String name,
                                 BuilderSelectionAction action, String... descriptions) {
        List<String> lore = new ArrayList<>(List.of(descriptions));
        boolean allowed = action.permission == null || player.hasPermission(action.permission);
        if (!allowed) lore.add("この操作の権限がありません");
        button(holder, slot, allowed ? icon : Material.GRAY_DYE, name, lore,
                allowed ? p -> service.select(p, action) : null);
    }

    /** 素材選択の戻り先を維持して初期パレットを開きます。 */
    private void materialScreen(Player player, Screen back, boolean replaceFrom) {
        show(player, Screen.MATERIAL, back, replaceFrom, false, 0, null);
    }

    /** パレットまたは全ブロック一覧を45件ずつ表示します。 */
    private void materials(Holder holder, BuilderSettings settings) {
        List<Material> source = holder.allBlocks ? ALL_BLOCKS : PALETTE;
        int pages = Math.max(1, (source.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(holder.page, pages - 1));
        for (int slot = 0; slot < PAGE_SIZE && page * PAGE_SIZE + slot < source.size(); slot++) {
            Material material = source.get(page * PAGE_SIZE + slot);
            List<Component> lore = List.of(text(holder.replaceFrom ? "置き換える前の素材に指定" : "配置する素材に指定"),
                    text(material.isAir() ? "空気＝ブロックを置かない/消す" : "クリックで選択して前の画面へ戻る"),
                    text("素材の選択でアイテムは消費しません"));
            holder.inventory.setItem(slot, GuiItems.create(icon(material), materialName(material), lore));
            holder.actions.put(slot, p -> chooseMaterial(p, holder, material));
        }
        button(holder, 45, Material.ARROW, "戻る", List.of("選択せず前の画面へ戻ります"), p -> show(p, holder.back));
        button(holder, 46, Material.BOOKSHELF, holder.allBlocks ? "よく使う建材へ" : "すべてのブロックへ",
                List.of("初期パレットは建材45種", "全一覧はブロック名順。矢印でページ移動"),
                p -> show(p, Screen.MATERIAL, holder.back, holder.replaceFrom, !holder.allBlocks, 0, null));
        if (page > 0) button(holder, 47, Material.ARROW, "前のページ", List.of(),
                p -> show(p, Screen.MATERIAL, holder.back, holder.replaceFrom, holder.allBlocks, page - 1, null));
        info(holder, 48, Material.PAPER, (page + 1) + " / " + pages + " ページ",
                holder.replaceFrom ? "選択先：置換前の素材" : "選択先：配置する素材", "下の持ち物のブロックをクリックしても選べます",
                "普通の左/右クリックを使用してください");
        if (page + 1 < pages) button(holder, 49, Material.ARROW, "次のページ", List.of(),
                p -> show(p, Screen.MATERIAL, holder.back, holder.replaceFrom, holder.allBlocks, page + 1, null));
        button(holder, 50, Material.BARRIER, "空気を選ぶ", List.of("置換後に指定：対象の素材を消します", "置換前に指定：空いている場所を埋めます"),
                p -> chooseMaterial(p, holder, Material.AIR));
        materialButton(holder, 51, holder.replaceFrom ? settings.replaceFrom : settings.material, "現在の素材", null);
    }

    /** アイテムを消費せず素材だけ設定し、元の画面へ戻します。 */
    private void chooseMaterial(Player player, Holder holder, Material material) {
        if (holder.replaceFrom) service.settings(player).replaceFrom = material;
        else service.settings(player).material = material;
        GuiSound.SELECT.play(player);
        show(player, holder.back);
    }

    /** 範囲内の編集を、置換の前後の素材とともに表示します。 */
    private void edit(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.BOOK, "編集する前に範囲と素材を確認",
                "どの操作も、次の確認画面で実行するまで変更しません", "置換は「置換前→配置する素材」に変えます", "空気を配置する素材に選ぶとブロックを消せます");
        materialButton(holder, 10, settings.material, "配置する素材", p -> materialScreen(p, Screen.EDIT, false));
        materialButton(holder, 12, settings.replaceFrom, "置換前の素材", p -> materialScreen(p, Screen.EDIT, true));
        button(holder, 16, Material.WOODEN_AXE, "範囲を見直す", selectionLore(snapshot), p -> show(p, Screen.SELECTION));
        operation(player, holder, 19, BuilderOperation.SET, snapshot, "箱の中を、選んだ素材で全部埋めます", "元のブロックも空気も置き換えます");
        operation(player, holder, 21, BuilderOperation.REPLACE, snapshot, "指定した置換前の素材だけを変更します", "それ以外のブロックは残します");
        operation(player, holder, 23, BuilderOperation.WALLS, snapshot, "箱の四方に壁を作ります", "天井と床は作りません");
        operation(player, holder, 25, BuilderOperation.FACES, snapshot, "壁・天井・床の6面を作ります", "箱の内側は変更しません");
        operation(player, holder, 30, BuilderOperation.DELETE, snapshot, "選択した箱の中を空気にします", "建築を消す操作です。範囲をよく確認してください");
        operation(player, holder, 32, BuilderOperation.SMOOTH, snapshot, "範囲内の地面の高さをなだらかにします", "山や地面用です。建物を含めないでください", "配置素材の設定は使用しません");
        status(holder, 40, snapshot);
    }

    /** 立ち位置を基準にしたコピーと貼り付けを説明します。 */
    private void clipboard(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.BOOK, "コピーは「自分と建築の位置関係」も覚えます",
                "① 建築全体を2隅で囲む", "② 入口など基準にしたい位置へ立ってコピー", "③ 貼り付け先の同じ基準位置へ移動して貼り付け",
                "WorldEditで基準位置を変更済みなら確認画面を優先",
                "回転・反転はコピーした内容だけに適用", "元の建築はそのまま残ります");
        operation(player, holder, 10, BuilderOperation.COPY, snapshot, "範囲内をコピーします。元の建築は残ります", "今の自分の立ち位置を基準として記憶します");
        operation(player, holder, 12, BuilderOperation.PASTE, snapshot, "現在位置を基準にコピーした建築を置きます", "コピー時の自分と建築の位置関係が使われます");
        button(holder, 16, settings.ignoreAir ? Material.GLASS : Material.BARRIER,
                "貼り付け時の空気：" + (settings.ignoreAir ? "無視する" : "貼り付ける"),
                List.of("クリックで切替", "無視する：コピー内の空気で既存建築を消さない", "貼り付ける：空気も置いて元の状態を再現", "繰り返し・移動でも同じ設定を使います"),
                p -> { service.settings(p).ignoreAir = !service.settings(p).ignoreAir; show(p, Screen.COPY); });
        operation(player, holder, 28, BuilderOperation.ROTATE_LEFT, snapshot, "コピーした内容を左へ90度回します", "貼り付ける前に使ってください");
        operation(player, holder, 30, BuilderOperation.ROTATE_RIGHT, snapshot, "コピーした内容を右へ90度回します", "貼り付ける前に使ってください");
        operation(player, holder, 32, BuilderOperation.FLIP_EAST, snapshot, "コピーした内容の東西を反転します");
        operation(player, holder, 34, BuilderOperation.FLIP_UP, snapshot, "コピーした内容の上下を反転します");
        info(holder, 40, Material.PAPER, "現在のコピー", clipboardLore(snapshot).toArray(String[]::new));
        button(holder, 49, Material.WOODEN_AXE, "範囲を見直す", selectionLore(snapshot), p -> show(p, Screen.SELECTION));
    }

    /** 数値を入力せず半径と高さを選べる図形画面です。 */
    private void shapes(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.BOOK, "基準位置に球・円柱を作ります",
                "通常は立ち位置が基準。先に作りたい場所へ移動", "WorldEditの設定により選択1隅目が基準になる場合もあります",
                "確認画面のWorldEdit基準位置を確認してください", "半径は中心から端までの長さ（ブロック）", "球の高さは半径で決まります", "円柱だけ高さの設定を使います");
        materialButton(holder, 10, settings.material, "図形の素材", p -> materialScreen(p, Screen.SHAPES, false));
        numberControls(holder, 12, "半径", settings.radius, "ブロック", 1, 32,
                p -> service.settings(p).radius--, p -> service.settings(p).radius++, Screen.SHAPES);
        numberControls(holder, 21, "円柱の高さ", settings.height, "ブロック", 1, 64,
                p -> service.settings(p).height--, p -> service.settings(p).height++, Screen.SHAPES);
        operation(player, holder, 28, BuilderOperation.SPHERE, snapshot, "中心から全方向へ丸い球を作ります", "中身も選んだ素材で埋まります");
        operation(player, holder, 30, BuilderOperation.HOLLOW_SPHERE, snapshot, "丸い球の外側だけを作ります", "中身を塗りつぶさない球です");
        operation(player, holder, 32, BuilderOperation.CYLINDER, snapshot, "足元から上へ円柱を作ります", "円の内側も選んだ素材で埋まります");
        operation(player, holder, 34, BuilderOperation.HOLLOW_CYLINDER, snapshot, "足元から上へ円柱の側面を作ります", "中身を塗りつぶさない円柱です");
    }

    /** 回数と距離を共用する方向固定の繰り返し・移動画面です。 */
    private void repeat(Player player, Holder holder, BuilderSettings settings, WorldEditSnapshot snapshot) {
        info(holder, 4, Material.BOOK, "範囲を「並べる」または「移す」",
                "繰り返し：箱の幅ぶんずつ、コピーを並べます", "移動：指定ブロック数だけ動かし、元を空気にします", "方向は視線によらず、下の方角で固定します",
                "空気を含めると周囲の建築を消す場合があります");
        button(holder, 10, Material.WOODEN_AXE, "範囲を見直す", selectionLore(snapshot), p -> show(p, Screen.SELECTION));
        numberControls(holder, 12, "回数 / 移動距離", settings.amount, "回 / ブロック", 1, 64,
                p -> service.settings(p).amount--, p -> service.settings(p).amount++, Screen.REPEAT);
        direction(holder, 16, settings, Screen.REPEAT);
        button(holder, 21, settings.ignoreAir ? Material.GLASS : Material.BARRIER,
                "空気：" + (settings.ignoreAir ? "無視する" : "含める"),
                List.of("クリックで切替。貼り付けと共通の設定です", "無視する：範囲内の空気を移動先に置きません", "含める：空気で移動先のブロックも消します"),
                p -> { service.settings(p).ignoreAir = !service.settings(p).ignoreAir; show(p, Screen.REPEAT); });
        operation(player, holder, 28, BuilderOperation.STACK, snapshot, settings.amount + " 回、" + settings.direction.label + "へ並べます",
                "1回ぶんの間隔は選択した箱の幅です", "繰り返しは最大16回。数値を16以下にしてください", "元の建築も残ります");
        operation(player, holder, 32, BuilderOperation.MOVE, snapshot, settings.amount + " ブロック、" + settings.direction.label + "へ移動します",
                "元の場所は空気になります", "移動先のブロックは上書きされます");
        status(holder, 40, snapshot);
    }

    /** 上下限を超えず、左クリックで1、右クリックで最大10単位変更します。 */
    private void numberControls(Holder holder, int slot, String label, int value, String unit, int min, int max,
                                Consumer<Player> decrease, Consumer<Player> increase, Screen screen) {
        List<String> controls = List.of("左クリック：1 / 右クリック：10", "最小 " + min + " / 最大 " + max);
        button(holder, slot, value > min ? Material.RED_DYE : Material.GRAY_DYE, label + " 減らす", controls,
                value > min ? p -> { for (int i = 0; i < Math.min(holder.fast ? 10 : 1, value - min); i++) decrease.accept(p); show(p, screen); } : null);
        info(holder, slot + 1, Material.PAPER, label + "：" + value + " " + unit, "左右のボタンで変更します", "右クリックなら10ずつ変更できます");
        button(holder, slot + 2, value < max ? Material.LIME_DYE : Material.GRAY_DYE, label + " 増やす", controls,
                value < max ? p -> { for (int i = 0; i < Math.min(holder.fast ? 10 : 1, max - value); i++) increase.accept(p); show(p, screen); } : null);
    }

    /** 方角の列挙値を順に選ぶボタンを表示します。 */
    private void direction(Holder holder, int slot, BuilderSettings settings, Screen screen) {
        button(holder, slot, Material.COMPASS, "方向：" + settings.direction.label,
                List.of("クリックで 北→東→南→西→上→下", "視線を変えてもこの方向は変わりません"), p -> {
                    BuilderSettings current = service.settings(p);
                    BuilderSettings.Direction[] directions = BuilderSettings.Direction.values();
                    current.direction = directions[(current.direction.ordinal() + 1) % directions.length];
                    show(p, screen);
                });
    }

    /** 実行可能性と操作の前提を表示し、操作選択後は必ず確認へ進めます。 */
    private void operation(Player player, Holder holder, int slot, BuilderOperation operation,
                           WorldEditSnapshot snapshot, String... descriptions) {
        List<String> lore = new ArrayList<>(List.of(descriptions));
        boolean allowed = service.canRun(player, operation);
        if (!allowed) lore.add("この操作の権限がないか、現在は利用できません");
        if (operation.selectionRequired && snapshot.selection() == null) lore.add("準備が必要：範囲の2隅を指定してください");
        else if (operation.selectionRequired && !snapshot.selection().cuboid()) lore.add("準備が必要：直方体へ切り替えてください");
        if (operation.clipboardRequired && snapshot.clipboard() == null) lore.add("準備が必要：先に建築をコピーしてください");
        lore.add("クリックで内容を確認。ここではまだ実行しません");
        button(holder, slot, allowed ? operation.icon : Material.GRAY_DYE, operation.label, lore, p -> {
            BuilderRequest request = service.prepare(p, operation);
            if (request != null) show(p, Screen.CONFIRM, holder.screen, false, false, 0, request);
            else show(p, holder.screen);
        });
    }

    /** 実際のWorldEdit範囲と生成位置を表示して一度の実行を選ばせます。 */
    private void confirmation(Holder holder, BuilderSettings settings, @Nullable BuilderRequest request) {
        if (request == null) return;
        BuilderOperation operation = request.operation();
        info(holder, 4, operation.icon, "確認：" + operation.label,
                "下の内容を確認してから、緑の実行ボタンを押す", "実行時にも権限・範囲・コピー・位置を再確認します");
        if (usesMaterial(operation)) {
            materialButton(holder, 10, operation == BuilderOperation.DELETE ? Material.AIR : settings.material, "配置する素材", null);
        } else {
            info(holder, 10, operation.icon, "素材設定は使用しません", "この操作は範囲・コピー内容・履歴を使用します");
        }
        if (operation == BuilderOperation.REPLACE) materialButton(holder, 12, settings.replaceFrom, "置換前の素材", null);
        info(holder, 16, Material.WOODEN_AXE, "WorldEditの選択範囲", selectionLore(request.snapshot()).toArray(String[]::new));
        info(holder, 21, Material.COMPASS, "実行する位置", "ワールド：" + worldName(request),
                "X " + request.x() + " / Y " + request.y() + " / Z " + request.z(),
                "WorldEditの基準：" + placementLabel(request.snapshot().placement()),
                "コピーはコピー時の位置関係と回転/反転を使用");
        List<String> details = new ArrayList<>();
        details.add(request.estimatedBlocks() < 0 ? "変更量：WorldEditに保存された履歴によります"
                : "見積もり対象：最大 " + number(request.estimatedBlocks()) + " ブロック");
        details.add("実際の変更数は素材や形状により異なります");
        details.add("マスク：" + (request.snapshot().mask() == null ? "なし" : "WorldEditで設定済み（適用されます）"));
        if (operation == BuilderOperation.PASTE || operation == BuilderOperation.STACK || operation == BuilderOperation.MOVE) {
            details.add("空気：" + (settings.ignoreAir ? "無視する" : "含める"));
        }
        if (operation == BuilderOperation.SPHERE || operation == BuilderOperation.HOLLOW_SPHERE
                || operation == BuilderOperation.CYLINDER || operation == BuilderOperation.HOLLOW_CYLINDER) {
            details.add("半径：" + settings.radius + " / 円柱の高さ：" + settings.height);
        }
        if (operation == BuilderOperation.STACK || operation == BuilderOperation.MOVE) {
            details.add("回数/距離：" + settings.amount + " / 方向：" + settings.direction.label);
        }
        info(holder, 23, Material.PAPER, "変更量と条件", details.toArray(String[]::new));
        if (operation.clipboardRequired) {
            info(holder, 28, Material.PAPER, "使用するコピー", clipboardLore(request.snapshot()).toArray(String[]::new));
        }
        info(holder, 31, Material.CLOCK, "間違えたら「取り消す」へ",
                operation.changesWorld ? "実行後、ホームの取り消すで直前の編集を戻せます" : "この操作はコピー内容を変更します。ワールドは変更しません",
                "取り消せる履歴や回数はWorldEditの設定に従います", "コピーの回転/反転は逆の操作か、再コピーで戻す");
        button(holder, 38, Material.RED_CONCRETE, "キャンセルして戻る", List.of("この操作を実行しません"), p -> show(p, holder.back));
        button(holder, 42, Material.LIME_CONCRETE, "この内容で実行する", List.of("GUIを閉じて操作を1回だけ実行します", "編集後はチャットの案内からGUIを再び開けます"), p -> service.execute(p, request));
    }

    /** 確認位置のワールド名は実ワールドから解決し、UUIDは露出させません。 */
    private String worldName(BuilderRequest request) {
        org.bukkit.World world = Bukkit.getWorld(request.worldId());
        return world == null ? "取得できません" : world.getName();
    }

    /** WorldEditの配置モードをプレイヤー向けの説明へ変換します。 */
    private String placementLabel(@Nullable String placement) {
        return placement == null || placement.isBlank() ? "未確認" : placement;
    }

    /** 配置素材を使う操作だけを確認画面で表示します。 */
    private boolean usesMaterial(BuilderOperation operation) {
        return switch (operation) {
            case SET, REPLACE, WALLS, FACES, DELETE, SPHERE, HOLLOW_SPHERE, CYLINDER, HOLLOW_CYLINDER -> true;
            default -> false;
        };
    }

    /** 機能ごとの短いガイドを常時参照できるように表示します。 */
    private void guide(Holder holder) {
        info(holder, 10, Material.WOODEN_AXE, "例：石の床を作る", "① 床の1隅で足元を1隅目に指定", "② 反対の角へ移動し、同じ高さで2隅目を指定",
                "③ 素材で石を選ぶ→塗りつぶす→確認→実行", "高さが違うと床ではなく厚みのある箱になります");
        info(holder, 12, Material.COPPER_BLOCK, "例：土だけ石に変える", "範囲を選び、編集画面を開きます", "置換前＝土、配置する素材＝石", "「素材を置き換える」を選ぶと土だけ変わります",
                "置換前を空気にすると、空いている所を埋めます");
        info(holder, 14, Material.PAPER, "例：家を別の場所へコピー", "家全体を、屋根と地面まで囲みます", "入口など基準にしたい場所でコピー", "別の場所の入口にしたい所で貼り付け",
                "空気を無視すると、貼り付け先を消しにくくなります");
        info(holder, 16, Material.CLOCK, "失敗した・戻したい", "ホームの「直前の編集を取り消す」を使います", "戻しすぎたら「やり直す」を使います", "コピー/回転/反転はワールド編集履歴とは別です",
                "戻せる回数はWorldEdit側の履歴設定によります");
        info(holder, 28, Material.BARRIER, "空気と削除の違い", "素材の「空気」は何もないブロックです", "削除：箱の中を全部空気にする", "置換の配置素材を空気：指定した素材だけ消す",
                "空気のアイコンは見えるようバリアで表示します");
        info(holder, 30, Material.GLASS, "一部しか変わらないとき", "WorldEditのマスクがあると変更対象が絞られます", "確認画面にマスクの有無を表示します", "このGUIでは既存のマスク設定を引き継ぎます",
                "操作できない場合は権限と範囲を確認してください");
        info(holder, 32, Material.COMPASS, "位置と範囲が思った通りでない", "2隅には高さも含まれます", "コピーはコピー時の基準位置を覚えています", "図形は通常足元、移動と繰り返しは指定した方角",
                "実行前の確認画面でワールドと座標を確認します");
        info(holder, 34, Material.BOOK, "操作の基本", "普通の左/右クリックでボタンを選びます", "画面内のアイテムを取り出すことはできません", "持ち物の素材選択は素材画面だけで使えます",
                "範囲指定で閉じた後は /builder で戻れます");
    }

    /** 現在の範囲はGUI独自の記録でなくWorldEditの実セッションを表示します。 */
    private List<String> selectionLore(WorldEditSnapshot snapshot) {
        if (!snapshot.available()) return List.of("WorldEditを利用できません");
        if (!snapshot.readable()) return List.of("WorldEditの状態を取得できません", "編集はできません。管理者へ確認してください");
        WorldEditSnapshot.Selection selection = snapshot.selection();
        if (selection == null) return List.of("範囲が未完成です。2隅を選んでください", "足元または見ているブロックで指定できます");
        return List.of("ワールド：" + selection.worldName(),
                "1端：X " + selection.minX() + " / Y " + selection.minY() + " / Z " + selection.minZ(),
                "2端：X " + selection.maxX() + " / Y " + selection.maxY() + " / Z " + selection.maxZ(),
                "体積：" + number(selection.volume()) + " ブロック", selection.cuboid() ? "直方体の範囲です" : "直方体へ切り替えてください");
    }

    /** 現在のコピーの有無と大きさを表示します。 */
    private List<String> clipboardLore(WorldEditSnapshot snapshot) {
        if (!snapshot.available() || !snapshot.readable()) return List.of("WorldEditのコピー状態を取得できません");
        if (snapshot.multipleClipboards()) return List.of("複数のコピーを読み込み中です", "GUIで建築を1つコピーし直してください");
        WorldEditSnapshot.Clipboard clipboard = snapshot.clipboard();
        if (clipboard != null && !clipboard.safeTransform()) return List.of("コピーに斜め回転・拡縮があります", "GUIでコピーし直して90度回転を使ってください");
        return clipboard == null ? List.of("コピーした建築はまだありません", "範囲を選んで「建築をコピー」へ")
                : List.of("コピーあり：幅 " + clipboard.width() + " / 高さ " + clipboard.height() + " / 奥行 " + clipboard.length(),
                "体積：" + number(clipboard.volume()) + " ブロック", "貼り付け前に基準位置を確認してください");
    }

    /** 現在の範囲状態を読み取り専用のアイコンにまとめます。 */
    private void status(Holder holder, int slot, WorldEditSnapshot snapshot) {
        info(holder, slot, snapshot.selection() == null ? Material.GRAY_DYE : Material.PAPER,
                "現在の範囲", selectionLore(snapshot).toArray(String[]::new));
    }

    /** 素材名はクライアントの言語で表示し、空気だけは説明を優先します。 */
    private Component materialName(Material material) {
        return material.isAir() ? Component.text("空気（ブロックを消す）", NamedTextColor.AQUA)
                : Component.translatable(material.translationKey()).color(NamedTextColor.AQUA);
    }

    /** 非アイテムの空気はバリアアイコンで示します。 */
    private Material icon(Material material) { return material.isAir() ? Material.BARRIER : material; }

    /** 選択中の素材を翻訳名つきボタンに表示します。 */
    private void materialButton(Holder holder, int slot, Material material, String label, @Nullable Consumer<Player> action) {
        holder.inventory.setItem(slot, GuiItems.create(icon(material), Component.text(label + "：", NamedTextColor.YELLOW).append(materialName(material)),
                List.of(text(action == null ? "現在の設定" : "クリックで素材を選び直す"), text("素材を選んでもアイテムは消費しません"))));
        if (action != null) holder.actions.put(slot, action);
    }

    /** 説明専用アイコンを配置します。 */
    private void info(Holder holder, int slot, Material icon, String name, String... lore) {
        button(holder, slot, icon, name, List.of(lore), null);
    }

    /** 表示と固定のクリック処理を同じスロットへ登録します。 */
    private void button(Holder holder, int slot, Material icon, String name, List<String> lore, @Nullable Consumer<Player> action) {
        holder.inventory.setItem(slot, GuiItems.create(icon, Component.text(name, NamedTextColor.AQUA), lore.stream().map(this::text).toList()));
        if (action != null) holder.actions.put(slot, action);
    }

    /** GUI説明行を共通色にします。 */
    private Component text(String text) { return Component.text(text, NamedTextColor.GRAY); }

    /** 大きなブロック数を桁区切りで表示します。 */
    private String number(long value) { return String.format(java.util.Locale.JAPAN, "%,d", value); }

    /** GUIの画面種別と表示名です。 */
    private enum Screen {
        HOME("ホーム"), SELECTION("範囲を選ぶ"), MATERIAL("素材を選ぶ"), EDIT("範囲の編集"),
        COPY("コピー・貼り付け"), SHAPES("球・円柱"), REPEAT("繰り返し・移動"), GUIDE("使い方"), CONFIRM("実行前の確認");
        private final String label;
        /** 画面名を設定します。 */
        Screen(String label) { this.label = label; }
    }

    /** 所有者と画面専用の操作を保持し、他プレイヤーからの操作を防ぎます。 */
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final Screen screen;
        private final Screen back;
        private final boolean replaceFrom;
        private final boolean allBlocks;
        private final int page;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private Inventory inventory;
        private boolean consumed;
        private boolean fast;
        /** この画面の設定を固定します。 */
        private Holder(UUID owner, Screen screen, Screen back, boolean replaceFrom, boolean allBlocks, int page) {
            this.owner = owner;
            this.screen = screen;
            this.back = back;
            this.replaceFrom = replaceFrom;
            this.allBlocks = allBlocks;
            this.page = page;
        }
        /** @return このholderが所有する実際のGUIインベントリ */
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
