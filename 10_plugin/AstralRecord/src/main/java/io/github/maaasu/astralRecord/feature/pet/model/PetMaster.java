package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.*;
import java.util.*;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** 一つのマスターに定義した種類・成長・配合ルールの公開スナップショットです。 */
public final class PetMaster {
    private final JsonObject data;
    /**
     * 読み込み済みJSONを複製します。
     * @param data APIが返した読み込み済みJSON
     */
    public PetMaster(JsonObject data) { this.data=data.deepCopy(); }
    /**
     * 共通ルールの複製を返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject rules() { return object(data,"rules").deepCopy(); }
    /**
     * 種類定義の複製を返します。未定義なら空です。
     * @param id 対象種類または個体ID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject species(String id) {
        for (JsonElement element:array(data,"species")) {
            JsonObject species=element.getAsJsonObject();
            if (text(species,"id","").equals(id)) return species.deepCopy();
        }
        return new JsonObject();
    }
    /**
     * 卵抽選候補の種類ID一覧を返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public List<String> speciesIds() {
        List<String> ids=new ArrayList<>();
        for(JsonElement element:array(data,"species")) ids.add(text(element.getAsJsonObject(),"id",""));
        return List.copyOf(ids);
    }
    /**
     * 種類内のスキル定義を返します。未定義なら空です。
     * @param speciesId ペットの種類ID
     * @param skillId スキルID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject skill(String speciesId,String skillId) {
        for(JsonElement element:array(species(speciesId),"skills")) {
            JsonObject skill=element.getAsJsonObject();
            if(text(skill,"id","").equals(skillId)) return skill.deepCopy();
        }
        return new JsonObject();
    }
    /**
     * 内部参照接頭辞を除いたアイテムIDを返します。
     * @param reference マスターのアイテム参照
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static String itemId(String reference) { return reference.startsWith("item:")?reference.substring(5):reference; }
}
