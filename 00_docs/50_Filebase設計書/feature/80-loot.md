# Loot 設計

## 役割

Loot は、mob、gathering、boss、quest などから得られる報酬候補と抽選構造を定義します。

## 設計方針

- pool は同じ抽選目的の候補をまとめ、table は必要な pool を組み合わせます。
- 通常報酬、希少報酬、確定報酬の役割を分けます。
- 低確率だけで進行必須品を供給しません。
- 参照元が存在しない pool/table を作りません。

## progression

- table は主な参照元の progression を基準にします。
- 通常報酬は参照元と同値、更新報酬は同値から `+1` を基準にします。
- 複数進行帯から共有する pool は、各 item の progression を変えず、table 側の抽選内容で調整します。

## 正本参照

- pool YAML: `E:\AstralRecord-Workspace\40_filebase\80.shared.loot\pool\docs.pool.YAMLスキーマ定義.md`
- table YAML: `E:\AstralRecord-Workspace\40_filebase\80.shared.loot\table\docs.table.YAMLスキーマ定義.md`
