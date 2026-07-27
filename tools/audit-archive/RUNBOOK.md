# Nexcompute 审计日志归档 Runbook（platform-audit-logging-ux D7 / 12.4）

> 归档由**独立进程**执行，使用**独立 DB 维护凭据**（`nexcompute_dba`），经 OS 调度按月触发。
> 不进应用进程，不阻塞业务，应用重启不影响归档调度。

## 1. 背景与约束

- 审计热表 `audit_log` 保留近 30 天；30 天前记录每月归档至 `audit_log_archive`。
- `audit_log` / `audit_log_archive` 均有 `BEFORE UPDATE` / `BEFORE DELETE` 触发器，**应用账号无法 DELETE**。
- 归档绕过触发器的方式：维护角色会话级 `SET session_replication_role = replica`（禁用所有触发器），完成后 `RESET`。
- 维护角色 `nexcompute_dba` 具 REPLICATION 属性 + 仅审计表 SELECT/INSERT/DELETE，应用账号无此权限。

## 2. 一次性准备

1. DBA 执行 `setup-dba-role.sql` 创建维护角色（密码从密钥管理注入，不硬编码）：
   ```bash
   psql -h db-host -U postgres -d nexcompute -v pwd='<强密码>' -f setup-dba-role.sql
   ```
2. 校验应用账号无 DELETE 权限：
   ```sql
   SELECT has_table_privilege('nexcompute_app', 'audit_log', 'DELETE');  -- 期望 false
   ```
3. 部署归档产物（SQL 脚本或 JAR）到目标路径，配置 OS 调度（见 `scheduling.md`）。

## 3. 归档执行步骤（手动 / 调度）

### SQL 脚本版
```bash
PGPASSWORD='<dba 密码>' psql \
  -h db-host -p 5432 -U nexcompute_dba -d nexcompute \
  -f archive.sql
```

### JAR 版
```bash
java -jar audit-archive.jar \
  --jdbc-url=jdbc:postgresql://db-host:5432/nexcompute \
  --db-user=nexcompute_dba \
  --db-password="$ARCHIVE_PWD"
```

归档流程（脚本/JAR 内已封装）：
1. `SET session_replication_role = replica`（禁用触发器）
2. `BEGIN`
3. `INSERT INTO audit_log_archive SELECT … WHERE created_at < now()-30d LIMIT 100000`
4. `DELETE FROM audit_log WHERE id IN (…同 WHERE + LIMIT)`
5. `COMMIT`
6. `RESET session_replication_role`

## 4. 故障排查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `permission denied for table audit_log` | 用了应用账号或角色未授权 | 改用 `nexcompute_dba`，重跑 `setup-dba-role.sql` |
| `permission denied to set session_replication_role` | 角色无 REPLICATION 属性 | 重建角色带 REPLICATION，或用 SUPERUSER 账号 |
| 归档数与删除数不一致 | 并发写入（30 天边界有新记录老化） | 正常，下次运行补齐；非错误 |
| 归档进程卡住 | 长事务/锁等待 | 检查 `pg_stat_activity`；归档进程独立可 kill，不影响应用 |
| `audit_log_archive` 满了 | 归档表未分区/未清理 | 评估归档表分区或转冷存储（超出本变更范围） |

## 5. 回滚

归档操作本身是**幂等**的迁移（INSERT 归档表 + DELETE 热表，同 WHERE + LIMIT）：
- 若归档已 DELETE 但 INSERT 失败（事务回滚）：两步在同一事务内，COMMIT 前任一失败整体回滚，热表记录不丢。
- 若需把归档表记录**迁回**热表（极少需要）：维护角色执行
  ```sql
  SET session_replication_role = replica;
  INSERT INTO audit_log SELECT * FROM audit_log_archive WHERE <条件>;
  DELETE FROM audit_log_archive WHERE <条件>;
  RESET session_replication_role;
  ```
  （注意：回迁会改变 `created_at` 之外的顺序，且 audit_log 的 id 序列需对齐。一般无需回迁。）
- DB 触发器本身的回滚：`DROP TRIGGER trg_audit_no_update ON audit_log;`（合规评审后才能做，**不建议**——不可删改是合规要求）。

## 6. 独立性与隔离验证（12.5）

- 归档进程 kill / 崩溃：应用进程不受影响（独立 JVM / 独立 DB 连接）。
- 应用重启：归档调度由 OS 管理（cron / 任务计划），不依赖应用进程，重启不影响下次触发。
- 应用账号尝试 DELETE `audit_log`：被触发器 RAISE 拒绝（已存记录不可删改）。
- 归档账号尝试 DELETE 应用表：仅授审计表权限，其他表拒绝。

## 7. 监控建议

- 归档日志：`/var/log/nexcompute/archive.log`（Linux）/ 任务计划历史（Windows）。
- 告警：归档失败（非 0 退出码）应通知 DBA。
- 容量：监控 `audit_log_archive` 增长，必要时分区或转冷存储。
