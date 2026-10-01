# 反馈接收服务

接收肥鱼笔记应用内的匿名反馈（spec §9 F01，接口见 [plan](../../docs/plan.md#032-更新与问题反馈实施计划)）。只用 Python 3 标准库和 SQLite，单进程只监听回环地址，由 Caddy 对外提供 HTTPS。

## 行为

- `POST /api/v1/feedback`：事务提交成功后返回 `201 {"reportId": "..."}`；同一个 `submissionId`、内容相同返回 200 和原编号，内容不同返回 409，原报告不会被覆盖。
- 错误统一为 `{"error": "固定错误码"}`：400 格式错误、415 媒体类型错误、413 超过 512 KiB、429 限流（附 `Retry-After`）、503 存储不可用或超出 256 MiB 预算。
- 限流在服务内按客户端 IP 执行，每分钟 5 次。只有来自回环地址的请求才采信 `X-Forwarded-For`（取最后一跳，即 Caddy 写入的地址），IP 只在内存中保留一个窗口。
- 不写访问日志，也不记录请求正文。报告保留 30 天，去重记录随报告一起删除。

## 本地测试

```bash
PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python -m unittest discover -s services/feedback -p 'test_*.py'
```

测试只使用临时数据库和合成报告。

## 部署样例

以下为样例，服务目录、运行用户和端口在部署前（plan P0）核实。

```bash
sudo useradd --system --home /var/lib/feiyu-feedback --shell /usr/sbin/nologin feiyu-feedback
sudo install -d -o feiyu-feedback -g feiyu-feedback -m 700 /var/lib/feiyu-feedback
sudo install -D -m 644 server.py /opt/feiyu-feedback/server.py
sudo install -m 644 deploy/feiyu-feedback.service deploy/feiyu-feedback-purge.service deploy/feiyu-feedback-purge.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now feiyu-feedback.service feiyu-feedback-purge.timer
```

数据库位于 `/var/lib/feiyu-feedback/`，不在任何网站目录下，只有服务用户能读写。Caddy 站点块由 vps-management 维护（`caddy/feiyunote.caddy`，全局 Caddyfile 末尾 import）：反馈路径不写访问日志（`log_skip`），请求体上限 512 KB，`/feiyu/` 与 `/updates/` 由 `/srv/feiyunote` 静态提供，APK 使用 `application/vnd.android.package-archive`。修改时遵循该仓库的比对、备份、校验和显式 reload 流程，不要覆盖其他站点。

## 维护者读取

按用户提供的反馈编号读取，不提供列表或公开查询：

```bash
sudo -u feiyu-feedback python3 /opt/feiyu-feedback/server.py show --db /var/lib/feiyu-feedback/feedback.db <reportId>
```
