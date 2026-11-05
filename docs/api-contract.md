# MVP 接口契约

外部接口前缀 `/api/v1`，JSON 使用 camelCase。错误为 `{code, message, requestId}`，状态码遵循技术方案。列表直接返回数组；预约、违约、申请、工单与操作日志支持 `page`（从1开始，默认1）、`pageSize`（默认50，范围1至200），按稳定时间/ID倒序分页，越界参数400。个人预约列表另支持 `statusGroup=current|history|all`（默认all），先按当前/历史筛选再分页。空间选择列表首版上限200条。所有时间使用带偏移 ISO 8601，校园时区 Asia/Shanghai。浏览器同源 Cookie 登录，写请求携带 `X-CSRF-TOKEN`，可携带 `Idempotency-Key`（同用户、动作、键重复返回原结果，同键不同内容409）。幂等结果在首版永久保留，暂无自动清理；资源请求键最大150字符，流程请求键最大128字符。

## 资源应用（8081）

- `GET /auth/csrf` → `{token, headerName}`，先取得匿名会话的 token；登录后重新取 token。
- `POST /auth/login` `{username,password}` → 用户；`GET /auth/me` → 用户；`POST /auth/logout`。
- `POST /auth/register` `{username,password,displayName,department}` → 201 用户，不自动登录。匿名调用仍需先取得CSRF token；角色固定为STUDENT，院系仅CS或ART。用户名3至32位、英文字母开头，只含字母/数字/下划线，保存为小写且登录/唯一性不区分大小写；姓名去除首尾空白后1至120字符；密码至少8个Unicode字符且UTF-8编码不超过72字节。注册成功后返回登录页，不在URL或响应中携带密码。重复用户名409，无效参数或试图授予其他角色400。
- 用户：`{id,username,displayName,department,roles}`。角色 `STUDENT,APPLICANT,ADMIN,TECHNICIAN`。
- `GET /classrooms` → `[{id,name,building,floor,department,openTime,closeTime,enabled,seatCount}]`。
- `GET /seats/availability?classroomId=&startTime=&endTime=&hasPower=` → `[{id,classroomId,label,hasPower,available,restrictionTypes}]`，不可用不泄露他人资料。
- `POST /reservations` `{seatId,startTime,endTime}`；`GET /reservations/mine`；`POST /reservations/{id}/cancel`；`POST /reservations/{id}/check-in` `{code}`。
- `POST /reservations/{id}/reuse` `{startTime,endTime}` → 使用本人历史预约的座位发起一条新的预约；服务端重新校验时间、权限、开放时间、冲突和维修/审批限制，不复制原预约状态。
- 预约：`{id,seatId,seatLabel,classroomId,classroomName,startTime,endTime,status,checkInDeadline,checkedInAt,cancelReason}`，状态 `PENDING,CHECKED_IN,CANCELLED,NO_SHOW,COMPLETED`。
- `GET /notifications/mine?page=1&pageSize=50` → `{id,reservationId,type,scheduledAt,status,deliveredAt}`；通知类型为 `PRE_START_REMINDER`、`NO_SHOW_WARNING`、`CANCELLED_NOTICE`。同一预约和类型只产生一条事件，扫描重复执行安全。
- `GET /violations/mine` → 数组；管理端 `GET /admin/users`，`GET /admin/check-in-codes`，`GET /admin/audit-logs`，`GET/PUT /admin/settings`（`{maxReservationHours}`），`POST /admin/classrooms`，`PUT /admin/classrooms/{id}`，`POST /admin/classrooms/{id}/seats`，`PUT /admin/seats/{id}`。
- 签到码由管理员每天为每教室设置，`PUT /admin/check-in-codes/{classroomId}` `{code}`，存储摘要，查询只返回已设置的教室及日期。演示初始化设置当天码 `246810`。
- `GET /admin/seat-statistics?from=&to=&classroomId=` → 按教室返回 `seatCount,reservedHours,usedHours,utilizationRate,cancelledCount,noShowCount,maintenanceHours`；仅ADMIN可访问，统计范围使用带偏移的 ISO 8601 时间。

### 管理员账号管理

以下接口仅限ADMIN；账号不提供硬删除，停用后保留预约、申请、工单和审计历史。

