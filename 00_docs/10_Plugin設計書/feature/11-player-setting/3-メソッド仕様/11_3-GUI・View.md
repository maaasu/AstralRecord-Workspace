# 11_3-GUI・View

## 1. 設定 GUI 表示

クラス名: `PlayerSettingGui`
物理名: `open`, `refresh`

54 slot の「プレイヤー設定」を開き、snapshot または draft 値から icon を描画する。設定項目は左右の枠を避け、3 行目の `20〜24`、4 行目の `29〜33`、5 行目の `38〜41` に配置する。slot `13` の共通案内で、戻る／閉じる時に保存・反映することを示す。保存済み値と異なるdraftを持つ項目の状態行は、保存済み値と変更予定値を区別する。同じ値へ戻したdraftは変更予定扱いにしない。クリックでは保存・反映せず、従来どおりInventoryCloseで保存する。

| slot | key / 操作 |
|---:|---|
| 13 | 保存・反映タイミングの共通案内 |
| 20 | `DAMAGE_LOG_DISPLAY` |
| 21 | `DAMAGE_LOG_MESSAGE` |
| 22 | `PARTICLE_DENSITY` |
| 23 | `PERFORMANCE_INFO_DISPLAY` |
| 24 | `DROP_LOG_DISPLAY` |
| 29 | `AUTO_SAVE_MESSAGE` |
| 30 | `BUFF_SIDEBAR_DISPLAY` |
| 31 | `ARMOR_DISPLAY` |
| 32 | `ACTION_RING_HOLD_SELECT`（`TRIDENT` icon） |
| 33 | `SKILL_TREE_COMPACT_DISPLAY`（`BOOK` icon） |
| 38 | `NIGHT_VISION`（`SPYGLASS` icon） |
| 39 | `RANDOM_STATUS_RANGE_DISPLAY`（`COMPARATOR` icon） |
| 40 | `COMBAT_DPS_DISPLAY`（`CLOCK` icon） |
| 41 | `GUIDE_REMINDER`（定期ガイド通知、`KNOWLEDGE_BOOK` icon） |
| 49 | 前画面へ戻る |
| 53 | icon を置かない管理者用 super mode secret slot |

`TEMP_DROP_DISPLAY` と `TEMP_BLOCK_DISPLAY` は GUI に表示せず、コマンドから変更する。

`ACTION_RING_HOLD_SELECT` は既定 `false` で、`true` のときだけ右クリック長押し選択を使う。保存後は inventory を再送し、選択中 hotbar 主武器だけをクライアント専用トライデント表示へ直ちに切り替える。ホットバースロット切り替え時も選択中 slot の表示を再同期する。

`NIGHT_VISION` は既定 `false` で、`true` のときだけ無期限・粒子なしの暗視効果を付与する。`false` へ変更した場合は暗視効果を除去する。

`GUIDE_REMINDER` は既定 `true`。GUIを閉じてOFFを保存すると次回以降の定期ガイド通知を止める。`/setting guide_reminder <on|off>` からも変更できる。

`RANDOM_STATUS_RANGE_DISPLAY` は既定 `false`。GUI の draft を閉じて反映した後、inventory を再送して装備ステータス直下の灰色の乱数範囲行を切り替える。`/setting random_status_range_display <on|off>` からも変更でき、同様に inventory を再送する。

## 2. GUI 識別・slot 解決

クラス名: `PlayerSettingGui`
物理名: `isInventory`, `getKeyAtSlot`, `getUserId`

専用 holder で GUI と user UUID を識別し、表示対象 slot だけを `PlayerSettingKey` へ変換する。secret slot は通常 key 解決に含めない。
