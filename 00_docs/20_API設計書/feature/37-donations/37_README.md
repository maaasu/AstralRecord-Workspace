# 寄付申請・審査・累計配布

## 対象

WebでMinecraftユーザー本人がAmazonギフトカード番号またはPayPay受け取りリンクを申請し、管理者が手動で受領確認する。決済サービスによる自動受領・自動承認は行わない。ショップ、EXPブーストの効果、優先アクセスの実装は範囲外。

## 認証と入力

- 既存`X-Api-Key`に加え、Web操作は専用`X-Donation-Web-Key`を必須とする。WebはCookieの本人UUIDを`actor_user_uuid`へ設定し、ブラウザ入力のUUIDは使わない。
- 管理操作はManagementDBの最新`web_admin`を都度確認する。一般ユーザーは本人の申請だけ閲覧・取消できる。詳細以外の一覧にギフト番号・URLを返さず、Web応答はキャッシュ禁止。
- Discord OAuthは`identify guilds.members.read`を使う。APIが本人IDと設定された公式Guild参加を検証し、申請時も再照会する。同一Discord IDを複数Minecraftユーザーへ関連付けない。
- 明細1～10件、各明細の種別・番号/URL・金額と申告合計を送信。合計一致、申告500～1,000,000円、規約版`2026-09-26`への同意を検証する。最大額は整数/報酬処理負荷の入力上限。
- PayPayは`https://pay.paypay.ne.jp/<受取ID>`のみ。ユーザー提供URLへAPIからアクセスせず、別ホスト・資格情報・クエリ・フラグメントを拒否する。
- コード/URLは暗号化保存し、重複判定用には正規化したSHA-256を保存する。秘密情報は一覧・通知・ログへ含めない。

## API

すべて`/api/donations`配下。JSONはcamelCase。エラーは`{message}`、不正入力400、権限/連携不成立403、未存在404、状態競合409、設定不足・外部障害503。

| メソッド・パス | 内容 |
|---|---|
| GET `/` | 本人履歴。`page`/`page_size`、`totalCount`と`totalApprovedAmount`、`discordLink`を返す |
| GET `/admin` | 管理者のみ全履歴。合計は全ユーザーの承認額 |
| GET `/{id}` | 本人または管理者に詳細・支払い明細を返す |
| POST `/` | `{operationId,declaredAmount,termsVersion,entries:[{method,value,declaredAmount}]}` |
| POST `/{id}/review` | PendingからReviewingへ。担当を確保する |
| POST `/{id}/approve` | `{approvedAmount:null}`なら申告額、指定値ならその額。1～1,000,000円 |
| POST `/{id}/reject` | `{reason}`必須。受領せず否認 |
| POST `/{id}/cancel` | 本人のPendingのみ取消 |
| GET `/discord` | 本人の連携状態。トークンは返さない |
| POST `/discord` | `{accessToken,refreshToken}`をDiscordに照会して保存 |
| GET `/notifications?user_uuid=...` | ゲーム用。未通知50件まで、`[{id,kind,amount,message}]` |
| POST `/notifications/{id}/ack?user_uuid=...` | ゲーム用。本人へ表示できた通知だけACK |

## 状態と整合性

- Pending+Reviewingをユーザーごとに最大2件。SQL Serverのユーザー台帳行/キー範囲ロックとSerializable transactionにより並行投稿も直列化する。
- Reviewing以降は本人の取消不可。承認/否認は確認開始した管理者だけ。管理者は支払い受領前にreviewし、受領できない場合は理由を付けてrejectする。
- 否認・取消では累計・報酬を変更しない。承認済み/否認済みを逆の終状態へ変更できない。同じ申請IDの再送・同じ判断の再試行は二重計上しない。
- 承認額が500円未満の場合は実額を配布し、次回500円以上とする警告を履歴・メール・通知へ含める。通常運用では受領前に否認する。
- 承認額1円につき`99a00021`（CURRENCY、アストラルド(有償)）1個。承認時はManagementDBだけで累計・承認通知を確定し、後続照合で各アカウントの予約済み累計との差分を配布指示にする。配信完了は別種別MailDeliveredで通知する。
- 2秒間隔の非同期照合で承認時点の全有効アカウントと新規・複製・削除後の代替アカウントを捕捉する。ゲームDB障害時は配布指示を保持し、復旧後再試行する。通常時のオンライン通知はAPI照合とPlugin通知監視の合計で数秒以内を目安とする。
- 固定ID`donation-<grant UUID>`の動的メールを作成する。配布指示に記録した固定数量を使い、配信後ACK喪失でも同じメールだけを再確認する。
- `publishTo=null`で無期限。本人の開封による既存`mailClaim` snapshotで添付付与と既読化を同時確定する。未受取の報酬付きメール削除・単独既読化を拒否する。
- 新規UUIDへ承認累計分、同UUID復元へは再配布しない。アカウント複製で有償通貨残高はコピーしない。通貨はtrade/market/drop不可、通常保管庫はアカウント単位。
- ManagementDBを保持したゲーム初期化では履歴・累計・配布記録が残る。ManagementDBも初期化した場合は完全リセット。復旧はDBとData Protection鍵を整合したバックアップから行う。

## 導入

1. `60_tool/14-management-db-migrate.bat`で`20260926_donations.sql`を適用。新規環境はManagementDB/init.sql。
2. Filebaseの有償通貨を配置してMasterDataDBへseedする。API/Web/Pluginを配置する。
3. APIとWebへ同じ`Donations__WebKey`（共通APIキーとは別）、`Donations__DiscordClientId`、`Donations__DiscordClientSecret`を設定。APIへ`Donations__DiscordGuildId`、Webへ`Donations__DiscordRedirectUri`を設定する。
4. Discord Developer PortalへWebのHTTPS callback `/Donations/Discord`を正確に登録する。公開画面・資格情報を使った疎通は運営環境で実施する。
5. API `DataProtection__KeyPath`を永続ディレクトリへ設定し、API実行ユーザーだけに書込/読取を許可する。再起動・複数API間で同じアプリ名と鍵を共有する。鍵喪失はコード・OAuth情報の復号不能につながる。

`WebKey`が空なら寄付Web APIは503、配布workerは停止する。秘密をリポジトリへ記入しない。DB migration前に有効化しない。

## 復旧と将来商品の境界

ゲームDBのリセット・復旧時はAPIとゲームの書込みを停止し、未配布指示とバックアップ時点を確認する。ManagementDBとゲームDBの配信/受取/所持品の一部だけを独立して巻き戻す復旧は対象外。通常の同UUID復元では新しい配布指示を作成せず、新規UUIDには累計を配布する。

有償通貨で購入する商品は今回追加しない。将来商品を追加する際は`unTradeable`・`unSellable`とアカウント複製除外を含む移動経路を確認する。現在、購入元を追跡して全商品へ自動で所有制限を付ける機能はない。