- `GET /admin/accounts?page=1&pageSize=20&query=` → 账号数组，包含已停用账号。按用户名/姓名过滤后按ID升序分页，pageSize范围1至100，query最多120字符。
- `POST /admin/accounts` `{username,password,displayName,department,roles,enabled}` → 201 账号。角色为非空的 `STUDENT/APPLICANT/TECHNICIAN/ADMIN` 集合，enabled必填；院系可为空，非空时最多80字符。用户名、姓名及密码规则同注册。
- `PUT /admin/accounts/{id}` `{displayName,department,roles,enabled}` → 更新后的账号，用户名不可修改。
- `POST /admin/accounts/{id}/password` `{password}` → `{success:true}`，管理员为目标设置新密码。
- 账号为 `{id,username,displayName,department,roles,enabled}`；任何账号接口均不返回密码或密码摘要。密码仅以BCrypt存储；包含密码的幂等请求指纹使用服务端密钥HMAC，幂等结果和审计不保存明文密码。
- 不能停用自己的账号或移除自己的ADMIN角色，必须保留至少一个启用的管理员。修改角色、院系、启停状态或密码后使目标账号已有登录失效；只改姓名保留会话。重新登录后使用新权限，重放同一幂等请求不会再次注销新会话。
- 派单使用 `GET /admin/users?role=TECHNICIAN`，只返回启用的维修人员；按角色筛选不受其他学生账号数量影响。原无role接口仍为兼容的人员选择列表，新账号管理必须使用分页的 `/admin/accounts`。

## 流程应用（8082）

M2 新申请使用两级审批及版本校验，完整状态矩阵、请求示例、配置和超时补偿见 [M2 多级审批](m2-approval.md)。原单级历史记录继续按原流程处理。

- `GET/POST /classroom-applications`（创建 `{classroomId,startTime,endTime,purpose}`）。
- `POST /classroom-applications/{id}/approve`、`/reject`、`/return` `{comment,revision}`；`/resubmit` `{purpose,revision}`；`/withdraw` 空对象。新两级申请必须带revision；旧单级记录兼容原comment请求。
- `GET /classroom-applications/policy` → `{steps,timeout,reviewSteps,legacyReview}`；`GET /classroom-applications/{id}/history` → 逐次事件，仅申请人或管理员可读。
- 申请：`{id,classroomId,classroomName,applicantId,applicantName,startTime,endTime,purpose,status,comment,pendingAction,lastError,currentStep,totalSteps,revision,approvalDeadline,applicantDepartment}`。状态 `PENDING,RETURNED,PROCESSING,APPROVED,REJECTED,WITHDRAWN,EXPIRED`，未确认远端前显示 PROCESSING；初审通过仍为PENDING并进入第2级。
- `GET/POST /repair-orders`（创建 `{classroomId,description}`）。
- `POST /repair-orders/{id}/assign`：首次派单 `{technicianId}`，改派 `{technicianId,reason,expectedTechnicianId}`；`/start` 空对象，`/complete` `{note}`，`/accept` `{comment}`，`/return` `{comment}`（返修原因必填）。
- 工单：`{id,classroomId,classroomName,reporterId,reporterName,technicianId,technicianName,description,status,note,comment,pendingAction,lastError,createdAt,repairDeadline}`。旧工单截止时间可暂为空，由扫描器按原始上报时间补齐。状态 `REPORTED,ASSIGNED,PROCESSING,IN_PROGRESS,AWAITING_ACCEPTANCE,ACCEPTED`。
- 列表：管理员看全部，申请人仅自己，工单上报人或分派维修人员可查看。维修人员只能处理分派自己的工单。

### M2 页面消费与错误反馈

policy 的 steps 表示新建流程配置，已有记录的节点以自身 totalSteps/currentStep 为准。reviewSteps 与 legacyReview 来自当前账号的服务端策略；两级初审还须按记录 applicantDepartment 核对院系。策略请求失败时页面关闭审核操作，不回退成“ADMIN均可审批”。所有判定仍由后端最终校验。

新两级记录示例：`{"id":101,"status":"PENDING","currentStep":2,"totalSteps":2,"revision":1,"approvalDeadline":"2026-10-28T09:00:00+08:00","applicantDepartment":"CS"}`；终审前仍未占用。策略示例：`{"steps":2,"timeout":"PT24H","reviewSteps":[2],"legacyReview":true}` 表示此账号只可操作两级流程的终审，旧单级记录另按legacyReview判断。

审核/退回/重提使用用户确认时的revision。409后刷新并重新确认，不自动换版本重放。错误例：`{"code":"STATE_CONFLICT","message":"申请已被更新，请刷新后重新操作","requestId":"m2-conflict-example"}`。页面展示响应的真实requestId；PROCESSING的排障编号从对应revision的审批历史取得，不以客户端随机值冒充。客户端时钟到期只关闭审核并提示等待服务端确认，不能自行把状态写成EXPIRED。

