# 运行与部署

采用Java21、Spring Boot4.1.1、PostgreSQL18.6及Node24 LTS。选型依据[Spring系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Node官方LTS下载](https://nodejs.org/en/download/)与[PostgreSQL版本记录](https://www.postgresql.org/docs/18/release.html)。具体依赖由Maven Wrapper和前端锁文件固定。

## 本地运行

前置：Docker引擎、Java21、Node24 LTS、Python3。Mac使用 `/usr/libexec/java_home -v 21`，其他系统设置 `JAVA_HOME`。Maven由仓库Wrapper自动下载，无需安装全局Maven。

```bash
./scripts/dev.sh
```

脚本生成 `.local/dev.env` 随机凭据，启动 `campus-dev` 数据库（仅本机54329）、构建后端、显式初始化样例数据、启动8081/8082后端及5173前端。按Ctrl+C停止应用；数据库与数据卷保留，再次运行不覆盖已修改账号/教室。日志在 `.local/resource.log`、`.local/workflow.log`。确认数据库数据无需保留后才使用 `docker compose ... down -v`。

学生 `student`、`student2`，艺术学院学生 `outsider`，申请人 `applicant`，管理员 `admin`，维修员 `technician`。演示密码 `Campus123!`。学生/申请人属于CS；教室为公共图书馆、CS专属、ART专属各24座。演示初始化设置当天签到码246810，跨天需管理员重新设置。账户仅由显式初始化建立；普通启动不会造数据或重置状态。

## 自动验证

```bash
./scripts/test.sh
CAMPUS_BASE_URL=http://127.0.0.1:5173 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p test_system_http.py -v
cd frontend
npx playwright install chromium
npm run test:e2e
```

第一条执行文档/部署边界、JUnit与真实PostgreSQL/HTTP测试、Vitest与类型检查/构建。系统HTTP用例需启动真实双应用；未配置CAMPUS_BASE_URL时明确跳过，不能将跳过记录当通过。浏览器签到要求固定的测试时钟，以覆盖窗口而不等待现实整点。

单独启动浏览器验收环境（用新的终端；先停止正常开发应用避免端口冲突）：

```bash
export E2E_CLOCK=2026-10-12T01:50:00Z
./scripts/dev.sh
```

另一个终端使用相同E2E_CLOCK运行Playwright。日期应为现实日期之后、30天之内，CI动态取未来两天。只有资源应用显式 `e2e` profile使用固定Clock；正常启动和部署使用服务端真实时间。测试创建独立教室，不依赖固定座位编号。

PostgreSQL Testcontainers使用与Compose相同的数据库版本。Docker Hub暂时不可用时可从官方镜像镜像源下载并按原标签加载；不能以H2代替数据库测试。关闭Ryuk仅用于本地下载异常时的排障，手动清理测试容器，CI默认开启资源清理。

## Compose部署与环境隔离

```bash
mkdir -p .local
cp deploy/.env.example .local/test.env
# 编辑4个随机密钥、WEB_PORT及绑定地址
docker compose --env-file .local/test.env -p campus-test -f deploy/compose.yml up -d --build --wait
./scripts/seed.sh .local/test.env campus-test
```

生产另用 `.local/prod.env`、`campus-prod` 项目与不同Web端口，设置 `COOKIE_SECURE=true`，在外层Nginx配置HTTPS、域名和受控路由。业务数据库与两个内部应用没有宿主机端口映射；仅Web入口对外。内部 `/internal/` 及actuator路径不经公共入口暴露。初始数据库脚本为资源/流程建立各自owner及schema并撤销互访，业务操作不使用超级用户。

每项目独立数据库卷、网络与随机凭据。应用内存上限640MiB/个、数据库768MiB、Web128MiB为起始配额，单环境约2.1GiB上限；还须给操作系统留余量。是否能同时常驻测试与生产须结合实际华为云配置及负载验证，1200元预算没有被当作已验证报价。

`scripts/deploy.sh ENV_FILE campus-test COMMIT` 可在干净、指定提交的Linux工作目录构建并部署。`scripts/seed.sh` 是显式演示初始化，不属于每次发布的动作。首次生产账户及签到码由运营者准备；云主机、SSH、HTTPS等配置不保存于Git。

## CI与发布

[CI配置](../.github/workflows/ci.yml) 对提交/PR执行静态、业务、真实HTTP、浏览器验收，然后构建3个镜像，以Git commit命名，打包镜像及源码为制品。只有全部检查通过才可发布。

CI首次启动双应用时最多等待前端入口300秒，覆盖镜像下载、构建和初始化。`scripts/wait-health.sh HTTP_URL [TIMEOUT_SECONDS]` 默认等待60秒，超时参数为1–86400的正整数；每次连接及请求最多5秒，并限制在剩余等待时间内。就绪退出0，超时退出1，参数错误退出2。

测试自动部署：在GitHub配置 `AUTO_DEPLOY_TEST=true`；创建 `campus-test` environment，设置 `SSH_HOST, SSH_USER, SSH_PORT, SSH_KEY, SSH_KNOWN_HOSTS, DEPLOY_ROOT, DEPLOY_ENV_FILE` Secrets。`SMOKE_URL` 是环境公开入口URL变量。SSH known_hosts需运维预先核对，执行器及成员的SSH来源须加入云安全组。服务器需Docker Compose、Python3、flock，实际部署文件路径必须为绝对路径，凭据文件留在服务器。

发布只安装CI验证的镜像，不在服务器重新编译。测试任务串行，并在执行前核对main最新提交，避免旧流水线覆盖新版本。全组3个镜像一次更新，迁移自动执行；应用健康检查、Flyway历史与提交记录随发布保存。`/actuator/info` 在容器内部提供commit信息。

生产发布：创建 `campus-prod` environment，配置同名Secrets并在GitHub设置required reviewers；手动运行workflow并勾选production，经平台审批后安装本次已验证制品。应为验收版本创建Git tag，保留本次及上一版本镜像。仓库门禁设置必须通过verify后才能合并；平台设置不是本地YAML自动配置的能力。

## 备份与回退

发布前从对应项目数据库创建备份：

```bash
docker compose --env-file .local/prod.env -p campus-prod -f deploy/compose.yml exec -T db pg_dump -U campus_owner -d campus -Fc > campus-backup.dump
```

服务器保存环境的current/previous提交与迁移记录，保留旧镜像。确认schema向后兼容后使用：

```bash
./scripts/rollback.sh /srv/campus /srv/campus/secrets/prod.env campus-prod PREVIOUS_COMMIT
```

回退只切换应用，不逆向执行Flyway迁移；破坏性schema修改必须先备份并另行恢复数据库，不能承诺换旧镜像就恢复数据。实际华为云部署、GitHubSecret配置、生产审批与回退演示尚须由有访问权限的团队在目标环境完成。
