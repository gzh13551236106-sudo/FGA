# FGA 定制开发计划

## 工作区基线与环境

- 当前开发分支：`codex/fga-custom-recovery`，创建点为 `8483b2f1be9d71a68d6ecf89b40917f6234bf9eb`。
- 此交付环境只有 `work` 分支，没有本地 `master` 引用且没有配置远端；`work` 在上述提交上且初始工作树干净。因此本分支以该只读快照为基线，未创建、重置或改写 `master`。在有远端的环境中继续工作前，应先核对该提交与 `gzh13551236106-sudo/FGA` 的 `master`。
- 写入测试已在开发分支通过，临时文件已删除。
- 项目要求 Gradle 9.7.1、Java 21 toolchain，并输出 Java 11 字节码；当前 shell 是 OpenJDK 25.0.2，Gradle wrapper 可启动，但未安装/配置 Android SDK（项目使用 `compileSdk 37`，应用 `minSdk 24`、`targetSdk 36`）。
- 最小检查 `./gradlew :scripts:test --no-daemon` 未完成：Maven Central 返回 HTTP 403，KSP、Kotlin stdlib 和 coroutines 依赖无法解析；这不是编译或测试通过。恢复网络/依赖缓存并提供 JDK 21 toolchain 后应重跑该命令；Android 应用检查还需配置含 API 37 的 SDK 后运行 `./gradlew :app:compileDebugKotlin`。

## 已验证的实际入口与职责

- `scripts/.../entrypoints/AutoBattle.kt`：顶层屏幕检测/处理循环；网络重试已排在 detector map 首位，是统一看门狗协调恢复动作的自然入口。
- `scripts/.../modules/Battle.kt`：每回合推进、技能执行、Attack 页面进入、洗牌和出卡串联。
- `scripts/.../modules/Card.kt`、`CardParser.kt`、`FaceCardPriority.kt`、`ApplyBraveChains.kt`：分别负责读/选/点击指令卡、解析卡色/克制/从者归属、优先级排序和同从者连携；识别失败当前只发通知并继续返回含 `Unknown` 的结果。
- `scripts/.../modules/ServantTracker.kt`：维护场上槽位映射，并通过打开从者详情页采集头像模板；详情页的打开、INFO、取样、关闭目前只有固定短等待，适合增加有界状态确认与恢复。
- `scripts/.../modules/Caster.kt`：技能/宝具/指令卡点击、目标选择、技能确认及动画等待；特殊技能限制检测应落在这里或其调用链，而不是绕过现有施放状态机。
- `scripts/.../modules/Support.kt` 及 support-selection 子模块：助战选择流程；需确认详情页恢复是否同样覆盖助战页面，但不可混淆助战选择与战斗内从者追踪。
- `scripts/.../modules/ConnectionRetry.kt`：现有网络弹窗探测与点击后等待；RecoveryWatchdog 应复用它，不复制网络重试判断或点击逻辑。
- 依赖方向保持 `app -> scripts -> libautomata`；功能逻辑和 JVM 测试应放在 `scripts`，Android 文案映射留在 `app`。

## 实施状态与后续建议

1. **已实现详情页防卡**：`ServantTracker` 会确认详情页已打开，再采样头像；关闭时最多重试三次并确认战斗控件恢复，耗尽后明确终止而不是无限卡住。
2. **已实现技能限制恢复**：`SkillRestrictionGuard` 根据游戏是否接受技能点击判断特殊发动条件（首个目标是 NP 不足 100% 的奥尔加玛丽三技能），条件不足时关闭提示并跳过；技能动画未能回到战斗页时也执行有界恢复。
3. **已新增独立选卡模式**：`SameServantBusterQuickArts` 优先宝具使用者，再按同从者三张/两张聚合，并在组内采用 Buster、Quick、Arts 顺序；既有模式保持不变。
4. **已实现指令卡识别恢复**：识别结果必须包含五张可信卡；失败后先重新截图解析三次，再退出并重进 Attack 页面进行最后一轮有界重试，禁止在不可信结果上盲点。
5. **后续统一 watchdog**：当前恢复均有明确上限并写入结构化日志；下一步可将这些局部策略汇入每次运行一个实例的 `RecoveryWatchdog`，并复用现有 `ConnectionRetry`。

每一步开始前应再次阅读坐标、资源、翻译或依赖对应的 `docs/agents/` 指引；若新增坐标或模板，必须遵守 720p 匹配、1440p 坐标及服务器资源回退规则。
