// Package gui - 受控端环境准备栏目（platform-env-ota-realtime D2/D3/D5/D6）。
// 含"环境网盘同步"按钮 + 按 docs/受控端环境准备.md 章节生成的分步按钮，点击拉起管理员
// PowerShell 执行该步骤脚本并保持窗口开启（-NoExit）；离线 Toolkit、验证 Toolkit 与
// Docker Engine 配置步骤以弹窗引导 + 一键复制。
package gui

import (
	"fmt"
	"log"
	"os"
	"path/filepath"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"github.com/nexcompute/controlled-agent/internal/docker"
	"github.com/nexcompute/controlled-agent/internal/executil"
)

// dockerEngineConfig 修改 Docker Engine 配置用的 JSON（来自 docs/受控端环境准备.md）。
const dockerEngineConfig = `{
  "runtimes": {
    "nvidia": {
      "path": "nvidia-container-runtime.exe",
      "runtimeArgs": null
    }
  }
}`

// nvidiaToolkitCommands 离线安装 NVIDIA Container Toolkit 的 dpkg 命令（依赖顺序）。
const nvidiaToolkitCommands = `sudo dpkg -i libnvidia-container1_1.19.1-1_amd64.deb
sudo dpkg -i libnvidia-container-tools_1.19.1-1_amd64.deb
sudo dpkg -i nvidia-container-toolkit-base_1.19.1-1_amd64.deb
sudo dpkg -i nvidia-container-toolkit_1.19.1-1_amd64.deb`

// nvidiaToolkitVerifyCommands 验证 Toolkit 并配置运行时命令（需在 WSL 内执行）。
const nvidiaToolkitVerifyCommands = `nvidia-ctk --version
sudo nvidia-ctk runtime configure --runtime=docker`

// insecureRegistriesSnippet 私有镜像仓库追加片段（registry-image-distribution D9）。
// 含前导逗号：Docker Engine 既有 JSON 通常已有末项，直接追加粘贴即合法；
// 若配置为空对象 {}，需去掉前导逗号（见 hint 说明）。
// 仓库地址为部署固定内网地址，与 Toolkit 包名等 envprep 常量同策略硬编码；
// 若后端 nexcompute.registry.url 改配，需同步此常量。
const insecureRegistriesSnippet = `,"insecure-registries": ["10.13.66.25:5000"]`

// buildEnvPrepCard 构建"受控端环境准备" Card，含环境网盘同步 + 9 个分步按钮（D2；D9 增 9）。
func (a *App) buildEnvPrepCard() fyne.CanvasObject {
	return container.NewVBox(
		widget.NewButton("环境网盘同步", a.onEnvSync),
		widget.NewButton("1. 安装 Docker", a.onInstallDocker),
		widget.NewButton("2. 安装 WSL 环境", a.runInstallWSL),
		widget.NewButton("3. 检查显卡驱动", a.runCheckGpuDriver),
		widget.NewButton("4. 离线安装 NVIDIA Container Toolkit", a.onInstallToolkitGuide),
		widget.NewButton("5. 验证 Toolkit 并配置运行时", a.onVerifyToolkitGuide),
		widget.NewButton("6. 修改 Docker Engine 配置", a.onConfigureDockerEngine),
		widget.NewButton("7. 创建 GPU 容器并启动 Jupyter", a.runCreateGpuContainer),
		widget.NewButton("8. 环境测试", a.runEnvTest),
		widget.NewButton("9. 配置私有镜像仓库", a.onConfigureInsecureRegistry),
	)
}

// onEnvSync 手动触发环境网盘同步（D4：受控端也可触发，与管理端"环境网盘同步"等价）。
func (a *App) onEnvSync() {
	if a.envSyncFn == nil {
		dialog.ShowError(fmt.Errorf("环境同步未初始化"), a.window)
		return
	}
	dialog.ShowInformation("环境网盘同步", "已开始同步，同步进度见受控端日志。", a.window)
	go func() {
		if err := a.envSyncFn(); err != nil {
			log.Printf("[env-sync] 手动同步失败: %v", err)
			return
		}
		log.Println("[env-sync] 手动同步完成")
	}()
}

// runElevated 拉起管理员 PowerShell 执行脚本，失败弹错误框。
func (a *App) runElevated(script string) {
	if err := executil.StartElevatedPowerShell(script); err != nil {
		dialog.ShowError(fmt.Errorf("拉起管理员 PowerShell 失败: %w", err), a.window)
	}
}

// 1. 安装 Docker（D3/D4）：执行 Env 文件夹下 Docker Desktop Installer.exe，缺失则提示先同步。
func (a *App) onInstallDocker() {
	envDir := a.envDir()
	installer := filepath.Join(envDir, "Docker Desktop Installer.exe")
	if _, err := os.Stat(installer); err != nil {
		dialog.ShowInformation("请先同步环境文件",
			"未在 Env 文件夹找到 Docker Desktop Installer.exe。\n请先点击管理端\"环境网盘同步\"将安装包同步至本机 Env 文件夹。", a.window)
		return
	}
	// 多行脚本走临时 .ps1，避免 -Command 引号转义问题
	script := fmt.Sprintf("# 启动 Docker Desktop 安装程序\nStart-Process -FilePath \"%s\"\n", installer)
	a.runElevated(script)
}

// 2. 安装 WSL 环境
func (a *App) runInstallWSL() {
	a.runElevated(`wsl --install
wsl --set-default-version 2
wsl --install -d Ubuntu-26.04
wsl -l -v
`)
}

// 3. 检查显卡驱动（单行 -> -Command）
func (a *App) runCheckGpuDriver() {
	a.runElevated("nvidia-smi")
}

