// Package filetransfer - 下载模块（任务 7.5）
// 受控端从管理端分块下载大文件，支持断点续传与完整性校验。
package filetransfer

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
)

// Downloader 文件下载器
type Downloader struct {
	serverURL string
	chunkSize int64
	token     string
	instance  string
}

// NewDownloader 创建下载器
func NewDownloader(serverURL, token, instance string, chunkSize int64) *Downloader {
	if chunkSize <= 0 {
		chunkSize = 4 * 1024 * 1024
	}
	return &Downloader{
		serverURL: serverURL,
		chunkSize: chunkSize,
		token:     token,
		instance:  instance,
	}
}

// DownloadProgress 下载进度回调
type DownloadProgress func(doneChunks, totalChunks int, doneBytes, totalBytes int64)

// Download 从管理端下载文件至本地
func (d *Downloader) Download(sourcePath, localPath, transferID, fileType string, progress DownloadProgress) error {
	// 初始化下载
	info, err := d.initDownload(transferID, sourcePath, fileType)
	if err != nil {
		return fmt.Errorf("初始化下载失败: %w", err)
	}

	// 创建本地文件
	if err := os.MkdirAll(filepath.Dir(localPath), 0755); err != nil {
		return err
	}
	f, err := os.Create(localPath)
	if err != nil {
		return err
	}
	defer f.Close()

	// 查询已完成块（断点续传）
	doneChunks, _ := d.queryDownloaded(transferID)

	totalChunks := info.TotalChunks
	totalBytes := info.TotalBytes

	// 逐块下载
	for i := 0; i < totalChunks; i++ {
		if doneChunks[i] {
			continue
		}

		chunk, err := d.downloadChunk(transferID, i)
		if err != nil {
			return fmt.Errorf("下载块 %d 失败: %w", i, err)
		}

		// 校验块校验和
		if chunk.Checksum != "" {
			actual := bytesSHA256(chunk.Data)
			if actual != chunk.Checksum {
				return fmt.Errorf("块 %d 校验失败: 期望 %s 实际 %s", i, chunk.Checksum, actual)
			}
		}

		// 写入文件
		offset := int64(i) * d.chunkSize
		if _, err := f.WriteAt(chunk.Data, offset); err != nil {
			return fmt.Errorf("写入块 %d 失败: %w", i, err)
		}

		// 确认接收
		d.ackChunk(transferID, i)
		doneChunks[i] = true

		if progress != nil {
			progress(len(doneChunks), totalChunks, int64(len(doneChunks))*d.chunkSize, totalBytes)
		}
	}

	// 整体校验和校验
	if info.Checksum != "" {
		actual, err := fileSHA256(localPath)
		if err != nil {
			return fmt.Errorf("计算本地校验和失败: %w", err)
		}
		if actual != info.Checksum {
			return fmt.Errorf("文件完整性校验失败: 期望 %s 实际 %s", info.Checksum, actual)
		}
	}

	return nil
}

type downloadInfo struct {
	TransferID  string `json:"transferId"`
	TotalChunks int    `json:"totalChunks"`
	TotalBytes  int64  `json:"totalBytes"`
	Checksum    string `json:"checksum"`
}

type chunkData struct {
	Index    int    `json:"index"`
	Data     []byte `json:"data"`
	Checksum string `json:"checksum"`
}

func (d *Downloader) initDownload(transferID, sourcePath, fileType string) (*downloadInfo, error) {
	body, _ := json.Marshal(map[string]any{
		"transferId":     transferID,
		"sourcePath":     sourcePath,
		"instanceNumber": d.instance,
		"type":           fileType,
	})
	req, _ := http.NewRequest("POST", d.serverURL+"/api/agent/file/download/init", bytes.NewReader(body))
	d.setAuth(req)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result struct {
		Code int          `json:"code"`
		Data downloadInfo `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	return &result.Data, nil
}

func (d *Downloader) downloadChunk(transferID string, index int) (*chunkData, error) {
	// transferId 经 URL 编码，避免含空格/特殊字符（如 env 文件名）破坏 URL 致返回 HTML 错误页
	u := fmt.Sprintf("%s/api/agent/file/download/chunk?transferId=%s&chunkIndex=%d",
		d.serverURL, url.QueryEscape(transferID), index)
	req, _ := http.NewRequest("GET", u, nil)
	d.setAuth(req)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result struct {
		Code int       `json:"code"`
		Data chunkData `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		// 响应非 JSON（多为 HTML 错误页 / 鉴权重定向），附状态码便于定位
		return nil, fmt.Errorf("解析下载块 %d 响应失败 (status=%d): %w", index, resp.StatusCode, err)
	}
	// platform-refinements #5：管理端返回错误时不应静默写空数据
	if result.Code != 0 {
		return nil, fmt.Errorf("管理端下载块 %d 失败: code=%d", index, result.Code)
	}
	return &result.Data, nil
}

func (d *Downloader) ackChunk(transferID string, index int) {
	u := fmt.Sprintf("%s/api/agent/file/download/ack?transferId=%s&chunkIndex=%d",
		d.serverURL, url.QueryEscape(transferID), index)
	req, _ := http.NewRequest("POST", u, nil)
	d.setAuth(req)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
}

func (d *Downloader) queryDownloaded(transferID string) (map[int]bool, error) {
	// 简化：总是从头下载（可扩展查询管理端已完成块）
	return map[int]bool{}, nil
}

func (d *Downloader) setAuth(req *http.Request) {
	req.Header.Set("X-Agent-Token", d.token)
	req.Header.Set("X-Instance-Number", d.instance)
}
