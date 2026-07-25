# Nexcompute 受控端 (Controlled Agent)

Go 实现的受控端，系统托盘常驻 GUI，定时心跳 + WebSocket 命令通道（混合模式）。

## 模块划分

```
controlled-agent/
├── cmd/nexcompute-agent/     # 入口
├── internal/
│   ├── config/               # 本地配置（控制端 IP、心跳间隔、存储根目录、本地管理员密码）
│   ├── gui/                  # 系统托盘常驻 GUI（Fyne + systray）
│   ├── heartbeat/            # 心跳上报模块（HTTP POST 定时状态采集）
│   ├── wsclient/             # WebSocket 命令通道客户端（连出、重连退避、命令接收执行、结果回传）
│   ├── agent/                # 命令执行器（分发各类命令、命令来源校验）
│   ├── security/             # 安全审计（拒绝并记录未鉴权命令）
│   ├── docker/               # 本地 Docker API 封装（容器、镜像、卷）
│   ├── sysinfo/              # 系统状态采集（CPU/GPU/温度/内存/进程）
│   ├── power/                # 防睡眠休眠（SetThreadExecutionState）
│   ├── storage/              # 存储池根目录与目录创建
│   └── filetransfer/         # 可断点续传大文件传输
└── assets/                   # 图标等资源
```

## 构建

```bash
# 需要 Go 1.23+，Windows 下需 CGO（Fyne 依赖）
GOOS=windows GOARCH=amd64 CGO_ENABLED=1 go build -o nexcompute-agent.exe ./cmd/nexcompute-agent
```

> 注意：本机未安装 Go，上述代码已编写但未在本机编译验证。安装 Go 后可直接构建。
