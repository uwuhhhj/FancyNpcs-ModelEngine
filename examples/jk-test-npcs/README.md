# 两个酒狐模型的测试 NPC

适用于 FancyNpcs 2.12.2-simmc-me.1、FancyNpcsModel 1.3.2-simmc-me.1 和 ModelEngine R4.1.1。此目录包含中文配置和指令，继续使用原模型，不生成专用蓝图。

| NPC 名称 | ModelEngine 模型 ID | 默认大小 |
| --- | --- | --- |
| `test_jk_01` | `ysm_01_jk` | 0.9 倍 |
| `test_jk_02` | `ysm_02_jk` | 0.9 倍 |

1. 替换 FancyNpcsModel 为 1.3.2 后重启，保留 FancyNpcs 核心和 NPC 数据。使用已加载的原模型，不需要重新打资源包。
2. 合并本包 `plugins/FancyNpcsModel/config.yml`，执行 `/fancynpcsmodel config reload`。配置影响全部 ME NPC，不控制 MEPlayerActions 的玩家伪装。
3. 已有两个测试 NPC 时，按 `commands/update_existing_npcs.txt` 恢复待机。首次创建则以 OP 站到两个位置，分别逐条运行两个 create 文件；确保 FancyNpcs 的 `register_commands: true`。
4. 右键发送台词并挥手，左键点头，点击冷却为 1 秒。调试文件按需单条执行，`idle --loop` 恢复自动待机。

`head-tracking.runtime-head-bone` 在模型没有显式头部行为时按顺序匹配 Head、AllHead，替换当前 NPC 实例的隐藏 HEAD 占位，使头发、耳朵等子骨骼跟随，同时保留微动作。已有 h_/hi_ 行为的模型沿用原设置。修改这两个新字段后重载会重建相关 NPC 的模型实例；普通速度参数重载继续平滑生效。

本配置采用更快的响应：头部水平最高 360°/秒、俯仰 240°/秒、响应系数 14；身体延迟 0.15 秒、最高 150°/秒、响应系数 8。上限仍为水平 65°、俯仰 35°。验证抬头/低头时，站到 NPC 前方较高或较低的位置，保持在 6 格范围内；同一高度时俯仰差很小。wave/nod/talk/sleep 等动作期间会暂停追踪。

头顶只显示“看不见需要更新材质包”。三个微动作分别为飘带与侧发、尾发与耳朵、眨眼；随机微笑和点头默认关闭，可开启 idle.random-gestures.enabled。

不要反复运行创建文件，以免重复添加交互。删除测试 NPC 用 `/npc remove test_jk_01` 或 `/npc remove test_jk_02`。动作展示不赋予实际行走或飞行能力。文件编码为 UTF-8。
