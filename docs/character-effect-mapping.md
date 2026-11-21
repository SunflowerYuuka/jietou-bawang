# 四角色 → 通用效果的接入方案

这份文件记录**每个角色的每个技能应该调用哪个通用效果、几档**。等 `agent-3` 的通用效果内核落地后，按这张表改 JSON。

> **状态：已落地。** 四个角色的 JSON 已按本表改写，`CharacterEffectWiringTest` 把下面每一行都变成了可执行的
> 断言（三刀轮转 + 八档 UB 的效果清单与档位），`docs/balance-derivation.md` §13 记录了最终数值与换算。
> 落地过程中与本表不同的地方只有一处，在 §「落地时的偏离」里说明。

数值档位由用户确定：
- 范围瞬回 / 自我瞬回：**5 档，每档 20% 最大生命**
- 范围攻击提升：**5 档，每档 +20% 攻击伤害**
- 范围物理/魔法防御提升：**5 档，每档 12% 减伤**（满档 60%，不取无敌）
- 破甲：**4 档，每档 20% 护甲**（满档 80%）
- 破盾：**1 档，100%**
- 加速：**3 档**
- 恐慌：**1 档，1.5 秒**
- 百分比伤害：**5 档**
- TP 提升率：**5 档**

---

## 姬塔 `astrum:djeeta`

原作：广域斩击（单体物理）、剑意迸发（自身 TP 回复）、UB 疾风剑雨/星河一天

| 技能 | 现在 | 改成 | 理由 |
|---|---|---|---|
| 广域斩击 | `extra_damage 6.7` | **保持 `extra_damage`** | 单体物理，无更贴切的通用效果 |
| 剑意迸发 | `attack_buff` + `gain_charge` | **`charge_rate_up` 2 档** | 原作是「自身 TP 特大回复」，`charge_rate_up` 才是"提高 TP 获取率"；保留 `attack_buff` 因为原作也提升物攻 |
| UB 6★ 星河一天 | `attackBuff 1.5/18s` | **改成 `buff_attack_area` 3 档**（+60%，18 秒） | 原作是「味方全体の物理攻撃力を特大アップ」，是**全队**不是自己 |

> ⚠️ 姬塔 UB 的 `attackBuff` 目前写在自己身上，原作其实是全队。这次一并修正。

## 可可萝 `astrum:kokkoro`（コッコロ）

原作：トライスラッシュ（单体物理）、スピードアップ（全队行动速度 + 物攻）、UB オーロラ（全队攻防 + 自回血）

| 技能 | 现在 | 改成 | 理由 |
|---|---|---|---|
| トライスラッシュ | `extra_damage 11.6` | **保持** | 单体物理 |
| スピードアップ | `ally_effect minecraft:speed` + `attack_buff` | **`haste` 2 档** + **`buff_attack_area` 1 档** | 原作是「行动速度 + 物攻」，`haste` 正好覆盖移速+攻速；物攻用范围 buff |
| UB オーロラ（6★前） | — | **`buff_attack_area` 1 档**（全队）+ **`heal_self` 2 档** | 原作：味方全体攻撃力小アップ + 自分HP中回復 |
| UB オーロラブルーミング（6★） | — | **`heal_area` 3 档** + **`buff_attack_area` 3 档** + **`buff_phys_defence_area` 2 档** | 原作：味方全体HP中回復 + 攻撃力大アップ + TP上昇 |

## 佩可莉姆 `astrum:pecorine`（ペコリーヌ）

原作：ランチタイム（自魔防 + 自回血）、フォールスラッシュ（自物攻 + 单体物理 + 物防减 + 持续回血）、UB プリンセスストライク（单体物理 + 自物魔防）

