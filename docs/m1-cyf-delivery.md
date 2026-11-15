# 程一帆 M1 前端与浏览器验收交付

日期：2026-11-04。任务依据为小组任务书中“程一帆 · M1前端与浏览器验收”，基线为团队main的81ee700。个人工作分支为feature/cyf-m1-01。任务书提供职责与验收要求；本记录不把文档中的发布、他人任务或人工签名视为已执行事实。

按用户后续明确要求，本次修改仅保留在本地工作区，不创建Git提交、不推送或创建远端PR；该要求优先于仓库AGENTS.md中的提交约定。

## 范围与验收标准

基线已有搜索、预约、签到、取消和当前/历史分页。基线未提供历史复用、预约通知或统计接口，因此本次同时补齐这些页面必需的最小资源应用支持；这部分是联调补充，需张子杨及公共层负责人复核，不声称是队友此前已完成的工作。M2/M3运行代码、CI和部署配置未改。

| 编号 | 验收行为 | 实现与测试 |
| --- | --- | --- |
| M1-CYF-01 | 本人历史记录保留座位与时段偏好，确认新日期；过期400、无权限403、冲突409显示真实请求编号，不自动重试或改时间 | [预约页面](../frontend/src/pages/Bookings.vue)、[交互规则](../frontend/src/lib/bookings.ts)、[页面测试](../frontend/src/tests/bookings-page.test.ts) |
| M1-CYF-02 | 待签到、即将开始、开始后未签到和窗口结束提示；计时不伪造服务端取消；站内提醒与外部送达分开 | 页面、[边界测试](../frontend/src/tests/bookings-rules.test.ts)、[fixture浏览器](../frontend/e2e/m1-fixture.spec.ts) |
| M1-CYF-03 | 管理员按教室及上海自然日筛选；聚合数据来自服务端；失败清空旧数据；1440px/380px无横向溢出 | [统计页面](../frontend/src/pages/Statistics.vue)、[统计测试](../frontend/src/tests/statistics-page.test.ts)、路由和导航权限回归 |
| M1-CYF-04 | 实际浏览器预约→签到→取消→历史复用；本人/他人会话隔离；伪造复用、签到、取消及统计访问被服务端拒绝 | [真实浏览器与HTTP](../frontend/e2e/m1-live.spec.ts) |
| M1-CYF-DB | V1旧库升级V4与空库迁移；复用再次检查归属/角色/日期/时长/开放/院系/M2/M3；提醒窗口防重、未签到与维修区别；统计维修并集 | [资源集成测试](../backend/resource-app/src/test/java/cn/campus/resource/ResourceIntegrationTest.java)、[统计规则测试](../backend/resource-app/src/test/java/cn/campus/resource/ReservationStatisticsTest.java) |

实现前先补充[接口契约](api-contract.md)中的“M1页面联调补充”，验收标准为上表。本次增加了前端规则/页面/浏览器测试、服务端规则/数据库/HTTP测试，并提供[受控破坏验证](../frontend/scripts/booking-mutation-probe.mjs)：移除签到截止判断时，10:15:00边界断言必须失败；finally逐字节恢复后规则测试必须通过。它验证前端截止提示边界，不能替代服务端权限测试。

## 接口、迁移和口径

新增POST /reservations/{id}/reuse、GET /reservations/notifications、GET /admin/reservation-statistics。复用在既有事务、教室锁、用户锁、时段占用和幂等结果机制下创建新记录，源记录保持原状；服务端读取源记录seatId，不能用前端按钮代替所有权检查。校验失败留在确认框，慢请求期间禁用输入与重复提交。

[V4迁移](../backend/resource-app/src/main/resources/db/migration/V4__reservation_notifications.sql)只新增事件表、外键、防重唯一约束及本人查询索引，不改旧预约数据。重复扫描同一预约/类型不重复写入。开始前15分钟及开始后10至15分钟为实时提醒窗口，重启只补扫描仍在窗口内的预约，不补发已过时提醒。NO_SHOW_CANCELLED在实际未签到取消事务内生成；维修取消不生成违约提醒。

统计按当前启用空间/座位和开放时间计算容量，反映预约占用，不代表实际到场。计数按开始日期，小时按查询窗口及开放区间裁剪；维修区间按来源并集去重，已解除取releasedAt，未解除仅累计到服务端当前时间。无容量返回null。管理员可以看全校聚合，普通用户403；返回值不含学生姓名、账号或个人预约明细。筛选日期包含首尾、最多31天；统计在一致性只读事务中查询。

## 验证命令与环境

默认流程保持不变：Java21、Node24.21.0、Docker/真实PostgreSQL18.6，执行：

```bash
./scripts/test.sh
# 按deployment.md启动测试双应用，前端与后端沿用同一个未来E2E_CLOCK
cd frontend
npm run test:e2e
node scripts/booking-mutation-probe.mjs
```

本机系统原为Java11/Node20，Docker守护进程不可访问。为完成真实验证，在被Git忽略的.local下下载私有JDK21、Node24及PostgreSQL18.6二进制，不改系统版本或应用依赖；Chromium使用Playwright版本。后端测试增加可选本地PostgreSQL适配，默认仍用原Testcontainers。设置以下变量可运行同一套完整测试：

