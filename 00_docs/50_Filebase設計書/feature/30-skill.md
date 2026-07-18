# Skill 設計

## 役割

Skill は、プレイヤー、mob、装備などが実行する能動・受動能力を定義します。

## 設計方針

- 1 skill につき、攻撃、防御、移動、回復、補助の主目的を1つ定めます。
- 発動条件、対象、コスト、クールダウン、効果範囲を組み合わせて強さを制御します。
- class や equipment の弱点を無条件で消す効果は避けます。
- 実行可能な action と値は Plugin 設計・実装を正とします。

## progression

標準的に習得または利用可能になる段階を記載します。skilltree、class、equipment など複数の入手経路がある場合は、最も早い経路を基準にします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\30.features.skill\skill.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