| 技能 | 现在 | 改成 | 理由 |
|---|---|---|---|
| ランチタイム | `heal 6.9` + `ally_effect resistance` | **`heal_self` 3 档** + **`buff_magic_defence_area` 2 档（radius 0）** | 原作是**魔防**提升（不是全防）；回复按百分比更贴切 |
| フォールスラッシュ | `attack_buff` + `extra_damage` + `defence_down` + `regeneration` | **`buff_attack_area` 2 档（radius 0）** + `extra_damage 11.9` + **`defence_down` 1 档** + 保留 `regeneration` | 原作确实有持续回血，`ally_effect regeneration` 合适 |
| UB プリンセスストライク | — | `extra_damage`（走 UB 伤害）+ **`buff_phys_defence_area` 2 档** + **`buff_magic_defence_area` 2 档**（radius 0） | 原作：自分の物理防御力と魔法防御力を大アップ |

## 凯露 `astrum:kyaru`（キャル）

原作：サンダーボール（单体魔法）、アーマーダウン（单体物魔防减）、UB グリムバースト（**全体魔法**）

| 技能 | 现在 | 改成 | 理由 |
|---|---|---|---|
| サンダーボール | `extra_damage 11.3` | **`extra_damage` + 魔法标记** | 她是魔法角色，伤害要带 `astrum:magic` |
| アーマーダウン | `defence_down amplifier 0` | **`defence_down` 2 档**（40%） | 原作「小ダウン」，2 档合适 |
| UB グリムバースト | `radius 6.0` 的 UB 伤害 | 保持，但**伤害带魔法标记** | 原作是全体魔法伤害 |
| UB 6★ アビスバースト | `victimEffect defence_down amplifier 2` | **`defence_down` 3 档** + **`panic` 1 档** | 原作：物理・魔法防御力を大ダウン + 恐慌 |

---

## 落地时的偏离（只有一处）

**佩可莉姆 6★ UB 的 `heal 3.4` 没有保留。** 本表对 プリンセスストライク 只列了 `extra_damage` + 两个防御
提升，而旧数据里 6★ 还有一条 `heal { amount: 3.4 }`（`balance-derivation.md` §6 的「小回復」）。我按表
把它删了，理由是：本表是这次任务的规格，而 3.4 那条回复在原文里属于「バリア解除 + 最大HP比例ダメージ」那一组
未实现成分旁边的近似，不是这个必杀的主功能。**如果它其实该留**，正确的写法是在 `pecorine.json` 的
`ub.enhanced.effects` 里加一条 `{"scope":"ally","effect":"heal_self","effectParams":{"tier":1}}`（= 20% 上限，
比 3.4/20 = 17% 略强一档），并在本表补一行。已经写在这里而不是只写进报告，是因为下一个作者会先读这份表。

---

## 待确认（原始记录，保留）

1. **姬塔 UB 的 `attackBuff` 要不要确实改成全队**？原作 gamewith 明确写「味方全体の物理攻撃力を特大アップ」。改了会让她从"自嗨型"变成"团队核心"。
   → **已改**：`ub.enhanced.effects` = `buff_attack_area` 3 档（+60%，18 秒），实测施放者与 2 格内队友都从
   attack 1.0 变 1.6（见 `task-32-report.md` 的 PROBE 行）。
2. **凯露 UB 的 `radius 6.0`** 是全体伤害——新系统里 `splash` 也能做范围伤害，要不要统一？
   → **保持 `radius 6.0`**：它是 UB 自己的伤害通道（`UbDamage.plan` 一次结算、带自己的伤害类型、
   走 `bypasses_cooldown` 标签所以多段不会被无敌帧吃掉）。换成 `splash` 会把这套一次结算的语义搬进 hook
   管线，而 hook 管线的伤害是「同一 tick 合并成一次 `hurt()`」，与 UB 的「N 段 × 每段独立命中」相反。
3. **佩可莉姆的 `regeneration`**（持续回血）保留原版药水效果，还是也做一个通用 `REGEN` 效果？
   → **保留 `ally_effect minecraft:regeneration`**：原版的 `regeneration` 就是「継続HP回復」这个机制本身，
   不是近似（见 `balance-derivation.md` §12）。通用效果里没有 `REGEN`，造一个只会是同一个东西的第二个名字。
