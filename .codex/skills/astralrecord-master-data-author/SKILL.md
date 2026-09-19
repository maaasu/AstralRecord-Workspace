---
name: astralrecord-master-data-author
description: AstralRecord の filebase マスタデータ作成 worker。準備済み task worktree の中で、50_Filebase設計書、対象 feature、モチーフ・進行度方針、既存 YAML スキーマを読み、item/equipment/material/consumable/class/skill/mob/spawner/world/loot/shop などの 40_filebase 定義を本番向けに整合させて追加・拡張する。通常依頼で worktree 作成や commit / develop 反映も必要になり得る場合は、統合入口 `$astralrecord-code-version-commit-develop` を優先する。
---

# AstralRecord マスターデータ作成

## 基本ルール

設計資料から本番向けの filebase マスターデータを作成し、単独の想像で補わない。個別 world のアイデアは対象 YAML または明示された world context に保持し、変更されやすい計画メモではなく安定した motif/progression comment を使う。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\40_filebase\AGENTS.md` を読む。
3. filebase 設計書を読む。
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\README.md`
   - the requested category's `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\feature\<category>.md`
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\モチーフ選定ガイド.md`
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\作成時チェックリスト.md`
4. 対象ディレクトリと schema file を選ぶために `references/filebase-target-map.md` を読む。
5. master file を編集または追加する前に、対象 YAML schema を読む。
6. 近隣の既存 YAML を確認し、ローカルの format、reference 形式、ID 形式、rarity の命名、値の尺度を把握する。

## Icon 選択ルール

- 新規の item または skill を定義するときは、既存コンテンツで使用済みの `icon` を確認し、原則として再利用しない。
- ただし、モチーフや用途から特定の `icon` が明らかに適切で、別の `icon` に置き換える方が不自然な場合は、既存コンテンツで使用済みでも再利用してよい。ノクスリンゴに `apple` を使うケースが該当する。
- 単なる見た目の近さや実装都合だけでは例外にしない。例外を適用した場合は、採用理由を作業報告へ記載する。

## 並列 package のルール

複数 worker が filebase master を並列作成する場合、個々の YAML file や相互依存する技術 layer ではなく、整合した最小の playable package 単位で分割する。

- package ごとに task branch と専用 worktree を割り当てる。並列 writer に writable worktree、Git index、checkout 済み branch を共有させない。
- 編集前に package name、owned path、reserved ID または ID prefix、shared file owner、他 package への dependency、予定する finalize 順を記録する。
- 1つの worktree 内で reference を検証できる独立した area / combat / economy package を優先する。dependency と merge 順が明示されていない限り、mob、material、loot chain を worker 間で分割しない。
- 複数 package から shared registry、schema、設計書、common YAML を編集しない。owner を1人に割り当てるか、後続の integration task へ延期する。
- Git が clean merge と報告しても、重複 ID は意味上の conflict として扱う。最新の `develop` に rebase した後、すべての `40_filebase/**/*.yml` を再走査して master ID の重複を確認し、merge 前に package が導入または変更したすべての reference を再検証する。

## 手順

1. 依頼内容を対象 group で分類する。
   - Area package: world、enemy mob、mob spawner、material、equipment、consumable、必要に応じた loot pool/table、shop。
   - Combat package: class、skill、buff、equipment、enemy、loot。
   - Economy package: material、consumable、equipment、recipe、shop、loot。
2. 整合した新規 master の最小集合を定義する。接続のない大きな一覧より、完成した playable loop を優先する。
3. 編集前に ID を確認する。
   - すべての `40_filebase/**/*.yml` から候補 `id` を検索する。
   - lowercase snake_case の ID を使う。
   - prefix は ownership または category の明確化に役立つ場合だけ付ける。ユーザーまたは対象 YAML が示していない area motif を推測しない。
4. schema が定めるディレクトリに YAML file を追加する。1 file につき1つの論理 master とし、ディレクトリが既存の `v1.<id>.yml` 命名を使っている場合はそれに合わせる。
5. reference を解決可能な状態に保つ。
   - 対象 schema に従い、`ref: item:<id>`、`ref: skill:<id>`、`ref: buff:<id>`、`ref: mob:<id>`、loot reference を使う。
   - 新しい mob が新しい material を drop する場合は、先に material を作成する。
   - loot table が新しい pool を参照する場合は、先に pool を作成する。
6. Filebase README が定める安定した `motif` と相対的な `progression` の設計 comment を追加し、category の役割、progression、rarity、入手難度から値を導く。
7. 報告前に、変更したすべての YAML と `作成時チェックリスト.md` を再読する。
8. 並列 package の場合は、後続の finalizer が再検証できるよう、owned path、reserved ID、dependency、shared file の判断、予定する finalize 順を報告へ含める。

## 品質基準

- 作成する各 master は具体的な gameplay purpose を持たせる。
- master data の player 向け string は、日本語 MMORPG の利用者向けに日本語で記載する。`name`、`displayName`、`title`、`label`、`description`、`lore`、mail `body`、NPC interaction `message`、shop name、quest text、その他 player に表示・送信される text を含む。
- technical ID、Bukkit Material name、enum value、reference prefix、tag、implementation identifier は、schema と Plugin が要求する既存の English / uppercase 形式を保つ。
- name と lore は、ユーザーが指定または対象 YAML に記録した motif に従い、未指定の world setting を追加しない。
- ユーザーが starter bundle を明示的に求めない限り、production 追加に `test`、`sample`、`lab`、`starter` などの debug name を使わない。
- ユーザーが実装 support を求めていない限り、または schema が resource reference の存在を要求していない限り、Plugin/API/Web/Resourcepack file を変更しない。
- 必要な実装 feature が不足している場合は、対応していない YAML を作らず、report に制限を記載する。

## 報告形式

結果は日本語で記載する。

```markdown
## 作成結果
- <追加した playable loop / master group の概要>

## 並列所有情報
- package: <単独作業なら不要 / package 名>
- owned paths / reserved IDs: <対象>
- dependencies / finalize order: <なし / 内容>

## 追加・変更ファイル
- `<path>`: <内容>

## 参照整合
- <主要 ref と確認結果>

## 検証
- `<command or manual check>`: 成功 / 失敗 / 未実行（理由）

## 残事項
- なし / <次に作るとよい master や要確認事項>
```
