# 乔梦涵 M2 交付与验证记录

日期：2026年10月26日。范围：小组任务书分配的 M2 后端状态机、超时撤销、审批冲突与写回、schema 演进；附必要的页面兼容及联调测试。业务规则与接口见 [M2 多级审批](m2-approval.md)。

## 任务追踪

| 编号 | 行为与实现 | 验收依据 |
| --- | --- | --- |
| M2-QMH-01 | [状态机](../backend/workflow-app/src/main/java/cn/campus/workflow/WorkflowService.java)、[审批权限](../backend/workflow-app/src/main/java/cn/campus/workflow/ApprovalPolicy.java)：初审、终审、驳回、退回和重提；revision 防止过期请求越级 | T22，授权节点、部门、历史可见性、双请求并发与幂等测试 |
| M2-QMH-02 | [超时扫描](../backend/workflow-app/src/main/java/cn/campus/workflow/ApplicationExpiryScanner.java)：持久化截止时间、重启补扫、待审直接撤销、同步中解除补偿 | T24，截止前、截止相等、重复扫描、响应丢失和在途请求竞争 |
| M2-QMH-03 | [outbox](../backend/workflow-app/src/main/java/cn/campus/workflow/OutboxProcessor.java)：409/503保持处理中，按来源和版本重试；高版本解除取代旧建立 | T23，真实预约冲突解除后自动恢复、真实503及丢响应后重启恢复 |
| M2-QMH-04 | [V3迁移](../backend/workflow-app/src/main/resources/db/migration/V3__multilevel_approval.sql)：新增节点、版本、期限、院系与逐次历史，保留旧单级记录 | T25，真实PostgreSQL空库迁移、V2升级及重复执行 |
| M2-QMH-05 | [页面](../frontend/src/pages/Applications.vue)展示节点、期限、退回重提与历史，写请求携带revision | T26，真实浏览器两级审批及退回重提流程；页面仍需杨臻复核 |

## 实际验证

所有计数均按独立用例记录，不把失败重跑或跳过项算作通过。

- 资源应用42项JUnit通过；本次未修改资源应用业务代码。
- 流程应用28项JUnit通过，无跳过，其中新增 [ApprovalPolicyTest](../backend/workflow-app/src/test/java/cn/campus/workflow/ApprovalPolicyTest.java)3项及 [MultilevelApprovalIntegrationTest](../backend/workflow-app/src/test/java/cn/campus/workflow/MultilevelApprovalIntegrationTest.java)11项。原有单级回归在显式单级配置下执行，新流程另以默认两级配置测试。
- 前端53项Vitest通过。路由单元测试只替换重量级座位页面模块，继续验证真实权限守卫；实际座位页面由页面测试及真实浏览器覆盖，不放宽仓库测试时限。类型检查、生产构建与格式检查通过。生产构建使用仓库指定的Node24.21.0，本机旧24.13.0原生打包进程曾异常退出。
- Python工程检查在Linux临时容器执行：36项通过，18项外部环境用例明确跳过。随后在实际本地双应用执行 [HTTP](../tests/test_system_http.py)12项、[数据库隔离](../tests/test_database_boundary.py)4项、[故障恢复](../tests/test_network_recovery.py)2项，均通过；共覆盖54个不同用例，分环境执行。
- Chromium真实浏览器7条流程全部通过：原6条回归加上M2退回、补充重提、逐级审批和历史流程。首次本地执行遇到服务尚未就绪与Vite依赖预优化重载，待服务就绪及缓存稳定后全套重跑通过，不把失败尝试算作通过。
- [受控破坏脚本](../backend/workflow-app/scripts/mutation-probe.py)移除审核人白名单判断，权限测试按预期失败；源码在finally中完整恢复，并重跑测试通过。

复现命令（已配置Java21、Node24.21.0及Docker）：

```bash
cd backend
./mvnw -B verify
cd ../frontend
npm ci
npm run format:check
npm run test
npm run build
cd ..
python3 backend/workflow-app/scripts/mutation-probe.py
./scripts/dev.sh
```

在另一个终端执行真实接口与故障恢复：

```bash
CAMPUS_BASE_URL=http://127.0.0.1:5173 python3 -m unittest discover -s tests -p test_system_http.py -v
CAMPUS_DB_TEST=1 python3 -m unittest discover -s tests -p test_database_boundary.py -v
CAMPUS_BASE_URL=http://127.0.0.1:5173 CAMPUS_NETWORK_TEST=1 python3 -m unittest discover -s tests -p test_network_recovery.py -v
```

浏览器签到流程按 [运行与部署](deployment.md) 使用相同的未来测试时钟分别启动资源应用及Playwright。测试结束后恢复真实时钟。测试数据只进入独立本地环境，不操作线上账号或生产库。

## AI 协作记录

目标是完成个人M2后端任务。约束包括保留既有MVP和旧数据、仅HTTP跨模块调用、服务端校验权限、版本化迁移、真实数据库测试、PR审核后合并。

用户明确确认了两级审批、24小时且不晚于开始时间、初审退回申请人和终审退回初审的规则。AI据此提出并实现revision校验、追加式历史、持久化期限与高版本解除补偿，并编写测试。测试发现并修正了并发请求的状态码顺序、旧单级兼容，以及数据库微秒精度下的边界测试问题。

本记录由AI根据实际执行结果整理。用户批准业务方案不等于已经逐行复核SQL、权限、并发和异常路径；提交前后仍需乔梦涵本人阅读、理解并在PR中确认。不得把AI测试结果写成人工审查已经完成。

## 工程伦理与责任边界

权限错误可能让无资格用户占用教室；服务端同时校验角色、节点白名单和院系，历史仅对申请人及管理员开放。审核意见属于业务数据，不能在公共错误或日志中扩大暴露。

网络超时不能证明资源未建立。因此界面保留处理中，撤销使用更高版本的按来源解除，直到收到确认才显示终态。超时扫描和人工审批使用相同的申请行锁；旧在途请求不能覆盖新的解除墓碑。自动解除若因原账号权限被撤销而遭403，仍保持处理中并显示错误，需管理员协调公共层排障，不能虚报已经释放。

历史迁移保持旧状态，不补造审批人或人工评审证据。数据库升级不会自动回退；新增状态可能不被旧应用识别，发布者须先备份，并结合兼容性安排应用回退或数据库恢复。

本次新增代码由使用本机授权账号的AI协助完成。最终质量责任、个人署名和课程陈述须由成员自己承担；本节可作为个人伦理材料的依据，仍需本人补充实际阅读、修改和判断。

## 评审与发布出口

本次PR包含schema、共享接口和Compose变更，按小组约定需要至少两名成员复核。真实PR评审、个人对其他成员PR的一次有效评审、生产发布与回退不在此伪造为已完成。PR通过CI并经成员审核后才能合并；本次不直接合并main或部署线上。
