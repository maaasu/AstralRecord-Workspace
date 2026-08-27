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

## 表示文と lore の使い分け

- `description` はスキルの主目的や印象を伝える抽象的な一文だけを記載します。原則として表示上1行に収まる長さ（目安40文字以内）にし、具体的な効果詳細、数値、発動条件、対象数、持続時間、例外処理、消費やクールダウンは書きません。
- 効果の詳細、条件、対象、数値、状態異常、発動・中断条件は `lore` へ移します。1行1要素を基本とし、変動する値は `params` のプレースホルダーで表示します。
- 消費リソース、クールダウン、詠唱時間などの共通値は top-level 項目と GUI の専用表示を正とし、`description` や `lore` へ重複記載しません。
- Mob 用など詳細説明を必要としない定義は、短い `description` のみでも構いません。具体的なプレイヤー向け説明が必要な場合は、必ず `lore` を追加します。

## 現行の職業発動スキル

| 職業 | コンセプト | 主リソース | 主な当たり判定 |
|:--|:--|:--|:--|
| `adventurer` | 近接・間接・魔法の基礎操作を試す見習い | `ENERGY` / `MANA` | 扇形、単体飛翔体、単体対象 |

現行定義は冒険者の6skillとソードマンの `swordsman_shield_drain` / `swordsman_bastion_strike` / `swordsman_flame_rush` / `swordsman_challenging_roar` を含みます。前3者はソードマンの `usableSkills` へ追加せず、それぞれskilltree node `1202` / `1203` / `1204` のskill effectとAdministratorの `usableSkills` から使用許可を与えます。`swordsman_challenging_roar` はソードマンとAdministratorの `usableSkills` から使用許可を与えます。クラスやskilltreeは使用許可だけを与え、習得済み個体の作成はジェム消費に限定します。

`swordsman_shield_drain` は敵Shieldへの3倍ブレイクと実減少量50%の自己Shield吸収を行い、発動時に自身のShieldが最大ならダメージ倍率を追加100%します。

`swordsman_bastion_strike` は前方単体を攻撃し、発動時に自身の現在Shieldを最大Shieldまで即時回復します。発動時の現在MPは全量消費し、固定MPコストは重ねません。

`swordsman_flame_rush` は前方最大5体へ火属性の二連撃を行い、Lv.8以降の二撃目だけに炎上判定を持ちます。`swordsman_challenging_roar` は周囲のMobを一時的に挑発する非攻撃スキルです。

## Administrator専用のドッジ連動パッシブ

`administrator_just_dodge` は `passive.bindRequired: true` のAdministrator専用パッシブです。成功したドッジから `params.invulnerabilityTicks` tick の間、`NORMAL_ATTACK` / `SKILL` の直接攻撃を無効化します。無効化回数に上限は設けず、状態異常DoTは `DamageService.applyConditionDamage` の専用経路であるため対象外です。

無効化時は既存のドッジパーティクルとシールドブロック音を表示し、同じドッジ中の最初の無効化時だけ `params.energyRecoveryAmount` のENを回復します。Lv.1・最大Lv.1の設定は無効化時間8 tick、EN回復量10です。ダメージを与えないためDPS算出対象外で、入手用ショップ商品やスキルツリーへの追加は行わず、使用許可だけをAdministrator classへ与えます。

`adventurer_lightning_bolt` は、通常時は単体へ `MAGIC` / `LIGHTNING` を適用し、命中対象から半径5m以内にいる別の `SHOCKED` 状態の Mob へだけ最大2体を連鎖させる。連鎖は距離順・UUID順、視線遮蔽なしの対象を選び、連鎖先からの再連鎖と本スキルによる `SHOCKED` 付与は行わない。

`adventurer_mana_burst` は前方扇形へ無属性の `MAGIC` ダメージを瞬間放出する基礎範囲魔法です。

## 条件付きリソース回復パッシブ

冒険者の `adventurer_meditation` は、`passive.bindRequired: true` のバインド必須パッシブです。YAML の `params.chargeTicks: 100` と `params.regenMultiplier: 3` は Plugin executor が固定値として検証し、スキルレベルでは変更しません。`chargeParticleIntervalTicks` / `activeParticleIntervalTicks` は控えめな予兆・維持演出の間隔、`activeSoundIntervalTicks` は発動中の環境音の間隔だけを指定します。環境音は `World#playSound` / `SoundCategory.PLAYERS` で音源位置の周囲へ再生し、効果対象の自然回復倍率は自身のみです。

このスキルは最大値や固定回復量を持たず、Plugin の自然回復処理が持つ MP / EN の既存回復量へ条件付き倍率を適用します。スニーク解除、被弾、通常攻撃、他スキル使用で runtime 状態を破棄し、再発動には再度100 tickの継続が必要です。

## progression

標準的にジェムを入手できる段階、またはclass / skilltreeから使用許可を得る段階を記載します。装備・ルーン・セット効果はスキルを付与しません。

## 正本参照

- 戦闘・ゲームバランス: ダメージ、回復、防御、状態異常、コスト、クールダウンなど性能に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\30.features.skill\docs.skill.YAMLスキーマ定義.md`
- Plugin 設計: `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\13-skill`
