// Package heartbeat - shouldSyncAdminPassword 纯函数测试（agent-defaults：密码未变不重写配置）
package heartbeat

import "testing"

func TestShouldSyncAdminPassword(t *testing.T) {
	cases := []struct {
		name     string
		received string
		current  string
		want     bool
	}{
		{"回包未携带密码不落盘", "", "old", false},
		{"回包与本地一致不落盘", "same", "same", false},
		{"密码变更落盘", "new", "old", true},
		{"本地为空首次获取落盘", "new", "", true},
	}
	for _, tc := range cases {
		if got := shouldSyncAdminPassword(tc.received, tc.current); got != tc.want {
			t.Errorf("%s: shouldSyncAdminPassword(%q, %q) = %v, want %v",
				tc.name, tc.received, tc.current, got, tc.want)
		}
	}
}
