# Plugin feature カタログ

この表は、設計書の機能境界と `10_plugin/AstralRecord` の実装責務を対応付ける正本である。複数 feature が同じ実装を参照する場合でも、主所有者はこの表の「主対象 package」に従う。

| No. | 設計 feature | 主な責務 | 主対象 package / 境界 |
|:--|:--|:--|:--|
| 01 | [[01_README]] | Minecraft ユーザー登録、権限、履歴 | `feature/user` |
| 02 | [[02_README]] | 選択アカウント、モード、アカウント進行 | `feature/account` |
| 03 | [[03_README]] | プレイヤーライフサイクル、チャット、死亡、ログインボーナス、クラス進行 | `feature/player`, `feature/loginbonus`, `feature/class`, `feature/playerclass` |
| 04 | [[04_README]] | アイテム定義、生成、使用、装備操作 | `feature/item` |
| 05 | [[05_README]] | バフ定義と適用状態 | `feature/buff` |
| 06 | [[06_README]] | ルートテーブル取得と抽選 | `feature/loot` |
| 07 | [[07_README]] | プレイヤーステータス計算と表示 | `feature/status` |
| 08 | [[08_README]] | インベントリ同期、装備、保管庫 | `feature/inventory`, `feature/storage` |
| 09 | [[09_README]] | 共通メニュー、ガイド、売却導線 | `feature/menu`, `feature/guide`, `feature/sell` |
| 10 | [[10_README]] | HUD の組み立てと表示 | `feature/hud` |
| 11 | [[11_README]] | プレイヤー設定と設定 GUI | `feature/playersetting` |
| 12 | [[12_README]] | Mob、NPC、採集、スポナー、固定 text display | `feature/mob`, `feature/gathering`, `feature/spawner`, `feature/textdisplay` |
| 13 | [[13_README]] | スキル発動、bind、skill tree | `feature/skill`, `feature/skilltree` |
| 14 | [[14_README]] | ダメージ計算と戦闘状態 | `feature/combat` |
| 15 | [[15_README]] | ホットバー入力と item / skill action の調停 | `shared/interaction` と item・inventory・skill の連携境界 |
| 16 | [[16_README]] | 通貨残高と両替 | `feature/currency` |
| 17 | [[17_README]] | WorldMasterData、ワールド遷移、スポーン | `feature/world`, `shared/teleport` |
| 18 | [[18_README]] | メール一覧、既読化、報酬受取 | `feature/mail` |
| 19 | [[19_README]] | パーティー状態と Mob 報酬共有 | `feature/party` |
| 20 | [[20_README]] | ショップ表示、コスト preview、購入補償 | `feature/shop` |
| 21 | [[21_README]] | Mob 討伐の冒険記録と閲覧 GUI | `feature/adventurerecord` |
| 22 | [[22_README]] | プレイヤー間トレードと返却補償 | `feature/trade` |
| 23 | [[23_README]] | マーケット出品・検索・取引 | `feature/market` |
| 24 | [[24_README]] | Minecraft からの Web ログインコード発行 | `feature/webauth` |
| 25 | [[25_README]] | ウェイストーン、解除状態、同一ワールド転送 | `feature/teleporter` |
| 26 | [[26_README]] | ボス挑戦、専用フィールド、終了処理 | `feature/boss` |
| 27 | [[27_README]] | 状態異常の付与、tick、解除 | `feature/condition` |
| 28 | [[28_README]] | 複数機能のクリック候補を一件へ調停 | `shared/interaction` の共通契約 |
| 29 | [[29_README]] | クエスト状態、進捗、報酬、board | `feature/quest` |
| 30 | [[30_README]] | Java 用リソースパック要求と client status | `feature/resourcepack` |

## 更新規則

1. `feature/<package>` を追加したら、主所有者となる設計 feature をこの表に割り当て、その README の「対象実装パス」に記載する。
2. 一つの実装 package を複数の設計 feature が参照する場合、主所有者以外は依存境界だけを記載し、同じ仕様を複製しない。
3. 実装 package の移動、統合、削除と同じ変更でこの表を更新する。
4. 独立した責務を既存 feature に収められない場合は、一意な次番号で設計 feature を追加する。
