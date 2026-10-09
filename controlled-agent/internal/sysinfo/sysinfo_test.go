// Package sysinfo - 测试（任务 14.2）
package sysinfo

import (
	"strings"
	"testing"
)

func TestParseFloat(t *testing.T) {
	cases := []struct {
		input    string
		expected float64
	}{
		{"42", 42.0},
		{"3.14", 3.14},
		{"  100  ", 100.0},
		{"abc", 0.0},
		{"", 0.0},
	}
	for _, tc := range cases {
		got := parseFloat(tc.input)
		if got != tc.expected {
			t.Errorf("parseFloat(%q) = %f, want %f", tc.input, got, tc.expected)
		}
	}
}

func TestCollect_ReturnsStatus(t *testing.T) {
	status, err := Collect()
	if err != nil {
		t.Fatalf("Collect failed: %v", err)
	}
	if status == nil {
		t.Fatal("expected non-nil status")
	}
	if status.Timestamp == 0 {
		t.Error("expected non-zero timestamp")
	}
	// CPU/GPU/内存值可能因环境而异，但结构应完整
	if status.MemoryTotal < 0 {
		t.Error("memory total should be non-negative")
	}
}

func TestCollectGPU_NoNvidiaSMI(t *testing.T) {
	// 在无 GPU 环境下应返回 nil 而非 panic
	info, usage, temp := collectGPU()
	// 不 panic 即通过；有 nvidia-smi 时返回值，无则返回 nil/0
	_ = info
	_ = usage
	_ = temp
}

func TestGPUInfo_Structure(t *testing.T) {
	info := &GPUInfo{
		Name:        "NVIDIA GeForce RTX 4090",
		MemoryTotal: 24576,
		MemoryUsed:  8192,
		DriverVer:   "555.42.02",
	}
	if info.Name != "NVIDIA GeForce RTX 4090" {
		t.Error("name mismatch")
	}
	if info.MemoryTotal != 24576 {
		t.Error("memory total mismatch")
	}
}

func TestHostInfo(t *testing.T) {
	info := GetHostInfo()
	// 不 panic 即通过；hostname 可能为空（测试环境）
	if info.Hostname != "" && strings.Contains(info.Hostname, " ") {
		t.Error("hostname should not contain spaces")
	}
}

func TestProcessInfo_Structure(t *testing.T) {
	p := ProcessInfo{
		PID:    1234,
		Name:   "python",
		CPU:    45.2,
		Memory: 12.5,
	}
	if p.PID != 1234 {
		t.Error("PID mismatch")
	}
	if p.Name != "python" {
		t.Error("name mismatch")
	}
}

// TestParseSmbiosUUIDOutput instance-identity：CIM/wmic 两种输出形态解析与全 0 判缺。
func TestParseSmbiosUUIDOutput(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"wmic 带表头", "UUID\r\n4C4C4544-0042-3510-8054-B7C04F4E3532\r\n\r\n", "4C4C4544-0042-3510-8054-B7C04F4E3532"},
		{"CIM 裸值", "4C4C4544-0042-3510-8054-B7C04F4E3533\r\n", "4C4C4544-0042-3510-8054-B7C04F4E3533"},
		{"全 0 视为缺失", "00000000-0000-0000-0000-000000000000\r\n", ""},
		{"空输出", "", ""},
		{"仅表头", "UUID\r\n", ""},
	}
	for _, tc := range cases {
		if got := parseSmbiosUUIDOutput(tc.in); got != tc.want {
			t.Errorf("%s: parseSmbiosUUIDOutput(%q) = %q, want %q", tc.name, tc.in, got, tc.want)
		}
	}
}
