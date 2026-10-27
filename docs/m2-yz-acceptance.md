# 杨臻 M2 页面与权限验收

日期：2026年10月27日。个人任务为M2审批页面、权限、超时/冲突提示与端到端验收。代码与测试已提交 [个人PR #2](https://github.com/NICERTYU/campus-resource-platform/pull/2)，人工评审及团队集成出口单独列在下方。

## 基线与个人贡献

个人分支feature/yz-m2-01基于乔梦涵的codex/qmh-m2-approval（19cb1c4）。该基线已有状态机、V3、审批历史和基础页面，见 [M2规则](m2-approval.md)、[后端交付](m2-delivery.md)。PR以该依赖分支为base，差异仅登记杨臻新增工作，不重复计算队友后端实现。

本次修改审批前端、测试、接口消费说明和个人材料，不修改数据库迁移、M1/M3业务、CI或部署配置。授权账号eqepety无上游写权限，通过 [个人fork](https://github.com/eqepety/campus-resource-platform/tree/feature/yz-m2-01) 交付，未合并团队main或执行云端部署。

| 提交 | 独立阶段 |
| --- | --- |
| a3986e7 | 先提交页面契约示例、权限方案和验收出口 |
| e905da9 | undici开发依赖7.30.0最小修复到7.30.1，npm audit零漏洞 |
| 0cdcc6c | 权限/期限/文案辅助模块及21项规则测试 |
| 3b60f95 | 页面接入、版本确认框、历史隔离及12项页面测试 |
| 543c20c | 5项fixture浏览器验收、2项真实矩阵/超时验收及既有流程时钟修正 |
| 3fb7b55 | 冷启动后暂停测试时钟，再精确推进审批期限；交付截图与完整复现记录 |
| 6f70d96 | 点击瞬间时钟、同账号权限变更、慢请求防重入及3项回归；AI/伦理与评审草稿 |

依赖修复依据为 [undici上游安全公告](https://github.com/nodejs/undici/security/advisories/GHSA-xjj6-f33q-929c)，只改lockfile中该包的三行。

## 需求、实现与验收映射

| 编号 | 实现位置与行为 | 验收位置 |
| --- | --- | --- |
| M2-YZ-01 | [权限规则](../frontend/src/lib/applications.ts) + [审批页面](../frontend/src/pages/Applications.vue)：policy与记录实际节点、院系、归属、期限共同判定；单级用legacyReview，策略失败暂停审核 | [规则测试](../frontend/src/tests/applications-permissions.test.ts)、[页面测试](../frontend/src/tests/applications-page.test.ts) |
| M2-YZ-02 | 确认框保存原revision并在确认前检查；409后不自动更换版本或重放；历史清空、加载、失败、重试及过时响应隔离；错误和同revision历史保留真实requestId | 页面测试及 [fixture浏览器](../frontend/e2e/m2-fixture.spec.ts) |
| M2-YZ-03 | 真实Cookie/CSRF会话验证401、学生越权写入403、他人历史/撤销403、非同院系初审403、旧revision409及范围过滤 | [真实HTTP矩阵](../frontend/e2e/m2-live.spec.ts) |
| M2-YZ-04 | 实际提交、逐级审批、占用、退回重提、驳回、撤销恢复和超时扫描；1440px/380px；受控移除院系限制必须失败 | [真实流程](../frontend/e2e/flows.spec.ts)、真实矩阵、fixture浏览器、[破坏验证脚本](../frontend/scripts/approval-mutation-probe.mjs) |

[契约示例](api-contract.md) 先于实现补充。学生列表现有契约为200返回本人记录，新测试学生没有记录因而为空；403验收针对越权提交/审核及查看他人历史，未擅自改变后端权限约定。

## 实际验证结果

计数按独立用例统计，不把失败尝试、跳过项、fixture或队友以前的结果改记为本次真实服务通过。

本机Windows无Java、Docker和WSL Linux，前端使用项目私有Node24.21.0和Chromium。本机89项Vitest通过（基线53 + 新增21规则 + 新增15页面）、vue-tsc和生产构建通过；5项fixture浏览器通过；文档链接/索引8项通过。只移除院系判定的受控破坏实际触发权限断言失败，finally逐字节恢复源码后21项规则测试重新通过。

[第一轮个人fork完整CI](https://github.com/eqepety/campus-resource-platform/actions/runs/40446654757) 在提交543c20c上成功，使用原有工作流、Java21、Node24.21.0和真实PostgreSQL/双应用：

| 检查 | 本轮结果 |
| --- | --- |
| 资源应用JUnit | 42通过，无跳过 |
| 流程应用JUnit | 28通过，无跳过 |
| Python静态/工程检查 | 54项中36通过，18项需外部环境而跳过；后续独立执行这18项 |
| 真实HTTP黑盒 | 12通过 |
| 真实网络故障与重启恢复 | 2通过 |
| 真实数据库账号隔离 | 4通过 |
| Chromium全站 | 14通过：7项原真实回归 + 5项fixture + 2项新增真实验收 |
| 前端 | 86项Vitest、类型检查、生产构建与全量格式检查通过 |
| 镜像 | resource/workflow/web三个部署镜像构建成功；未部署 |

[第二轮复验](https://github.com/eqepety/campus-resource-platform/actions/runs/40447204603) 针对6f70d96：89项Vitest、后端70项、真实HTTP/数据库/故障恢复均通过，浏览器13项通过、1项fixture时序失败，镜像步骤因此未执行。冷启动超过原测试的一秒窗口时，点击前期限已经过去，页面正确拒绝打开，测试却继续寻找确认按钮。测试改为加载完成后暂停在期限前一秒，确认框出现后再推进1100ms，保留原有全部断言；本地该用例重复3次通过，不把重复次数计入独立用例总数。修正后 [最终完整CI](https://github.com/eqepety/campus-resource-platform/actions/runs/40447995070) 在3fb7b55上全部成功：89项Vitest、后端70项、真实HTTP12项、故障恢复2项、数据库隔离4项、浏览器14项、类型/格式/生产构建和三个部署镜像均通过，无部署。其后提交只登记本验收结果，不修改运行代码与测试；文档链接及索引8项再次通过。

Windows检出时全量format:check曾因44个基线文件的CRLF报格式提示；本次变更文件均单独检查通过，Linux CI全量格式检查通过，未批量格式化队友代码，也未修改全局Git配置。

真实超时用例只在CI或CAMPUS_DB_TEST=1的本地Compose运行，要求回环地址和.local/dev.env，限定两个本用例唯一purpose标记的申请ID。SQL只把测试记录deadline置为过去，EXPIRED与历史由真实扫描器产生；测试不新增生产测试接口。临时账号最终停用，保留审计证据。

## 复现命令

已具备Java21、Node24.21.0与Docker的Linux环境，完整命令仍使用仓库原流程：

```bash
./scripts/test.sh
# 按deployment.md，在独立Compose环境设置未来E2E_CLOCK并启动双应用
./scripts/dev.sh
# 另一终端，沿用相同E2E_CLOCK
cd frontend
npx playwright install --with-deps chromium
npm run test:e2e
node scripts/approval-mutation-probe.mjs
```

只验收页面（不计真实服务通过），先执行npm ci与npm run build，再单独启动预览并指定地址：

```bash
# frontend目录，终端1
npx vite preview --host 127.0.0.1 --port 4173 --strictPort
# frontend目录，终端2
CAMPUS_BASE_URL=http://127.0.0.1:4173 npm run test:e2e -- e2e/m2-fixture.spec.ts
```

Windows本项目实际使用.local/tools/node-v24.21.0-win-x64与.local/browsers。运行时给进程PATH加入该Node目录，并设置PLAYWRIGHT_BROWSERS_PATH为该浏览器目录即可，不需要修改系统PATH。

## 演示顺序与页面证据

1. 申请人提交，显示第1级、期限和本人记录；不能看到审核按钮。
2. 同院系管理员初审后进入第2级，仍无整室占用；终审完成后查看座位不可预约。
3. 退回补充并重提，原期限不延长；展示包含revision与requestId的历史。
4. 驳回不形成占用；已通过申请撤销后重新查询可用性，不能只凭撤销提示推断其他限制消失。
5. 独立测试环境展示真实扫描器把待审和待重提记录转为EXPIRED及系统请求编号。
6. 打开两个审批会话制造旧版本冲突，观察409编号和重新确认要求；窄屏核对按钮换行及长编号。

下面两张是明确使用fixture的本地页面截图，证明布局与文案，不能用作真实后端执行证据：[1440px审批台](images/m2-yz-desktop.png)、[380px审批历史](images/m2-yz-mobile-history.png)。

## 剩余风险、协作和交付出口

- 上游PR #2的fork工作流仍需上游维护者批准运行；个人fork成功不替代这项批准。PR依赖后端PR #1，后者合并后须由负责人核对/调整base，公共文件冲突由成员协调。
- 降低新流程默认级数时，后端policy可能遗漏存量两级节点权限；默认steps=2本次验收通过。见 [有效同伴评审草稿与复现](m2-yz-peer-review.md)，配置变更前需由后端负责人确认，前端不能猜测额外权限。
- 本人需亲自阅读、理解代码并核对 [AI协作与工程伦理材料](m2-yz-ai-ethics.md)，补记真实人工修改/判断，不能把AI记录当作本人审查已完成。
- 按小组任务书，本人还需实际提交一次有效同伴评审，且本PR至少经一名非主责成员评审。草稿尚未发布，AI不代签。团队部署、发布tag、回退和最终课堂版本由负责人以通过CI的团队提交安排，本次未代做或伪造。
