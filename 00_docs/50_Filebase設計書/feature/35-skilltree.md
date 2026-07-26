# Skilltree 設計

## 役割

Skilltree は、skill や能力解放の順序、前提関係、選択分岐を定義します。ノード定義と配置・接続構造は filebase JSON を正本とし、プレイヤー別の解放状態は `account-skilltree` API / DB を正本とします。

## 設計方針

- 開始ノード、前提ノード、排他または分岐の意味を明確にします。
- 一本道だけでなく、同程度の進行度に役割の異なる選択肢を用意します。
- 後続ノードが前提ノードの役割を単純に無効化しないようにします。
- 存在しない skill や未実装の解放条件を参照しません。
- ノード効果は `effects[]` に統合し、`skill` と `status` を `type` で識別します。
- ノードには任意の `unlockCondition.classId` と `unlockCondition.playerLevel` を設定できます。職業レベル条件は持ちません。
- クラス条件は現在職そのもの、または `unlockClassLevel[].class` を再帰的に辿った祖先職なら成立します。複数経路のいずれかで祖先に到達できれば成立します。
- 条件不成立ノードは通常プレイヤーから非表示とし、解放済みでも効果を停止します。
- `nodeId` は Git 管理の `node-id-sequence.json` を高水位としてエディターが自動採番し、欠番を許容して削除済み ID を再利用しません。Plugin は採番状態を読み込みません。
- 配置は構造 JSON で `nodeId` と相対座標を直接対応させ、`positionId` は使用しません。
- edge は無向接続として端点だけを保持し、edge ID は持ちません。
- 自動配置は編集補助に限定し、採用した最終座標を必ず構造 JSON へ明示保存します。
- Plugin は JSON を読み取り専用で使用し、ゲーム内からノード定義や構造を書き換えません。

## 開発者用エディター

- ローカル Web エディターは `60_tool/skilltree-editor/` に配置します。
- ノード定義、配置、接続の編集は同エディターから行います。
- JSON Schema と意味検証に成功したデータだけを保存し、保存前ファイルをバックアップします。
- JSON Schema が追加された場合、汎用 JSON テキスト編集と Schema 検証をフォールバックとして使用します。
- ゲーム内表示シミュレーションでは現在職とプレイヤーレベルを指定し、Plugin と同じ祖先職・レベル条件でノードと両端が可視な edge だけを表示します。
- Plugin 設定編集はリポジトリ上の `10_plugin/AstralRecord/src/main/resources/config.yml` を対象とします。稼働環境の設定と filebase へデプロイまたは同期した後、`/masterdata reload` で反映し、エディターから稼働サーバーへ直接書き込みません。
- ステータス効果は`75.shared.status`の日本語名と整形済み値を一覧・キャンバスのホバー・詳細へ表示し、編集候補も日本語名付きで提示します。保存JSONには従来どおり不変IDだけを記録します。
- スキル効果は`30.features.skill`から日本語名・説明・種別を読み、一覧・キャンバスのホバー・詳細へ表示します。保存JSONには従来どおり`skillId`だけを記録します。
- キャンバスのノード表示サイズは32～140pxで変更でき、ブラウザのローカル設定へ保持します。構造JSONのブロック座標やPlugin表示サイズは変更しません。

## progression

ノードを標準的に解放できる段階は、タグ、接続、コストとカテゴリ設計を合わせて判断します。後続ノードを前提ノードより早い段階へ配置しません。

## 正本参照

- JSON 契約: `E:\AstralRecord-Workspace\40_filebase\35.features.skilltree\docs.skilltree.JSONスキーマ定義.md`
- JSON Schema: `E:\AstralRecord-Workspace\40_filebase\35.features.skilltree\schemas\`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
- API 設計: `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\20-skilltree`
