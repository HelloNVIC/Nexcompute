# Nexcompute 审计归档 OS 调度配置（platform-audit-logging-ux D7 / 12.3）

每月 1 号凌晨 02:00 触发独立归档进程。不进应用进程，应用重启不影响调度。

## 一、Linux（cron）

编辑 DBA 账号 crontab：
```bash
crontab -e -u nexcompute_dba
```

加入（每月 1 号 02:00 执行 SQL 脚本版）：
```cron
# Nexcompute 审计归档（每月 1 号 02:00）
0 2 1 * * /usr/bin/psql "host=db-host port=5432 dbname=nexcompute user=nexcompute_dba" -f /opt/nexcompute/tools/audit-archive/archive.sql >> /var/log/nexcompute/archive.log 2>&1
```

或 JAR 版（需 JRE 21+）：
```cron
0 2 1 * * /usr/bin/java -jar /opt/nexcompute/tools/audit-archive/audit-archive.jar \
    --jdbc-url=jdbc:postgresql://db-host:5432/nexcompute \
    --db-user=nexcompute_dba \
    --db-password="${PGPASSWORD}" >> /var/log/nexcompute/archive.log 2>&1
```

密码建议经 `~/.pgpass` 或环境变量注入，不写进 crontab。

## 二、Windows（任务计划）

### 2.1 命令行创建（PowerShell 管理员）

```powershell
# SQL 脚本版（需 psql 客户端）
$action = New-ScheduledTaskAction `
    -Execute "C:\Program Files\PostgreSQL\17\bin\psql.exe" `
    -Argument "-h db-host -p 5432 -U nexcompute_dba -d nexcompute -f C:\Nexcompute\tools\audit-archive\archive.sql" `
    -WorkingDirectory "C:\Nexcompute\tools\audit-archive"

$trigger = New-ScheduledTaskTrigger -Monthly -DaysOfMonth 1 -At 2am
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd

Register-ScheduledTask -TaskName "Nexcompute-AuditArchive" `
    -Action $action -Trigger $trigger -Settings $settings `
    -User "SYSTEM" -RunLevel Highest
```

### 2.2 JAR 版

```powershell
$action = New-ScheduledTaskAction `
    -Execute "C:\Program Files\Java\jdk-21\bin\java.exe" `
    -Argument "-jar C:\Nexcompute\tools\audit-archive\audit-archive.jar --jdbc-url=jdbc:postgresql://db-host:5432/nexcompute --db-user=nexcompute_dba --db-password=$env:ARCHIVE_PWD" `
    -WorkingDirectory "C:\Nexcompute\tools\audit-archive"

Register-ScheduledTask -TaskName "Nexcompute-AuditArchive" `
    -Action $action -Trigger (New-ScheduledTaskTrigger -Monthly -DaysOfMonth 1 -At 2am) `
    -User "SYSTEM" -RunLevel Highest
```

## 三、独立凭据环境变量

建议在系统环境变量或 DBA 账号 profile 中设置：
```
PGPASSWORD=<nexcompute_dba 密码>
ARCHIVE_PWD=<nexcompute_dba 密码>
```
不在脚本中明文存储密码。
