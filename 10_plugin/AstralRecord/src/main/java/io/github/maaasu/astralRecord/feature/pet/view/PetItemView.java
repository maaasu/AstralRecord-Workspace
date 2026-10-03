package io.github.maaasu.astralRecord.feature.pet.view;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.service.PetService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** 卵と孵化済み個体の表示を分離します。卵から個体詳細を参照しません。 */
public final class PetItemView {
    public static final NamespacedKey INSTANCE_KEY=new NamespacedKey("astralrecord","pet_instance_id");
    private static final Map<String,String> STAT_NAMES=Map.of("VITALITY","生命力","POWER","攻撃力","DEFENSE","防御力","EVASION","回避力","SUPPORT","支援力");
    private static final Map<String,StatusType> STAT_COLORS=Map.of("VITALITY",StatusType.VITALITY,"POWER",StatusType.ATTACK,
        "DEFENSE",StatusType.DEFENSE,"EVASION",StatusType.EVASION,"SUPPORT",StatusType.SUPPORT_POWER);
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
        int footer=footerStart(lore);
        List<Component> detailsLore=new ArrayList<>();
        meta.getPersistentDataContainer().set(INSTANCE_KEY,PersistentDataType.STRING,pet.id().toString());
        if(pet.isEgg()) {
            detailsLore.add(line("❖ 魔法の卵",NamedTextColor.LIGHT_PURPLE));
            detailsLore.add(line("孵化するまで個体の能力は分かりません",NamedTextColor.GRAY));
        } else {
            JsonObject details=pet.details();
            meta.displayName(ColorCodeUtil.toComponent(pet.name(),"ペット").decoration(TextDecoration.ITALIC,false));
            detailsLore.add(line("❖ ペット情報",NamedTextColor.AQUA));
            detailsLore.add(line(" ▸ 性別: "+(text(details,"sex","").equals("MALE")?"オス":"メス"),NamedTextColor.GRAY));
            detailsLore.add(line(" ▸ 成長度: "+(int)number(details,"level",1),NamedTextColor.AQUA));
            detailsLore.add(line(" ▸ サイズ: "+String.format(Locale.ROOT,"%.2f",number(details,"size",1)),NamedTextColor.GRAY));
            detailsLore.add(line(service.dead(pet)?" ▸ 戦闘不能 — 施設か復活オーブが必要":" ▸ HP: "+Math.round(service.healthRatio(pet)*100)+"%",service.dead(pet)?NamedTextColor.RED:NamedTextColor.GREEN));
            detailsLore.add(Component.empty());
            detailsLore.add(line("❖ ステータス",NamedTextColor.GREEN));
            for(var row:object(details,"stats").entrySet()) {
                JsonObject stat=row.getValue().getAsJsonObject();
                StatusType color=STAT_COLORS.get(row.getKey());
                Component name=Component.text(" ▸ "+STAT_NAMES.getOrDefault(row.getKey(),"能力"),color==null?NamedTextColor.GRAY:color.namedColor());
                Component value=Component.text(" : ",NamedTextColor.DARK_GRAY)
                    .append(Component.text(String.format(Locale.ROOT,"%.1f",number(stat,"currentValue",0)),
                        flag(stat,"potential")?NamedTextColor.GOLD:NamedTextColor.WHITE,TextDecoration.BOLD))
                    .append(Component.text(" (T"+(int)number(stat,"tier",0)+")",NamedTextColor.GRAY));
                if(flag(stat,"potential"))value=value.append(Component.text(" ★",NamedTextColor.GOLD));
                detailsLore.add(name.append(value).decoration(TextDecoration.ITALIC,false));
            }
            detailsLore.add(Component.empty());
            detailsLore.add(line("❖ スキル",NamedTextColor.LIGHT_PURPLE));
            int learned=0;
            for(JsonElement row:array(details,"skills")) {
                JsonObject owned=row.getAsJsonObject();JsonObject skill=service.master().skill(pet.speciesId(),text(owned,"id",""));
                int displayedSlot=(int)number(owned,"slotIndex",learned++)+1;
                detailsLore.add(line(" ▸ スキル "+displayedSlot+": "+text(skill,"name","未登録のスキル"),NamedTextColor.LIGHT_PURPLE)
                    .append(line(" (T"+(int)number(owned,"tier",0)+")",NamedTextColor.GRAY)));
            }
            for(int slot=array(details,"skills").size()+1;slot<=3;slot++)detailsLore.add(line(" ▸ スキル "+slot+": 未解放",NamedTextColor.DARK_GRAY));
        }
        detailsLore.add(Component.empty());
        lore.addAll(footer,detailsLore);
        if(!pet.tradeAllowed()&&(item==null||!item.getUnTradeable()))
            lore.add(footer+detailsLore.size()+1,line("✖ 取引不可",NamedTextColor.RED));
        meta.lore(lore);stack.setItemMeta(meta);return stack;
    }
    /** 共通アイテムの末尾区切りの直前を返し、個体情報をフッターより上に配置します。 */
    private static int footerStart(List<Component> lore) {
        for(int index=lore.size()-1;index>=0;index--)
            if(PlainTextComponentSerializer.plainText().serialize(lore.get(index)).contains(DisplaySeparators.SECTION))return index;
        lore.add(line(DisplaySeparators.SECTION,NamedTextColor.DARK_GRAY));
        return lore.size()-1;
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
