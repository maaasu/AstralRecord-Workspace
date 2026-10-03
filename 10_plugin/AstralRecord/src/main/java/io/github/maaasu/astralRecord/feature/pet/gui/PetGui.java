package io.github.maaasu.astralRecord.feature.pet.gui;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.service.PetService;
import io.github.maaasu.astralRecord.feature.pet.service.PetBagAccess;
import io.github.maaasu.astralRecord.feature.pet.view.PetItemView;
import io.github.maaasu.astralRecord.feature.pet.view.PetOperationFeedback;
import io.github.maaasu.astralRecord.feature.player.*;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.*;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.*;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** バッグ内の個体選択と、施設・管理者による孵化・配合・復活を提供します。 */
public final class PetGui implements Listener {
    private static final int PAGE_SIZE=27;
    private static final int PREVIOUS_SLOT=45;
    private static final int CLOSE_SLOT=49;
    private static final int NEXT_SLOT=53;
    private static final NamespacedKey PLACEHOLDER_KEY=new NamespacedKey("astralrecord","pet_gui_placeholder");
    private final org.bukkit.plugin.Plugin plugin;
    private final PetService pets;
    private final InventoryService inventory;
    private final ItemService items;
    private final ItemStackFactory factory;
    private final MobService mobs;
    /**
     * GUI表示と施設距離検証に必要な既存サービスを接続します。
     * @param plugin タスク所有プラグイン
     * @param pets 契約に従った入力値
     * @param inventory 読み込み済みインベントリサービス
     * @param items 公開済みアイテムマスター
     * @param factory 共通アイテム描画ファクトリ
     * @param mobs 契約に従った入力値
     */
    public PetGui(org.bukkit.plugin.Plugin plugin,PetService pets,InventoryService inventory,ItemService items,ItemStackFactory factory,MobService mobs){
        this.plugin=plugin;this.pets=pets;this.inventory=inventory;this.items=items;this.factory=factory;this.mobs=mobs;
    }
    /**
     * 互換入口から管理権限者用の施設画面を開きます。
     * @param player 操作するプレイヤー
     */
    public void open(Player player){openAdminFacility(player);}
    /** 管理権限者が通常PLAYERモードのGAME BAGを対象に施設画面を開きます。権限は実行時にも再確認します。 */
    public void openAdminFacility(Player player){
        AstPlayer owner=AstPlayerCache.get(player);
        if(owner==null||!AccountModeGuard.isGameplayPlayer(owner)){
            PlayerMessageService.getInstance().send(player,PlayerMsgId.P_5065);GuiSound.DENY.play(player);return;
        }
        if(!owner.hasAdminPermission()){
            PlayerMessageService.getInstance().send(player,PlayerMsgId.P_5707);GuiSound.DENY.play(player);return;
        }
        open(player,null,null,true);
    }
    /**
     * 実際に対話したNPCの個体を保持して施設画面を開きます。
     * @param player 操作するプレイヤー
     * @param npc 実際に対話した施設NPC
     */
    public void openFromNpc(Player player,MobInstance npc){open(player,npc.instanceId(),null,false);}
    /**
     * BAGの復活オーブを起点に、戦闘不能個体の選択画面を開きます。
     * @param player 操作するプレイヤー
     * @param entryId 消費元のオーブentry ID
     */
    public void openReviveOrb(Player player,UUID entryId){open(player,null,entryId,false);}
    private void open(Player player,UUID facility,UUID orbEntry,boolean adminFacility){
        AstPlayer owner=AstPlayerCache.get(player);
        if(owner==null||!AccountModeGuard.isGameplayPlayer(owner))return;
        if(adminFacility&&!owner.hasAdminPermission())return;
        Holder holder=new Holder(owner.getAccount().getUuid(),facility,orbEntry,adminFacility);
        holder.busy=pets.hasUnresolved(holder.accountId);
        holder.inventory=Bukkit.createInventory(holder,54,Component.text(orbEntry==null?"ペット飼育員":"ペット復活",NamedTextColor.DARK_GREEN));
        render(holder);GuiOpenSupport.open(player,holder.inventory,()->GuiSound.OPEN.play(player));
    }
    /**
     * API確定後に現在の管理画面だけを更新します。メインスレッドで呼びます。
     * @param player 操作するプレイヤー
     */
    public void refreshCurrent(Player player){
        if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder){
            holder.busy=pets.hasUnresolved(holder.accountId);render(holder);
        }
    }
    private void render(Holder holder){
        Inventory view=holder.inventory;view.clear();holder.rows.clear();
        Set<UUID> owned=new HashSet<>();
        for(var entry:PetBagAccess.entries(inventory.getStateRegistry().get(holder.accountId))){
            if(entry.getQuantity()==1&&entry.getInstanceId()!=null&&
                (io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType.PET.getCode().equalsIgnoreCase(entry.getInstanceType())
                ||io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType.PET_EGG.getCode().equalsIgnoreCase(entry.getInstanceType())))owned.add(entry.getInstanceId());
        }
        // 装備枠へ移動した戦闘不能個体も復活対象に含める。孵化・配合はBAGだけを使う。
        if(holder.mode==Mode.REVIVE){UUID equipped=pets.equippedId(holder.accountId);if(equipped!=null)owned.add(equipped);}
        List<PetInstance> candidates=pets.instances(holder.accountId).stream().filter(pet->owned.contains(pet.id()))
            .filter(pet->holder.mode==Mode.HATCH?pet.isEgg():!pet.isEgg())
            .filter(pet->holder.mode!=Mode.REVIVE||pets.dead(pet)).toList();
        if(owned.stream().anyMatch(id->pets.find(holder.accountId,id)==null))pets.ensureLoaded(holder.accountId);
        if(holder.selected!=null&&!owned.contains(holder.selected))holder.selected=null;
        if(holder.male!=null&&!owned.contains(holder.male))holder.male=null;
        if(holder.female!=null&&!owned.contains(holder.female))holder.female=null;
        if(holder.selected!=null&&candidates.stream().noneMatch(pet->pet.id().equals(holder.selected)))holder.selected=null;
        List<PetInstance> all=candidates.stream().filter(pet->holder.mode==Mode.BREED
            ? !pet.id().equals(holder.male)&&!pet.id().equals(holder.female)
            : !pet.id().equals(holder.selected)).toList();
        holder.listCount=all.size();
        holder.page=GuiPagination.normalizePage(holder.page,all.size(),PAGE_SIZE);
        if(holder.facility!=null||holder.adminFacility){
            view.setItem(0,tab(holder,Mode.HATCH,Material.EGG,"孵化"));
            view.setItem(1,tab(holder,Mode.BREED,Material.WHEAT,"配合"));
            view.setItem(2,tab(holder,Mode.REVIVE,Material.GHAST_TEAR,"復活"));
        }
        List<String> guide=switch(holder.mode){
            case HATCH->List.of("① バッグ内の魔法の卵を選ぶ","② 入力欄と必要素材を確認する","③ 孵化ボタンを押す","入力欄をクリックすると選択を解除できます");
            case BREED->List.of("オスとメスをクリックして親に選ぶ","親の欄をクリックすると選択を解除できます","必要成長度: "+(int)number(pets.master().rules(),"breedMinLevel",20),"素材はバッグに入れてください");
            case REVIVE->List.of("戦闘不能のペットを選ぶ",holder.orbEntry==null?"下段の素材をバッグに用意する":"使用した復活オーブを1個消費します","入力欄をクリックすると選択を解除できます");
        };
        view.setItem(4,button(Material.BOOK,"使い方",guide));
        view.setItem(8,button(Material.PAPER,"ページ "+(holder.page+1)+" / "+GuiPagination.totalPages(all.size(),PAGE_SIZE),List.of("一覧の対象: "+all.size()+"件")));
        for(int index=GuiPagination.pageStart(holder.page,PAGE_SIZE);index<GuiPagination.pageEnd(holder.page,all.size(),PAGE_SIZE);index++){
            PetInstance pet=all.get(index);int slot=9+index%PAGE_SIZE;holder.rows.put(slot,pet.id());
            ItemStack stack=PetItemView.create(pet,pets,items,factory);var meta=stack.getItemMeta();
            List<Component> lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());
            lore.add(Component.text(holder.mode==Mode.BREED?"クリック: 配合する親に設定":"クリック: 入力欄に選ぶ",NamedTextColor.GREEN));
            meta.lore(lore);stack.setItemMeta(meta);view.setItem(slot,stack);
        }
        if(candidates.isEmpty())view.setItem(22,button(Material.GRAY_DYE,"対象がありません",List.of(holder.mode==Mode.HATCH?"魔法の卵をバッグに入れてください":"対象のペットをバッグに入れてください")));
        for(int slot=45;slot<54;slot++)view.setItem(slot,GuiItems.placeholder(PLACEHOLDER_KEY));
        view.setItem(PREVIOUS_SLOT,GuiItems.previousPageButton(Component.text("前のページ",NamedTextColor.WHITE,TextDecoration.BOLD),
            List.of(Component.text(holder.page+" / "+GuiPagination.totalPages(all.size(),PAGE_SIZE),NamedTextColor.GRAY)),GuiPagination.hasPreviousPage(holder.page)));
        view.setItem(NEXT_SLOT,GuiItems.nextPageButton(Component.text("次のページ",NamedTextColor.WHITE,TextDecoration.BOLD),
            List.of(Component.text((holder.page+2)+" / "+GuiPagination.totalPages(all.size(),PAGE_SIZE),NamedTextColor.GRAY)),
            GuiPagination.hasNextPage(holder.page,all.size(),PAGE_SIZE)));
        view.setItem(CLOSE_SLOT,GuiItems.closeButton());
        PetInstance chosen=holder.selected==null?null:pets.find(holder.accountId,holder.selected);
        view.setItem(36,chosen==null?button(Material.GRAY_DYE,"入力欄: 対象を選んでください",List.of("上の一覧をクリック"))
            :selectedItem(chosen));
        if(holder.mode==Mode.HATCH){
            JsonArray materials=chosen==null?new JsonArray():array(pets.master().species(chosen.speciesId()),"hatchMaterials");
            showMaterials(holder,materials);
            view.setItem(50,button(chosen==null?Material.GRAY_DYE:Material.EGG,"選択した魔法の卵を孵化する",
                chosen==null?List.of("まず上の一覧から魔法の卵を選んでください"):materialLore(holder.accountId,materials)));
        }
        if(holder.mode==Mode.BREED){
            view.setItem(47,parent(holder,"オスの親",holder.male));view.setItem(48,parent(holder,"メスの親",holder.female));
            showMaterials(holder,array(pets.master().rules(),"breedMaterials"));
            view.setItem(51,button(Material.WHEAT,"配合して魔法の卵を作る",materialLore(holder.accountId,array(pets.master().rules(),"breedMaterials"))));
        }
        if(holder.mode==Mode.REVIVE){
            if(holder.orbEntry==null)showMaterials(holder,array(pets.master().rules(),"reviveMaterials"));
            view.setItem(52,button(Material.GHAST_TEAR,"選択したペットを復活する",holder.orbEntry==null?materialLore(holder.accountId,array(pets.master().rules(),"reviveMaterials")):List.of("復活オーブを1個消費")));
        }
    }
    /** 施設内の操作用途を切り替えるタブと現在の選択状態を描画します。 */
    private ItemStack tab(Holder holder,Mode mode,Material material,String name){
        return button(material,(holder.mode==mode?"▶ ":"")+name,List.of(holder.mode==mode?"表示中":"クリックで切り替え"));
    }
    private ItemStack parent(Holder holder,String title,UUID id){
        PetInstance pet=id==null?null:pets.find(holder.accountId,id);
        if(pet==null)return button(Material.NAME_TAG,title,List.of("未選択","一覧から選んでください"));
        ItemStack stack=selectedItem(pet);var meta=stack.getItemMeta();
        List<Component> lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());
        lore.add(Component.text(title+"に選択中",NamedTextColor.GREEN));
        meta.lore(lore);stack.setItemMeta(meta);return stack;
    }
    /** 選択済み個体を入力欄へ移し、同じ欄のクリックで解除できることを表示します。 */
    private ItemStack selectedItem(PetInstance pet){
        ItemStack stack=PetItemView.create(pet,pets,items,factory);var meta=stack.getItemMeta();
        List<Component> lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());
        lore.add(Component.text("クリック: 選択を解除",NamedTextColor.YELLOW));
        meta.lore(lore);stack.setItemMeta(meta);return stack;
    }
    /** APIと同じバッグ集計から必要数・所持数・不足数を表示します。予約量は含む所持数です。 */
    private List<String> materialLore(UUID accountId,JsonArray materials){
        List<String> result=new ArrayList<>();result.add("必要素材（バッグ所持数 / 必要数）");
        var entries=PetBagAccess.entries(inventory.getStateRegistry().get(accountId));
        for(var cost:payments(materials).entrySet()){
            var master=items.findLoadedById(cost.getKey());long owned=PetBagAccess.materialAmount(entries,cost.getKey());
            result.add((master==null?"未登録の素材":ColorCodeUtil.toLegacyText(master.getName(),"未登録の素材"))+"&r &f"+owned+" / "+cost.getValue());
            if(master==null)result.add("&c素材情報を確認できません");
            else result.add(owned<cost.getValue()?"&c不足: "+(cost.getValue()-owned)+"個":"&a必要数を所持しています");
        }
        result.add("&7ホットバー・倉庫の素材は使えません");
        return result;
    }
    /** 重複素材を合算し、選択した操作の素材を個別アイコンでも表示します。 */
    private void showMaterials(Holder holder,JsonArray materials){
        int slot=38;
        for(var cost:payments(materials).entrySet()){
            if(slot>44)break;
            JsonObject row=new JsonObject();row.addProperty("itemId",cost.getKey());row.addProperty("quantity",cost.getValue());
            JsonArray single=new JsonArray();single.add(row);
            var master=items.findLoadedById(cost.getKey());
            ItemStack stack=master==null?new ItemStack(Material.BARRIER):factory.create(master,1);
            var meta=stack.getItemMeta();meta.lore(materialLore(holder.accountId,single).stream().map(line->ColorCodeUtil.toComponent(line,"").decoration(TextDecoration.ITALIC,false)).toList());
            stack.setItemMeta(meta);holder.inventory.setItem(slot++,stack);
        }
    }
    private static ItemStack button(Material material,String name,List<String> lore){
        return GuiItems.create(material,Component.text(name,NamedTextColor.YELLOW),lore.stream().map(line->ColorCodeUtil.toComponent(line,"")).toList());
    }
    /** 管理画面のクリックを処理します。個体や素材の持ち出しは許可しません。 */
    @EventHandler public void onClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder)||!(event.getWhoClicked() instanceof Player player))return;
        event.setCancelled(true);
        if(HotbarShortcutClickSupport.handle(event,player,inventory))return;
        if(event.getRawSlot()<0||event.getRawSlot()>=54)return;
        int slot=event.getRawSlot();UUID id=holder.rows.get(slot);
        if(slot==CLOSE_SLOT){GuiSound.CLOSE.play(player);player.closeInventory();return;}
        if(holder.busy||pets.hasUnresolved(holder.accountId)){
            holder.busy=true;PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9603);GuiSound.DENY.play(player);return;
        }
        AstPlayer owner=AstPlayerCache.get(player);
        if(owner==null||!owner.getAccount().getUuid().equals(holder.accountId)||!AccountModeGuard.isGameplayPlayer(owner)){
            GuiSound.DENY.play(player);return;
        }
        if(holder.adminFacility&&!owner.hasAdminPermission()){
            PlayerMessageService.getInstance().send(player,PlayerMsgId.P_5707);GuiSound.DENY.play(player);player.closeInventory();return;
        }
        if((holder.facility!=null||holder.adminFacility)&&slot>=0&&slot<3){
            holder.mode=Mode.values()[slot];holder.selected=null;holder.page=0;render(holder);GuiSound.SELECT.play(player);return;
        }
        if(id!=null){
            PetInstance pet=pets.find(holder.accountId,id);if(pet==null){GuiSound.DENY.play(player);return;}
            if(holder.mode==Mode.BREED){
                String sex=text(pet.details(),"sex","");
                if(sex.equals("MALE"))holder.male=id;
                else if(sex.equals("FEMALE"))holder.female=id;
                else{GuiSound.DENY.play(player);return;}
            }else holder.selected=id;
            render(holder);GuiSound.SELECT.play(player);return;
        }
        if(slot==36&&holder.mode!=Mode.BREED&&holder.selected!=null){
            holder.selected=null;render(holder);GuiSound.SELECT.play(player);return;
        }
        if(holder.mode==Mode.BREED&&slot==47&&holder.male!=null){
            holder.male=null;render(holder);GuiSound.SELECT.play(player);return;
        }
        if(holder.mode==Mode.BREED&&slot==48&&holder.female!=null){
            holder.female=null;render(holder);GuiSound.SELECT.play(player);return;
        }
        if(slot==PREVIOUS_SLOT||slot==NEXT_SLOT){
            boolean available=slot==PREVIOUS_SLOT?GuiPagination.hasPreviousPage(holder.page)
                :GuiPagination.hasNextPage(holder.page,holder.listCount,PAGE_SIZE);
            if(!available){GuiSound.DENY.play(player);return;}
            holder.page+=slot==PREVIOUS_SLOT?-1:1;render(holder);GuiSound.PAGE.play(player);return;
        }
        PetInstance chosen=holder.selected==null?null:pets.find(holder.accountId,holder.selected);
        JsonObject body=new JsonObject();String suffix;String method="POST";Map<String,Long> payment=new HashMap<>();
        if(slot==50&&holder.mode==Mode.HATCH){
            if(chosen==null||!chosen.isEgg()){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9620);GuiSound.DENY.play(player);return;}
            if(!atFacility(player,holder)){GuiSound.DENY.play(player);return;}
            suffix="/"+chosen.id()+"/hatch";body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));
            payment=payments(array(pets.master().species(chosen.speciesId()),"hatchMaterials"));
        }else if(slot==51&&holder.mode==Mode.BREED){
            if(holder.male==null||holder.female==null){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9609);GuiSound.DENY.play(player);return;}
            if(!atFacility(player,holder)){GuiSound.DENY.play(player);return;}
            suffix="/breed";body.addProperty("maleId",holder.male.toString());body.addProperty("femaleId",holder.female.toString());
            body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));payment=payments(array(pets.master().rules(),"breedMaterials"));
        }else if(slot==52&&holder.mode==Mode.REVIVE){
            if(chosen==null||chosen.isEgg()){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9621);GuiSound.DENY.play(player);return;}
            if(!pets.dead(chosen)){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9613);GuiSound.DENY.play(player);return;}
            suffix="/"+chosen.id()+"/revive";
            if(holder.orbEntry!=null){
                body.addProperty("orbInventoryEntryId",holder.orbEntry.toString());payment.put(PetMaster.itemId(text(pets.master().rules(),"reviveOrbItemId","")),1L);
            }else{
                if(!atFacility(player,holder)){GuiSound.DENY.play(player);return;}body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));
                payment=payments(array(pets.master().rules(),"reviveMaterials"));
            }
        }else{GuiSound.DENY.play(player);return;}
        if(payment.isEmpty()||payment.keySet().stream().anyMatch(itemId->items.findLoadedById(itemId)==null)){
            PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9608);GuiSound.DENY.play(player);return;
        }
        holder.busy=true;
        pets.mutate(owner,UUID.randomUUID(),suffix,method,body,payment).whenComplete((result,failure)->{
            if(!plugin.isEnabled())return;
            Bukkit.getScheduler().runTask(plugin,()->{
                holder.busy=pets.hasUnresolved(holder.accountId);
                if(AstPlayerCache.get(player)!=owner)return;
                PlayerMessageService.getInstance().send(player,PetOperationFeedback.message(result,failure,pets.hasUnresolved(holder.accountId)));
                if(failure==null&&!pets.rejected(result)){
                    holder.selected=null;holder.male=null;holder.female=null;GuiSound.SUCCESS.play(player);
                }else GuiSound.DENY.play(player);
                inventory.applyInventoriesToGuiOnJoin(owner);
                if(player.getOpenInventory().getTopInventory()==holder.inventory)render(holder);
            });
        });
    }
    private boolean atFacility(Player player,Holder holder){
        if(holder.adminFacility){
            AstPlayer owner=AstPlayerCache.get(player);
            return owner!=null&&AccountModeGuard.isGameplayPlayer(owner)&&owner.hasAdminPermission();
        }
        MobInstance npc=holder.facility==null?null:mobs.getInstance(holder.facility);
        if(npc!=null&&npc.template().id().equals(text(pets.master().rules(),"facilityId",""))&&npc.currentLocation().getWorld()==player.getWorld()
            &&npc.currentLocation().distanceSquared(player.getLocation())<=36)return true;
        PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9602);return false;
    }
    private static Map<String,Long> payments(JsonArray rows){
        Map<String,Long> result=new HashMap<>();for(JsonElement row:rows){JsonObject item=row.getAsJsonObject();result.merge(PetMaster.itemId(text(item,"itemId","")),(long)number(item,"quantity",0),Math::addExact);}return result;
    }
    /** ドラッグで個体表示を持ち出せないようにします。 */
    @EventHandler public void onDrag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    private enum Mode { HATCH, BREED, REVIVE }
    private static final class Holder implements HotbarShortcutGuiHolder{
        final UUID accountId,facility,orbEntry;final Map<Integer,UUID> rows=new HashMap<>();
        final boolean adminFacility;
        Inventory inventory;UUID selected,male,female;int page,listCount;boolean busy;Mode mode;
        Holder(UUID accountId,UUID facility,UUID orbEntry,boolean adminFacility){
            this.accountId=accountId;this.facility=facility;this.orbEntry=orbEntry;this.adminFacility=adminFacility;
            mode=orbEntry!=null?Mode.REVIVE:Mode.HATCH;
        }
        @Override public @NotNull Inventory getInventory(){return inventory;}
        @Override public int getBackSlot(){return CLOSE_SLOT;}
        @Override public boolean isAlwaysCloseNavigation(){return true;}
    }
}
