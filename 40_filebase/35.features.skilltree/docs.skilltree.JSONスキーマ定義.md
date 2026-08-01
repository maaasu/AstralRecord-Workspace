# SkillTree JSON スキーマ定義

SkillTree のノード定義と配置・接続構造は、`40_filebase/35.features.skilltree/` 配下の JSON を正本とします。
プレイヤー別の解放状態は JSON に保存せず、従来どおり `account-skilltree` API と AstralRecord DB を正本とします。

## ディレクトリ構成

```text
35.features.skilltree/
├─ node-id-sequence.json
├─ nodes/
│  └─ <nodeId>.json
├─ structures/
│  └─ <structureId>.json
├─ schemas/
│  ├─ node.v1.schema.json
│  ├─ node-id-sequence.v1.schema.json
│  └─ structure.v1.schema.json
└─ docs.skilltree.JSONスキーマ定義.md
```

- `nodes/`: ノードの表示、解放コスト、効果を管理する。
- `structures/`: ノード ID と中心座標からの相対座標、接続を管理する。
- `schemas/`: JSON Schema Draft 2020-12 による構文検証契約を管理する。
- `node-id-sequence.json`: エディターが最後に発行したノード ID を管理する。Plugin は読み込まない。
- `40_filebase/config.yml` の `database[]` には SkillTree を登録しない。MasterDataDB Seeder は YAML を対象としており、Plugin と開発者用エディターが本ディレクトリを直接読み込む。

## 共通ルール

- JSON は UTF-8（BOM なし）、LF、2 スペースインデント、末尾改行ありで保存する。
- 各文書は `$schema` で使用するローカルスキーマを明示し、`schemaVersion` でデータ契約のメジャーバージョンを表す。
- 現在の `schemaVersion` は整数 `1` とし、未知のバージョンは読み込まない。
- スキーマを破壊的に変更する場合は、既存スキーマを上書きせず `*.v2.schema.json` のように追加する。
- オブジェクトのプロパティ順は各サンプルの順とし、エディター保存時も安定した順序を維持する。

## ノード定義

スキーマ: `schemas/node.v1.schema.json`

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `$schema` | String | 必須 | `../schemas/node.v1.schema.json` |
| `schemaVersion` | Integer | 必須 | 現在は `1` |
| `nodeId` | String | 必須 | 数字だけで構成するノード ID。最大 100 文字 |
| `name` | String | 必須 | Legacy color code を使用できる表示名。空白のみ不可 |
| `icon` | String | 必須 | Bukkit `Material` 名。空白のみ不可 |
| `lore` | List\<String\> | 必須 | ノード説明 |
| `tags` | List\<String\> | 必須 | `76.shared.tag/v1.tags.yml`で`SKILLTREE_NODE`対象に定義されたタグID。各要素は重複不可 |
| `pointType` | String | 必須 | `CP` または `PP` |
| `pointCost` | Integer | 必須 | 0 以上、2147483647 以下の解放コスト（Java `int` 範囲） |
| `unlockCondition` | Object | 任意 | ノードを表示・有効化する条件。省略時は条件なし |
| `unlockCondition.classId` | String | 任意 | 現在クラスまたはその祖先として必要なクラス ID。1 職だけ指定可能 |
| `unlockCondition.playerLevel` | Integer | 任意 | 必要プレイヤーレベル。1 以上 |
| `effects` | List\<Effect\> | 必須 | 解放時に有効になる効果 |

`nodeId` はエディターが `node-id-sequence.json` の `lastIssuedNodeId` の次から自動採番し、初回は `1000` から開始します。作成後は変更せず、ノードを削除しても `lastIssuedNodeId` を戻さないため、削除済み ID は再利用しません。採番の欠番は許容します。ファイル名は `<nodeId>.json` と一致させます。

`unlockCondition` は `classId` と `playerLevel` の少なくとも一方を持ちます。職業レベルはノード条件として定義しません。`classId` は現在クラスそのものに加え、現在クラスから `unlockClassLevel[].class` を再帰的に辿ったいずれかの祖先であれば成立します。条件を満たさないノードはゲーム内で非表示となり、解放済みでも効果を発揮しません。

### nodeId 採番状態

`node-id-sequence.json` はエディターだけが更新する Git 管理の高水位ファイルです。

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `$schema` | String | 必須 | `./schemas/node-id-sequence.v1.schema.json` |
| `schemaVersion` | Integer | 必須 | 現在は `1` |
| `lastIssuedNodeId` | String | 必須 | 最後に発行した数字のみのノード ID。最大 100 文字 |

エディターは新規ノードの採番時だけ高水位を進め、ノードの保存失敗や削除による欠番を詰めません。Plugin は `nodes/` と選択された `structures/` を読み込み、このファイルを参照しません。

`positionId` による間接参照は使用しません。配置対象は構造 JSON の `nodes[].nodeId` で直接指定します。

### effects

`effects[]` は `type` を discriminator とする次のいずれかです。

#### スキル効果