## M3 增量开发契约

M3-03 沿用既有状态机。返修原因须由服务端校验为1至1000字；改派只允许ASSIGNED，须提供原维修人员expectedTechnicianId、新technicianId及1至1000字reason。原首次派单请求保持兼容；过期负责人、非法状态返回409，权限不足403。`GET /repair-orders/{id}/history`仅管理员、报修人和当前维修人员可读，保留逐次原因、人员变化、操作者、时间和请求号。

M3-01 默认处理期限为PT24H、扫描间隔60000毫秒，可经M3_REPAIR_TIMEOUT、M3_REPAIR_SCAN_MS调整。上报时固定截止时间，修改配置仅影响新工单；旧工单缺少截止时间时按created_at与首次处理时配置补齐。REPORTED、ASSIGNED、IN_PROGRESS及PROCESSING/START适用，到达截止时间触发一次一级升级；待验收和已验收不升级。返修不重置期限。升级与管理员站内提醒是同一条持久化事件，具有工单/级别唯一约束；页面显示不代表邮件或微信送达。不复用资源限制outbox，不变更维修状态或资源版本。部署首次启用可能升级旧积压工单，应由管理员评估。

M3-02 `POST /repair-orders/{id}/review`接受`{rating,comment}`：rating为1至5整数，comment可空且最多1000字；仅报修人对ACCEPTED工单评价，每单一次。相同幂等键和内容返回原结果；重复评价409、越权403、无效输入400。`GET /repair-orders/{id}/review`仅管理员或报修人可读，未评价返回204。评价保留评价人和时间，不对外公开或改变维修状态。

`GET /repair-orders/escalations?page=1&pageSize=50`仅ADMIN可读，分页规则沿用现有列表，按升级时间、ID倒序返回`{id,repairId,level,observedStatus,deadline,escalatedAt,requestId,notificationStatus}`。AVAILABLE仅表示站内提醒已持久化、可供管理员查询，不表示外部通知送达。扫描采用ID分页并逐单事务处理，失败记录不会阻塞后续分页。

M3-04 验证上报至评价闭环、返修/改派、重复和并发、超时恢复，以及无其他占用干扰的多个维修来源。沿用现有HTTP和outbox契约；公共通知推送仍由公共层维护，站内事件不冒充外部送达。

## 内部接口

只在容器网络提供，均必须带 `X-Module-Key`。环境变量 `MODULE_KEY`，至少32字符；浏览器无法通过公共 Nginx 访问内部接口。

- `POST /internal/v1/identity` `{cookie,csrfToken,write}` → 用户。资源应用解析 JSESSIONID 并检查实际 Session；write=true 时核对该 Session 的 CSRF。流程应用每次将收到的 Cookie/CSRF 转交验证，不接受客户端自报身份。
- `GET /internal/v1/classrooms/{id}` → 教室；`GET /internal/v1/users` → 可用于分派的用户。流程应用校验院系/资格。
- 分派维修人员时使用 `/internal/v1/users?role=TECHNICIAN`，先按有效角色筛选，不因前200个学生账号占用名额而漏掉新维修人员。
- `PUT /internal/v1/restrictions/{sourceType}/{sourceId}`，请求 `{version,active,classroomId,startTime,endTime,actorId,requestId}`，M2 使用申请区间，M3 建立时使用服务端当前时间且 endTime=null。
- 返回 `{version,active,applied}`。相同版本同参数幂等；旧版本不执行并返回现有版本；相同版本不同内容409；解除保留版本墓碑。M2 创建检查个人预约、其他M2及M3；M3允许既有记录及多来源并存。来源归属教室不能变更。内部身份与命令响应携带请求标识。

跨应用操作先落库 outbox，网络在本地事务外，再提交最终状态。网络/5xx失败保留 pendingAction/lastError，每5秒重试。新两级M2的409资源冲突也保持PROCESSING并重试，超过审批期限后转为解除补偿；旧单级M2及M3的确定性资源冲突回到先前状态。解除使用递增版本，不允许旧建立请求重新生效；被超时或撤销替代的旧意图为SUPERSEDED。

M3增量验证（2026-11-03）：Java 21/PostgreSQL 18.6双后端91项、前端99项、真实HTTP/故障恢复/数据库隔离21项及Chromium主流程4项通过。覆盖旧库迁移、时间边界、并发防重、真实进程重启、失败回滚、跨页失败隔离、锁定工单跳过及查询权限。初始派单沿用旧请求指纹，兼容历史幂等结果。外部推送未接入；人工评审和远端CI尚未验证。
