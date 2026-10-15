# 实现与验证记录

日期：2026-10-10；实现提交：`d71ab27`。本次完成产品设计的首版MVP，采用用户确认的[业务规则](acceptance.md)，完整运行命令见[运行与部署](deployment.md)。

## 已实现

前端提供登录、座位查询与预约、当前/历史预约及签到、整室申请与审批、报修工单、运营管理六类页面，支持手机布局、角色菜单、分页与失败提示。管理员可维护教室、座位、插座、开放时间、每日签到码、预约时长参数，并查看两应用的操作日志。

资源应用负责账号、Session、CSRF、院系访问、M1及来源限制；流程应用负责M2/M3状态与持久化操作意图，通过真实HTTP验证身份和建立/解除M2/M3限制。两个应用使用独立PostgreSQL schema及非超级用户账号。审批、预约、维修和超时处理遵守共同教室锁协议，解除限制按来源执行；定时补扫和版本化重试覆盖重启及网络失败。

工程包含Maven Wrapper、锁定依赖、Flyway新建/升级迁移、显式幂等演示初始化、四容器Compose、Nginx入口隔离、GitHub Actions验收与制品发布、部署及回退脚本。CI与部署镜像按Git提交关联；生产通过受保护environment审批。

## 自动验证结果

以下均实际运行通过，合计107项，不包含跳过的用例。

| 验证 | 通过数 | 证据与覆盖 |
| --- | ---: | --- |
| 资源应用JUnit | 28 | [数据库与HTTP集成测试](../backend/resource-app/src/test/java/cn/campus/resource/ResourceIntegrationTest.java)21项、[时间规则](../backend/resource-app/src/test/java/cn/campus/resource/TimeRulesTest.java)4项、[Session](../backend/resource-app/src/test/java/cn/campus/resource/SessionsTest.java)3项 |
| 流程应用JUnit | 13 | [数据库与HTTP集成测试](../backend/workflow-app/src/test/java/cn/campus/workflow/WorkflowIntegrationTest.java)11项、[规则测试](../backend/workflow-app/src/test/java/cn/campus/workflow/RulesTest.java)2项 |
| 前端Vitest | 33 | [交互测试](../frontend/src/tests/)8个文件，覆盖权限导航、登录、会话退出、请求重试、分页与表单 |
| Python系统与工程验证 | 30 | [文档](../tests/test_documentation.py)8项、[部署边界](../tests/test_deployment.py)6项、[真实HTTP](../tests/test_system_http.py)10项、[真实故障恢复](../tests/test_network_recovery.py)2项、[数据库账号隔离](../tests/test_database_boundary.py)4项 |
| Chromium浏览器 | 3 | [实际三条主流程](../frontend/e2e/flows.spec.ts)：预约→签到→取消；申请→批准→占用→撤销；报修→派单→维修→完工→返修→验收 |

环境为Java21、Spring Boot4.1.1、Node24.21.0、PostgreSQL18.6及Chromium。后端测试使用真实PostgreSQL Testcontainers，41项全部执行，无跳过；Maven verify、前端Prettier、TypeScript检查及生产构建均通过。本地因镜像下载异常禁用Ryuk，CI保留默认清理机制。

Python全量验收通过时启用了 `CAMPUS_BASE_URL`、`CAMPUS_NETWORK_TEST=1` 及 `CAMPUS_DB_TEST=1`，30项全部执行，无跳过。未设置这些变量的普通单元测试命令会明确跳过外部环境用例。测试使用独立教室，清理有效个人预约；故障恢复测试清理独立流程schema及对应来源限制，避免污染后续验收。

故障恢复测试启动真实流程JAR、真实资源应用和数据库，仅在HTTP代理处注入503或提交后断开响应。验证处理中状态、持久化重启补偿、同来源只建立一次，以及撤销解除。数据库账号隔离另在完整四容器环境重复通过4项；资源账号不能读流程业务表，流程账号不能读资源身份表。

四个正式部署镜像/服务完成实际构建与健康检查。经Nginx入口的10项HTTP验收及3条浏览器流程全部通过，两个后端在Compose网络中通过服务名通信。公共入口 `/internal/v1/identity` 与 `/actuator/health` 均返回404；Session/CSRF与流程401响应检查通过。容器浏览器验收使用资源应用显式e2e时钟 `2026-10-12T01:50:00Z`，演示和正常部署使用真实时间。

## 针对性回归验证

- 验证同座位并发、M1/M2审批竞争、维修启动与预约竞争、签到与截止扫描竞争；相邻区间可用，截止时刻拒绝签到。
- 验证两张维修工单及M2并存时，只解除指定来源；完工和验收驳回保持维修限制。
- 验证维修恰在签到截止启动不记违约，而截止后才开始的维修不免除已经发生的未签到违约。
- 验证单条定时处理失败不阻塞其他记录，审计失败完整回滚，重启后补扫不重复记违约。
- 验证相同幂等键返回完全相同的状态码/JSON/带时区时间，同键不同参数拒绝；历史/权限过滤在分页前执行。
- 验证Flyway从V1升级V2保留既有数据；显式初始化重复执行不覆盖账号与空间。
- [受控破坏脚本](../backend/resource-app/scripts/mutation-probe.py)故意将按来源解除改成整室解除，关键多来源测试按预期失败；恢复源码后通过，证明断言能识别该回归。
- 通过独立进程树实测，开发脚本收到SIGTERM/SIGINT后清理npm/Vite及两个后端，无应用进程残留；Nginx健康检查使用显式IPv4地址，避免localhost解析导致误报。
- 桌面1440px和手机380px页面经过截图检查，无横向溢出。

## 尚未实际执行的环境事项

公开仓库已配置为 [NICERTYU/campus-resource-platform](https://github.com/NICERTYU/campus-resource-platform)，CI运行状态见[GitHub Actions工作流记录](https://github.com/NICERTYU/campus-resource-platform/actions/workflows/ci.yml)。华为云主机、SSH、域名及部署Secrets尚未配置，云端自动部署、HTTPS、生产审批及线上回退尚未实际运行。流水线和脚本已实现，仍须按部署文档接入目标环境验收。服务器配额和预算也未经过目标主机负载验证。

提醒/通知、运营统计、历史复用、动态权限、多级审批及维修评价等保持为产品文档中的后续阶段，本次未列为已完成功能。

## 2026-10-15公开发布准备

删除简历和代理工具等无关文件，公开分支从不含这些文件的实现历史建立；本地原有历史单独保留，不推送。新增5项仓库范围检查，防止个人文件、运行数据和非示例环境配置进入Git索引。

CI首次启动的前端就绪等待调整为300秒，等待脚本支持显式超时并限制每次HTTP请求耗时；新增6项真实HTTP回归测试。新增11项测试及既有文档/部署检查均通过；后端41项、前端33项及生产构建也已在干净发布工作区重新验证。实际GitHub CI结果以工作流记录为准，不将需要外部环境而跳过的本地用例计为通过。
