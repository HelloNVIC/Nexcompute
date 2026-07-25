// Package filetransfer - 测试（任务 14.2）
package filetransfer

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestNewManager_Defaults(t *testing.T) {
	m := NewManager(TransferConfig{})
	if m.Config().ChunkSize != 4*1024*1024 {
		t.Errorf("expected default chunk size 4MB, got %d", m.Config().ChunkSize)
	}
	if m.Config().ChecksumAlgo != "sha256" {
		t.Errorf("expected default checksum sha256, got %s", m.Config().ChecksumAlgo)
	}
}

func TestNewManager_CustomConfig(t *testing.T) {
	m := NewManager(TransferConfig{ChunkSize: 1024, ChecksumAlgo: "md5"})
	if m.Config().ChunkSize != 1024 {
		t.Errorf("expected chunk size 1024, got %d", m.Config().ChunkSize)
	}
	if m.Config().ChecksumAlgo != "md5" {
		t.Errorf("expected md5, got %s", m.Config().ChecksumAlgo)
	}
}

func TestChunkData_Structure(t *testing.T) {
	data := []byte("test chunk data")
	sum := sha256.Sum256(data)
	checksum := hex.EncodeToString(sum[:])

	c := Chunk{Index: 5, Data: data, Checksum: checksum}
	if c.Index != 5 {
		t.Errorf("expected index 5, got %d", c.Index)
	}
	if string(c.Data) != "test chunk data" {
		t.Error("data mismatch")
	}
	if c.Checksum != checksum {
		t.Error("checksum mismatch")
	}
}

func TestTransferProgress_Calculation(t *testing.T) {
	p := TransferProgress{
		TotalChunks: 100,
		DoneChunks:  30,
		TotalBytes:  400 * 1024 * 1024,
		DoneBytes:   120 * 1024 * 1024,
		Status:      "transferring",
	}
	if p.DoneChunks != 30 {
		t.Errorf("expected 30 done chunks, got %d", p.DoneChunks)
	}
	// 30% progress
	pct := float64(p.DoneChunks) / float64(p.TotalChunks) * 100
	if pct != 30.0 {
		t.Errorf("expected 30%% progress, got %f%%", pct)
	}
}

func TestTransferProgress_Completed(t *testing.T) {
	p := TransferProgress{
		TotalChunks: 10,
		DoneChunks:  10,
		Status:      "completed",
	}
	if p.DoneChunks != p.TotalChunks {
		t.Error("expected all chunks done")
	}
	if p.Status != "completed" {
		t.Error("expected completed status")
	}
}