```bash
# 数据库须为本人管理的本地独立测试实例；账号需要CREATEDB
export CAMPUS_TEST_PG_URL=jdbc:postgresql://127.0.0.1:55439/postgres
export CAMPUS_TEST_PG_USER=你的本地测试账号
export CAMPUS_TEST_PG_PASSWORD=你的本地测试密码
cd backend
./mvnw -B verify
```

[TestPostgres资源适配](../backend/resource-app/src/test/java/cn/campus/resource/TestPostgres.java)和[流程适配](../backend/workflow-app/src/test/java/cn/campus/workflow/TestPostgres.java)仅在测试源码生效，只接受回环地址/postgres且禁止query参数，为每个测试类创建随机campus_test_数据库，结束时只删除其自行创建的数据库；不清空指定URL中的既有库。不设置变量时仍自动拉取postgres:18.6容器。两个模块分开编译，因此各自保留同样的小型测试适配器，无生产依赖变更。

本次实际双应用浏览器环境使用独立campus_cyf_acceptance数据库与分离的resource/workflow数据库账号；资源应用固定E2E_CLOCK=2026-11-06T01:50:00Z，流程应用保持真实时钟。为缩短本地提醒验收，资源扫描间隔仅在本地测试进程设为500ms；生产默认仍为30000ms。环境代理须给127.0.0.1/localhost设置NO_PROXY。

## 实际验证结果

以下只登记本轮实际执行结果，fixture与真实服务分别计数，不沿用队友旧CI作为本次证据。

| 检查 | 结果 |
| --- | --- |
| 前端Vitest | 134通过，包含本轮新增35项规则/页面测试 |
| vue-tsc、Vite生产构建、全量前端格式 | 通过 |
| 双后端JUnit | 101通过、0跳过：资源52项、流程49项；真实PostgreSQL18.6 |
| M1 fixture浏览器4项、真实M1闭环1项 | 5项全部通过；真实闭环同时验证服务端400/403/409、站内事件、教室筛选和380px布局 |
| 全站Chromium | 19项中18通过、1跳过；通过项含9项真实HTTP/浏览器和9项fixture。跳过的是依赖本地Compose数据库的M2真实超时SQL夹具 |
| Python文档/工程及真实HTTP | 65项中57通过、8跳过，含13项真实HTTP全部通过；跳过4项Compose数据库账号隔离、4项真实进程/网络故障恢复 |
| 最终文档完整性与差异检查 | 8项文档链接/索引检查通过，git diff --check通过 |
| 截止边界破坏验证 | 移除判断使边界测试失败，恢复后规则测试通过 |

首轮浏览器因本机代理使健康检查失败，测试未连接服务；第二轮发现Element Plus的label同时标记控件与列表/表单组，控件内部的只读输入框又被placeholder覆盖。最终使用可点击的.el-select容器和明确date input定位，保留所有行为断言。失败尝试不计为通过。

最终回归时曾在测试服务运行期间执行Maven verify，覆盖了服务使用的target JAR，触发惰性加载类的ClassNotFoundException，匿名登录请求因而挂起。已将测试服务改为从.local/runtime中的独立JAR副本启动，确认匿名/auth/me恢复401后重跑。该问题来自本次本地测试操作，没有为此改动登录、鉴权或生产代码。

实际本地双应用页面证据：[1440px统计页面](images/m1-cyf-statistics-desktop.png)、[380px统计页面](images/m1-cyf-statistics-mobile.png)。截图来自真实M1浏览器测试的随机测试账号和教室，不能代替人工评审或生产验收。

## 演示与剩余出口

1. 学生在寻找座位中预约，当前预约显示待签到和校园时区窗口；输入当天签到码后显示已签到。
2. 取消并切换历史记录，一键复用打开新的日期确认；提交成功创建新ID，源记录仍为已取消。
3. 选择过去时间查看提示；在另一个会话制造占用或移除权限，查看服务端错误和请求编号。
4. 站内提醒显示真实已保存事件；用浏览器fixture演示截止时刻仅提示等待服务端确认，真实未签到取消由后端集成测试验证。
5. 管理员打开预约统计，筛选教室与日期，展示数量、预约占用率与维修影响；380px窄屏核对换行。普通学生直接请求统计返回403。

尚待真实人员完成：创建并提交个人PR、至少一名非主责同学审核；迁移/公共接口/测试基础设施补充至少两人复核；本人实际发布一次有效同伴评审。已准备[可复现评审草稿](m1-cyf-peer-review.md)和[AI/伦理材料](m1-cyf-ai-ethics.md)，不代签或宣称人工评审已经发生。

本次不执行推送、合并、生产部署、tag或回退演示。站内事件尚未接入外部推送；历史空间配置没有快照，当前统计不能用于追溯计费或到场率评价；统计按教室查询明细后聚合，31天上限下适合当前教学规模，大量教室时应增加批量聚合/分页。以上限制保留在交付记录，后续由对应负责人确认。

Docker专项跳过项并未记为通过；现有JUnit包含数据库隔离到随机测试库、HTTP、失败回滚及重启相关测试，但不能替代上述Compose环境专项。M1本次新增的5项浏览器验收没有跳过。代码与本地可执行验收已完成，人工评审、团队环境专项和发布出口仍由实际成员推进。
