## v1.1.8 门禁安全修复

### 🔴 严重修复

- **幽匿之惧误触发**：里程碑 `warden` 的 `advancement` 字段误配为 `minecraft:adventure/kill_a_mob`（原版"怪物猎人"成就——杀任意敌对怪触发），导致击杀任何怪物都会解锁幽匿之惧。现改为仅依赖 `first_kill` 触发器（实体限定 `minecraft:warden`）
- **凋零之陨误触发**：里程碑 `wither` 的 `advancement` 字段误配为 `minecraft:nether/summon_wither`（原版"召唤凋零"成就），导致召唤凋零即可解锁。现改为仅依赖 `first_kill` 触发器（实体限定 `minecraft:wither`）
- **无限跳跃 Bug**：`JumpInputHandler` 客户端侧在空中按住空格时无条件调用 `jumpFromGround()` 并发送二段跳请求，没有检查 `void_step` 能力。导致所有玩家（包括未激活冒险者的新玩家）在空中都能无限弹跳。现增加 `isAdventurer() && isAbilityEnabled("void_step")` 门禁

### 🟡 防御性加固

- `milestones.json` 前 4 个里程碑的 `advancement` 字段清理：从指向不存在的模组成就改为 `null`
- `AbilityTogglePacket` 服务端增加 `isAdventurer/isFullyUnlocked` 门禁（与 `BuffTogglePacket` 保持一致）
- `catchUpMissedMilestones` 新增 `first_death`/`first_trade`/`first_kill` 三种 trigger 类型的追赶逻辑（通过玩家统计数据检测）
- `executeJudgment` 增加防御性门禁，防止未来可能的无权限调用
- `AdventureProgressCapability.onPlayerTick` 增加非冒险者提早 return，减少无效计算
