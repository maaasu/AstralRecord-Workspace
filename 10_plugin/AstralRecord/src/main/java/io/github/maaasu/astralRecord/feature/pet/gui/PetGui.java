package io.github.maaasu.astralRecord.feature.pet.gui;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.service.PetService;
import io.github.maaasu.astralRecord.feature.pet.view.PetItemView;
import io.github.maaasu.astralRecord.feature.player.*;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.*;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.*;
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
import java.util.concurrent.CompletableFuture;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** 所有個体の確認・装備保存と、施設での孵化・配合・復活を提供します。 */
public final class PetGui implements Listener {
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
     * 通常のペット管理画面を開きます。素材を伴う施設操作は許可しません。
     * @param player 操作するプレイヤー
     */
    public void open(Player player){open(player,null,null);}
    /**
     * 実際に対話したNPCの個体を保持して施設画面を開きます。
     * @param player 操作するプレイヤー
     * @param npc 実際に対話した施設NPC
     */
    public void openFromNpc(Player player,MobInstance npc){open(player,npc.instanceId(),null);}
    /**
     * BAGの復活オーブを起点に、戦闘不能個体の選択画面を開きます。
     * @param player 操作するプレイヤー
     * @param entryId 消費元のオーブentry ID
     */
    public void openReviveOrb(Player player,UUID entryId){open(player,null,entryId);}
    private void open(Player player,UUID facility,UUID orbEntry){
        AstPlayer owner=AstPlayerCache.get(player);
        if(owner==null||!AccountModeGuard.isGameplayPlayer(owner))return;
        Holder holder=new Holder(owner.getAccount().getUuid(),facility,orbEntry);
        holder.selected=pets.equippedId(holder.accountId);
        holder.inventory=Bukkit.createInventory(holder,54,Component.text(facility==null?"ペット管理":"ペット施設",NamedTextColor.DARK_GREEN));
        render(holder);GuiOpenSupport.open(player,holder.inventory);
    }
    /**
     * API確定後に現在の管理画面だけを更新します。メインスレッドで呼びます。
     * @param player 操作するプレイヤー
     */
    public void refreshCurrent(Player player){
        if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder){holder.busy=false;render(holder);}
    }
    private void render(Holder holder){
        Inventory view=holder.inventory;view.clear();holder.rows.clear();
        var inventoryState=inventory.getStateRegistry().get(holder.accountId);
        Set<UUID> owned=new HashSet<>();
        if(inventoryState!=null){
            synchronized(inventoryState){
                for(var bag:inventoryState.snapshotInventories()){
                    if(bag.isDeleted())continue;
                    for(var entry:inventoryState.snapshotEntries(bag.getInventoryId())){
                        if(!entry.isDeleted()&&entry.getInstanceId()!=null&&
                            (io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType.PET.getCode().equalsIgnoreCase(entry.getInstanceType())
                            ||io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType.PET_EGG.getCode().equalsIgnoreCase(entry.getInstanceType())))owned.add(entry.getInstanceId());
                    }
                }
            }
        }
        List<PetInstance> all=pets.instances(holder.accountId).stream().filter(pet->owned.contains(pet.id())).toList();
        if(owned.stream().anyMatch(id->pets.find(holder.accountId,id)==null))pets.ensureLoaded(holder.accountId);
        if(holder.selected!=null&&!owned.contains(holder.selected))holder.selected=null;
        if(holder.male!=null&&!owned.contains(holder.male))holder.male=null;
        if(holder.female!=null&&!owned.contains(holder.female))holder.female=null;
        if(holder.orbEntry!=null)all=all.stream().filter(pet->!pet.isEgg()&&pets.dead(pet)).toList();
        holder.page=Math.max(0,Math.min(holder.page,Math.max(0,(all.size()-1)/45)));
        for(int index=holder.page*45;index<Math.min(all.size(),(holder.page+1)*45);index++){
            PetInstance pet=all.get(index);int slot=index%45;holder.rows.put(slot,pet.id());
            ItemStack stack=PetItemView.create(pet,pets,items,factory);var meta=stack.getItemMeta();
            List<Component> lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());
            lore.add(Component.text(pet.id().equals(holder.selected)?"選択中":"左クリック: 選択",NamedTextColor.GREEN));
            if(holder.facility!=null&&!pet.isEgg())lore.add(Component.text("右クリック: 配合する親に設定",NamedTextColor.GRAY));
            meta.lore(lore);stack.setItemMeta(meta);view.setItem(slot,stack);
        }
        view.setItem(45,button(Material.ARROW,"前のページ",List.of()));
        view.setItem(46,button(Material.ARROW,"次のページ",List.of()));
        if(holder.facility!=null){
            view.setItem(47,parent(holder,"オスの親",holder.male));view.setItem(48,parent(holder,"メスの親",holder.female));
            PetInstance chosen=holder.selected==null?null:pets.find(holder.accountId,holder.selected);
            if(chosen!=null&&chosen.isEgg())view.setItem(50,button(Material.EGG,"選択した卵を孵化する",materialLore(array(pets.master().species(chosen.speciesId()),"hatchMaterials"))));
            view.setItem(51,button(Material.WHEAT,"配合して卵を作る",materialLore(array(pets.master().rules(),"breedMaterials"))));
        }
        view.setItem(49,button(Material.BARRIER,"閉じる",List.of()));
        view.setItem(52,button(Material.SADDLE,"選択したペットを装備して保存",List.of("卵は装備できません","Shift+クリック: 装備を解除して保存")));
        view.setItem(53,button(Material.GHAST_TEAR,"選択したペットを復活する",holder.orbEntry==null?materialLore(array(pets.master().rules(),"reviveMaterials")):List.of("復活オーブを1個消費")));
    }
    private ItemStack parent(Holder holder,String title,UUID id){
        PetInstance pet=id==null?null:pets.find(holder.accountId,id);
        return button(Material.NAME_TAG,title,List.of(pet==null?"未選択":pet.name()));
    }
    private List<String> materialLore(JsonArray materials){
        List<String> result=new ArrayList<>();
        for(JsonElement row:materials){JsonObject item=row.getAsJsonObject();var master=items.findLoadedById(PetMaster.itemId(text(item,"itemId","")));
            result.add((master==null?"未登録の素材":ColorCodeUtil.toLegacyText(master.getName(),"未登録の素材"))+" × "+(long)number(item,"quantity",0));}
        return result;
    }
    private static ItemStack button(Material material,String name,List<String> lore){
        ItemStack stack=new ItemStack(material);var meta=stack.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC,false));
        meta.lore(lore.stream().map(line->ColorCodeUtil.toComponent(line,"").decoration(TextDecoration.ITALIC,false)).toList());stack.setItemMeta(meta);return stack;
    }
    /** 管理画面のクリックを処理します。個体や素材の持ち出しは許可しません。 */
    @EventHandler public void onClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder)||!(event.getWhoClicked() instanceof Player player))return;
        event.setCancelled(true);
        if(HotbarShortcutClickSupport.handle(event,player,inventory))return;
        if(event.getRawSlot()<0||event.getRawSlot()>=54)return;
        if(holder.busy){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9603);return;}
        AstPlayer owner=AstPlayerCache.get(player);if(owner==null||!owner.getAccount().getUuid().equals(holder.accountId))return;
        int slot=event.getRawSlot();UUID id=holder.rows.get(slot);
        if(id!=null){
            PetInstance pet=pets.find(holder.accountId,id);if(pet==null)return;
            if(event.isRightClick()&&holder.facility!=null&&!pet.isEgg()){
                if(text(pet.details(),"sex","").equals("MALE"))holder.male=id;else holder.female=id;
            }else holder.selected=id;
            render(holder);return;
        }
        if(slot==45||slot==46){holder.page+=slot==45?-1:1;render(holder);return;}
        if(slot==49){player.closeInventory();return;}
        PetInstance chosen=holder.selected==null?null:pets.find(holder.accountId,holder.selected);
        JsonObject body=new JsonObject();String suffix;String method="POST";Map<String,Long> payment=new HashMap<>();
        if(slot==52){
            if(!event.isShiftClick()&&(chosen==null||chosen.isEgg()))return;
            if(event.isShiftClick())body.add("petId",JsonNull.INSTANCE);else body.addProperty("petId",chosen.id().toString());
            suffix="/equipped";method="PUT";
        }else if(slot==50&&chosen!=null&&chosen.isEgg()){
            if(!atFacility(player,holder))return;
            suffix="/"+chosen.id()+"/hatch";body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));
            payment=payments(array(pets.master().species(chosen.speciesId()),"hatchMaterials"));
        }else if(slot==51&&holder.male!=null&&holder.female!=null){
            if(!atFacility(player,holder))return;
            suffix="/breed";body.addProperty("maleId",holder.male.toString());body.addProperty("femaleId",holder.female.toString());
            body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));payment=payments(array(pets.master().rules(),"breedMaterials"));
        }else if(slot==53&&chosen!=null&&!chosen.isEgg()&&pets.dead(chosen)){
            suffix="/"+chosen.id()+"/revive";
            if(holder.orbEntry!=null){
                body.addProperty("orbInventoryEntryId",holder.orbEntry.toString());payment.put(PetMaster.itemId(text(pets.master().rules(),"reviveOrbItemId","")),1L);
            }else{
                if(!atFacility(player,holder))return;body.addProperty("facilityId",text(pets.master().rules(),"facilityId",""));
                payment=payments(array(pets.master().rules(),"reviveMaterials"));
            }
        }else return;
        holder.busy=true;
        pets.mutate(owner,UUID.randomUUID(),suffix,method,body,payment).whenComplete((result,failure)->{
            if(!plugin.isEnabled())return;
            Bukkit.getScheduler().runTask(plugin,()->{
                holder.busy=false;
                if(AstPlayerCache.get(player)!=owner)return;
                PlayerMessageService.getInstance().send(player,failure==null&&!pets.rejected(result)?PlayerMsgId.P_9601:
                    pets.hasUnresolved(holder.accountId)?PlayerMsgId.P_9603:PlayerMsgId.P_9600);
                inventory.applyInventoriesToGuiOnJoin(owner);
                if(player.getOpenInventory().getTopInventory()==holder.inventory)render(holder);
            });
        });
    }
    private boolean atFacility(Player player,Holder holder){
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
    private static final class Holder implements HotbarShortcutGuiHolder{
        final UUID accountId,facility,orbEntry;final Map<Integer,UUID> rows=new HashMap<>();
        Inventory inventory;UUID selected,male,female;int page;boolean busy;
        Holder(UUID accountId,UUID facility,UUID orbEntry){this.accountId=accountId;this.facility=facility;this.orbEntry=orbEntry;}
        @Override public @NotNull Inventory getInventory(){return inventory;}
        @Override public int getBackSlot(){return 49;}
        @Override public boolean isAlwaysCloseNavigation(){return true;}
    }
}
