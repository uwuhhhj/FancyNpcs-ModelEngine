# FancyNpcs-ModelEngine

为 FancyNpcs 增加 ModelEngine 模型、动画与玩家交互的定制版，保留 BetterModel 支持。当前版本为 **FancyNpcs 2.12.2-simmc-me.1 + FancyNpcsModel 1.3.3-simmc-me.1**，作者署名：**OliverSchlueter、Loliiiico**。

基于官方稳定版 [FancyNpcs 2.12.1](https://hangar.papermc.io/Oliver/FancyNpcs/versions/2.12.1)，源码基线为 [`d71e17d5`](https://github.com/FancyInnovations/FancyPlugins/tree/d71e17d5aab44218b45c156af6bfa8c9cdd57465)，模型扩展参考上游 [PR #315](https://github.com/FancyInnovations/FancyPlugins/pull/315)。这是独立维护的非官方定制版，版本号为本项目递增。

主插件保留全部 **7 组 NPC NMS + 7 组 packets 实现及源码**，包括 26.x。验证环境为 **Paper 1.21.11、Java 25、ModelEngine R4.1.1**；26.x 的 ME 集成未验证，本 addon 不支持 Folia。

本版构建与 **51 项单元测试**通过；覆盖平滑追踪、身体跟随、动画分层以及视线高度、缩放与俯仰微调。本次另用原 `ysm_01_jk`、`ysm_02_jk` 验证眼高、运行时头部绑定与并行微动作，报告见 [validation.json](validation.json)。实际玩家观看/点击、资源包显示和多人可见性仍需验证。

### 源码修复：降低模型更新扫描频率（尚未发布）

ModelEngine 附属插件的周期玩家扫描、观察者同步、位置/缩放/碰撞箱同步，以及 NPC/模型有效性检查，改为跟随 FancyNpcs 的 `npc_update_visibility_interval`，最低间隔为 **20 tick（正常 20 TPS 下约 1 秒）**。主插件默认值为 `20`；配置为 `100` 时，附属插件也每约 5 秒扫描一次；更长的主插件间隔也会被尊重。首次绑定模型及明确的模型设置命令仍立即同步。

头部和身体每 tick 继续平滑趋近上次扫描得到的角度，不在这些中间 tick 搜索玩家或候选列表。发现/切换目标及其位置采样允许一个扫描周期的延迟；目标保持和丢失缓冲计时仍按每 tick 推进。观察者出现还受主插件自身扫描影响，两个阶段可能叠加延迟。点击时仍即时检查有效性、可见性及距离。

本次只提交源码修复，不生成安装包，也不递增发行版本。上述 1.3.3 发行验证及现有 `dist/` 包属于修复前版本；后续发布需重新构建、收集验证、递增版本并打包。

源码修复已通过 Gradle 编译、`shadowJar` 构建和 **61 项单元测试**；新增测试覆盖 1~5 秒刷新间隔、间隔重载、tick 计数回绕，以及降低采样频率后的转头与目标计时。本次未进行真人多人压测。

### 源码保护：每个 NPC 的模型观看人数上限（尚未发布）

`settings.modelengine.viewer-protection.enabled` 默认 `true`，`max-viewers-per-npc` 默认 `10`。每个 ME NPC 保留先进入有效可见范围的 10 位观看者，超额玩家看到原来的基础 NPC；空位按等待顺序补齐，持续在范围内的玩家不会因扫描顺序变化被替换。名额针对每个 NPC 分别计算，有效观看者须满足主插件的可见性、权限、世界、距离及实体已显示条件，不使用摄像机朝向或转头追踪范围作为人数判定。

检查沿用上述最低 20 tick 的刷新间隔；新进入、补位及配置重载允许一个刷新周期的延迟。`max-viewers-per-npc: 0` 让所有玩家使用基础 NPC，`enabled: false` 取消人数上限。修改后执行 `/fancynpcsmodel config reload`；不修改 NPC 的 `custom_model` 数据、蓝图或资源包。现有版本升级后缺失字段自动补齐。

基础 NPC 的隐身只在获得模型名额的玩家出站元数据中生效，不再将共享实体全局设为隐形；原有隐身、发光等属性保留。恢复基础 NPC 后仍可使用原交互，模型碰撞箱只接受模型观看者的点击；移除模型、玩家退出及插件关闭会清理隐藏状态。适配器尚未就绪或发生错误的玩家保留基础 NPC。

此保护主要限制模型观察者相关处理与网络发送量。每个 NPC 仍保留一个模型动画实例，不是骨骼计算总量或玩家同屏模型数的全局限制。本功能仅作用于 ModelEngine 提供者，BetterModel 保持原有行为。

源码已通过 Gradle 编译与 **85 项单元测试**（含 Netty 内存通道测试），覆盖 10 人名额、等待与退出补位、配置改变、不同玩家共用数据包时的隔离、基础 NPC 恢复、重连与关闭清理；另核对了本地 Paper 1.21.11 元数据及 Bundle 接口。新增实现仍需在实际客户端检查 11 人以上的显示、点击与补位。本次继续只提交源码，不生成安装包或递增发行版本。

## 安装与使用

从 [Releases](https://github.com/uwuhhhj/FancyNpcs-ModelEngine/releases) 下载两个插件 JAR 或安装 ZIP；同一发布还提供源码 ZIP、SHA-256 校验和与打包报告。

1. 停服，移出旧 FancyNpcs、FancyNpcsModel JAR，保留 `plugins/FancyNpcs/` 数据。
2. 将两个新 JAR 放入服务器 `plugins/`，另行安装 ModelEngine R4.1.1。仅使用 ME 模型时不需要 BetterModel 或 MythicMobs。
3. 用 Java 25 启动 Paper 1.21.11。模型继续放在 `plugins/ModelEngine/blueprints/npc/`；已有模型与 ME 资源包可以继续使用，首次导入才需 `/meg reload models` 并更新资源包。

从上个定制版升级时，仅替换 FancyNpcsModel 为 `1.3.3-simmc-me.1`；FancyNpcs 核心仍为 `2.12.2-simmc-me.1`。保留 NPC 数据和 `plugins/FancyNpcsModel/config.yml`，启动后自动补齐缺少的配置，无需重新创建 NPC 或更新未改动的模型资源包。

原 FancyNpcs 命令保持可用。下面以 OP 创建测试 NPC；模型需要包含示例使用的 `wave`、`sit`、`idle` 动画：

```text
/npc create test_npc
/npc type test_npc player
/npc custom_model test_npc me:ysm_01_jk
/npc action test_npc RIGHT_CLICK add message 你好，我是测试NPC。
/npc action test_npc RIGHT_CLICK add play_animation_once wave
/npc play_animation test_npc sit --loop
/npc play_animation test_npc idle --loop
/npc scale test_npc 0.9
```

右键执行台词和动作；左键使用 `LEFT_CLICK`，任意点击使用 `ANY_CLICK`。动画支持 Tab 补全，`idle --loop` 可切回待机。移除模型用 `/npc custom_model test_npc @none`，删除用 `/npc remove test_npc`。模型动画本身不赋予飞行或爬行移动能力。

确保 FancyNpcs 的 `register_commands: true`。模型设置需要 `fancynpcsmodel.command.npc.custom_model`；添加动作使用原 FancyNpcs 的 action 指令及对应动作权限。

模型生命周期、缩放、位置、可见性和点击校验接入 FancyNpcs。ME 模型的朝向由观察者共享，原生 NPC 初次隐身同步前可能短暂闪现。

### 自然待机与平滑看向玩家

`1.3.3` 修正视线偏高：采用 `Eyes`、`Eye`、`Head` 骨骼的初始高度计算视线起点，未匹配时回退到碰撞箱眼高。两个原模型在 0.9 倍下眼高约为 1.89 格，旧碰撞箱参考点仅 1.30 格，会错误地向上看。视觉眼高随模型缩放，不改动碰撞箱，也不读取已被俯仰旋转的眼睛位置。

新参数位于 `settings.modelengine.head-tracking`：`eye-height.bone-names` 指定眼骨候选；`eye-height.fixed-height-blocks: -1` 自动读取，非负值指定 1 倍模型的眼高；`eye-height.offset-blocks: 0` 修正该高度，随缩放变化；`pitch-offset-degrees: 0` 微调追踪角度，正值更低头，负值更抬头。若修正后仍略看高，可尝试 `2` 或 `3` 度。四个参数均支持 `/fancynpcsmodel config reload`，不重建模型，最大俯仰角仍生效。

`1.3.2` 为没有 h_/hi_ 头部行为的模型补齐运行时绑定：默认按顺序查找 `Head`、`AllHead`，替换当前实例的隐藏 HEAD 占位，添加局部头部旋转并刷新子骨骼继承，头发、耳朵等子骨骼保留原动画。已有显式头部行为的模型沿用原设置。无需修改 `.bbmodel` 或重新打包资源包。

新字段为 `head-tracking.runtime-head-bone.enabled` 和 `head-tracking.runtime-head-bone.bone-names`；改动后重载会重建相关 NPC 模型。速度参数仍支持平滑重载。使用原 `ysm_01_jk`、`ysm_02_jk` 的中文配置和两个测试 NPC 指令见 [测试示例](examples/jk-test-npcs/README.md)，其中提供更快的转头参数和正确的 `pre_parallel1/2/3` 微动作列表。

`1.3.1` 修正上一版固定身体的处理：头部先看向玩家，偏转达到约 35° 并持续 0.3 秒后，身体以最高 90°/秒平滑跟上，直到头身基本对齐。身体跟随保持头部的世界朝向，头和身体由同一套逻辑控制，避免两套转向互相覆盖。启用追踪及设置范围：

```text
/npc turn_to_player test_npc true
/npc turn_to_player_distance test_npc 8
```

待机保留 ME 默认 `idle`，并像酒狐 MM 配置一样同时循环 `ribbon_sway`、`tail_hair_sway`、`blink`；模型缺少的动画会跳过。普通手势、姿态及微动作分层播放：一次动作结束后恢复待机，循环姿态持续到下一次手动切换，`/npc play_animation test_npc idle --loop` 恢复自动待机。随机 `smile`/`nod` 默认关闭，避免频繁全身动作。

修改 `plugins/FancyNpcsModel/config.yml` 后执行 `/fancynpcsmodel config reload`。完整默认配置见 [config.yml](plugins/fancynpcs-model/src/main/resources/config.yml)，所有以下参数位于 `settings.modelengine`：

| 参数 | 默认值及用途 |
| --- | --- |
| `idle.enabled` / `idle.loop-animations` | `true` / 三个微动作；设 `false` 关闭自动微动作与随机手势，动画名称可按模型修改。 |
| `idle.default-states` | `idle/walk/jump/death`；映射 ME 默认状态。 |
| `idle.random-gestures` | `enabled: false`；启用后候选 `smile/nod`，间隔 160~400 tick（8~20 秒）。 |
| `animations.blend-in-seconds` / `blend-out-seconds` | `0.05` 秒，与 MM 的 1 tick 过渡一致；可适当增大以柔化切换。 |
| `animations.speed` / `gesture-speed` | `1.0` / `1.35`；姿态正常速度，普通手势更快响应。 |
| `head-tracking.max-yaw-degrees` / `max-pitch-degrees` | `65` / `35` 度，限制头部相对身体当前朝向的偏转。 |
| `head-tracking.yaw-speed-degrees-per-second` / `pitch-speed-degrees-per-second` / `response-per-second` | `180` / `120` / `8`；降低速度或响应系数会转得更柔和、更慢。 |
| `head-tracking.switch-distance-ratio` / `minimum-target-hold-seconds` | `0.8` / `0.75` 秒；新玩家明显更近后才切换，减少来回看人。 |
| `body-follow.enabled` | `true`；身体随后跟上。关闭后保持身体当前朝向，头部仍可追踪。 |
| `body-follow.start-angle-degrees` / `stop-angle-degrees` | `35` / `2` 度；达到起转角度后开始，持续跟到基本对齐，避免反复启停。起转角度自动限制到头部角度上限。 |
| `body-follow.delay-seconds` | `0.3` 秒；头部达到起转角度后等待多久。 |
| `body-follow.yaw-speed-degrees-per-second` / `response-per-second` | `90` / `5`；分别限制身体转速和调整身体平滑程度，身体始终追随已转动的头部。 |
| `body-follow.pause-during-pose` | `true`；坐下等手动姿态期间暂停转身，头部可继续看人。 |

`head-tracking.enabled: false` 可关闭头部追踪；死区、目标离开缓冲和范围边界参数见完整配置。`sleep/nod/talk/wave` 默认暂停叠加追踪，让动作自身控制头部；姿态期间默认暂停随机手势。

玩家离开后，身体保持刚才的朝向，头部平滑回正；插件不会每 tick 把身体拉回保存的摆放角度。主动修改 NPC 摆放朝向时会采用新方向。头部和身体参数支持配置重载，微动作循环不受身体参数调整影响。

## 源码构建

Gradle 项目位于仓库根目录，包含全部相关 NMS 分支及共享库。使用 **JDK 25** 运行 Gradle；完整构建会下载各版本 Paper dev-bundle，其他 Java 工具链按模块/dev-bundle 的要求配置。ModelEngine 与 BetterModel JAR 是外部编译依赖，不随仓库或发行包分发。准备合法取得的 **ModelEngine R4.1.1** 和 **BetterModel 3.5.0** JAR，以绝对路径构建：

```powershell
./gradlew.bat :plugins:fancynpcs-v2:shadowJar :plugins:fancynpcs-model:test :plugins:fancynpcs-model:shadowJar '-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT' '-PmodelEngineJar=C:/deps/ModelEngine-R4.1.1.jar' '-PbetterModelJar=C:/deps/bettermodel-3.5.0-paper.jar'
```

Linux/macOS 使用 `./gradlew`。也可将依赖放入仓库根目录的 `deps/`，文件名与上述示例一致。构建产物位于 `plugins/fancynpcs-v2/build/libs/` 和 `plugins/fancynpcs-model/build/libs/`。

Git 检出使用当前 HEAD 生成元数据；不含 `.git` 的源码归档使用根目录 [SOURCE_COMMIT](SOURCE_COMMIT)。如需复现本次发布元数据，追加 `'-PsourceCommitHash=d71e17d5aab44218b45c156af6bfa8c9cdd57465'`；采用其他提交重新构建时，JAR 哈希会变化，应更新验证报告后再打包。

默认构建完整源码。上述 `-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT` 固定本次发布的核心 API；省略时采用上游默认 26.2 API。本次发布构建另使用可选 `-PupstreamNmsJar` 复用官方 2.12.1 JAR 中 **432 个字节保持不变的实现类（7 组 NPC + 7 组 packets）**；核心、API、加载器及插件元数据仍从本项目构建，全部 NMS 源码保留。官方下载地址与哈希记录在 [upstream-release.json](upstream-release.json)。取得对应官方 JAR 后，在仓库根目录使用 Python 3.11+ 提取：

```powershell
python tools/extract_upstream_nms.py --upstream-jar C:/deps/FancyNpcs-2.12.1.jar --release-json upstream-release.json
```

工具验证官方版本、提交、哈希和原始类字节，输出根目录 `build/deps/FancyNpcs-2.12.1-upstream-nms.jar` 及同目录的 `FancyNpcs-2.12.1-upstream-nms.manifest.json`。在上面的 Gradle 命令中追加 `'-PupstreamNmsJar=提取结果的绝对路径'` 即可复现该构建路径；省略则编译全部源码模块。

Windows 中文路径出现启动器问题时，可将 `-Djdk.net.unixdomain.tmpdir` 和可选 `-PtestClasspathDir` 指向短英文目录。在仓库根目录执行 `python tools/package_release.py` 生成发布包到 `dist/`；工具核验当前两个 JAR 与 `validation.json` 的哈希。依赖、缓存、日志及构建目录不上传。

## 许可与来源

源码沿用 [MIT 许可](LICENSE)，保留上游版权 **Copyright (c) 2025 Oliver Schlüter**；定制维护与集成为 **Loliiiico**。上游项目、PR 提交及外部依赖说明见 [NOTICE](NOTICE)。ModelEngine 和 BetterModel 按各自许可取得并独立安装，本项目不分发它们的 JAR。
