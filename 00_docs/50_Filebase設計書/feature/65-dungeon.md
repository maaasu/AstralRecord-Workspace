# Dungeon 設計

## 役割

Dungeon は、入場条件、道中、節目、boss、報酬、退出条件を一連の攻略単位として束ねます。

## 設計方針

- 想定人数、所要時間、失敗条件、再挑戦条件、主な報酬を定めます。
- 道中と boss に、事前準備した装備・消耗品・skill を確認する役割を持たせます。
- dungeon 外の進行必須品を、極端な低確率報酬だけに依存させません。
- 個別 dungeon のテーマや部屋構成は個別定義で扱います。
- 個別調整が不要な dungeon は受付地点と Mob 参照だけの最小構成とし、生成寸法・Material・柱・人数には Plugin の既定値を使います。

## progression

標準攻略段階を記載します。準備コンテンツは `P-1` から `P`、道中と boss は `P`、更新報酬は `P` から `P+1` を基準にします。

## 正本参照

- database 登録先: `40_filebase/config.yml` の `dungeon`
- YAML スキーマ: `40_filebase/65.features.dungeon/docs.dungeon.YAMLスキーマ定義.md`
- Plugin の生成・進行契約: `00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md`
- 個別 dungeon YAML はモチーフ、攻略段階、参照 World/Mob が決まってから作成します。スキーマ例は本番データとして読み込みません。
