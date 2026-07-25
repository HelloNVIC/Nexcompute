// Package filetransfer - 上传模块（任务 7.4）
// 受控端向管理端分块上传大文件，支持断点续传与进度上报。
package filetransfer

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
)

// Uploader 文件上传器
type Uploader struct {
	serverURL    string
	chunkSize    int64
	token        string
	instance     string
}

// NewUploader 创建上传器
func NewUploader(serverURL, token, instance string, chunkSize int64) *Uploader {
	if chunkSize <= 0 {
		chunkSize = 4 * 1024 * 1024
	}
	return &Uploader{
		serverURL: serverURL,
		chunkSize: chunkSize,
		token:     token,
		instance:  instance,
	}
}

// UploadProgress 上传进度回调
type UploadProgress func(doneChunks, totalChunks int, doneBytes, totalBytes int64)

// Upload 上传文件至管理端
func (u *Uploader) Upload(localPath, transferID, fileType string, progress UploadProgress) error {
	f, err := os.Open(localPath)
	if err != nil {
		return fmt.Errorf("打开文件失败: %w", err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return fmt.Errorf("获取文件信息失败: %w", err)
	}
	totalBytes := stat.Size()
	totalChunks := int((totalBytes + u.chunkSize - 1) / u.chunkSize)
	checksum, err := fileSHA256(localPath)
	if err != nil {
		return fmt.Errorf("计算校验和失败: %w", err)
	}

	// 初始化上传
	if err := u.initUpload(transferID, filepath.Base(localPath), totalBytes, checksum, fileType); err != nil {
		return err
	}

	// 查询已完成块（断点续传）
	doneChunks, err := u.queryCompleted(transferID)
	if err != nil {
		return fmt.Errorf("查询断点失败: %w", err)
	}

	// 逐块上传
	buf := make([]byte, u.chunkSize)
	for i := 0; i < totalChunks; i++ {
		if doneChunks[i] {
			continue // 已完成，跳过
		}

		offset := int64(i) * u.chunkSize
		_, err := f.ReadAt(buf, offset)
		if err != nil && err != io.EOF {
			return fmt.Errorf("读取块 %d 失败: %w", i, err)
		}

		// 计算实际块大小（最后一块可能不足）
		chunkData := buf
		if offset+u.chunkSize > totalBytes {
			chunkData = buf[:totalBytes-offset]
		}

		chunkChecksum := bytesSHA256(chunkData)
		if err := u.uploadChunk(transferID, i, chunkChecksum, chunkData); err != nil {
			return fmt.Errorf("上传块 %d 失败: %w", i, err)
		}

		doneChunks[i] = true
		if progress != nil {
			progress(len(doneChunks), totalChunks, int64(len(doneChunks))*u.chunkSize, totalBytes)
		}
	}

	return nil
}

func (u *Uploader) initUpload(transferID, fileName string, totalBytes int64, checksum, fileType string) error {
	body, _ := json.Marshal(map[string]any{
		"transferId":     transferID,
		"fileName":       fileName,
		"totalBytes":     totalBytes,
		"checksum":       checksum,
		"instanceNumber": u.instance,
		"type":           fileType,
	})
	resp, err := u.post("/api/agent/file/upload/init", body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return nil
}

func (u *Uploader) queryCompleted(transferID string) (map[int]bool, error) {
	req, _ := http.NewRequest("GET", u.serverURL+"/api/agent/file/upload/status?transferId="+transferID, nil)
	u.setAuth(req)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result struct {
		Code int `json:"code"`
		Data []int `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return map[int]bool{}, nil
	}
	done := make(map[int]bool)
	for _, idx := range result.Data {
		done[idx] = true
	}
	return done, nil
}

func (u *Uploader) uploadChunk(transferID string, index int, checksum string, data []byte) error {
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	_ = writer.WriteField("transferId", transferID)
	_ = writer.WriteField("chunkIndex", fmt.Sprintf("%d", index))
	_ = writer.WriteField("chunkChecksum", checksum)
	part, err := writer.CreateFormFile("file", "chunk")
	if err != nil {
		return err
	}
	part.Write(data)
	writer.Close()

	req, _ := http.NewRequest("POST", u.serverURL+"/api/agent/file/upload/chunk", &buf)
	u.setAuth(req)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("管理端返回 %d", resp.StatusCode)
	}
	return nil
}

func (u *Uploader) setAuth(req *http.Request) {
	req.Header.Set("X-Agent-Token", u.token)
	req.Header.Set("X-Instance-Number", u.instance)
}

func (u *Uploader) post(path string, body []byte) (*http.Response, error) {
	req, _ := http.NewRequest("POST", u.serverURL+path, bytes.NewReader(body))
	u.setAuth(req)
	req.Header.Set("Content-Type", "application/json")
	return http.DefaultClient.Do(req)
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func bytesSHA256(data []byte) string {
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
