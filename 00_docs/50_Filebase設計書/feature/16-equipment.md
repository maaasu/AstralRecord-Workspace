# Equipment 設計

## 役割

Equipment は、装備中のステータス、skill、操作、採集能力などを変化させる item です。装備ごとに主役となる用途を明確にします。

## 装備カテゴリ

| カテゴリ  | 主な役割                | 主に想定するステータス                     |
| :---- | :------------------ | :------------------------------ |
| 近接武器  | 近距離の通常攻撃・近接 skill   | `MELEE_ATTACK` を中心とする近接系        |
| 遠隔武器  | 距離を保った通常攻撃・遠隔 skill | `RANGED_ATTACK` を中心とする遠隔系       |
| 魔法武器  | 魔法 skill とリソース運用    | `MAGIC_ATTACK`、`MAX_MANA` など    |
| サブ武器  | 主武器の補助、防御、操作追加      | 主武器を補完する防御・補助系                  |
| 防具    | 被ダメージ抑制と生存方針        | `DEFENSE`、`MAGIC_DEFENSE`、リソース系 |
| アクセサリ | ビルドの補強・特化           | 装備種別の役割に応じた少数のステータス             |
| ツール   | 採集など戦闘外の機能          | Plugin が対応する用途タグと関連能力           |

使用可能な slot、hand type、accessory slot、tool/accessory tag は本書へ複製せず、正本参照先を確認します。

## 装備可能数

- 通常装備の slot と装着動作は Plugin の `ItemEquipmentSlot` と inventory 実装を正とします。
- アクセサリの種類別装備可能数は Plugin の `AccessorySlotType` を正とします。
- 装備可能数が多いカテゴリほど、1個あたりの性能配分を小さくします。

## ステータス

- 主役となるステータスは1〜2種類を基本とします。
- 近接武器は `MELEE_ATTACK`、遠隔武器は `RANGED_ATTACK`、魔法武器は `MAGIC_ATTACK` を中心に設計します。
- 特定ステータスへ触れることはできますが、使用可能な一覧は `StatusType.kt` を正とします。
- 固定値とランダム範囲は、同じ進行度・レアリティの装備間で期待値を比較できるようにします。

## レアリティと progression

- progression は標準入手段階、レアリティは同段階内の希少性と性能配分を表します。
- 攻略前に得る標準装備は対象コンテンツの `P-1` から `P` とします。
- 対象コンテンツから得る更新装備は `P` から `P+1` とします。
- レアリティだけを理由に、複数段階先の装備性能を与えません。

## 強化・セット効果・特殊効果

- 強化は同じ装備を継続利用する手段とし、無強化時の役割を変えません。
- セット効果は複数部位を固定するコストに見合う効果とし、単品装備の主役を残します。
- enchant、rune、transcendence、付与 skill は、Plugin と各スキーマに存在する仕組みだけを使用します。
- 特殊効果を持たせる場合、発動条件、対象、頻度、代替できない理由を明確にします。

## 入手方法の確認

- 入手前に攻略が必要な対象と、その装備で攻略させたい対象が逆転していないこと。
- loot、recipe、shop、quest の複数経路を設ける場合、最も早い経路を progression の基準にすること。
- 強化素材やセット構成品が、装備本体より大きく後の段階に偏っていないこと。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\equipment\docs.equipment.YAMLスキーマ定義.md`
- set effect YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\equipment\set_effect\docs.set_effect.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\status\model\StatusType.kt`
- slot / hand type / enhance / enchant / rune / transcendence: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\item\model\ItemEquipment.kt`
- accessory slot / tag: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\inventory\model\AccessorySlotType.java`
- inventory slot mapping: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\inventory\model\EquipmentType.kt`
