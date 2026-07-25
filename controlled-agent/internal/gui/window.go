// Package gui 实现受控端系统托盘常驻 GUI（任务 5.1）。
// 使用 Fyne 提供可视化窗口，systray 提供系统托盘常驻。
// 关窗口缩到托盘不退出，退出需本地管理员密码（任务 5.5）。
package gui

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"github.com/nexcompute/controlled-agent/internal/autostart"
	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/docker"
	"github.com/nexcompute/controlled-agent/internal/heartbeat"
	"github.com/nexcompute/controlled-agent/internal/storage"
	"github.com/nexcompute/controlled-agent/internal/wsclient"
)

// App 受控端 GUI 应用
type App struct {
	cfg       *config.Config
	hb        *heartbeat.Reporter
	wsClient  *wsclient.Client
	dockerMgr *docker.Manager
	fyneApp   fyne.App
	window    fyne.Window
	tray      *Tray
}

// NewApp 创建 GUI 应用
func NewApp(cfg *config.Config, hb *heartbeat.Reporter, ws *wsclient.Client, dockerMgr *docker.Manager) *App {
	a := &App{
		cfg:       cfg,
		hb:        hb,
		wsClient:  ws,
		dockerMgr: dockerMgr,
		fyneApp:   app.NewWithID("com.nexcompute.agent"),
	}
	a.fyneApp.SetIcon(AppIcon())
	a.buildWindow()
	return a
}

// Run 启动 GUI 事件循环 + 系统托盘（阻塞）
// Fyne 要求 ShowAndRun 必须在主 goroutine 调用，故 Fyne 占主线程，
// systray 放后台 goroutine（Windows 下 systray 可在非主线程运行）。
func (a *App) Run() {
	// platform-refinements #2：开机自启默认开启（用户未明确关闭则确保注册）
	a.ensureDefaultAutostart()

	a.tray = NewTray(
		func() { a.window.Show() }, // 显示窗口
		func() { a.confirmQuit() }, // 退出（需密码）
	)

	// systray 在后台 goroutine 运行
	go a.tray.Run()

	// Fyne 事件循环在主 goroutine（Fyne 要求）
	a.window.ShowAndRun()
}

// ensureDefaultAutostart 默认开启开机自启（platform-refinements #2）
func (a *App) ensureDefaultAutostart() {
	if a.cfg.AutostartDisabled {
		return
	}
	if !autostart.IsEnabled() {
		if err := autostart.Enable(); err != nil {
			log.Printf("[gui] 默认开启自启动失败: %v", err)
		} else {
			log.Println("[gui] 已默认开启开机自启")
		}
	}
}

// Quit 退出应用
func (a *App) Quit() {
	a.fyneApp.Quit()
}

// confirmQuit 退出前校验密码（任务 5.5）
func (a *App) confirmQuit() {
	VerifyExitPassword(a.window, func() {
		a.tray.Quit()
		a.fyneApp.Quit()
	})
}

