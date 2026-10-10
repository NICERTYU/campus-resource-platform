# 校园资源服务平台

Vue3 + Spring Boot双应用 + PostgreSQL的校园预约与报修MVP。产品与技术约定见[文档索引](docs/README.md)。

本地需Java21、Node24 LTS、Docker及Python3；运行：

```bash
./scripts/dev.sh
```

打开 `http://localhost:5173`。演示账号 `student`、`student2`、`outsider`、`applicant`、`admin`、`technician`，密码均为 `Campus123!`。初始化当天教室签到码为 `246810`，管理员可为当天重新设置。

```bash
./scripts/test.sh
```

容器部署、浏览器验收及云端配置见[运行与部署](docs/deployment.md)，实际测试结果见[验证记录](docs/implementation.md)。
