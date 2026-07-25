// Package sysinfo 采集系统状态（任务 11.6、4.6）。
// 采集 CPU 占用、GPU 占用、CPU 温度、GPU 温度、内存占用、进程列表。
// 通过 gopsutil 采集 CPU/内存，GPU 通过 nvidia-smi 采集。
package sysinfo

import (
	"context"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/nexcompute/controlled-agent/internal/executil"
	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
)

// Status 系统状态快照
type Status struct {
	CPUUsage    float64       `json:"cpuUsage"`    // 百分比
	CPUTemp     float64       `json:"cpuTemp"`     // 摄氏度
	GPUUsage    float64       `json:"gpuUsage"`    // 百分比（兼容旧字段）
	GPUTemp     float64       `json:"gpuTemp"`     // 摄氏度（兼容旧字段）
	MemoryUsage float64       `json:"memoryUsage"` // 百分比
	MemoryTotal uint64        `json:"memoryTotal"` // 字节
	MemoryUsed  uint64        `json:"memoryUsed"`  // 字节
	Processes   []ProcessInfo `json:"processes"`
	GPUInfo     *GPUInfo      `json:"gpuInfo,omitempty"`
	DiskPartitions []DiskPartition `json:"diskPartitions"` // 各分区存储空间情况
	Timestamp   int64             `json:"timestamp"`
}

// DiskPartition 磁盘分区存储空间
type DiskPartition struct {
	Device     string `json:"device"`     // 设备/挂载点（如 C:、D:）
	Mountpoint string `json:"mountpoint"` // 挂载路径
	Total      uint64 `json:"total"`      // 总容量（字节）
	Used       uint64 `json:"used"`       // 已用（字节）
	Free       uint64 `json:"free"`       // 可用（字节）
	Usage      float64 `json:"usage"`     // 使用率百分比
}

// ProcessInfo 进程信息
type ProcessInfo struct {
	PID    int32   `json:"pid"`
	Name   string  `json:"name"`
	CPU    float64 `json:"cpu"`
	Memory float64 `json:"memory"`
}

// GPUInfo GPU 信息（platform-improvements 任务 4.1：整合结构化 GPU）
type GPUInfo struct {
	Name         string  `json:"name"`
	MemoryTotal  uint64  `json:"memoryTotal"`  // MiB（nvidia-smi nounits）
	MemoryUsed   uint64  `json:"memoryUsed"`   // MiB
	DriverVer    string  `json:"driverVersion"`
	Utilization  float64 `json:"utilization"`  // 百分比
	Temperature  float64 `json:"temperature"`  // 摄氏度
}

// HostInfo 主机信息
type HostInfo struct {
	Hostname string `json:"hostname"`
	OS       string `json:"os"`
}

// GetHostInfo 返回主机基本信息
func GetHostInfo() HostInfo {
	host, err := host.Info()
	if err != nil {
		return HostInfo{}
	}
	return HostInfo{
		Hostname: host.Hostname,
		OS:       host.OS + " " + host.PlatformVersion,
	}
}

// Collect 采集当前系统状态
func Collect() (*Status, error) {
	s := &Status{Timestamp: time.Now().UnixMilli()}

	// CPU 占用
	if cpuPercent, err := cpu.Percent(time.Second, false); err == nil && len(cpuPercent) > 0 {
		s.CPUUsage = cpuPercent[0]
	}

	// 内存
	if m, err := mem.VirtualMemory(); err == nil {
		s.MemoryUsage = m.UsedPercent
		s.MemoryTotal = m.Total
		s.MemoryUsed = m.Used
	}

	// GPU（通过 nvidia-smi）
	s.GPUInfo, s.GPUUsage, s.GPUTemp = collectGPU()

	// 磁盘分区存储空间（任务 2：各分区存储空间情况）
	s.DiskPartitions = collectDiskPartitions()

	return s, nil
}

// CollectIPAddresses 采集主机全部 IP 地址（过滤回环与虚拟网卡，任务 4.1）
func CollectIPAddresses() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var result []string
	for _, ifc := range ifaces {
		// 跳过未启用或回环接口
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		// 启发式过滤虚拟网卡（常见虚拟桥/veth 前缀）
		name := strings.ToLower(ifc.Name)
		if strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "veth") ||
			strings.HasPrefix(name, "br-") || strings.HasPrefix(name, "vmnet") {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
				continue
			}
			result = append(result, ip.String())
		}
	}
	return result
}

// MachineFingerprint 机器指纹（platform-refinements #1：MAC + 机器码，用于注册去重）
type MachineFingerprint struct {
	MAC        string `json:"mac"`
	MachineCode string `json:"machineCode"`
}

// CollectMachineFingerprint 采集 MAC（首个非虚拟网卡）+ 机器码（host HostID）
func CollectMachineFingerprint() MachineFingerprint {
	fp := MachineFingerprint{}
	ifaces, err := net.Interfaces()
	if err == nil {
		for _, ifc := range ifaces {
			if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
				continue
			}
			name := strings.ToLower(ifc.Name)
			if strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "veth") ||
				strings.HasPrefix(name, "br-") || strings.HasPrefix(name, "vmnet") {
				continue
			}
			if mac := ifc.HardwareAddr.String(); mac != "" {
				fp.MAC = mac
				break
			}
		}
	}
	if hi, err := host.Info(); err == nil && hi.HostID != "" {
		fp.MachineCode = hi.HostID
	}
	return fp
}

// collectDiskPartitions 采集各磁盘分区存储空间（任务 2）
func collectDiskPartitions() []DiskPartition {
	stats, err := disk.Partitions(false) // false=只物理分区
	if err != nil {
		return nil
	}
	var result []DiskPartition
	for _, p := range stats {
		usage, err := disk.Usage(p.Mountpoint)
		if err != nil {
			continue
		}
		dp := DiskPartition{
			Device:     p.Device,
			Mountpoint: p.Mountpoint,
			Total:      usage.Total,
			Used:       usage.Used,
			Free:       usage.Free,
			Usage:      usage.UsedPercent,
		}
		result = append(result, dp)
	}
	return result
}

// collectGPU 通过 nvidia-smi 采集 GPU 状态（任务 4.1：整合结构化 GPU 显存/利用率/温度）
func collectGPU() (*GPUInfo, float64, float64) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	cmd := executil.HideWindow(exec.CommandContext(ctx, "nvidia-smi",
		"--query-gpu=name,temperature.gpu,utilization.gpu,memory.total,memory.used,driver_version",
		"--format=csv,noheader,nounits"))
	out, err := cmd.Output()
	if err != nil {
		return nil, 0, 0
	}
	fields := strings.Split(strings.TrimSpace(string(out)), ",")
	if len(fields) < 6 {
		return nil, 0, 0
	}
	utilization := parseFloat(fields[2])
	temp := parseFloat(fields[1])
	return &GPUInfo{
		Name:        strings.TrimSpace(fields[0]),
		MemoryTotal: parseUint(fields[3]), // MiB
		MemoryUsed:  parseUint(fields[4]), // MiB
		DriverVer:   strings.TrimSpace(fields[5]),
		Utilization: utilization,
		Temperature: temp,
	}, utilization, temp
}

func parseUint(s string) uint64 {
	v, err := strconv.ParseUint(strings.TrimSpace(s), 10, 64)
	if err != nil {
		return 0
	}
	return v
}

func parseFloat(s string) float64 {
	f, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	if err != nil {
		return 0
	}
	return f
}
