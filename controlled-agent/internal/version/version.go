// Package version 暴露受控端版本号常量（platform-env-ota-realtime D1）。
// 版本号由构建脚本经 ldflags 注入：
//
//	go build -ldflags "-X github.com/nexcompute/controlled-agent/internal/version.Version=$(VERSION)"
//
// 未注入时为开发占位 "0.1.0-dev"，不再在心跳中硬编码。
package version

// Version 受控端版本号。构建期可由 ldflags 覆盖。
var Version = "0.1.0-dev"
