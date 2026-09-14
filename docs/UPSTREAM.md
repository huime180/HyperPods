# 上游同步（Upstream）

本仓库是**融合**项目：架构、界面与代码风格取自 `1812z/OppoPods`，水月雨（MOONDROP）功能
来自作者早先的水月雨模块。融合原则见 [FUSION_ARCHITECTURE.md](FUSION_ARCHITECTURE.md)。

## 上游是谁

| 项 | 值 |
| --- | --- |
| 上游仓库 | https://github.com/1812z/OppoPods |
| 本地 remote 名 | `upstream` |
| 上游默认分支 | `master` |
| 融合基线 | `2c5bda4`「Release 2.1.0」（versionName 2.1.0 / versionCode 16） |
| 截止 2026-09-14 的上游 HEAD | `0d9e8a6`「fix: 修复关于页 GitHub 链接点击崩溃」（2026-08-24） |

添加 remote（每个新克隆都要做一次，remote 不进版本库）：

```bash
git remote add upstream https://github.com/1812z/OppoPods.git
git fetch upstream master
```

> ⚠ 不要执行 `git branch --set-upstream-to=upstream/master dev`。那会把 `dev` 的推送目标
> 指向上游仓库（我们没有写权限，`git push` 会失败）。同步只用 `fetch` + 手工移植，
> 分支的跟踪关系保持指向 `origin`。

## 为什么不能直接 merge

两边的 git 历史**没有共同祖先**：本仓库的 `dev` 是把上游源码**重新提交**成一套新历史
（见 `b061de2`「重做：以 1812z/OppoPods v2.1.0 为基线重建 HyperPods」），不是 fork 出来的。
`git merge-base dev upstream/master` 为空，直接 `git merge --allow-unrelated-histories`
等于把两边每个文件都当作冲突，没有意义。

因此「同步上游」 = **比对增量 + 手工移植**：

```bash
# 1. 拉最新
git fetch upstream master

# 2. 看基线之后上游有哪些提交（--oneline 即可，通常很少）
git log --oneline 2c5bda4..upstream/master

# 3. 逐个提交看改动，判断哪些还没移植到本仓库
git show --stat <sha>
git show <sha>

# 4. 在本仓库按融合后的写法重新实现（不要照抄上游的包名/类名/SPDX 头）
```

## 同步记录

| 日期 | 上游 HEAD | 结论 |
| --- | --- | --- |
| 2026-09-14 | `0d9e8a6` | 基线（`2c5bda4`）之后上游只有 1 个提交 `0d9e8a6`，内容是「关于页 GitHub 链接点击崩溃」的修复：给非 Activity 上下文加 `FLAG_ACTIVITY_NEW_TASK`，并抽出 `Context.openUrl()`。该修复**本仓库已经有**（`ui/pages/AboutPage.kt:20-26` 同款 Activity 判断），因此本次无需移植，无改动。 |
