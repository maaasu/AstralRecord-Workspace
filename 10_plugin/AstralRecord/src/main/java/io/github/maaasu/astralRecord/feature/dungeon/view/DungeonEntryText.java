package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import org.jetbrains.annotations.NotNull;

/** ダンジョン受付地点の TextDisplay に表示する案内文を構築します。 */
public final class DungeonEntryText {
    /** インスタンス化を禁止します。 */
    private DungeonEntryText() {
    }

    /**
     * ダンジョン名、推奨レベル、挑戦開始案内の受付表示を構築します。
     *
     * @param definition 表示対象のダンジョン定義
     * @return プレイヤー向けの受付 TextDisplay 文
     */
    public static @NotNull String render(@NotNull DungeonDefinition definition) {
        return PlayerMsgResource.format(
                PlayerMsgId.P_7035.getId(),
                definition.displayName(),
                definition.recommendedLevel()
        );
    }
}
