package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;

/**
 * 報酬の TextDisplay に、本文の行数・幅・表示時間に合わせた統合版の枠を表示します。
 *
 * @param lines 枠の高さを決める本文の行数
 * @param rare レアドロップの装飾を有効にする値
 * @param duration 枠のアニメーションを終えるtick
 * @param width 枠の幅
 */
record CombatRewardPlaqueEntity(
    GeyserIntEntityProperty lines, GeyserIntEntityProperty rare, GeyserIntEntityProperty duration,
    GeyserIntEntityProperty width
) {
    private static final long TAG = 0xa57ac2e570000002L;
    private static final int MIN_LINES = 5;
    private static final int MAX_LINES = 10;
    private static final int LINE_MASK = 0xf;
    private static final int RARE_FLAG = 0x10;
    private static final int DURATION_SHIFT = 5;
    private static final int DEFAULT_DURATION_TICKS = 120;
    private static final int MAX_DURATION_TICKS = 65535;
    private static final int WIDTH_SHIFT = 21;
    private static final int BASE_WIDTH = 192;
    private static final int WIDTH_UNIT = 8;
    private static final int MAX_WIDTH = BASE_WIDTH + ((1 << (Integer.SIZE - WIDTH_SHIFT)) - 1) * WIDTH_UNIT;
    private static final Identifier IDENTIFIER = Identifier.of("astralrecord", "combat_reward_plaque");
    static final CustomEntityDefinition DEFINITION = CustomEntityDefinition.of(IDENTIFIER);

    /**
     * @param event Geyserのエンティティプロパティ登録イベント
     * @return 描画時に使うプロパティの定義
     */
    static CombatRewardPlaqueEntity registerProperties(GeyserDefineEntityPropertiesEvent event) {
        return new CombatRewardPlaqueEntity(
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_lines"),
                MIN_LINES, MAX_LINES, MIN_LINES),
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_rare"), 0, 1, 0),
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_duration"),
                1, MAX_DURATION_TICKS, DEFAULT_DURATION_TICKS),
            event.registerIntegerProperty(IDENTIFIER, Identifier.of("astralrecord", "reward_width"),
                BASE_WIDTH, MAX_WIDTH, BASE_WIDTH)
        );
    }

    /**
     * @param uuid 報酬TextDisplayの識別子。旧形式の幅のビットは0
     * @param entityId Java側のエンティティID
     * @return 正規の識別子から解決した描画設定。対象外または不正な識別子はnull
     */
    static Layout decode(UUID uuid, int entityId) {
        if (uuid == null || uuid.getMostSignificantBits() != TAG) return null;
        long payload = uuid.getLeastSignificantBits();
        long flags = payload >>> 32;
        int lines = (int) flags & LINE_MASK;
        int encodedDuration = (int) (flags >>> DURATION_SHIFT) & MAX_DURATION_TICKS;
        if ((int) payload != entityId || lines < MIN_LINES || lines > MAX_LINES) {
            return null;
        }
        int durationTicks = encodedDuration == 0 ? DEFAULT_DURATION_TICKS : encodedDuration;
        int widthPixels = BASE_WIDTH + (int) (flags >>> WIDTH_SHIFT) * WIDTH_UNIT;
        return new Layout(lines, (flags & RARE_FLAG) != 0, durationTicks, widthPixels);
    }

    /**
     * @param entity Geyserが生成する表示エンティティ
     * @param layout UUIDから解決した描画設定
     * @param packVersion 接続時に登録された報酬パックの互換版
     */
    void initialize(GeyserEntity entity, Layout layout, int packVersion) {
        // 元の TextDisplay の名前欄と、Geyser が計算する行数別の位置を利用します。
        entity.override(GeyserEntityDataTypes.SCALE, 1f);
        entity.override(GeyserEntityDataTypes.WIDTH, 0f);
        entity.override(GeyserEntityDataTypes.HEIGHT, 0f);
        entity.updatePropertiesBatched(batch -> {
            batch.update(lines, layout.lines());
            batch.update(rare, layout.rare() ? 1 : 0);
            batch.update(duration, packVersion >= 2 ? layout.durationTicks() : DEFAULT_DURATION_TICKS);
            batch.update(width, packVersion >= 3 ? layout.widthPixels() : BASE_WIDTH);
        }, false);
    }

    record Layout(int lines, boolean rare, int durationTicks, int widthPixels) {}
}
