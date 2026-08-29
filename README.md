# 捌宿轻居 · 酒店记账 / 经营分析 / AI 定价系统

面向单体酒店的记账与经营分析系统：Excel 月度账单一键导入 → 费用智能归类 → 利润/盈亏平衡/渠道分析 → 入住率与营收预测 → AI 定价建议与解读。

## 技术栈

| 模块 | 技术 | 说明 |
|---|---|---|
| 前端 | Vue 3 + Vite + Pinia + ECharts | SPA，仅调主后端 `/api`（统一信封） |
| 主后端 | Java 21 + Spring Boot 3.3 + MyBatis-Plus | JWT 鉴权、业务逻辑、Excel 模板生成（POI） |
| 旁车服务 | Python 3.12 + FastAPI | Excel 解析 / 智能归类 / 时序预测 / DeepSeek 解读，无状态不碰库 |
| 数据库 | MySQL 8.0 | `db/schema.sql` 建表 + `db/seed.sql` 演示数据 |
| 部署 | Docker Compose | 四个服务一键起，仅前端对外暴露端口 |

## 架构

```
                 ┌──────────────────── 对外唯一入口 :${APP_PORT}
                 │
          ┌──────▼──────┐   /api 反代   ┌───────────────┐
          │  frontend   │─────────────▶│    backend    │
          │ nginx + Vue │              │ Spring Boot   │
          └─────────────┘              │    :8081      │
                                       └───┬───────┬───┘
                                           │       │ HTTP(内网)
                                    JDBC   │       ▼
                                           │  ┌───────────────┐
                                    ┌──────▼──┤   sidecar     │
                                    │  mysql  │ FastAPI:8001  │
                                    │  :3306  │ 解析/归类/预测  │
                                    └─────────┴───────────────┘
```

- `backend` 与 `sidecar` 共享 `storage-data` 卷（同路径 `/app/storage`）：主后端落盘 Excel 后把文件路径交给旁车解析。
- `sidecar` 不暴露端口、无 JWT，仅在 compose 网络内被主后端调用。
- `mysql` 不暴露端口，仅 compose 网络内可达。
- 前端 nginx 负责：SPA 静态资源托管、history 路由回退、`/api/` 反向代理。

## 目录结构

```
hotel_accounting/
├── docker-compose.yml        # 一键部署编排
├── .env.example              # 环境变量模板（复制为 .env 使用）
├── backend/                  # Java 主后端（Spring Boot 3）
│   ├── Dockerfile
│   └── src/                  # 源码；Maven 多阶段构建出 jar
├── frontend/                 # Vue3 前端（Vite）
│   ├── Dockerfile
│   ├── nginx.conf            # 容器内 nginx：静态托管 + /api 反代
│   └── src/
├── sidecar/                  # Python 旁车（FastAPI）
│   ├── Dockerfile
│   └── app.py                # 解析 / 归类 / 预测 / LLM 解读
├── db/
│   ├── schema.sql            # MySQL 8 建库建表（权威 DDL）
│   └── seed.sql              # 演示种子数据（含 admin 账号）
└── docs/                     # 设计文档（系统/数据库/API/工单）
```

## 快速开始（Docker Compose）

前置要求：Docker ≥ 24 与 Docker Compose v2。

```bash
# 1. 配置环境变量
cp .env.example .env
#    修改 MYSQL_ROOT_PASSWORD、JWT_SECRET（生产必改）；
#    可选填 DEEPSEEK_API_KEY 开启 AI 解读

# 2. 一键构建并启动
docker compose up -d --build

# 3. 查看状态（等待 mysql/backend 健康检查通过）
docker compose ps

# 4. 访问
#    前端：http://<服务器IP>:${APP_PORT}   （默认 8080）
#    默认账号：admin / admin123（登录后请修改密码）
```

首次启动会自动执行 `db/schema.sql` + `db/seed.sql` 初始化数据库（仅 `mysql-data` 卷为空时执行）。

## 服务与端口

| 服务 | 容器端口 | 对外暴露 | 说明 |
|---|---|---|---|
| frontend | 80 | ✅ `${APP_PORT:-8080}` | Vue SPA + `/api` 反代 |
| backend | 8081 | ❌ | Spring Boot 主后端，健康检查 `/api/health` |
| sidecar | 8001 | ❌ | FastAPI 旁车，仅主后端内网调用 |
| mysql | 3306 | ❌ | 数据库，仅 compose 网络内可达 |

## 环境变量（.env）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `APP_PORT` | `8080` | 对外访问端口 |
| `MYSQL_ROOT_PASSWORD` | `change-me-root` | MySQL root 密码（后端使用同一密码） |
| `JWT_SECRET` | 开发默认值 | JWT 签名密钥，生产务必改为随机 ≥32 位 |
| `DEEPSEEK_API_KEY` | 空 | 可选；不填则 AI 解读自动降级为纯统计 |

## 常用命令

```bash
docker compose up -d --build    # 构建 + 启动（代码更新后重新执行即可）
docker compose logs -f backend  # 跟踪后端日志
docker compose ps               # 服务状态与健康检查
docker compose down             # 停止（保留数据卷）
docker compose down -v          # 停止并清空数据（含数据库！慎用）
```

## 部署到远端服务器

```bash
# 服务器上（Ubuntu/Debian 示例，需先装 Docker）
curl -fsSL https://get.docker.com | sh

git clone https://github.com/boy6666/hotel_accounting.git
cd hotel_accounting
cp .env.example .env && vim .env   # 修改密码/密钥
docker compose up -d --build
```

生产建议：
- 在宿主机用 nginx / Cloudflare 套一层 HTTPS，反代到 `${APP_PORT}`；
- 修改默认密码与 JWT 密钥；`admin/admin123` 仅用于演示；
- `mysql-data`、`storage-data` 两个数据卷承载持久化数据，纳入服务器备份计划。

## 本地开发（非 Docker）

```bash
# 数据库：本机 MySQL 8 执行 db/schema.sql、db/seed.sql
# 旁车（8001）
cd sidecar && pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8001

# 主后端（8081，application.yml 默认连 127.0.0.1:3306）
cd backend && mvn spring-boot:run

# 前端（5174，/api 代理到 8081）
cd frontend && npm install && npm run dev
```

## 文档

详见 [docs/](docs/)：`01-系统设计` / `02-数据库设计` / `03-API文档` / `04-UI原型` / `05-08 各端工单` / `99-进度快照`。
