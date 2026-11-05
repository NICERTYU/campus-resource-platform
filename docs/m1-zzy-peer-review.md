# 张子杨：同伴评审准备与记录模板

日期：2026-11-05。状态：准备材料，尚未完成正式同伴评审。本文件不是评审通过声明，不计作张子杨已经完成的一次有效评审；本次未向他人的PR发布评论或Review。

## 建议评审对象与原因

建议本人选择 [宋诺金的PR #4](https://github.com/NICERTYU/campus-resource-platform/pull/4) 中M3验收/返修与资源限制的边界进行复核。它直接影响M1座位是否恢复，因此需要从调用方和资源使用方检查，而不只是查看工单页面。

PR #4已合并，复核时应明确这是合并后复核，不伪造合并前审批。基准可选择main提交 `7a9eef36a390c15aa3862d7b95883c9e8e1cf5ce`，并在真正开始时确认代码是否已有后续变化。对象和范围由本人选择、阅读后确认。

## 本人需要阅读的文件

- [WorkflowService.java](../backend/workflow-app/src/main/java/cn/campus/workflow/WorkflowService.java)：验收、返修、改派与状态检查。
- [WorkflowRepository.java](../backend/workflow-app/src/main/java/cn/campus/workflow/WorkflowRepository.java)：工单更新及历史持久化。
- [OutboxProcessor.java](../backend/workflow-app/src/main/java/cn/campus/workflow/OutboxProcessor.java) 与 [ResourceClient.java](../backend/workflow-app/src/main/java/cn/campus/workflow/ResourceClient.java)：资源限制的调用和失败重试。
- [ResourceService.java](../backend/resource-app/src/main/java/cn/campus/resource/ResourceService.java)：按sourceType/sourceId/version建立与解除限制。
- [WorkflowIntegrationTest.java](../backend/workflow-app/src/test/java/cn/campus/workflow/WorkflowIntegrationTest.java)、[真实HTTP测试](../tests/test_system_http.py)、[网络恢复测试](../tests/test_network_recovery.py)：对照现有断言判断是否覆盖所选场景。

## 复核场景与预期

以下是复核计划，不是本次已执行或已发现失败的结论。使用独立测试环境和测试数据，先检查现有用例，避免重复造测试。

| 场景 | 应检查的行为 | 记录要求 |
| --- | --- | --- |
| 两张工单同时限制同一教室 | 验收其中一张后另一来源仍生效，M1仍不可预约 | 关联工单/来源和接口结果，确认现有测试有实际断言 |
| 完工后被退回维修 | 完工与退回不提前解除限制，保留返修原因和历史 | 状态前后、拒绝非法流转的结果 |
| 资源应用已解除但响应丢失 | 流程保持待确认并可恢复，重试不误解除其他来源 | 隔离环境故障注入方法、版本和重试结果 |
| 多次返修/验收或旧请求重放 | 旧版本不能重建已解除限制，状态和历史一致 | 请求版本、执行顺序和断言 |
| 改派与旧维修人员并发操作 | 原人员的权限被后端重新核验，历史记录可追踪 | 账号角色、工单状态与真实拒绝结果 |

## 形成有效评审的方法

1. 本人阅读当前代码和契约，选择实际理解的一个场景。
2. 运行现有测试或构造最小复现，写清环境与版本；没有运行就明确标注静态分析。
3. 只有确有问题时才写“缺陷”；否则记录已核对的行为、测试依据和仍存疑处，不为了凑评审编造问题。
4. 若提出问题，写明触发条件、预期/实际、影响、建议和测试缺口。不要只有“已看”“没问题”。
5. 本人发布实际评审/合并后复核，与作者沟通并登记回应。AI提供的草稿不能代替本人理解。
6. 将最终链接和结论回填 [个人交付记录](m1-zzy-delivery.md)。不要把检查自己的PR #3算作同伴评审。

杨臻的 [M2评审草稿](m2-yz-peer-review.md) 已提出审批配置兼容风险，可以参考其证据表达方式，但不复制为本人独立发现。

## 实际记录（待本人完成）

| 字段 | 当前内容 |
| --- | --- |
| 对象与版本 | 候选为PR #4及main 7a9eef3；待本人确认 |
| 阅读范围与日期 | 待填写实际记录 |
| 测试/复现命令与结果 | 尚未执行本计划，不填“通过” |
| 具体问题或核对结论 | 待本人判断，不预设存在缺陷 |
| 正式评审链接 | 尚无 |
| 作者反馈与处理提交 | 待实际反馈 |
| 本人复核结果 | 待完成 |

此模板不会自动完成小组任务书要求的有效评审。若选择其他对象，应按真实工作替换，而不是保留不适用的内容。
