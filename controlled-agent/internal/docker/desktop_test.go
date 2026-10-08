// Package docker - 看门狗纯函数测试（agent-defaults：30 秒周期/拉起冷却/失败限流）
package docker

import (
	"testing"
	"time"
)

func TestShouldAttemptStart(t *testing.T) {
	now := time.Now()
	cases := []struct {
		name      string
		lastStart time.Time
		want      bool
	}{
		{"从未拉起过应尝试", time.Time{}, true},
		{"冷却期内不重复拉起", now.Add(-30 * time.Second), false},
		{"冷却期满可再次拉起", now.Add(-60 * time.Second), true},
		{"远超冷却期可拉起", now.Add(-10 * time.Minute), true},
	}
	for _, tc := range cases {
		if got := shouldAttemptStart(now, tc.lastStart); got != tc.want {
			t.Errorf("%s: shouldAttemptStart(now, %v) = %v, want %v", tc.name, tc.lastStart, got, tc.want)
		}
	}
}

func TestShouldLogStartFailure(t *testing.T) {
	logged := map[int]bool{}
	for streak := 1; streak <= watchdogFailLogEvery*2+1; streak++ {
		logged[streak] = shouldLogStartFailure(streak)
	}
	if !logged[1] {
		t.Error("第 1 次失败应记日志")
	}
	if logged[2] || logged[watchdogFailLogEvery-1] {
		t.Error("第 2 至 N-1 次失败不应记日志")
	}
	if !logged[watchdogFailLogEvery] || !logged[2*watchdogFailLogEvery] {
		t.Errorf("第 N/2N 次失败应记日志 (N=%d)", watchdogFailLogEvery)
	}
}

// TestDockerDesktopCandidatesIncludePerUser per-user 两种安装形态均须在候选中
// （agent-defaults：DESKTOP-9IQCUKC per-user 安装不在候选，致看门狗拉起失败）。
func TestDockerDesktopCandidatesIncludePerUser(t *testing.T) {
	t.Setenv("LOCALAPPDATA", `C:\Users\Admin\AppData\Local`)
	t.Setenv("ProgramFiles", `C:\Program Files`)
	got := dockerDesktopCandidates()
	wants := []string{
		`C:\Users\Admin\AppData\Local\Docker\Docker\Docker Desktop.exe`,
		`C:\Users\Admin\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe`,
	}
	for _, want := range wants {
		found := false
		for _, p := range got {
			if p == want {
				found = true
			}
		}
		if !found {
			t.Errorf("候选应含 per-user 安装路径 %s，实际: %v", want, got)
		}
	}
}
