// Package autostart - 非 Windows 平台 stub（任务 5.3）

//go:build !windows

package autostart

func setRunValue(name, value string) error  { return nil }
func getRunValue(name string) (string, error) { return "", nil }
func deleteRunValue(name string) error       { return nil }
