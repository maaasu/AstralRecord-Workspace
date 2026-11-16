# 11_3-GUI・View

## 1. 設定 GUI 表示

統合版では、メインメニューの設定ボタンと `/setting gui` から Geyser が表示するフォームを開く。Java版のスロット順で一般設定を表示する。統合版フォームでは `ACTION_RING_HOLD_SELECT`（アクションリング長押し選択）を表示・保存対象から外す。ダメージ数値の大きさは小・標準・大の選択欄にする。フォームを送信すると、変更した項目だけを本人の現在の設定セッションへ反映する。一般設定から「画面UI設定」を選ぶ場合も、その画面の変更を保存してから遷移する。送信せずに閉じた場合は下書きを破棄する。Java版の保存契約は下記の通り。

クラス名: `PlayerSettingGui`
物理名: `open`, `refresh`

54 slot の「プレイヤー設定」を開き、snapshot または draft 値から icon を描画する。設定項目・画面UI設定の入口・保存案内は専用リソースパックのアイコンを使い、パック未適用時は素材本来の外観で表示する。設定項目は左右の枠を避け、戦闘表示を3行目の `20〜22`、外観を4行目の `29〜33`、通知・操作などを5行目の `38〜42` に配置する。slot `13` の共通案内で、戻る／閉じる時に保存・反映することを示す。保存済み値と異なるdraftを持つ項目の状態行は、保存済み値と変更予定値を区別する。同じ値へ戻したdraftは変更予定扱いにしない。クリックでは保存・反映せず、従来どおりInventoryCloseで保存する。

| slot | key / 操作 |
|---:|---|
| 13 | 保存・反映タイミングの共通案内 |
| 20 | `DAMAGE_LOG_DISPLAY` |
| 21 | `DAMAGE_NUMBER_SIZE` |
| 22 | `DAMAGE_LOG_MESSAGE` |
| 24 | `BUFF_SIDEBAR_DISPLAY` |
| 25 | 「画面UI設定」を開く |
| 29 | `PARTICLE_DENSITY` |
| 30 | `ARMOR_DISPLAY` |
| 31 | `NIGHT_VISION` |
| 32 | `SKILL_TREE_COMPACT_DISPLAY` |
| 33 | `RANDOM_STATUS_RANGE_DISPLAY` |
| 38 | `DROP_LOG_DISPLAY` |
| 39 | `AUTO_SAVE_MESSAGE` |
| 40 | `GUIDE_REMINDER`（定期ガイド通知） |
| 42 | `ACTION_RING_HOLD_SELECT`（Java版の設定GUI） |
| 49 | 前画面へ戻る |
| 53 | icon を置かない管理者用 super mode secret slot |

`DAMAGE_NUMBER_SIZE` はクリックするたびに小 → 標準 → 大 → 小の順で切り替える。コマンドでは `/setting damage_number_size <small|normal|large>` で変更できる。

`TEMP_DROP_DISPLAY` と `TEMP_BLOCK_DISPLAY` は GUI に表示せず、コマンドから変更する。

`ACTION_RING_HOLD_SELECT` は既定 `false` で、`true` のときだけ右クリック長押し選択を使う。保存後は inventory を再送し、選択中 hotbar 主武器だけをクライアント専用トライデント表示へ直ちに切り替える。ホットバースロット切り替え時も選択中 slot の表示を再同期する。

`NIGHT_VISION` は既定 `false` で、`true` のときだけ無期限・粒子なしの暗視効果を付与する。`false` へ変更した場合は暗視効果を除去する。

`GUIDE_REMINDER` は既定 `true`。GUIを閉じてOFFを保存すると次回以降の定期ガイド通知を止める。`/setting guide_reminder <on|off>` からも変更できる。

`RANDOM_STATUS_RANGE_DISPLAY` は既定 `false`。GUI の draft を閉じて反映した後、inventory を再送して装備ステータス直下の灰色の乱数範囲行を切り替える。`/setting random_status_range_display <on|off>` からも変更でき、同様に inventory を再送する。

## 2. 画面UI設定

一般設定に「画面UI設定」の入口を一つ設け、専用画面で、クエスト追跡・アクションリングCT・装備スキルCT・DPS・ワールド名・エリア名・MSPT・Pingを個別に切り替える。Java版は戻る／閉じる時に下書きを保存する。統合版は保存または変更せずに戻る操作を持つ。統合版のアクションリングCTと装備スキルCTは赤字「非対応」で表示し、入力欄を出さない。クエスト追跡は、現在の接続が専用TIPに対応しない場合だけ、同じく非対応表示にする。保存時も現在の接続能力とセッションを再確認し、古いフォーム、重複応答、途中で非対応になった項目を受理しない。非対応でも保存済みの好みを変更しない。

## 3. GUI 識別・slot 解決

クラス名: `PlayerSettingGui`
物理名: `isInventory`, `getKeyAtSlot`, `getUserId`

専用 holder で通常・画面UIの種別、user UUID と作成時の session token を識別し、現在の画面の設定 slot だけを `PlayerSettingKey` へ変換する。secret slot は通常画面だけで処理し、設定 key 解決に含めない。
