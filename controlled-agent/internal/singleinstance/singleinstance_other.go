// Package singleinstance - 非 Windows 平台 stub（registry-image-distribution D8）。
// 开发环境（Linux 等）不启用单实例防护。

//go:build !windows

package singleinstance

// Acquire 非 Windows 平台恒成功（无互斥语义）。
func Acquire() (handle uintptr, alreadyExists bool, err error) {
	return 0, false, nil
}
