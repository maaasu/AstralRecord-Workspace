# 11_3-GUI・View

## 1. 設定 GUI 表示

クラス名: `PlayerSettingGui`
物理名: `open`, `refresh`

54 slot の「プレイヤー設定」を開き、snapshot または draft 値から icon を描画する。設定項目は左右の枠を避け、3 行目の `20〜24` と 4 行目の `29〜32` に配置する。

| slot | key / 操作 |
|---:|---|
| 20 | `DAMAGE_LOG_DISPLAY` |
| 21 | `DAMAGE_LOG_MESSAGE` |
| 22 | `PARTICLE_DENSITY` |
| 23 | `PERFORMANCE_INFO_DISPLAY` |
| 24 | `DROP_LOG_DISPLAY` |
| 29 | `AUTO_SAVE_MESSAGE` |
| 30 | `BUFF_SIDEBAR_DISPLAY` |
| 31 | `ARMOR_DISPLAY` |
| 32 | `ACTION_RING_HOLD_SELECT`（`TRIDENT` icon） |
| 49 | 前画面へ戻る |
| 53 | icon を置かない管理者用 super mode secret slot |

`TEMP_DROP_DISPLAY` と `TEMP_BLOCK_DISPLAY` は GUI に表示せず、コマンドから変更する。

`ACTION_RING_HOLD_SELECT` は既定 `false` で、`true` のときだけ右クリック長押し選択を使う。保存後は inventory を再送し、選択中 hotbar 主武器だけをクライアント専用トライデント表示へ直ちに切り替える。ホットバースロット切り替え時も選択中 slot の表示を再同期する。

## 2. GUI 識別・slot 解決

クラス名: `PlayerSettingGui`
物理名: `isInventory`, `getKeyAtSlot`, `getUserId`

専用 holder で GUI と user UUID を識別し、表示対象 slot だけを `PlayerSettingKey` へ変換する。secret slot は通常 key 解決に含めない。
