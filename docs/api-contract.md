# MVP 接口契约

外部接口前缀 `/api/v1`，JSON 使用 camelCase。错误为 `{code, message, requestId}`，状态码遵循技术方案。列表直接返回数组；预约、违约、申请、工单与操作日志支持 `page`（从1开始，默认1）、`pageSize`（默认50，范围1至200），按稳定时间/ID倒序分页，越界参数400。个人预约列表另支持 `statusGroup=current|history|all`（默认all），先按当前/历史筛选再分页。空间选择列表首版上限200条。所有时间使用带偏移 ISO 8601，校园时区 Asia/Shanghai。浏览器同源 Cookie 登录，写请求携带 `X-CSRF-TOKEN`，可携带 `Idempotency-Key`（同用户、动作、键重复返回原结果，同键不同内容409）。幂等结果在首版永久保留，暂无自动清理；资源请求键最大150字符，流程请求键最大128字符。

## 资源应用（8081）

- `GET /auth/csrf` → `{token, headerName}`，先取得匿名会话的 token；登录后重新取 token。
- `POST /auth/login` `{username,password}` → 用户；`GET /auth/me` → 用户；`POST /auth/logout`。
- 用户：`{id,username,displayName,department,roles}`。角色 `STUDENT,APPLICANT,ADMIN,TECHNICIAN`。
- `GET /classrooms` → `[{id,name,building,floor,department,openTime,closeTime,enabled,seatCount}]`。
- `GET /seats/availability?classroomId=&startTime=&endTime=&hasPower=` → `[{id,classroomId,label,hasPower,available,restrictionTypes}]`，不可用不泄露他人资料。
- `POST /reservations` `{seatId,startTime,endTime}`；`GET /reservations/mine`；`POST /reservations/{id}/cancel`；`POST /reservations/{id}/check-in` `{code}`。
- 预约：`{id,seatId,seatLabel,classroomId,classroomName,startTime,endTime,status,checkInDeadline,checkedInAt,cancelReason}`，状态 `PENDING,CHECKED_IN,CANCELLED,NO_SHOW,COMPLETED`。
- `GET /violations/mine` → 数组；管理端 `GET /admin/users`，`GET /admin/check-in-codes`，`GET /admin/audit-logs`，`GET/PUT /admin/settings`（`{maxReservationHours}`），`POST /admin/classrooms`，`PUT /admin/classrooms/{id}`，`POST /admin/classrooms/{id}/seats`，`PUT /admin/seats/{id}`。
- 签到码由管理员每天为每教室设置，`PUT /admin/check-in-codes/{classroomId}` `{code}`，存储摘要，查询只返回已设置的教室及日期。演示初始化设置当天码 `246810`。

## 流程应用（8082）

- `GET/POST /classroom-applications`（创建 `{classroomId,startTime,endTime,purpose}`）。
- `POST /classroom-applications/{id}/approve` 或 `/reject` `{comment}`，`/withdraw` 空对象。
- 申请：`{id,classroomId,classroomName,applicantId,applicantName,startTime,endTime,purpose,status,comment,pendingAction,lastError}`。状态 `PENDING,PROCESSING,APPROVED,REJECTED,WITHDRAWN`，未确认远端前显示 PROCESSING。
- `GET/POST /repair-orders`（创建 `{classroomId,description}`）。
- `POST /repair-orders/{id}/assign` `{technicianId}`，`/start` 空对象，`/complete` `{note}`，`/accept` `{comment}`，`/return` `{comment}`。
- 工单：`{id,classroomId,classroomName,reporterId,reporterName,technicianId,technicianName,description,status,note,comment,pendingAction,lastError}`。状态 `REPORTED,ASSIGNED,PROCESSING,IN_PROGRESS,AWAITING_ACCEPTANCE,ACCEPTED`。
- 列表：管理员看全部，申请人仅自己，工单上报人或分派维修人员可查看。维修人员只能处理分派自己的工单。

## 内部接口

只在容器网络提供，均必须带 `X-Module-Key`。环境变量 `MODULE_KEY`，至少32字符；浏览器无法通过公共 Nginx 访问内部接口。

- `POST /internal/v1/identity` `{cookie,csrfToken,write}` → 用户。资源应用解析 JSESSIONID 并检查实际 Session；write=true 时核对该 Session 的 CSRF。流程应用每次将收到的 Cookie/CSRF 转交验证，不接受客户端自报身份。
- `GET /internal/v1/classrooms/{id}` → 教室；`GET /internal/v1/users` → 可用于分派的用户。流程应用校验院系/资格。
- `PUT /internal/v1/restrictions/{sourceType}/{sourceId}`，请求 `{version,active,classroomId,startTime,endTime,actorId,requestId}`，M2 使用申请区间，M3 建立时使用服务端当前时间且 endTime=null。
- 返回 `{version,active,applied}`。相同版本同参数幂等；旧版本不执行并返回现有版本；相同版本不同内容409；解除保留版本墓碑。M2 创建检查个人预约、其他M2及M3；M3允许既有记录及多来源并存。来源归属教室不能变更。内部身份与命令响应携带请求标识。

跨应用操作先落库 outbox，网络在本地事务外，再提交最终状态。失败保留 pendingAction/lastError，每5秒重试，资源冲突回到先前状态供用户调整；超时保留处理中。解除使用递增版本，不允许旧建立请求重新生效。
