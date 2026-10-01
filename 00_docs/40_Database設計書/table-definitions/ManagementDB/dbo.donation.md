# 寄付管理テーブル

正本DDLは`migrations/20260926_donations.sql`と`init.sql`。API契約は[37-donations](../../../20_API設計書/feature/37-donations/37_README.md)。すべてManagementDBに保存し、ゲーム側テーブルへの外部キーは持たない。

| テーブル | 主キー・一意性 | 保持する情報 |
|---|---|---|
| donation_ledger | user_uuid | 承認円累計bigint、revision |
| donation_request | id（申請operationId） | 本人UUID/MCID、申告/承認額、状態、平文JSON明細、同意規約版、Discord本人ID、確認者/日時、否認・警告理由 |
| donation_entry_fingerprint | fingerprint | 正規化したコード/URLのSHA-256、申請ID。否認・取消時だけ解放 |
| donation_grant | id、unique(account_uuid,through_amount) | 本人/アカウントUUID、累計到達額、今回数量、本文、作成/配信日時 |
| donation_notification | id | 本人UUID、イベント種別、額、本文、作成/確認日時 |
| donation_discord_link | user_uuid、unique(discord_user_id) | Discord ID/表示名、暗号化access/refresh token、参加確認結果/日時、revision |

`donation_request.protected_entries`の列名・型は維持し、新規申請は支払い明細の平文JSON配列を保存する。旧申請の暗号化形式も読取対象とするが、旧鍵がない場合は明細を開示せず、申請の基本情報だけ返す。保存済みの暗号化データを空配列で上書きしない。Discord OAuth tokenは引き続き暗号化保存する。

取消・否認は履歴を消さず終状態を残す。確認開始から最終判断までは同じ管理者が担当し、二重承認を拒否する。寄付履歴をアカウント削除APIの削除対象に含めない。

配布はManagementDBの指示確定→ゲームDBの固定メールID登録→ManagementDB配信日時確定の順。別DBをまたぐ停止時は固定IDを使って再送し、同じアカウントへ二重付与しない。ゲームDBを初期化して新規UUIDを作成した場合は累計分を配布する。
