package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PetInstancePrivacyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 個体と卵の不変条件
     * 検証契約: 卵の上流応答に詳細が混入してもPlugin変換境界では個体詳細を公開しない。
     */
    @Test void neverExposesEggDetailsEvenWhenUpstreamIncludesThem() {
        JsonObject response=fixture(true);
        JsonObject details=new JsonObject();details.addProperty("hiddenValue",123);response.add("details",details);
        PetInstance pet=new PetInstance(response);
        assertTrue(pet.details().isEmpty());
        response.getAsJsonObject("details").addProperty("anotherSecret",456);
        assertTrue(pet.details().isEmpty());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 個体と卵の不変条件
     * 検証契約: 孵化済み詳細は入力と返却JSONから独立して保持し、表示側の変更を保存値へ反映しない。
     */
    @Test void isolatesHatchedDetailsFromInputAndDisplayMutation() {
        JsonObject response=fixture(false);JsonObject details=new JsonObject();details.addProperty("name","before");response.add("details",details);
        PetInstance pet=new PetInstance(response);
        details.addProperty("name","input changed");pet.details().addProperty("name","display changed");
        assertEquals("before",pet.name());
    }
    private static JsonObject fixture(boolean egg){
        JsonObject response=new JsonObject();response.addProperty("instanceId",UUID.randomUUID().toString());
        response.addProperty("accountId",UUID.randomUUID().toString());response.addProperty("isEgg",egg);return response;
    }
}
