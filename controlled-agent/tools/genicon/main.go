// genicon renders the Nexcompute logo (the "N" made of distributed GPU chips)
// to icon.png (256px, straight alpha) and icon.ico (16/32/48/256, classic
// BMP-format entries for max compatibility with the Windows shell and
// getlantern/systray's CreateIconFromResource).
//
// Run from the controlled-agent module root:
//
//	go run ./tools/genicon
package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/png"
	"os"
	"path/filepath"
)

// ---- design space (matches management-frontend/public/favicon.svg) ----
const (
	design = 64.0

	tileX, tileY, tileW, tileH = 2.0, 2.0, 60.0, 60.0
	tileRX                     = 14.0

	stroke = 4.0 // N line stroke width
)

// the three strokes that form the letter N (TL->BL left, TR->BR right, TL->BR diagonal)
var lines = [3][4]float64{
	{19, 19, 19, 45}, // left vertical
	{45, 19, 45, 45}, // right vertical
	{19, 19, 45, 45}, // diagonal (top-left -> bottom-right)
}

// four GPU chip nodes at the corners
var chipCenters = [4][2]float64{{19, 19}, {45, 19}, {19, 45}, {45, 45}}

const (
	chipHalf = 6.0
	chipRX   = 3.5
	dieHalf  = 2.0
	dieRX    = 1.0
)

// brand colors #1677ff -> #0958d9
var c0 = [3]byte{22, 119, 255}
var c1 = [3]byte{9, 88, 217}

