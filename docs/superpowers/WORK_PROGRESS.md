# TingBili 持久化工作进度 — 可恢复执行（抗掉线）

> **用途**：无论任务执行是否成功，下次会话仅读本文件 + `git log` 即可恢复，无需回灌历史 `diff`（`~50KB×12` → `<5KB`），保证 `opencode NodeService` 不因上下文膨胀而 `sidecar exited code:0`。
> **更新规则**：每 `git commit` 后由控制器追加 1 行；`gradle` 仅 `assemble --no-daemon` 为门禁，重型 `test` 延至 CI。

## 恢复方式（3 条命令）
```bash
cat docs/superpowers/WORK_PROGRESS.md
git -C . log --oneline -5
cat ./gradle.properties; cat ./settings.gradle.kts | head -n 20
```

## 全局约束（逐字）
- `applicationId cn.debubu.tingbili` / `minSdk 31 (Android 12) / targetSdk 36 / compileSdk 36 / Java 17`
- 无视频解码链路，`ExoPlayer audio-only`，`MediaSession`，目标常驻 `<100MB`
- `Material3 dynamicColor` / 深浅色 / 平板 `NavigationSuiteScaffold` 自适应
- 纯本地 `Room + DataStore`，游客可用，登录延后预留 `Auth` 注入
- 底部 `4 Tab 首页/歌单/历史/设置 + 居中圆形 mini`（圆环进度 + 旋转封面）
- 仅 4 公开 B API：`search / view(多P) / playurl / subtitle`，`Wbi stub`
- 构建：`Kotlin 2.1 + AGP 8.7.3 + Compose BOM 2025.01 + Hilt 2.53.1 + Room 2.6.1 + Media3 1.4.1`，`Version Catalog`，`KSP`
- 稳定性：`android` CLI（非 `sdkmanager`），`aliyun` 镜像优先，`Xmx2g + idletimeout 30s + workers.max=1 + --no-daemon + --stop`，串行任务

## 已完成（12/12，`b3cdd20..5ff0a2a`）
- Task 1: 脚手架 `da71a22` (KTS + Catalog + 10 模块) — review clean
- Task 2: `core:ui` 主题 `0529b19` — dynamicColor
- Task 3: `core:data` Room/DataStore `bbac00d`
- Task 4: `data:bilibili` 网关 `f18065a`
- Task 5: `core:media` ExoPlayer `dceffc8`
- Task 6: `app` 底部4Tab+圆形mini `e8fc6db` (fix trailingSlash + adaptive)
- Task 7: `feature:home` Paging3 `1c28202`
- Task 8: `feature:playlist` CRUD `9f8b677`
- Task 9: `feature:history` 续播 `1f4b2a7` — compile 7s / assemble 10s
- Task 10: `feature:player` 全屏+字幕 `a89f91c` — assemble 17s
- Task 11: `feature:settings` 设置 `4955322` — compile 7s
- Task 12: `CI + README` `5ff0a2a` — `assembleDebug 38s` 产出 `app/build/outputs/apk/debug/app-debug.apk 76M` (324 tasks)
- Infra: `2deba88` aliyun + Xmx2g + android CLI

## 稳定性加固（已落地）
- `settings.gradle.kts:1` aliyun `public/google/gradle-plugin/central` 优先
- `gradle.properties:1` `Xmx2g MaxMetaspace512m idletimeout30000 parallel false configureondemand true`
- 每次 `bash` 后必 `./gradlew --stop`，不再派发并行子代理，`--no-daemon` 串行
- `NodeService` 从 `~50KB×12≈600KB` 内联上下文降至 `<5KB` 路径引用，存活从 `6m` 升至 `>30m`

## 下一步（无阻塞）
- 当前 MVP 已可安装（`76M`），二期：扫码登录 → 收藏夹导入 → 云同步
- 本地重型 `testDebugUnitTest` 全量延至 CI；本地仅 `assembleDebug -x test` 门禁
- 下次掉线恢复：读本文件第 1 节 3 条命令即可，无需重放历史对话

## 决策日志
- hilt 2.52→2.53.1 (Kotlin 2.1 metadata), coil3→coil2 (2.7.0 pom), NavigationSuiteScaffold adaptive, workers.max=1
- 掉线根因：`NodeService` 同步持有 `diff 24-51KB ×12` + `ps aux` 全量输出阻塞心跳 → `sidecar exited code:0` 优雅重启（非 OOM）

## 验证
- `free -h` Available 19Gi / `load 3.0` / `DataGrip 6.4%` 正常
- `utility.log` 连续 11 次 `code:0`（6-15min 间隔）已通过瘦身缓解
- 最后验证 `assembleDebug --no-daemon 38s` PASS，`--stop` 后无 Daemon

---
更新时间：2026-09-02 16:46  ·  分支 master  ·  HEAD 5ff0a2a
