# Skill 設計

## 役割

Skill は、プレイヤー、mob、装備などが実行する能動・受動能力を定義します。

## 設計方針

- 1 skill につき、攻撃、防御、移動、回復、補助の主目的を1つ定めます。
- 発動条件、対象、コスト、クールダウン、効果範囲を組み合わせて強さを制御します。
- class や equipment の弱点を無条件で消す効果は避けます。
- 実行可能な action と値は Plugin 設計・実装を正とします。
- プレイヤー向け職業発動スキルの ID は、職業 prefix と lowercase snake_case を組み合わせ、`implementationId` と同じ値にします。
- リソース種別・消費量・クールダウン・詠唱時間などの共通値は top-level 項目へ定義し、`params` は実装固有の拡張が本当に必要な場合だけ使用します。
- 当たり判定、倍率、状態異常、演出はスキルごとの Plugin 実装へ型付きで定義し、YAML へ同じ既定値を大量に複製しません。プレイヤーが判断するために必要な効果値は `description` / `lore` と実装を同期します。
- 攻撃演出は当たり判定の形と発生時刻が読み取れる輪郭を優先し、持続パーティクルを常時大量に表示しません。

## 初期職業発動スキルのコンセプト

| 職業 | コンセプト | 主リソース | 主な当たり判定 |
|:--|:--|:--|:--|
| `swordsman` | 間合いを奪い、受けて前線を固定する守護剣 | `ENERGY` | 扇形、直線、周囲、突進経路 |
| `hunter` | 射線と位置取りで狩場を組み立てる射手 | `ENERGY` | 飛翔体、複数射線、設置罠、地面指定 |
| `mage` | 元素ごとの形と時間差を使い分ける陣形魔術師 | `MANA` | 飛翔体、連鎖、周囲、地面指定 |

初期段階では各職業8件、合計24件を定義します。通常職にはまだ習得設定を行わず、運営検証用 `administrator` の `levelSkills` から全件を Lv.1 で利用可能にします。

## progression

標準的に習得または利用可能になる段階を記載します。skilltree、class、equipment など複数の入手経路がある場合は、最も早い経路を基準にします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\30.features.skill\docs.skill.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