func (a *App) buildWindow() {
	w := a.fyneApp.NewWindow("Nexcompute 受控端")
	w.SetIcon(AppIcon())
	w.SetMaster()
	w.Resize(fyne.NewSize(500, 640))
	w.SetCloseIntercept(func() {
		// 关窗口缩到托盘不退出（任务 5.1）
		log.Println("[gui] 窗口关闭，缩到托盘")
		w.Hide()
	})

	statusLabel := widget.NewLabel("正在连接管理端...")
	connStatus := widget.NewLabel("连接状态：未知")
	instanceLabel := widget.NewLabel("物理实例编号：未注册")
	storageRootLabel := widget.NewLabel(a.storageRootDisplay())

	// 控制端 IP 设置（任务 5.6）
	serverEntry := widget.NewEntry()
	serverEntry.SetText(a.cfg.ServerURL)
	serverBtn := widget.NewButton("保存控制端 IP（重启后生效）", func() {
		_ = config.Update(func(c *config.Config) {
			c.ServerURL = serverEntry.Text
			c.HeartbeatURL = serverEntry.Text + "/api/agent/heartbeat"
			c.WSURL = wsURLFromHTTP(serverEntry.Text) + "/api/agent/ws"
		})
		a.cfg = config.Get()
		dialog.ShowInformation("已保存", "控制端 IP 已更新，需重启受控端生效", w)
	})

	// Docker 环境检查（任务 5.7）
	dockerResultLabel := widget.NewLabel("尚未检查")
	dockerCheckBtn := widget.NewButton("检查 Docker 环境", func() {
		if a.dockerMgr == nil {
			dialog.ShowError(errors.New("Docker 管理器未初始化"), w)
			return
		}
		check, err := a.dockerMgr.CheckEnvironmentFull(context.Background())
		if err != nil {
			dockerResultLabel.SetText("检查失败: " + err.Error())
			return
		}
		text := "Docker: "
		if check.DockerAvailable {
			text += "可用 (v" + check.DockerVersion + ")"
		} else {
			text += "不可用"
		}
		text += "\nGPU: "
		if check.GPUAvailable {
			text += "可用"
		} else {
			text += "不可用"
		}
		if check.ToolkitVersion != "" {
			text += "\nToolkit: " + check.ToolkitVersion
		}
		if check.Error != "" {
			text += "\n问题: " + check.Error
		}
		dockerResultLabel.SetText(text)
	})

	// 存储池根目录设置（任务 5.9；platform-refinements #3：选择文件夹 + 容器占用检查 + 密码 + 迁移复制）
	storageEntry := widget.NewEntry()
	storageEntry.SetPlaceHolder("如 D:\\NexcomputeStorage")
	storageEntry.SetText(a.cfg.StorageRoot)
	pickFolderBtn := widget.NewButton("选择文件夹", func() {
		dialog.ShowFolderOpen(func(dir fyne.ListableURI, err error) {
			if err != nil || dir == nil {
				return
			}
			storageEntry.SetText(dir.Path())
		}, w)
	})
	storageBtn := widget.NewButton("设置存储池根目录", func() {
		newPath := storageEntry.Text
		if newPath == "" {
			dialog.ShowError(errors.New("路径不能为空"), w)
			return
		}
		mgr := storage.NewManager(a.cfg)
		if !(a.cfg.StorageRootLocked && a.cfg.StorageRoot != "") {
			// 首次设置
			if err := mgr.SetRoot(newPath); err != nil {
				dialog.ShowError(err, w)
				return
			}
			a.cfg = config.Get()
			storageRootLabel.SetText(a.storageRootDisplay())
			dialog.ShowInformation("成功", "存储池根目录已设置", w)
			return
		}
		// 变更（已锁定）：1) 容器占用检查
		oldRoot := a.cfg.StorageRoot
		if a.dockerMgr != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			using := a.dockerMgr.ContainersUsingRoot(ctx, oldRoot)
			cancel()
			if len(using) > 0 {
				dialog.ShowError(fmt.Errorf("有 %d 个容器正在使用当前存储池，请先停止/删除相关容器：%s",
					len(using), strings.Join(using, ", ")), w)
				return
			}
		}
		// 2) 管理密码
		VerifyPasswordForRootChange(w, func(pwd string) {
			// 3) 询问是否迁移文件
			dialog.ShowConfirm("迁移文件", "是否将原存储池中的文件复制到新路径？",
				func(doCopy bool) {
					if doCopy {
						if err := storage.CopyDir(oldRoot, newPath); err != nil {
							dialog.ShowError(fmt.Errorf("复制文件失败: %w", err), w)
							return
						}
					}
					if err := mgr.ChangeRoot(newPath, pwd); err != nil {
						dialog.ShowError(err, w)
						return
					}
					a.cfg = config.Get()
					storageRootLabel.SetText(a.storageRootDisplay())
					dialog.ShowInformation("成功", "存储池根目录已修改", w)
				}, w)
		})
	})

	// 开机自启开关（platform-refinements #2：默认开启，关闭需管理密码）
	autostartCheck := widget.NewCheck("开机自启动", nil)
	autostartCheck.SetChecked(autostart.IsEnabled())
	// 静默设置勾选状态，避免 SetChecked 递归触发 OnChanged
	setCheckedQuiet := func(c *widget.Check, v bool) {
		cb := c.OnChanged
		c.OnChanged = nil
		c.SetChecked(v)
		c.OnChanged = cb
	}
	autostartCheck.OnChanged = func(checked bool) {
		if checked {
			if err := autostart.Enable(); err != nil {
				dialog.ShowError(fmt.Errorf("自启动设置失败: %w", err), w)
				setCheckedQuiet(autostartCheck, false)
				return
			}
			_ = config.Update(func(c *config.Config) { c.AutostartDisabled = false })
			log.Println("[gui] 开机自启已开启")
			return
		}
		// 关闭需管理密码：先复选框回退为勾选，密码通过后再取消勾选
		setCheckedQuiet(autostartCheck, true)
		VerifyExitPassword(w, func() {
			if err := autostart.Disable(); err != nil {
				dialog.ShowError(fmt.Errorf("关闭自启动失败: %w", err), w)
				setCheckedQuiet(autostartCheck, true)
				return
			}
			_ = config.Update(func(c *config.Config) { c.AutostartDisabled = true })
			setCheckedQuiet(autostartCheck, false)
			dialog.ShowInformation("已关闭", "开机自启已关闭", w)
		})
	}

	// 退出按钮（需密码）
	quitBtn := widget.NewButton("退出受控端", func() {
		a.confirmQuit()
	})

	content := container.NewVBox(
		widget.NewCard("系统状态", "", container.NewVBox(statusLabel, connStatus, instanceLabel)),
		widget.NewCard("控制端设置", "", container.NewVBox(serverEntry, serverBtn)),
		widget.NewCard("Docker 环境", "", container.NewVBox(dockerCheckBtn, dockerResultLabel)),
		widget.NewCard("存储池", "", container.NewVBox(storageRootLabel, storageEntry, pickFolderBtn, storageBtn)),
		widget.NewCard("系统", "", container.NewVBox(autostartCheck, quitBtn)),
	)

	w.SetContent(container.NewVScroll(content))
	a.window = w

	// 异步更新状态
	go a.refreshStatus(statusLabel, connStatus, instanceLabel, storageRootLabel)
}