// 4. 离线安装 NVIDIA Container Toolkit（D5）：弹引导框 + 一键复制，不直接执行。
func (a *App) onInstallToolkitGuide() {
	hint := "请先将 Env 文件夹中上述 4 个 deb 包复制到 WSL 用户目录，再在 WSL 内按依赖顺序执行以下命令（顺序不可调）。"
	a.showGuideDialog("离线安装 NVIDIA Container Toolkit", nvidiaToolkitCommands, hint)
}

// 5. 验证 Toolkit 并配置运行时（D5）：弹引导框 + 一键复制，提醒命令在 WSL 内执行。
func (a *App) onVerifyToolkitGuide() {
	hint := "以下命令需在 WSL 内执行（非 Windows PowerShell）。\n" +
		"nvidia-ctk --version 检查安装是否成功；nvidia-ctk runtime configure 配置 Docker 运行时以支持 NVIDIA 容器。"
	a.showGuideDialog("验证 Toolkit 并配置运行时", nvidiaToolkitVerifyCommands, hint)
}

// 6. 修改 Docker Engine 配置（D6）：先启动 Docker Desktop，再弹引导框。
// 启动逻辑复用 docker.StartDockerDesktop（与受控端看门狗共用）。
func (a *App) onConfigureDockerEngine() {
	if err := docker.StartDockerDesktop(); err != nil {
		dialog.ShowInformation("未自动启动 Docker Desktop",
			"未能自动启动 Docker Desktop（"+err.Error()+"）。\n请手动打开 Docker Desktop 后再按指引修改配置。", a.window)
	} else {
		dialog.ShowInformation("已启动 Docker Desktop", "Docker Desktop 正在启动，请等待其就绪后修改配置。", a.window)
	}
	hint := "打开 Docker Desktop > Settings > Docker Engine，粘贴以下 JSON 配置后点 Apply & Restart。"
	a.showGuideDialog("修改 Docker Engine 配置", dockerEngineConfig, hint)
}

// 7. 创建 GPU 容器并启动 Jupyter（D2：先从 Env 载入 pytorch-26.06 镜像 tar，离线环境必需）
func (a *App) runCreateGpuContainer() {
	envDir := a.envDir()
	script := fmt.Sprintf(`# 先从 Env 文件夹载入 pytorch-26.06 镜像 tar（离线环境）
$tars = Get-ChildItem "%s" -Filter "*pytorch*26.06*.tar" -ErrorAction SilentlyContinue
if ($tars) {
    $tars | ForEach-Object { Write-Host "载入镜像: $($_.Name)"; docker load -i $_.FullName }
} else {
    Write-Host "未在 Env 文件夹找到 pytorch-26.06 tar 包，将尝试直接拉取（需联网）"
}
docker run -it --rm --gpus all --name pytorch-26.06 -p 8888:8888 cmk5jct5sp432brntc-nvcr.xuanyuan.run/nvidia/pytorch:26.06-py3
docker exec -it pytorch-26.06 jupyter lab
`, envDir)
	a.runElevated(script)
}

// 8. 环境测试（python -c 多行用 here-string，避免转义）
func (a *App) runEnvTest() {
	a.runElevated(`$py = @"
import torch
print('PyTorch:', torch.__version__)
print('CUDA Available:', torch.cuda.is_available())
print('GPU:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'None')
a = torch.randn(1024, 1024, device='cuda')
b = torch.randn(1024, 1024, device='cuda')
print('MatMul Shape:', torch.matmul(a, b).shape)
print('Test Passed!')
"@
docker exec pytorch-26.06 python -c $py
`)
}

// 9. 配置私有镜像仓库（D9）：弹引导框展示 daemon.json / Docker Engine 需追加的
// insecure-registries 片段（含前导逗号），一键复制后粘贴到既有 JSON 末项之后。
func (a *App) onConfigureInsecureRegistry() {
	hint := "打开 Docker Desktop > Settings > Docker Engine（或编辑 daemon.json），" +
		"把以下内容追加粘贴到既有 JSON 最后一项之后（前导逗号用于与上一项分隔），点 Apply & Restart。\n" +
		"若当前配置为空对象 {}（无任何配置项），请去掉开头的前导逗号再粘贴。"
	a.showGuideDialog("配置私有镜像仓库", insecureRegistriesSnippet, hint)
}

// showGuideDialog 弹出独立引导窗口（D5/D6），比 dialog 大且可调整。
// 展示提示文案 + 代码块（可滚动）+ 一键复制按钮；复制成功后按钮文案变更为"已复制 ✓"。
func (a *App) showGuideDialog(title, codeBlock, hint string) {
	w := a.fyneApp.NewWindow(title)
	w.SetIcon(AppIcon())
	w.Resize(fyne.NewSize(720, 560))

	hintLabel := widget.NewLabel(hint)
	hintLabel.Wrapping = fyne.TextWrapWord

	code := widget.NewMultiLineEntry()
	code.SetText(codeBlock)
	code.Wrapping = fyne.TextWrapWord
	code.SetMinRowsVisible(18)

	copyBtn := widget.NewButton("一键复制", nil)
	copyBtn.Importance = widget.HighImportance
	copyBtn.OnTapped = func() {
		w.Clipboard().SetContent(codeBlock)
		copyBtn.SetText("已复制 ✓")
	}

	closeBtn := widget.NewButton("关闭", func() { w.Close() })

	content := container.NewBorder(
		container.NewVBox(hintLabel, widget.NewSeparator()), // 顶部：提示
		container.NewHBox(copyBtn, closeBtn),                // 底部：按钮
		nil, nil,
		container.NewVScroll(code), // 中部：可滚动代码
	)
	w.SetContent(content)
	w.Show()
}

// envDir 受控端本地 Env 文件夹（存储池根目录下 Env 子目录，D4）。
func (a *App) envDir() string {
	return a.cfg.EnvDir()
}
