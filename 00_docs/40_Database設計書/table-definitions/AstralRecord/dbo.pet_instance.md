# dbo.pet_instance

卵と孵化済みペットの所有個体正本。主キー `instance_id`、外部キー `account_id→account.uuid`。全カラムは `init.sql` と `migrations/20261001_pet.sql` が正本。

| 列 | 型 | 内容 |
|---|---|---|
| instance_id/account_id | UNIQUEIDENTIFIER | 個体と現在所有者 |
| species_id/item_id | NVARCHAR(64)/NVARCHAR(100) | 種類と現在のアイテムマスターID |
| is_egg/origin | BIT/NVARCHAR(8) | 孵化前フラグ、WILD/BRED〈変更しない出自〉 |
| details_json | NVARCHAR(MAX) | 卵時は非公開の親遺伝スナップショット。孵化後は名前・性別・サイズ・成長・基礎値・潜在・スキル・HP・CD |
| male_parent_id/female_parent_id | UNIQUEIDENTIFIER NULL | BREDで双方必須、異なる親。親削除/移管後も血統を保持するためFKは置かない |
| version | BIGINT | 原子progressの楽観ロック、1以上 |
| created_at/updated_at | DATETIME2(3) | UTC |
| created_by/updated_by | UNIQUEIDENTIFIER | 操作者 |
| is_deleted | BIT | 論理削除 |

`IX_pet_instance_owner(account_id,is_deleted)`。details_jsonはISJSON制約、originと親ペアはCHECK。API DTOは卵detailsを常に省略し、個体entityを直接返さない。

削除は所有者単位で論理削除。複製では全所有個体に新IDを付け、所有内親IDを再マップし、遺伝・CD・死亡・BRED出自を保持。トレード/マーケットはWILDだけ所有者を更新しversion加算。成長現在値ではなく基礎個体値を遺伝する。
