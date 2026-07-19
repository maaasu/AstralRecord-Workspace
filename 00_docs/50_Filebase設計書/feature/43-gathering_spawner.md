# Gathering Spawner 設計

## 役割

Gathering Spawner は、gathering をワールド内の指定条件で出現・再出現させる定義です。

## 設計方針

- 出現対象、範囲、上限、間隔、再出現条件を組み合わせて供給量を制御します。
- spawner 自体で gathering の報酬内容や必要ツールを変更しません。
- 複数対象を混ぜる場合、同じ進行帯と採集目的に揃えます。
- world の配置条件が決まっていない spawner を先行して量産しません。

## progression

出現対象のうち、標準採集段階が最も高いものを基準にします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\43.features.gathering.spawner\docs.spawner.YAMLスキーマ定義.md`
