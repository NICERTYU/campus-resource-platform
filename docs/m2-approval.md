# M2 多级审批与超时撤销

负责人：乔梦涵。此方案按小组任务书完成 M2 后端及数据库迁移；两级审批、24 小时期限和退回规则已由本人开发会话中的用户确认。前端仅作接口兼容与演示适配，页面验收仍需与杨臻共同复核。

## 状态与权限

新申请默认经过院系初审（第1级）、资源终审（第2级），只有终审成功同步资源占用才为 APPROVED。PENDING 表示等待当前节点，RETURNED 表示初审退回申请人补充用途，PROCESSING 表示资源建立或解除尚未确认；终态为 APPROVED、REJECTED、WITHDRAWN、EXPIRED。

初审通过进入终审，不占用座位；任一级可驳回。初审退回进入 RETURNED，申请人可修改用途并重提；终审退回回到初审。退回和重提不延长原期限，教室和时段保持不变。申请人可撤销自己的待审、退回和同步建立中的申请；已批准申请仅在开始前撤销。管理员可执行同样的撤销。

审核人须有 ADMIN 角色。初审还须与申请人院系一致（申请人无院系时仅按管理员与配置判断）；终审按资源管理员范围。每一级另可配置管理员 ID 白名单，空值表示该级所有符合上述条件的管理员。默认允许同一管理员操作两个节点，课程演示可使用现有管理员；实际分权时配置不同白名单。前端隐藏按钮不能替代服务端权限判断。

每次审核、退回和重提带 revision；服务端锁定申请后比较版本，不匹配返回409。已迁移的单级历史记录维持 totalSteps=1，兼容原 comment 请求。新记录必须携带 revision，不能使用旧客户端一次操作完成两级。审核历史永久保留操作者、节点、意见、时间和请求编号；历史数据缺少的审批人、时间不伪造。

## 超时与可靠性

approvalDeadline 在创建时持久化，为 min(创建时刻+配置期限,申请开始时刻)。到达该时刻不再接受审核或重提。扫描 PENDING、RETURNED 及正在同步 APPROVE 的记录，重启后继续补扫。待审/退回直接转 EXPIRED；同步中的建立由更高 sourceVersion 的解除意图取代，确认解除后才转 EXPIRED。撤销同步中的建立采用同样补偿方式。旧 outbox 标记 SUPERSEDED；即使旧请求已经发出，资源端版本墓碑阻止它恢复占用。

新流程的资源409冲突及网络/5xx故障保留 PROCESSING、lastError，持久化重试，不重复审批。超过截止时间后转为解除补偿。M3和旧单级申请保留原409回到待处理状态的兼容行为。所有资源调用仍通过 HTTP，不直访资源库。

自动解除使用申请人身份归属进行审计及内部权限检查；若账号已失去申请权限，解除会保持处理中并记录错误，须由管理员排障，不能显示已经释放。此边界应与公共层负责人复核。

## 接口

- GET /api/v1/classroom-applications/policy：steps、timeout、当前账号可审核的 reviewSteps，legacyReview 表示能否审核旧单级记录。
- 列表及写响应追加 currentStep、totalSteps、revision、approvalDeadline、applicantDepartment。
- POST /classroom-applications/{id}/approve、reject、return：{comment,revision}；驳回和退回须有意见。
- POST /classroom-applications/{id}/resubmit：{purpose,revision}；仅原申请人且仍有申请资格。
- POST /classroom-applications/{id}/withdraw：保持空对象兼容。处理中可撤销建立；已在解除中不可重复发新意图。
- GET /classroom-applications/{id}/history：仅申请人或管理员，可查询逐次事件；他人403，不存在404。

列表遵守原分页及本人范围，401/403/409沿用 {code,message,requestId}。202表示尚未确认资源状态；相同幂等键重放原响应，查询列表取得最新状态。

## 配置与迁移

M2_APPROVAL_TIMEOUT 默认 PT24H；M2_FIRST_REVIEWER_IDS、M2_FINAL_REVIEWER_IDS 为逗号分隔的正整数账号ID。WORKFLOW_APPROVAL_STEPS 默认2，兼容测试可指定1。M2_EXPIRY_SCAN_MS 默认5000。配置变更仅影响新申请的期限和级数，白名单实时按进程配置校验，修改后重启流程应用。

流程 schema 使用新增 V3，保留 V1/V2及旧申请、outbox、幂等与审计。旧待审按创建时间和开始时间回填期限；旧 APPROVED 不重新审批。新增 history 表不回填不存在的人工证据。需验证空库重放、V2升级的数据保留和重复迁移无操作。回退旧应用前必须评估新增状态，不承诺直接切旧镜像兼容。

## 验收与个人证据

T22：两级、退回与重提、记录可见性及审批白名单；T23：版本并发、幂等、409/503重试；T24：期限边界、重启补扫、处理中超时及撤销补偿；T25：旧数据升级、空库重放与约束；T26：真实 HTTP 和浏览器两级审批。

测试命令、实际结果、人工复核与剩余风险在交付记录中补充。PR须由非主责成员评审；schema及跨模块变更按任务书要求至少两名成员复核。本文件及测试不能代替真实评审、人工阅读或个人伦理署名。
