# 运行与部署

采用Java21、Spring Boot4.1.1、PostgreSQL18.6及Node24 LTS。选型依据[Spring系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Node官方LTS下载](https://nodejs.org/en/download/)与[PostgreSQL版本记录](https://www.postgresql.org/docs/18/release.html)。具体依赖由Maven Wrapper和前端锁文件固定。

## 本地运行

前置：Docker引擎、Java21、Node24 LTS、Python3。Mac使用 `/usr/libexec/java_home -v 21`，其他系统设置 `JAVA_HOME`。Maven由仓库Wrapper自动下载，无需安装全局Maven。

```bash
./scripts/dev.sh
```

脚本生成 `.local/dev.env` 随机凭据，启动 `campus-dev` 数据库（仅本机54329）、构建后端、显式初始化样例数据、启动8081/8082后端及5173前端。按Ctrl+C停止应用；数据库与数据卷保留，再次运行不覆盖已修改账号/教室。日志在 `.local/resource.log`、`.local/workflow.log`。确认数据库数据无需保留后才使用 `docker compose ... down -v`。

学生 `student`、`student2`，艺术学院学生 `outsider`，申请人 `applicant`，管理员 `admin`，维修员 `technician`。演示密码 `Campus123!`。学生/申请人属于CS；教室为公共图书馆、CS专属、ART专属各24座。演示初始化设置当天签到码246810，跨天需管理员重新设置。账户仅由显式初始化建立；普通启动不会造数据或重置状态。以上固定口令仅用于本地演示；云测试环境的六个预置账号已改用独立随机强口令。

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

每项目独立数据库卷、网络与随机凭据。应用内存上限640MiB/个、数据库768MiB、Web128MiB为起始配额，单环境约2.1GiB上限；还须给操作系统留余量。这些配置上限不代表已经验证的负载容量。本次云主机仅运行一套测试环境；同时常驻测试与生产仍须另行评估和实测，1200元预算没有被当作已验证报价。

`scripts/deploy.sh ENV_FILE campus-test COMMIT` 可在干净、指定提交的Linux工作目录构建并部署。`scripts/seed.sh` 是显式演示初始化，不属于每次发布的动作。首次管理员账号和签到码由运营者初始化；后续学生从登录页自行注册，管理员在“账号管理”维护账号和角色。云主机、SSH、HTTPS等配置不保存于Git。

## 注册与账号使用

学生在登录页点击“注册学生账号”，填写用户名、姓名、所属院系、密码及确认密码，注册成功后使用新账号登录。用户名3至32位英文字母、数字或下划线且必须以字母开头；密码至少8个字符，UTF-8最多72字节。注册仅获得学生权限，不直接授予管理员、维修或整室申请权限。

管理员登录后从侧栏进入“账号管理”，可搜索全部账号、新增账号、编辑角色/院系和启停状态、重置密码。编辑和改密对话框显示明确的目标账号；不能停用自己或取消自己的管理员角色。账号停用后保留历史记录，重新启用即可再次登录。改密、角色、院系或状态变化会注销该账号原有登录；仅姓名修改不注销。重置自己的密码后应使用新密码重新登录。

V3迁移保留现有用户及业务数据，增加大小写不敏感的用户名唯一索引；正常发布不会重置原有密码。新增维修账号时分配TECHNICIAN角色并保持启用，即可在派单选择器中使用。云端初始随机密码保存在部署者本地私有凭据文件中，不在网页或仓库公开。

## 2026-10-15云测试部署记录

已在Ubuntu24.04 x86_64主机完成一套测试环境部署，主机具有1.6GiB内存及2GiB swap，运行Docker29.1.3与Compose2.40.3。Web、资源应用、流程应用及PostgreSQL四个服务均为healthy，公开HTTP入口可达。六个预置账号已分别设置不同的随机强口令，旧演示口令登录已验证被拒绝。

真实云端HTTP验证已通过：个人预约及取消；整室申请审批后阻止个人预约，撤销后恢复；报修派单、进入维修后建立限制，提交完工仍保留限制，验收后解除对应来源；公共入口访问内部接口返回404。

修正后的远程安装脚本已经通过真实SSH的 `bash -s` 模式重跑：两个应用的健康检查均实际执行，部署current记录写入 `af35206`，资源和流程共4条迁移记录均为 `success=t`，迁移记录文件已保存，四个容器仍为healthy。

此主机当前仅承载一套测试环境。后续已启用受信任的公网IP HTTPS证书和HTTP跳转，配置见下节。独立域名、生产审批发布、线上回退和实际负载容量尚未验证，现有启动与业务冒烟结果不构成这些项目的验收结论。

## HTTPS入口与证书续期

云端使用宿主机Nginx终止TLS，再代理到容器Web。服务器环境文件应设置 `WEB_BIND=127.0.0.1`、`WEB_PORT=8080`、`COOKIE_SECURE=true`，应用接口及数据库仍不直接映射公网。HTTP仅保留ACME验证目录，其余请求308跳转到固定HTTPS地址。GitHub环境的 `SMOKE_URL` 同时改为HTTPS入口。

[Nginx模板](../deploy/https/nginx.conf.template)使用 `${PUBLIC_HOST}` 和 `${ACME_CERT_NAME}` 两个占位。只替换这两个变量，保留 `$request_uri` 等Nginx变量。证书私钥留在服务器，不进入仓库或发布制品。首次启用先使用仅监听80的ACME验证配置，签发后再启用包含443和证书路径的完整模板。

Let’s Encrypt已支持公网IP证书，要求短期证书profile；使用Certbot5.4及以上版本的webroot功能，见[官方IP证书说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot/)。本次客户端固定为5.8.0，安装在服务器 `/opt/certbot` 虚拟环境。签发示例（替换为运营者控制的公网IP和证书名称）：

```bash
export PUBLIC_HOST=YOUR_PUBLIC_IP
export ACME_CERT_NAME=campus-ip
/opt/certbot/bin/certbot certonly --non-interactive --agree-tos \
  --register-unsafely-without-email --preferred-profile shortlived \
  --webroot --webroot-path /var/lib/letsencrypt \
  --ip-address "$PUBLIC_HOST" --cert-name "$ACME_CERT_NAME"
envsubst '${PUBLIC_HOST} ${ACME_CERT_NAME}' < deploy/https/nginx.conf.template \
  > /etc/nginx/sites-available/campus
nginx -t
systemctl reload nginx
```

可用 `--email` 登记运营邮箱，替换不登记邮箱选项。IP证书有效期约六天，必须自动续期。将[重载hook](../deploy/https/reload-nginx.sh)安装为root所有的 `/usr/local/sbin/campus-reload-nginx`（0755），将[service](../deploy/https/campus-certbot-renew.service)及[timer](../deploy/https/campus-certbot-renew.timer)安装到 `/etc/systemd/system/`，启用 `campus-certbot-renew.timer`。定时器每六小时检查，成功续期后先验证Nginx配置再重载。用 `certbot renew --dry-run --run-deploy-hooks --no-random-sleep-on-renew` 验证续期链路；测试证书不替换现有正式证书。

部署后验证证书信任和IP匹配、HTTP跳转、HTTPS登录及Secure Cookie、内部路径404和自动续期状态。生产构建隐藏演示账号和固定密码，使用实际配置的随机口令登录。前端请求编号支持缺少 `crypto.randomUUID` 的浏览器，并保留失败重试的幂等键；浏览器回归覆盖登录、退出及错误口令反馈。

## CI与发布

[CI配置](../.github/workflows/ci.yml) 对提交/PR执行静态、业务、真实HTTP、浏览器验收，然后构建3个镜像，以Git commit命名，打包镜像及源码为制品。只有全部检查通过才可发布。

CI首次启动双应用时最多等待前端入口300秒，覆盖镜像下载、构建和初始化。`scripts/wait-health.sh HTTP_URL [TIMEOUT_SECONDS]` 默认等待60秒，超时参数为1–86400的正整数；每次连接及请求最多5秒，并限制在剩余等待时间内。就绪退出0，超时退出1，参数错误退出2。

测试自动部署：`AUTO_DEPLOY_TEST=true` 必须配置为GitHub仓库变量或组织变量，不能仅配置在environment中；`deploy-test` 的job-level `if` 在进入environment前求值，读取不到仅在该environment定义的变量。创建 `campus-test` environment，在其中设置 `SSH_HOST, SSH_USER, SSH_PORT, SSH_KEY, SSH_KNOWN_HOSTS, DEPLOY_ROOT, DEPLOY_ENV_FILE` Secrets，并将 `SMOKE_URL` 配置为该environment的变量，供HTTP冒烟步骤读取。SSH known_hosts需运维预先核对，执行器及成员的SSH来源须加入云安全组。服务器需Docker Compose、Python3、flock，实际部署文件路径必须为绝对路径，凭据文件留在服务器。本次测试主机和SSH部署配置已接入，`AUTO_DEPLOY_TEST` 仓库变量已启用，测试environment的发布分支限定为main。

远程脚本通过SSH的 `bash -s` 从标准输入执行。Compose的 `exec` 即使使用 `-T` 也可能读取标准输入，必须同时指定 `--interactive=false`；后台 `up` 使用 `</dev/null` 隔离标准输入，防止吞掉后续健康检查和版本记录语句。[远程安装回归测试](../tests/test_remote_install.py)新增4项检查，覆盖完整安装记录、`up` 不能消耗脚本输入、首次安装记录及失败时保持既有版本记录；标准输入修复及4项回归已提交为 `8807b5a`；这些本地回归已通过，真实SSH重跑结果见上节。

每次自动发布的触发提交、verify和deploy-test结论、部署HTTP冒烟结果，均以[GitHub Actions实际运行记录](https://github.com/NICERTYU/campus-resource-platform/actions/workflows/ci.yml)为准。

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

回退只切换应用，不逆向执行Flyway迁移；破坏性schema修改必须先备份并另行恢复数据库，不能承诺换旧镜像就恢复数据。云测试部署和SSH安装已实际完成；生产审批发布及线上回退演示尚须在相应目标环境实际完成。
