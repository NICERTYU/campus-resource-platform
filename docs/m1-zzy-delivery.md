# 张子杨：M1 交付与验证记录

核查日期：2026-11-05（北京时间）。代码基准：main提交 `7a9eef36a390c15aa3862d7b95883c9e8e1cf5ce`。本文由AI根据真实提交、代码及GitHub记录整理，供本人核对；不代签个人验收。

## 个人范围与协作边界

小组任务书分配给张子杨的主责是M1后端规则与运营数据：历史复用、提醒通知、运营统计及相关回归。原MVP中的搜索、预约、签到、违约和跨模块资源限制属于既有基础，不重新登记为本人从零实现的成果。

[PR #3](https://github.com/NICERTYU/campus-resource-platform/pull/3)提交了后端、迁移、测试、契约和部分页面接入。前端包含 [Bookings.vue](../frontend/src/pages/Bookings.vue) 与 [Admin.vue](../frontend/src/pages/Admin.vue) 的增量；程一帆负责的前端完善及浏览器验收应在此基础上协作，不重复归属相同成果。页面教室筛选、异常反馈、窄屏和增量e2e的下一步分工仍需双方确认。

本次文档整理只修正文档顺序、完成边界和证据，不修复下表业务缺口，不创建新业务迁移，不宣称已有人工评审。

## 需求到实现与验证

接口的唯一公共约定继续维护在 [接口契约](api-contract.md)，验收目标见 [M1后续交付验收](acceptance.md#m1-后续交付验收)。本表补充个人范围及实际证据，不复制另一套接口定义。

| 需求 | 实现位置 | 已有验证与当前结论 |
| --- | --- | --- |
| M1-F01 历史复用 | [ResourceController.java](../backend/resource-app/src/main/java/cn/campus/resource/ResourceController.java)的reuse入口；[ResourceService.java](../backend/resource-app/src/main/java/cn/campus/resource/ResourceService.java)的reuse调用既有reserve；[Models.java](../backend/resource-app/src/main/java/cn/campus/resource/Models.java)的ReuseRequest；Bookings.vue | [ResourceIntegrationTest.java](../backend/resource-app/src/test/java/cn/campus/resource/ResourceIntegrationTest.java)中的historicalReservationCanBeReusedOnlyByOwnerAndSchedulesNotifications覆盖本人复用、事件创建和非本人拒绝；新增路径的冲突、边界及专门浏览器验收尚不完整 |
| M1-F02 提醒通知 | ResourceService的scheduleNotifications/processNotifications及取消通知插入；[TimeoutScanner.java](../backend/resource-app/src/main/java/cn/campus/resource/TimeoutScanner.java)；[V4迁移](../backend/resource-app/src/main/resources/db/migration/V4__m1_follow_up.sql)；Bookings.vue通知列表 | notificationProcessingIsIdempotentAndStatisticsAreAdminOnly覆盖开始前提醒事件重复处理；已提供事件和站内查询，尚未接入外部发送，不能认定推送需求完整验收 |
| M1-F03 运营统计 | ResourceController的statistics入口；ResourceService的statistics；[types.ts](../frontend/src/lib/types.ts)的SeatStatistic；Admin.vue | 同一集成测试验证统计教室数、座位数及服务层管理员权限；尚缺统计数值与历史维修验证、页面教室筛选、接口HTTP403和增量e2e |

V4属于resource应用的独立schema/迁移历史，与workflow应用同名版本不冲突。未来结构修复应新增迁移，不回改已经执行的V4。

## 提交、合并与CI证据

| 记录 | 说明 |
| --- | --- |
| [0463ffb](https://github.com/NICERTYU/campus-resource-platform/commit/0463ffb18a9670ac80c56c0ed1fcb32e58e15c60) | M1后续实现、V4、测试、接口文档及页面 |
| `ead4a92`、`0db0387` | 后续通知时间参数类型修正与页面格式统一；可在PR #3提交列表核查 |
| `d2d4970`、`98f12c3` | 同步已合并主分支与整合公共文档，不能将带入的M2/M3代码算作本人新增实现 |
| [PR #3](https://github.com/NICERTYU/campus-resource-platform/pull/3) | 2026-11-05 10:53:40合入main；合并提交`60f93784e605e8bb02beb3a309cc9ac3e9ade8ed` |
| [运行41957127747](https://github.com/NICERTYU/campus-resource-platform/actions/runs/41957127747) | PR提交`98f12c381ccce9bcc1d30e94742cba5d750c9866`的verify成功；PR部署任务按条件跳过 |
| [运行41958241093](https://github.com/NICERTYU/campus-resource-platform/actions/runs/41958241093) | 包含PR #3及PR #5的main提交`7a9eef3`：verify、测试环境实际安装和HTTP冒烟成功；生产步骤跳过 |

PR的verify覆盖文档、后端PostgreSQL/HTTP及前端检查、真实HTTP、网络故障与重启恢复、数据库账号隔离、Chromium和部署镜像构建。这些是对应版本的流水线证据，不是本人电脑全量运行的证据，也不是所有新需求均有专项测试的证明。不累计不同版本的测试数量。

11月1日记录的本地全量验证未完成是当时情况；11月5日CI结果是后续证据。测试环境部署成功不等于生产发布、回退或每个M1新功能均已完成线上人工验收。

## 可复核的验证方法

以下是复核入口，不宣称本次文档整理执行了全部命令：

- 文档：`python -B -m unittest discover -s tests -p test_documentation.py -v`，检查链接和索引。
- 项目检查：`./scripts/test.sh`，需要Java 21、Node 24及Docker/PostgreSQL测试环境。
- 后端：在backend中运行`./mvnw -B verify`。
- 前端：在frontend中运行`npm ci`、`npm run format:check`、`npm run test`和`npm run build`。
- 真实HTTP、故障恢复、数据库隔离和浏览器验证的环境变量及启动方式，以 [部署说明](deployment.md) 和 [CI配置](../.github/workflows/ci.yml) 为准。缺少外部环境而跳过的测试不能计为通过。

现有 [来源解除破坏性验证脚本](../backend/resource-app/scripts/mutation-probe.py) 属于基线工程证据，不冒充张子杨已独立完成的M1增量破坏性验证。

## 剩余事项与验收出口

下面的“建议协作”是待确认安排，不是他人已接受任务。问题来自代码静态核对，除已有CI/测试记录外，本次未运行新的业务复现实验。

| 追踪项 | 当前缺口 | 下一步与验收出口 | 建议协作 |
| --- | --- | --- | --- |
| M1-F02-A | 到期事件直接更新为SENT/deliveredAt，无邮件/微信发送；页面显示“发送时间” | 统一事件可见、发送、失败与送达语义，接入真实渠道；验证收件、失败重试、防重、启动补扫和三种提醒时间边界，渠道成功也不等于已读 | 张子杨与D负责人、程一帆 |
| M1-F03-A | 分母为座位数×整个查询时长；usedHours取已签到/完成预约区间，非实际在场时间 | 确认开放时间、不可用座位、签到/取消与统计单位；用可手算数据集断言数值，页面准确命名 | 张子杨、程一帆 |
| M1-F03-B | 维修仅查询active限制，已解除来源不计入；重叠来源直接求和 | 确认历史和返修区间的保存方式及是否求区间并集；验证验收后历史不丢、两工单重叠不错误重复 | 张子杨与M3协作 |
| M1-F03-C | 后端有classroomId筛选，页面只发送日期；缺专门HTTP403及浏览器验收 | 增加筛选交互，验证参数、日期边界、管理员结果及普通账号真实403 | 程一帆、张子杨 |
| M1-F01-A | 契约写statusGroup=all，服务端只接受current/history或不传 | 共同决定支持all或修正文档；补接口契约回归，再通知调用方 | 张子杨、程一帆 |
| M1-QA | M1新路径专项e2e与数值测试不足 | 复用冲突/越权、通知、统计的真实流程；受控破坏权限/公式后测试失败、恢复后通过，记录真实结果 | M1模块组 |
| M1-PROCESS | 人工验收、独立修改及有效同伴评审证据不完整 | 本人阅读并说明取舍，完成 [评审准备](m1-zzy-peer-review.md) 中的真实评审；登记链接、反馈和处理结果 | 张子杨及非主责评审人 |

## 人工责任与交付边界

本次核查PR #3的正式Review列表为空，不能把“已合并”写成“已经完成同伴评审”。尚未取得本人逐项人工验收、独立修改或完整增量破坏性测试的证据，应据实标为待完成。

历史冲突的AI参与及后续人工复核要求见 [AI协作与工程伦理](m1-zzy-ai-ethics.md)。这些个人材料不代替最终个人课程论文，也不代替本人阅读、复现和正式评审。