// ---- math helpers ----
func clampf(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func lerpCol(a, b [3]byte, t float64) [3]byte {
	return [3]byte{
		byte(float64(a[0]) + (float64(b[0])-float64(a[0]))*t),
		byte(float64(a[1]) + (float64(b[1])-float64(a[1]))*t),
		byte(float64(a[2]) + (float64(b[2])-float64(a[2]))*t),
	}
}

// gradient t along the diagonal of a box with local coords (lx,ly) in [0,size]
func diagT(lx, ly, size float64) float64 {
	return clampf((lx+ly)/(2*size), 0, 1)
}

// point in rounded rect with origin (ox,oy), size (w,h), corner radius rx
func inRoundRect(px, py, ox, oy, w, h, rx float64) bool {
	lx := px - ox
	ly := py - oy
	if lx < 0 || ly < 0 || lx > w || ly > h {
		return false
	}
	r := rx
	if r > w/2 {
		r = w / 2
	}
	if r > h/2 {
		r = h / 2
	}
	nx := clampf(lx, r, w-r)
	ny := clampf(ly, r, h-r)
	dx := lx - nx
	dy := ly - ny
	return dx*dx+dy*dy <= r*r
}

// point within radius r of segment (x1,y1)-(x2,y2)
func inCapsule(px, py, x1, y1, x2, y2, r float64) bool {
	vx, vy := x2-x1, y2-y1
	wx, wy := px-x1, py-y1
	seg2 := vx*vx + vy*vy
	t := 0.0
	if seg2 > 0 {
		t = (wx*vx + wy*vy) / seg2
		if t < 0 {
			t = 0
		} else if t > 1 {
			t = 1
		}
	}
	cx := x1 + t*vx
	cy := y1 + t*vy
	dx := px - cx
	dy := py - cy
	return dx*dx+dy*dy <= r*r
}

// ---- per-pixel color at a design-space point ----
func shade(px, py float64) (r, g, b, a byte) {
	// 1. tile (gradient) -- transparent outside the rounded tile
	if inRoundRect(px, py, tileX, tileY, tileW, tileH, tileRX) {
		lx := px - tileX
		ly := py - tileY
		c := lerpCol(c0, c1, diagT(lx, ly, tileW))
		r, g, b = c[0], c[1], c[2]
		a = 255
	}
	// 2. N strokes (white)
	for _, l := range lines {
		if inCapsule(px, py, l[0], l[1], l[2], l[3], stroke/2) {
			r, g, b, a = 255, 255, 255, 255
		}
	}
	// 3. chip packages (white) sit on top of line endpoints
	for _, ct := range chipCenters {
		if inRoundRect(px, py, ct[0]-chipHalf, ct[1]-chipHalf, 2*chipHalf, 2*chipHalf, chipRX) {
			r, g, b, a = 255, 255, 255, 255
		}
	}
	// 4. chip dies (brand gradient)
	for _, ct := range chipCenters {
		if inRoundRect(px, py, ct[0]-dieHalf, ct[1]-dieHalf, 2*dieHalf, 2*dieHalf, dieRX) {
			dlx := px - (ct[0] - dieHalf)
			dly := py - (ct[1] - dieHalf)
			c := lerpCol(c0, c1, diagT(dlx, dly, 2*dieHalf))
			r, g, b, a = c[0], c[1], c[2], 255
		}
	}
	return
}

// render master at high resolution (supersampled), return *image.RGBA
func renderMaster(size int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	scale := float64(size) / design
	for j := 0; j < size; j++ {
		py := (float64(j) + 0.5) / scale
		for i := 0; i < size; i++ {
			px := (float64(i) + 0.5) / scale
			r, g, b, a := shade(px, py)
			off := j*img.Stride + i*4
			img.Pix[off+0] = r
			img.Pix[off+1] = g
			img.Pix[off+2] = b
			img.Pix[off+3] = a
		}
	}
	return img
}

// area-average downsample master -> size S (straight alpha, alpha-weighted RGB)
func downsize(master *image.RGBA, S int) *image.RGBA {
	M := master.Bounds().Dx()
	ratio := M / S // integer by construction (master chosen as multiple)
	out := image.NewRGBA(image.Rect(0, 0, S, S))
	for ty := 0; ty < S; ty++ {
		for tx := 0; tx < S; tx++ {
			var accA, accR, accG, accB uint64
			for j := 0; j < ratio; j++ {
				for i := 0; i < ratio; i++ {
					mx := tx*ratio + i
					my := ty*ratio + j
					c := master.RGBAAt(mx, my)
					accA += uint64(c.A)
					accR += uint64(c.R) * uint64(c.A)
					accG += uint64(c.G) * uint64(c.A)
					accB += uint64(c.B) * uint64(c.A)
				}
			}
			count := uint64(ratio * ratio)
			o := out.PixOffset(tx, ty)
			if accA == 0 {
				out.Pix[o+0], out.Pix[o+1], out.Pix[o+2], out.Pix[o+3] = 0, 0, 0, 0
				continue
			}
			out.Pix[o+3] = byte(accA / count)
			out.Pix[o+0] = byte(accR / accA)
			out.Pix[o+1] = byte(accG / accA)
			out.Pix[o+2] = byte(accB / accA)
		}
	}
	return out
}

// encode one BMP-format ICO entry (32bpp, premultiplied BGRA + AND mask)
func bmpEntry(img *image.RGBA) []byte {
	S := img.Bounds().Dx()
	andRow := ((S+7)/8 + 3) &^ 3 // 1bpp rows padded to 4 bytes
	andSize := andRow * S
	xorSize := S * S * 4

	var b bytes.Buffer
	// BITMAPINFOHEADER
	binary.Write(&b, binary.LittleEndian, uint32(40)) // biSize
	binary.Write(&b, binary.LittleEndian, int32(S))   // biWidth
	binary.Write(&b, binary.LittleEndian, int32(2*S)) // biHeight (XOR+AND)
	binary.Write(&b, binary.LittleEndian, uint16(1))  // planes
	binary.Write(&b, binary.LittleEndian, uint16(32)) // bitcount
	binary.Write(&b, binary.LittleEndian, uint32(0))  // compression BI_RGB
	binary.Write(&b, binary.LittleEndian, uint32(xorSize+andSize))
	binary.Write(&b, binary.LittleEndian, uint32(0)) // x ppm
	binary.Write(&b, binary.LittleEndian, uint32(0)) // y ppm
	binary.Write(&b, binary.LittleEndian, uint32(0)) // clrused
	binary.Write(&b, binary.LittleEndian, uint32(0)) // clrimportant
	// XOR: bottom-up, premultiplied BGRA
	for y := S - 1; y >= 0; y-- {
		for x := 0; x < S; x++ {
			c := img.RGBAAt(x, y)
			a := uint32(c.A)
			b.WriteByte(byte(uint32(c.B) * a / 255))
			b.WriteByte(byte(uint32(c.G) * a / 255))
			b.WriteByte(byte(uint32(c.R) * a / 255))
			b.WriteByte(c.A)
		}
	}
	// AND mask: bottom-up, 1bpp, MSB-first; 1 = transparent
	for y := S - 1; y >= 0; y-- {
		row := make([]byte, andRow)
		for x := 0; x < S; x++ {
			if img.RGBAAt(x, y).A == 0 {
				row[x/8] |= 1 << (7 - uint(x%8))
			}
		}
		b.Write(row)
	}
	return b.Bytes()
}

func buildICO(imgs map[int]*image.RGBA, order []int) []byte {
	var dir, data bytes.Buffer
	binary.Write(&dir, binary.LittleEndian, uint16(0))          // reserved
	binary.Write(&dir, binary.LittleEndian, uint16(1))          // type = icon
	binary.Write(&dir, binary.LittleEndian, uint16(len(order))) // count

	offset := uint32(6 + 16*len(order))
	for _, S := range order {
		entry := bmpEntry(imgs[S])
		wh := byte(S)
		if S >= 256 {
			wh = 0
		}
		dir.WriteByte(wh)                                           // width
		dir.WriteByte(wh)                                           // height
		dir.WriteByte(0)                                            // color count
		dir.WriteByte(0)                                            // reserved
		binary.Write(&dir, binary.LittleEndian, uint16(1))          // planes
		binary.Write(&dir, binary.LittleEndian, uint16(32))         // bit count
		binary.Write(&dir, binary.LittleEndian, uint32(len(entry))) // bytes
		binary.Write(&dir, binary.LittleEndian, offset)             // offset
		data.Write(entry)
		offset += uint32(len(entry))
	}
	var out bytes.Buffer
	out.Write(dir.Bytes())
	out.Write(data.Bytes())
	return out.Bytes()
}

func writePNG(path string, img *image.RGBA) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := png.Encoder{CompressionLevel: png.BestCompression}
	return enc.Encode(f, img)
}

