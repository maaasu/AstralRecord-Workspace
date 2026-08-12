# Skill 設計

## 役割

Skill は、プレイヤーまたは Mob が実行する能動・受動能力と、自動生成するスキルジェムを定義します。

## 設計方針

- 1 skill につき、攻撃、防御、移動、回復、補助の主目的を1つ定めます。
- 発動条件、対象、コスト、クールダウン、効果範囲を組み合わせて強さを制御します。
- class や equipment の弱点を無条件で消す効果は避けます。
- 実行可能な action と値は Plugin 設計・実装を正とします。
- プレイヤー向け職業発動スキルの ID は、職業 prefix と lowercase snake_case を組み合わせ、`implementationId` と同じ値にします。
- リソース種別・消費量・クールダウン・詠唱時間などの共通値は top-level 項目へ定義し、`params` は実装固有の拡張が本当に必要な場合だけ使用します。
- 当たり判定、倍率、状態異常、演出はスキルごとの Plugin 実装へ型付きで定義し、YAML へ同じ既定値を大量に複製しません。プレイヤーが判断するために必要な効果値は `description` / `lore` と実装を同期します。
- 攻撃演出は当たり判定の形と発生時刻が読み取れる輪郭を優先し、持続パーティクルを常時大量に表示しません。
- API は全スキルから決定的 ID `00_skill_gem_<skillId>` の非スタック型ジェムを自動生成します。ジェム自身はレベルを持ちません。生成後IDはインベントリ列に合わせ100文字以内とし、loot・quest等のitem参照検証でも有効な仮想itemとして扱います。
- 全skill masterで `gem` と空でない `gem.rarity` を明示し、レベル別status補正には共有カタログの既知status IDだけを指定します。
- ジェムの左クリック習得は常に Lv.1 の新しい個体を作り、同じスキルの既存個体を上書きしません。既存個体のレベルアップはスキルマネージャー内の合成だけで行います。
- レベル差分は `levels` へ前レベルからの増分として定義し、最大レベルはスキルごとの `maxLevel` を正本とします。
- `allowedSigilIds` と `sigilSlotsByLevel` で、消費装着可能なシジルとレベル別枠数を定義します。

## 現行の職業発動スキル

| 職業 | コンセプト | 主リソース | 主な当たり判定 |
|:--|:--|:--|:--|
| `adventurer` | 近接・間接・魔法の基礎操作を試す見習い | `ENERGY` / `MANA` | 扇形、単体飛翔体、単体対象 |

現行定義は冒険者の6skillに加え、ソードマンの `swordsman_blade_counter` を含みます。専門職skillはclassの `usableSkills` へ追加せず、skilltreeのskill effectで使用許可を与えます。クラスやskilltreeは使用許可だけを与え、習得済み個体の作成はジェム消費に限定します。

`swordsman_blade_counter` はEN 20、詠唱20 tick、cooldown 600 tickで400 tickのバフを開始します。通常攻撃直後10 tickの受付中に管理対象Mobの直接hitを受けると、damageを50%へ軽減して `MELEE` / `NONE` 100%で反撃します。最大反撃回数はSkill Lv.1～5で `3 / 3 / 4 / 4 / 5` です。

`adventurer_lightning_bolt` は、通常時は単体へ `MAGIC` / `LIGHTNING` を適用し、命中対象から半径5m以内にいる別の `SHOCKED` 状態の Mob へだけ最大2体を連鎖させる。連鎖は距離順・UUID順、視線遮蔽なしの対象を選び、連鎖先からの再連鎖と本スキルによる `SHOCKED` 付与は行わない。

`adventurer_mana_burst` は前方扇形へ無属性の `MAGIC` ダメージを瞬間放出する基礎範囲魔法です。

## 条件付きリソース回復パッシブ

冒険者の `adventurer_meditation` は、`passive.bindRequired: true` のバインド必須パッシブです。YAML の `params.chargeTicks: 100` と `params.regenMultiplier: 3` は Plugin executor が固定値として検証し、スキルレベルでは変更しません。`chargeParticleIntervalTicks` / `activeParticleIntervalTicks` は控えめな予兆・維持演出の間隔、`activeSoundIntervalTicks` は発動中の環境音の間隔だけを指定します。環境音は `World#playSound` / `SoundCategory.PLAYERS` で音源位置の周囲へ再生し、効果対象の自然回復倍率は自身のみです。

このスキルは最大値や固定回復量を持たず、Plugin の自然回復処理が持つ MP / EN の既存回復量へ条件付き倍率を適用します。スニーク解除、被弾、通常攻撃、他スキル使用で runtime 状態を破棄し、再発動には再度100 tickの継続が必要です。

## progression

標準的にジェムを入手できる段階、またはclass / skilltreeから使用許可を得る段階を記載します。装備・ルーン・セット効果はスキルを付与しません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\30.features.skill\docs.skill.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