func (a *App) storageRootDisplay() string {
	if a.cfg.StorageRoot == "" {
		return "存储池根目录：未设置"
	}
	status := "已设置"
	if a.cfg.StorageRootLocked {
		status = "已锁定"
	}
	return "存储池根目录：" + a.cfg.StorageRoot + "（" + status + "）"
}

func (a *App) refreshStatus(statusLabel, connStatus, instanceLabel, storageRootLabel *widget.Label) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	update := func() {
		st := a.hb.GetStatus()

		// 主状态标签
		var mainText string
		if !st.Registered {
			mainText = "正在注册到管理端..."
		} else if st.Connected {
			mainText = "已连接管理端"
		} else {
			mainText = "正在连接管理端..."
		}
		statusLabel.SetText(mainText)

		// 连接状态
		var connText string
		if st.Connected {
			connText = "连接状态：已连接（最后心跳：" + st.LastSuccess.Format("15:04:05") + "）"
		} else {
			if st.LastError != "" {
				connText = "连接状态：断开（" + st.LastError + "）"
			} else {
				connText = "连接状态：未连接"
			}
		}
		connStatus.SetText(connText)

		// 实例编号
		if st.InstanceNumber != "" {
			instanceLabel.SetText("物理实例编号：" + st.InstanceNumber + "（ID: " + int64ToStr(st.InstanceID) + "）")
		} else {
			instanceLabel.SetText("物理实例编号：未注册")
		}

		// 存储根目录
		storageRootLabel.SetText(a.storageRootDisplay())
	}

	// 首次立即更新
	update()
	for range ticker.C {
		update()
	}
}

func int64ToStr(n int64) string {
	if n == 0 {
		return "-"
	}
	s := ""
	for n > 0 {
		s = string(rune('0'+n%10)) + s
		n /= 10
	}
	return s
}

func wsURLFromHTTP(httpURL string) string {
	if len(httpURL) > 4 && httpURL[:5] == "https" {
		return "wss" + httpURL[5:]
	}
	if len(httpURL) > 3 && httpURL[:4] == "http" {
		return "ws" + httpURL[4:]
	}
	return httpURL
}
