/**
 * 复制文本到剪贴板。
 *
 * 优先用 navigator.clipboard.writeText（仅在 HTTPS / localhost 等安全上下文可用）；
 * HTTP 部署下 navigator.clipboard 为 undefined，回退到 隐藏 textarea + document.execCommand('copy')。
 *
 * 注意：调用方应 await 返回值后再提示，避免复制失败却误报"已复制"。
 *
 * @returns 是否复制成功
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  // 1. 安全上下文：Clipboard API
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒 / 页面失焦等，走回退
    }
  }

  // 2. 回退：隐藏 textarea + execCommand（HTTP 非安全上下文可用）
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '0'
    textarea.style.left = '0'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
