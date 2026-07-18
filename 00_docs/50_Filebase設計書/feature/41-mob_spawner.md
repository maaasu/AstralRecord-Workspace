# Mob Spawner 設計

## 役割

Mob Spawner は、mob をワールド内の指定条件で出現・再出現させる定義です。

## 設計方針

- 出現対象、範囲、上限、間隔、再出現条件を組み合わせて密度を制御します。
- spawner 自体で対象マスタの役割や強さを変更しません。
- 複数の mob を混ぜる場合、同じ進行帯と戦闘目的に揃えます。
- world の配置条件が決まっていない spawner を先行して量産しません。

## progression

出現対象のうち、標準遭遇段階が最も高いものを基準にします。出現密度によって実質難度が上がる場合は `+1` を検討します。

## 正本参照

- mob spawner YAML: `E:\AstralRecord-Workspace\40_filebase\41.features.mob.spawner\spawner.YAMLスキーマ定義.md`
