# 杨臻：M2 同伴评审草稿

状态：AI辅助代码审阅材料，待杨臻本人阅读、复现和判断。尚未发布GitHub评审，不能计作已经完成的有效同伴评审。

对象：[乔梦涵 M2 后端PR #1](https://github.com/NICERTYU/campus-resource-platform/pull/1)，基线19cb1c4。本人前端PR依赖该分支。

## 发现：降低新流程默认级数会遗漏存量两级流程的审核权限

位置：[ApprovalPolicy.java](../backend/workflow-app/src/main/java/cn/campus/workflow/ApprovalPolicy.java) 的view方法（IntStream.rangeClosed(1, steps)）与allowed方法。风险等级建议P2：只在改变配置且仍有存量两级待审单时触发；默认steps=2的现有验收不受此配置场景影响。

触发条件：系统原先使用两级审批，某条存量记录已经进入第2级；随后仅把workflow.approval.steps改成1，希望新申请改为单级。服务端审核判断仍按该记录totalSteps=2、currentStep=2调用allowed，因此终审白名单里的管理员仍应可处理这条旧单。

实际推导：view只遍历1..steps，此时只能返回reviewSteps=[1]或空数组，无法返回2。前端按契约核对存量记录自身节点与reviewSteps后会隐藏该终审按钮，导致后端仍允许的旧单在页面上无法继续处理。第1级存量单也可能被默认单级所使用的终审白名单影响权限展示。

最小复现建议（尚未新增后端JUnit执行结果）：

1. 构造ApprovalPolicy(1, PT24H, 初审ID=11, 终审ID=22)，准备ADMIN用户22。
2. 确认policy.allowed(user22, 2, 2, "CS")为true。
3. 检查policy.view(user22).reviewSteps()，当前实现不包含2。
4. 将一条totalSteps=2/currentStep=2的未到期记录交给页面，确认应有权限的终审按钮不出现。

建议：让reviewSteps独立表达存量两级流程可审核的节点，steps仅表示新申请默认级数；legacyReview继续表达存量单级权限。至少新增“默认改为1，但已有两级第1/2节点仍按各自白名单处理”的回归测试，并保持接口兼容。暂不改代码时，应在配置变更说明中禁止存量两级申请结束前降低默认级数。

这是根据实际源代码和前端契约作出的推导，不声称已经跑过该配置的真实后端复现。可结合 [前端规则测试](../frontend/src/tests/applications-permissions.test.ts) 中“keeps actual record steps despite new-policy steps”检查消费约定。

## 可供本人核对后提交的评审正文

> 配置从两级改成单级后，view按新steps遍历权限节点，会漏掉存量两级单的第2级权限。比如默认steps=1、终审白名单包含用户22，allowed(user22, 2, 2, "CS")仍为true，但reviewSteps不可能包含2，页面会隐藏该用户应有的终审按钮。建议把存量两级节点权限与新申请默认级数分开返回，并补默认改为1后的存量1/2节点回归；若暂不支持动态降级，请明确配置限制。

提交前请本人确认源代码未被队友后续修改，并复核上述场景。发布后在个人验收记录登记真实评审链接、作者回复及最终处理结果；仅复制草稿而未理解，不能作为已完成的人工审查。
