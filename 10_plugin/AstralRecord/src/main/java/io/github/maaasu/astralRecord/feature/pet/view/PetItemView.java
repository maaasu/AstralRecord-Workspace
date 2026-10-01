package io.github.maaasu.astralRecord.feature.pet.view;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.service.PetService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.*;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import java.util.*;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** 卵と孵化済み個体の表示を分離します。卵から個体詳細を参照しません。 */
public final class PetItemView {
    public static final NamespacedKey INSTANCE_KEY=new NamespacedKey("astralrecord","pet_instance_id");
    private static final Map<String,String> STAT_NAMES=Map.of("VITALITY","生命力","POWER","攻撃力","DEFENSE","防御力","EVASION","回避力","SUPPORT","支援力");
    private PetItemView() { }
    /**
     * マスターの共通アイテム表示に、所有個体の公開情報だけを追加します。
     * @param pet 所有確認済みのペット個体
     * @param service 個体キャッシュサービス
     * @param items 公開済みアイテムマスター
     * @param factory 共通アイテム描画ファクトリ
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static ItemStack create(PetInstance pet,PetService service,ItemService items,ItemStackFactory factory) {
        ItemModel item=items.findLoadedById(pet.itemId());
        ItemStack stack=item==null?new ItemStack(pet.isEgg()?Material.EGG:Material.SADDLE):factory.create(item,1);
        var meta=stack.getItemMeta();
        List<Component> lore=meta.lore()==null?new ArrayList<>():new ArrayList<>(meta.lore());
        meta.getPersistentDataContainer().set(INSTANCE_KEY,PersistentDataType.STRING,pet.id().toString());
        lore.add(Component.empty());
        if(pet.isEgg()) {
            lore.add(line("孵化するまで個体の能力は分かりません",NamedTextColor.GRAY));
        } else {
            JsonObject details=pet.details();
            meta.displayName(ColorCodeUtil.toComponent(pet.name(),"ペット").decoration(TextDecoration.ITALIC,false));
            lore.add(line("性別: "+(text(details,"sex","").equals("MALE")?"オス":"メス"),NamedTextColor.GRAY));
            lore.add(line("成長度: "+(int)number(details,"level",1),NamedTextColor.AQUA));
            lore.add(line("サイズ: "+String.format(Locale.ROOT,"%.2f",number(details,"size",1)),NamedTextColor.GRAY));
            lore.add(line(service.dead(pet)?"戦闘不能 — 施設か復活オーブが必要":"HP: "+Math.round(service.healthRatio(pet)*100)+"%",service.dead(pet)?NamedTextColor.RED:NamedTextColor.GREEN));
            for(var row:object(details,"stats").entrySet()) {
                JsonObject stat=row.getValue().getAsJsonObject();
                lore.add(line(STAT_NAMES.getOrDefault(row.getKey(),"能力")+" T"+(int)number(stat,"tier",0)+": "+String.format(Locale.ROOT,"%.1f",number(stat,"currentValue",0)),flag(stat,"potential")?NamedTextColor.GOLD:NamedTextColor.WHITE));
            }
            lore.add(Component.empty());
            int learned=0;
            for(JsonElement row:array(details,"skills")) {
                JsonObject owned=row.getAsJsonObject();JsonObject skill=service.master().skill(pet.speciesId(),text(owned,"id",""));
                lore.add(line("スキル "+(int)number(owned,"slotIndex",++learned)+": "+text(skill,"name","未登録のスキル")+" T"+(int)number(owned,"tier",0),NamedTextColor.LIGHT_PURPLE));
            }
            for(int slot=array(details,"skills").size()+1;slot<=3;slot++)lore.add(line("スキル "+slot+": 未解放",NamedTextColor.DARK_GRAY));
        }
        lore.add(line(pet.tradeAllowed()?"野生由来・トレード可能":"配合由来・トレード不可",pet.tradeAllowed()?NamedTextColor.GRAY:NamedTextColor.RED));
        meta.lore(lore);stack.setItemMeta(meta);return stack;
    }
    /**
     * 内部個体IDをPDCから解決します。表示文字列からは推測しません。
     * @param stack 識別するItemStack
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static UUID instanceId(ItemStack stack) {
        if(stack==null||!stack.hasItemMeta())return null;
        String value=stack.getItemMeta().getPersistentDataContainer().get(INSTANCE_KEY,PersistentDataType.STRING);
        try{return value==null?null:UUID.fromString(value);}catch(IllegalArgumentException ignored){return null;}
    }
    private static Component line(String text,NamedTextColor color){return Component.text(text,color).decoration(TextDecoration.ITALIC,false);}
}
