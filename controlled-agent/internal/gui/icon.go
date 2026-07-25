package gui

import (
	_ "embed"

	"fyne.io/fyne/v2"
)

//go:embed icon.png
var iconPNG []byte

//go:embed icon.ico
var iconICO []byte

// AppIcon returns the Nexcompute logo as a Fyne resource (PNG), used as the
// Fyne application and window icon.
func AppIcon() fyne.Resource {
	return fyne.NewStaticResource("nexcompute-icon.png", iconPNG)
}

// TrayIconBytes returns the logo as ICO bytes for getlantern/systray
// (Windows expects ICO-format bytes in SetIcon).
func TrayIconBytes() []byte {
	return iconICO
}
