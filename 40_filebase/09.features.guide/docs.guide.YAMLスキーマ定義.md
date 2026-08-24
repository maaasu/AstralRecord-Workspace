# Guide YAML スキーマ定義

ゲーム内ガイド GUI に表示する手順、達成条件、操作方法、クリック時の案内アクションを定義します。手順の表示順と達成判定の順序は独立し、プレイヤーが条件を満たした step は記載順に関係なく達成します。GUI の物理スロット配置、条件判定、プレイヤーごとの達成状態は Plugin 側で制御します。

## スキーマ定義

```yaml
schemaVersion: 3
id: string
category: beginner
displayOrder: 10
title: string
iconMaterial: WRITABLE_BOOK
summary: string
steps:
  - id: string
    text: string
    details:
      - string
    action:
      type: NAVIGATE_NPC
      npcId: string
      description: string
    condition:
      type: ACTION_RING_OPENED
      targetId: string
      level: 2
```

| Key | Required | Description |
|---|---:|---|
| `schemaVersion` | yes | スキーマバージョン。現状は `3`。 |
| `id` | yes | ガイド ID。 |
| `category` | yes | ガイド分類。Plugin は `beginner` / `equipment` / `skill` / `world` / その他の順で表示する。 |
| `displayOrder` | yes | 同一カテゴリ内の表示順。小さい順に表示する。 |
| `title` | yes | GUI 一覧と詳細画面のタイトル。 |
| `iconMaterial` | no | Bukkit `Material` 名。未指定または不正な場合は `WRITABLE_BOOK` にフォールバックする。 |
| `summary` | no | 一覧 lore の短い説明。 |
| `steps[]` | yes | GUI に表示する手順。1件以上必要。配列順は表示順だけを決め、達成順を強制しない。 |
| `steps[].id` | yes | Guide 内で一意な手順 ID。公開後は意味を変更せず、別手順には新しい ID を使用する。 |
| `steps[].text` | yes | 白色で表示する短い手順名。敬語ではなく「～する」の形式を基本とする。 |
| `steps[].details` | no | 手順の具体的な操作方法。複数行を指定し、Plugin は灰色で表示する。 |
| `steps[].action` | no | 詳細画面の手順 item をクリックしたときの型付き案内動作。 |
| `steps[].action.type` | yes when action exists | `NAVIGATE_NPC` または `OPEN_MENU`。未知の値は読み込みエラーとする。 |
| `steps[].action.npcId` | yes for `NAVIGATE_NPC` | 案内対象の NPC マスタ ID。画面には ID を表示しない。 |
| `steps[].action.menuId` | yes for `OPEN_MENU` | 起動するメニュー ID。現状は `mail` を使用する。 |
| `steps[].action.description` | no | クリック時に実行される動作の説明。指定時は details の下へ灰色で表示する。 |
| `steps[].condition.type` | yes | Plugin が解釈する達成条件種別。 |
| `steps[].condition.targetId` | no | 条件対象 ID。未指定時は同じ type の全対象に一致する。 |
| `steps[].condition.level` | no | `MOB_DEFEATED` の対象 Mob レベル。未指定時は全レベルに一致する。 |

## 達成条件の評価

Plugin は各 guide の全未達成 step をイベントごとに評価します。条件種別と対象 ID が一致した step は、配列内の位置や他 step の達成状態に関係なく達成済みとして記録します。同一イベントで複数 step が一致する場合は、該当するすべての step を達成します。

達成状態はアカウント単位で `account_guide_step_progress` に保存します。新規 DB ではこの定義に基づく状態だけを作成し、旧スキーマからの移行・既存状態の遡及再判定は行いません。

## 達成条件種別

| type | targetId | 達成タイミング |
|:--|:--|:--|
| `PLAYER_LOGGED_IN` | 使用しない | ゲームプレイ状態でログインしたとき |
| `LOGIN_BONUS_CLAIMED` | 使用しない | ログインボーナスを受け取ったとき |
| `MAIL_RECEIVED` | mail ID | メールを既読化し、報酬受取が成功したとき |
| `BUNDLE_OPENED` | bundle item ID | bundle を開封して報酬付与が成功したとき |
| `SHOP_PURCHASED` | shop entry ID | ショップの商品購入または交換が成功したとき |
| `SKILL_LEARNED` | skill ID | スキルジェムからスキル個体の習得が成功したとき |
| `SKILLTREE_NODE_UNLOCKED` | node ID | スキルツリーのノード解放が成功したとき |
| `SKILL_ENHANCED` | skill ID | スキルの強化が成功したとき |
| `ACTION_RING_OPENED` | 使用しない | アクションリングの表示に成功したとき |
| `SKILL_CAST` | 任意の skill ID | プレイヤーのスキル実行が成功したとき。未指定なら任意のスキル |
| `MOB_DEFEATED` | mob master ID | 敵 Mob を討伐したとき |
| `GATHERING_COMPLETED` | gathering spawner ID | 指定スポナーに属する採集を完了したとき |

## クリック時アクション

### `NAVIGATE_NPC`

プレイヤーと同じワールドにいる指定 NPC のうち、最も近い対象を案内します。対象がスポーン中であれば座標をチャットに表示し、NPC を一時的に発光させます。スポーン中でない場合でも、登録済み配置座標があれば座標を表示します。対象が存在しない場合は汎用エラーを表示し、内部 NPC ID はプレイヤーへ表示しません。

### `OPEN_MENU`

Plugin が許可したゲーム内メニューを開きます。現状の `mail` は通常のメニューから開けるメール GUI を起動し、NPC の場所案内は行いません。

## 本文参照

本文と details では `{item:starter_sword}` のような参照プレースホルダーを使用できます。Plugin は表示時にロード済みマスター名へ解決し、未解決の参照はプレイヤー向け画面へ内部 ID として出さないよう、汎用表示へフォールバックします。

初期対応する参照種別:

| Prefix | Description |
|---|---|
| `item:` | item マスター表示名 |
| `class:` | class マスター表示名 |
| `world:` | world マスター表示名 |
| `menu:` | Plugin 側で定義するメニュー表示名 |

## YAML 例

```yaml
schemaVersion: 3
id: login_bonus_guide
category: beginner
displayOrder: 10
title: "&bログインボーナス"
iconMaterial: CHEST
summary: "&7ログインボーナスを受け取る方法を確認する。"
steps:
  - id: claim_login_bonus
    text: "&fログインボーナスを受け取る"
    details:
      - "&7ログインボーナス案内人に話しかける。"
      - "&7開いたログインボーナスGUIで、本日の日付の報酬をクリックする。"
    action:
      type: NAVIGATE_NPC
      npcId: login_bonus_clerk
      description: "&7クリックでログインボーナス案内人の場所を表示し、発光させる。"
    condition:
      type: LOGIN_BONUS_CLAIMED
  - id: receive_welcome_mail
    text: "&f初ログイン記念の贈り物をメールから受け取る"
    details:
      - "&7メールGUIを開く。"
      - "&7対象メールをクリックして報酬を受け取る。"
    action:
      type: OPEN_MENU
      menuId: mail
      description: "&7クリックでメールGUIを開く。"
    condition:
      type: MAIL_RECEIVED
      targetId: welcome_mail
```