func writeBytes(path string, data []byte) error {
	return os.WriteFile(path, data, 0o644)
}

func main() {
	// module root = parent of tools/
	root, _ := filepath.Abs(filepath.Join(filepath.Dir(os.Args[0]), ".."))
	// when run via `go run`, os.Args[0] is a temp path; resolve from CWD instead
	if _, err := os.Stat(filepath.Join("tools", "genicon")); err == nil {
		root, _ = filepath.Abs(".")
	}
	guiDir := filepath.Join(root, "internal", "gui")
	cmdDir := filepath.Join(root, "cmd", "nexcompute-agent")

	const master = 1536 // divisible by 256(×6), 48(×32), 32(×48), 16(×96)
	sizes := []int{256, 48, 32, 16}
	fmt.Printf("rendering master %dx%d...\n", master, master)
	m := renderMaster(master)
	imgs := map[int]*image.RGBA{}
	for _, s := range sizes {
		imgs[s] = downsize(m, s)
	}
	// also a clean 256 for the standalone PNG (reuse the downsize)
	pngPath := filepath.Join(guiDir, "icon.png")
	if err := writePNG(pngPath, imgs[256]); err != nil {
		fmt.Fprintln(os.Stderr, "write png:", err)
		os.Exit(1)
	}
	ico := buildICO(imgs, sizes)
	icoPaths := []string{
		filepath.Join(guiDir, "icon.ico"),
		filepath.Join(cmdDir, "icon.ico"),
	}
	for _, p := range icoPaths {
		if err := writeBytes(p, ico); err != nil {
			fmt.Fprintln(os.Stderr, "write ico:", err)
			os.Exit(1)
		}
	}

	fmt.Printf("wrote %s (%dx%d PNG)\n", pngPath, 256, 256)
	for _, p := range icoPaths {
		fmt.Printf("wrote %s (%d bytes, sizes %v)\n", p, len(ico), sizes)
	}

	// sanity: spot-check a few design->pixel colors on the 256 image
	img := imgs[256]
	check := func(dx, dy float64, label string) {
		s := float64(256) / design
		x := int(dx * s)
		y := int(dy * s)
		c := img.RGBAAt(x, y)
		fmt.Printf("  %-22s design(%.0f,%.0f) -> pix(%d,%d) RGBA(%d,%d,%d,%d)\n", label, dx, dy, x, y, c.R, c.G, c.B, c.A)
	}
	fmt.Println("spot check:")
	check(1, 1, "outside tile (transp)")
	check(10, 10, "tile only (blue grad)")
	check(19, 15, "chip ring (white)")
	check(19, 32, "left stroke (white)")
	check(32, 32, "diagonal stroke (white)")
	check(19, 19, "chip die (blue)")
	// verify the PNG re-decodes
	pngBytes, err := os.ReadFile(pngPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read png:", err)
		os.Exit(1)
	}
	if _, err := png.Decode(bytes.NewReader(pngBytes)); err != nil {
		fmt.Fprintln(os.Stderr, "PNG decode failed:", err)
		os.Exit(1)
	}
	fmt.Println("PNG re-decodes OK")
}
