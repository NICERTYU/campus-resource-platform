# 程一帆 M1 同伴评审草稿

状态：本地可复现问题与评审草稿，尚未发布到PR，尚无同学签字。供程一帆确认后向M1后端负责人张子杨提交；不能将此文件计为“已完成一次有效人工PR评审”。

## 发现：预约列表的all分类与契约不一致

位置：[资源查询逻辑](../backend/resource-app/src/main/java/cn/campus/resource/ResourceService.java)的mine(User,int,int,String)，以及[接口契约](api-contract.md)的列表段落。

当前契约声明statusGroup=current|history|all、默认all。实现只允许null/current/history，显式all返回400。省略参数则会查询全部。按文档构造请求的调用方会失败；本次页面使用current/history，未触发该基线问题。

最小复现：使用已登录账号的同一个会话分别请求GET /api/v1/reservations/mine和GET /api/v1/reservations/mine?statusGroup=all。前者200，后者400，错误码INVALID_INPUT，提示分类应为current或history。可在本地测试平台浏览器控制台使用同源fetch查看状态；不要公开复制会话Cookie。

建议：由接口负责人确定all是否作为有效枚举。如果保留契约则将all规范化为null并补充显式all/分页/本人范围测试；如果暂不支持则调整契约为“省略参数查询全部”，同时通知调用方。确认后再形成独立修复，不在本次页面任务中擅自变更既有公共枚举。

## 本次PR应请同学重点复核

1. 复用必须在服务端检查本人终态源记录，再重新使用现行预约规则；幂等结果不能绕过正常归属校验或导致重复创建。
2. V4事件防重与NO_SHOW写入同一事务，维修取消不产生违约事件；站内提示不能冒充外部送达。
3. 统计的只读权限、时间窗口、维修并集与零容量；当前配置口径是否符合课程演示需求。
4. 本地测试适配只接受回环PostgreSQL，为每类创建随机库且只删除自建库；默认Docker CI路径保持原样。

需本人补充：真实PR链接、提交日期、评审人、评论链接、后端负责人回复及最终处理结论。公共接口、迁移和跨模块测试适配需至少两名实际成员复核。
