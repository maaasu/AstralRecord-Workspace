# Dungeon 設計

## 役割

Dungeon は、入場条件、道中、節目、boss、報酬、退出条件を一連の攻略単位として束ねます。

## 設計方針

- 想定人数、所要時間、失敗条件、再挑戦条件、主な報酬を定めます。
- 道中と boss に、事前準備した装備・消耗品・skill を確認する役割を持たせます。
- dungeon 外の進行必須品を、極端な低確率報酬だけに依存させません。
- 個別 dungeon のテーマや部屋構成は個別定義で扱います。

## progression

標準攻略段階を記載します。準備コンテンツは `P-1` から `P`、道中と boss は `P`、更新報酬は `P` から `P+1` を基準にします。

## 正本参照

- database 登録先: `E:\AstralRecord-Workspace\40_filebase\config.yml` の `dungeon`
- 現時点では `40_filebase\65.features.dungeon` と YAML スキーマが未整備です。スキーマが作成されるまで個別 dungeon YAML は作成しません。
