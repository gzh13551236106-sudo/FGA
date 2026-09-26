# FGA 定制开发计划

## 工作区基线与环境

- 当前开发分支：`codex`。交付环境没有 Git 远端，也不含用户给出的 PR head `108062066d32313403e463f776d24e13db27e574` 对象；本轮只能在提供的第一轮快照上追加提交，不能在本地验证或推送 PR #1。
- 未创建、重置、改写或合并 `master`。恢复网络后必须先比较远端 `codex` 与本地提交，采用普通快进/合并方式保留远端变更，禁止强推。
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

1. **统一恢复状态**：`@ScriptScope RecoveryWatchdog` 以单调时钟保存 UI 状态、有效进展、wave/turn/action、动作状态和共享预算；`AutoBattle` 对未知/停滞画面只重新截图并在预算耗尽后安全停止，不在未知页面点击。详情页、技能和卡牌恢复共用该预算，网络弹窗仍复用 `ConnectionRetry`。
2. **动作幂等边界**：技能、宝具选择和换人记录 `NotSent/Sent/Confirmed/Failed/Unknown`；`Sent` 或 `Unknown` 不允许盲目重发。动画、助战和结算仍由同步 handler/既有有界等待处理，后续审计需重点验证极慢设备的阈值。
3. **奥尔加玛丽 NP 前置检查**：通过实际截图 OCR 读取场上槽位 NP 文本，最多三次；详情头像与内置 U-Olga 支援头像匹配以跟随换人后的 `TeamSlot -> FieldSlot` 映射。NP 小于 100 或 OCR 未知均在点击前保守跳过并区分记录。坐标目前只可依据仓库 KR 战斗参考图验证，仍需要真实多服务器/宽屏截图回归，不能视为设备实测完成。
4. **结构化读卡降级**：结果区分 `Normal/Degraded/NeedsRecovery/Unsafe`。归属未知或单张卡色未知允许本回合稳定降级；两张以上卡色未知或卡位不完整才进入共享恢复。重新进入 Attack 后仍不安全则给出可诊断停止，不盲点。
5. **测试状态**：已增加 watchdog、NP 文本/阈值和读卡分类的 JVM 测试源码；当前 Maven Central HTTP 403 阻止依赖解析，因此这些测试尚未实际编译或运行，不能标记为通过。

每一步开始前应再次阅读坐标、资源、翻译或依赖对应的 `docs/agents/` 指引；若新增坐标或模板，必须遵守 720p 匹配、1440p 坐标及服务器资源回退规则。
