# 有償サービス・チャンネルブーストのテーブル

- `dbo.channel_boost`: `channel_id`主キー。EXP/DROP別の倍率、期限、発動操作ID、発動者名を保持。期限切れは読取時に無効として扱う。
- `dbo.channel_boost_cursor`: 1行だけの`last_event_cursor`。`UPDLOCK,HOLDLOCK`で通知順をtransactionのcommit順に固定する。
- `dbo.channel_boost_event`: `event_cursor`主キー。全チャンネル告知用の操作ID、発動アカウントID・名・発動時VIP種別、種別、倍率、期限を保持。
- `dbo.channel_boost_operation`: `operation_id`主キー。ゲームチケット消費の入力hash、結果、entry ID、event cursorを保持して再送を冪等にする。
- `dbo.astrald_shop_purchase`: `operation_id`主キー。Web購入者・現在アカウント・master価格の期待値・購入時効果・対象チャンネル・結果JSONを保持。`PENDING`はPlugin保存境界で処理。
- `dbo.web_mail_currency_claim`: `operation_id`主キー。アカウントとmail IDの未拒否行に一意制約。申請時通貨報酬JSONと非通貨報酬有無を固定し、通貨のみの直接付与と再送を記録する。

初期作成SQLは`init.sql`、既存DBには`migrations/20260926_channel_boost.sql`、`20260927_astrald_shop_purchase.sql`、`20260927_web_mail_currency_claim.sql`をこの順で適用する。
