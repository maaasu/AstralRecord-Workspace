package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.JsonObject;
import java.util.UUID;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/** APIが確定したペット個体です。卵ではdetailsを保持せず、個体情報を公開しません。 */
public final class PetInstance {
    private final JsonObject data;
    /**
     * 応答を複製し、必須個体IDと卵の非公開境界を検証します。
     * @param data APIが返した読み込み済みJSON
     */
    public PetInstance(JsonObject data) {
        this.data = data.deepCopy();
        id(); accountId();
        if (isEgg()) this.data.remove("details");
    }
    /**
     * 個体IDを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public UUID id() { return UUID.fromString(text(data,"instanceId","")); }
    /**
     * 所有アカウントIDを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public UUID accountId() { return UUID.fromString(text(data,"accountId","")); }
    /**
     * 種類IDを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public String speciesId() { return text(data,"speciesId",""); }
    /**
     * アイテムIDを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public String itemId() { return text(data,"itemId",""); }
    /**
     * 孵化前かを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean isEgg() { return flag(data,"isEgg"); }
    /**
     * 個体ごとの譲渡許可を返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean tradeAllowed() { return flag(data,"tradeAllowed"); }
    /**
     * 楽観的排他用の版を返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public long version() { return (long)number(data,"version",0); }
    /**
     * 孵化後だけ詳細の複製を返します。卵の場合は空です。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject details() { return isEgg() ? new JsonObject() : object(data,"details").deepCopy(); }
    /**
     * ペット名を返します。卵から名前を解決しません。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public String name() { return text(details(),"name","ペット"); }
    /**
     * 戦闘不能かを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean dead() { return !isEgg() && flag(details(),"isDead"); }
}
