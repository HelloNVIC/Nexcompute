// Package gui - 受控端"关于"窗口（platform-env-ota-realtime）。
// 展示受控端版本（构建期注入）、管理端版本、系统信息（维护人/责任人等）。
package gui

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/version"
)

// labelColWidth 左列（字段名）固定宽度，右列（值）占满剩余并自动换行。
const labelColWidth = 110

// showAbout 弹出"关于"独立窗口：受控端版本 + 管理端版本 + 系统信息。
// 管理端版本与系统信息经 GET /api/agent/about 拉取（agent-token 鉴权）。
func (a *App) showAbout() {
	w := a.fyneApp.NewWindow("关于")
	w.SetIcon(AppIcon())
	w.Resize(fyne.NewSize(560, 480))

	// 受控端本地信息（立即可展示）
	localRows := [][2]string{
		{"受控端版本", version.Version},
		{"管理端地址", a.cfg.ServerURL},
		{"物理实例编号", a.cfg.InstanceNumber},
	}

	// 管理端信息容器（异步填充）
	mgmtContainer := container.NewVBox(widget.NewLabel("正在从管理端获取信息…"))

	content := container.NewVBox(
		boldLabel("受控端"),
		renderKVRows(localRows),
		widget.NewSeparator(),
		boldLabel("管理端"),
		mgmtContainer,
	)
	w.SetContent(container.NewVScroll(content))
	w.Show()

	// 异步拉取管理端版本 + 系统信息
	go func() {
		about, err := fetchAgentAbout(a.cfg)
		var rows [][2]string
		if err != nil {
			rows = [][2]string{{"获取失败", err.Error()}}
		} else {
			rows = [][2]string{
				{"管理端版本", about.ManagementVersion},
				{"维护人", about.Maintainer},
				{"维护电话", about.MaintainerPhone},
				{"责任人", about.Owner},
				{"责任人电话", about.OwnerPhone},
			}
		}
		rendered := renderKVRows(rows)
		// 替换管理端容器内容（Fyne 容器 Add/Remove 需在主线程；用 fyne.Do 不可用，
		// 改为重建容器内容：清空后逐行加入，Fyne 的容器操作内部加锁，可直接调用）
		mgmtContainer.Objects = nil
		mgmtContainer.Add(rendered)
		mgmtContainer.Refresh()
	}()
}

// agentAbout 管理端 /api/agent/about 返回结构
type agentAbout struct {
	ManagementVersion string `json:"managementVersion"`
	Maintainer        string `json:"maintainer"`
	MaintainerPhone   string `json:"maintainerPhone"`
	Owner             string `json:"owner"`
	OwnerPhone        string `json:"ownerPhone"`
}

func fetchAgentAbout(cfg *config.Config) (agentAbout, error) {
	var result agentAbout
	if cfg.InstanceNumber == "" || cfg.AgentToken == "" {
		return result, fmt.Errorf("未注册")
	}
	url := cfg.ServerURL + "/api/agent/about"
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return result, err
	}
	req.Header.Set("X-Agent-Token", cfg.AgentToken)
	req.Header.Set("X-Instance-Number", cfg.InstanceNumber)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return result, err
	}
	defer resp.Body.Close()

	var apiResult struct {
		Code int        `json:"code"`
		Data agentAbout `json:"data"`
	}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &apiResult); err != nil {
		return result, fmt.Errorf("解析响应失败 (status=%d)", resp.StatusCode)
	}
	if apiResult.Code != 0 {
		return result, fmt.Errorf("管理端返回 code=%d", apiResult.Code)
	}
	return apiResult.Data, nil
}

// boldLabel 返回加粗样式的小节标题 Label
func boldLabel(text string) *widget.Label {
	l := widget.NewLabel(text)
	l.TextStyle = fyne.TextStyle{Bold: true}
	return l
}

// renderKVRows 渲染键值对为竖排行：左列固定宽度（字段名，加粗），右列占满剩余并自动换行。
// 避免使用 widget.Table（无显式高度时列宽会平分窗口，致"一半一半"）。
func renderKVRows(rows [][2]string) fyne.CanvasObject {
	items := make([]fyne.CanvasObject, 0, len(rows))
	for _, r := range rows {
		key := widget.NewLabel(r[0] + "：")
		key.TextStyle = fyne.TextStyle{Bold: true}
		// 固定左列宽度
		keyContainer := container.NewGridWrap(fyne.NewSize(labelColWidth, key.MinSize().Height), key)

		val := widget.NewLabel(r[1])
		val.Wrapping = fyne.TextWrapWord

		items = append(items, container.NewBorder(nil, nil, keyContainer, nil, val))
	}
	return container.NewVBox(items...)
}
