<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { containerApi } from '@/api/container'

const props = defineProps<{ visible: boolean; containerId: number }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const hostRef = ref<HTMLDivElement | null>(null)
// platform-refinements #8：shell 选择 + 自定义
const shellPresets = ['/bin/bash', '/bin/sh', '/bin/zsh', 'sh']
const shellMode = ref<'preset' | 'custom'>('preset')
const shellPreset = ref('/bin/bash')
const shellCustom = ref('')
const connected = ref(false)
let term: Terminal | null = null
let fit: FitAddon | null = null
let sessionId = ''
let pollTimer: ReturnType<typeof setInterval> | null = null
let writeBusy = false

const chosenShell = () => (shellMode.value === 'custom' ? (shellCustom.value.trim() || 'sh') : shellPreset.value)

function b64Encode(s: string): string {
  const bytes = new TextEncoder().encode(s)
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin)
}

function b64Decode(b64: string): Uint8Array {
  if (!b64) return new Uint8Array()
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

async function connect(): Promise<void> {
  if (connected.value) return
  if (!hostRef.value) return
  term = new Terminal({ fontSize: 13, cursorBlink: true, theme: { background: '#1e1e1e' } })
  fit = new FitAddon()
  term.loadAddon(fit)
  term.open(hostRef.value)
  fit.fit()
  term.onData(async (data) => {
    if (!sessionId || writeBusy) return
    writeBusy = true
    try {
      await containerApi.terminalWrite(props.containerId, sessionId, b64Encode(data))
    } catch {
      // 忽略单次失败
    } finally {
      writeBusy = false
    }
  })
  try {
    const out = await containerApi.terminalOpen(props.containerId, chosenShell())
    sessionId = JSON.parse(out).sessionId
    connected.value = true
    pollTimer = setInterval(pollRead, 150)
  } catch {
    term.writeln('\r\n[打开终端失败]')
    term.dispose()
    term = null
  }
}

async function pollRead(): Promise<void> {
  if (!sessionId || !term) return
  try {
    const out = await containerApi.terminalRead(props.containerId, sessionId)
    const data = JSON.parse(out).data as string
    if (data) term.write(b64Decode(data))
  } catch {
    // 忽略单次失败
  }
}

async function stop(): Promise<void> {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (sessionId) {
    try { await containerApi.terminalClose(props.containerId, sessionId) } catch { /* */ }
    sessionId = ''
  }
  term?.dispose()
  term = null
  connected.value = false
}

onBeforeUnmount(stop)
watch(() => props.visible, async (v) => {
  if (!v) await stop()
})
</script>

<template>
  <a-modal
    :open="visible"
    title="容器终端"
    width="860px"
    :footer="null"
    :mask-closable="false"
    @cancel="emit('update:visible', false)"
  >
    <a-space v-if="!connected" style="margin-bottom: 12px">
      <a-radio-group v-model:value="shellMode">
        <a-radio value="preset">预设</a-radio>
        <a-radio value="custom">自定义</a-radio>
      </a-radio-group>
      <a-select v-if="shellMode === 'preset'" v-model:value="shellPreset" style="width: 160px">
        <a-select-option v-for="s in shellPresets" :key="s" :value="s">{{ s }}</a-select-option>
      </a-select>
      <a-input v-else v-model:value="shellCustom" placeholder="如 /bin/ash" style="width: 160px" />
      <a-button type="primary" :disabled="shellMode === 'custom' && !shellCustom.trim()" @click="connect">连接</a-button>
    </a-space>
    <div ref="hostRef" style="height: 460px; background: #1e1e1e; padding: 4px" />
    <div style="color: #999; font-size: 12px; margin-top: 8px">
      交互式 shell，关闭弹窗即结束会话。输入字符即下发，输出每 150ms 轮询回传。
    </div>
  </a-modal>
</template>
