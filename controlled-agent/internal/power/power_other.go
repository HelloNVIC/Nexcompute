// Package power - 非 Windows 平台 stub。

//go:build !windows

package power

func setThreadExecutionState() error  { return nil }
func clearThreadExecutionState() error { return nil }