```json
{
  "type": "skill",
  "skillId": "iron_will"
}
```

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `type` | String | 必須 | `skill` 固定 |
| `skillId` | String | 必須 | 所持とは独立して使用を許可するスキル ID。空白のみ不可 |

同じ `unlockCondition.classId`（条件なし同士を含む）では、同じ `skillId` を複数ノードへ重複定義できません。`unlockCondition.classId` が異なるノード間に限り、同じスキル使用許可を定義できます。`playerLevel` の違いだけでは別のクラス条件として扱いません。

#### ステータス効果

```json
{
  "type": "status",
  "status": "MAX_HEALTH",
  "modifierType": "FLAT",
  "value": 5
}
```

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `type` | String | 必須 | `status` 固定 |
| `status` | String | 必須 | `75.shared.status/v1.status_types.yml`のステータスID |
| `modifierType` | String | 必須 | `FLAT` または `SCALAR` |
| `value` | Number | 必須 | 補正値。`SCALAR` は `0.10` を 10% として扱う |

旧 `skillIds` と `statuses` は受理せず、すべて `effects[]` へ定義します。将来効果種別を追加するときは、node schema の `oneOf` と Plugin の effect parser を同時に拡張します。

## 構造定義

スキーマ: `schemas/structure.v1.schema.json`

| キー | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `$schema` | String | 必須 | `../schemas/structure.v1.schema.json` |
| `schemaVersion` | Integer | 必須 | 現在は `1` |
| `structureId` | String | 必須 | Plugin の設定から選択する構造 ID |
| `name` | String | 必須 | エディター上の表示名。空白のみ不可 |
| `rootNodeId` | String | 必須 | 到達可能性検証の起点となるノード ID |
| `nodes` | List\<PlacedNode\> | 必須 | ノード ID と相対座標の対応 |
| `edges` | List\<Edge\> | 必須 | ノード間の無向接続 |

### nodes

```json
{
  "nodeId": "1000",
  "x": 0,
  "y": 0,
  "z": 0
}
```

`x`、`y`、`z` は -2147483648 以上、2147483647 以下の整数（Java `int` 範囲）で、Plugin の `config.yml` にある `skilltree.center.x` / `y` / `z` からの相対ブロック座標です。Plugin は `skilltree.worldName` と `skilltree.structureId` で表示先と構造を選択します。ワールド名と中心座標は構造 JSON に重複保存しません。

### edges

```json
{
  "sourceNodeId": "1000",
  "targetNodeId": "1001"
}
```

- edge は無向で、永続的な edge ID は持たない。
- 同じ接続を逆順で重複保存しない。
- 保存時は小さい nodeId を `sourceNodeId`、大きい nodeId を `targetNodeId` とし、全 edge を両端 ID の順に安定ソートする。
- 自己接続と、`nodes[]` に存在しない nodeId への接続は禁止する。

## 意味検証

JSON Schema による型・必須項目検証に加え、エディターのバックエンドは保存前に次を検証します。

- node 定義間の `nodeId` 重複
- 同じクラス条件における `skillId` 使用許可の重複（同一ノード内を含む）
- 共有タグカタログに存在しない、または`SKILLTREE_NODE`へ適用できない`tags[]`
- node ファイル名と `nodeId` の不一致
- `node-id-sequence.json` のスキーマ違反、および `lastIssuedNodeId` が既存 node ID より小さい状態
- 構造内の `nodeId` 重複
- 構造内の相対座標重複
- 自己接続
- 端点を正規化した後の edge 重複
- 存在しない nodeId への参照
- `rootNodeId` が配置済みであること
- root から到達できない配置済みノード

検証エラーがある場合は保存しません。保存時は置換前ファイルをバックアップしてから、一時ファイルを介して原子的に置換します。

## Plugin とエディターの責務

- 開発者用ローカル Web エディターのソースは `60_tool/skilltree-editor/` に置く。
- ステータス候補とタグ候補の日本語表示は各共有カタログのTypeScript生成物、スキル情報は`30.features.skill`の読取結果を使用し、JSONにはIDだけを保存する。
- キャンバス上のノード表示サイズはWeb UIのローカル設定とし、構造JSONには保存しない。
- ノード定義、配置、接続の編集と JSON 書き込みはエディターだけが行う。
- エディターが更新する Plugin 設定はリポジトリ上の `10_plugin/AstralRecord/src/main/resources/config.yml` とし、稼働環境の `plugins/AstralRecord/config.yml` へ直接書き込まない。稼働環境へ filebase と設定をデプロイまたは同期した後、`/masterdata reload` で反映する。
- Plugin は選択された node/structure JSON を読み取り専用で使用し、ゲーム内操作から構造ファイルを書き換えない。
- Plugin の `/masterdata reload` は node/structure JSON を再読込し、検証済みスナップショットを一括反映する。
- プレイヤー別の解放ノードと CP 消費元クラスは `account-skilltree` API/DB に保存し、構造 JSON や Plugin ローカルファイルには保存しない。
