<!--
  Barrage 火力网压测引擎 - 设置页面（config.toml 管理）
-->

<template>
  <div class="h-full overflow-auto p-8">
    <div class="max-w-3xl mx-auto space-y-6">

      <!-- 标题 -->
      <div class="flex items-center justify-between mb-2">
        <div>
          <h2 class="text-2xl font-bold text-white">引擎设置</h2>
          <p class="text-sm text-white/40 mt-1">配置 Barrage 压测引擎运行参数</p>
        </div>
        <div class="flex items-center gap-3">
          <span v-if="saveState === 'success'" class="text-xs text-green-400">已保存</span>
          <span v-if="saveState === 'error'" class="text-xs text-red-400">{{ saveError }}</span>
          <button
            @click="saveConfig"
            :disabled="saveState === 'saving' || loading"
            class="px-5 py-2 rounded-lg text-sm font-semibold
                   bg-sky-500/20 text-sky-400 border border-sky-500/30
                   hover:bg-sky-500/30 transition-all duration-200
                   disabled:opacity-50 disabled:cursor-not-allowed
                   flex items-center gap-2"
          >
            <svg v-if="saveState === 'saving'" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
            </svg>
            {{ saveState === 'saving' ? '保存中...' : '保存配置' }}
          </button>
        </div>
      </div>

      <!-- 加载状态 -->
      <div v-if="loading" class="flex items-center justify-center py-20">
        <svg class="w-8 h-8 animate-spin text-sky-400" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
        <span class="ml-3 text-white/60">加载配置中...</span>
      </div>

      <template v-else>

        <!-- 网络配置 -->
        <div class="rounded-xl bg-white/5 border border-white/10 p-6">
          <h3 class="text-base font-semibold text-white mb-4 flex items-center gap-2">
            <svg class="w-5 h-5 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9"/>
            </svg>
            网络配置
          </h3>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-white/60 mb-1">IP 地址</label>
              <input v-model="config.ip" type="text"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">端口</label>
              <input v-model.number="config.port" type="number"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
          </div>
        </div>

        <!-- 引擎配置 -->
        <div class="rounded-xl bg-white/5 border border-white/10 p-6">
          <h3 class="text-base font-semibold text-white mb-4 flex items-center gap-2">
            <svg class="w-5 h-5 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
            </svg>
            引擎配置
          </h3>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-white/60 mb-1">服务端线程数</label>
              <p class="text-xs text-white/30 mb-1">io_uring 服务端 IO 线程数</p>
              <input v-model.number="config.serverThreads" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">客户端线程数</label>
              <p class="text-xs text-white/30 mb-1">io_uring 客户端 IO 线程数</p>
              <input v-model.number="config.clientThreads" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">每客户端连接数</label>
              <p class="text-xs text-white/30 mb-1">每个 IO 线程的 TCP 连接数</p>
              <input v-model.number="config.connsPerClient" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">QPS 递增步长</label>
              <p class="text-xs text-white/30 mb-1">每次递增的请求数上限</p>
              <input v-model.number="config.step" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
          </div>
        </div>

        <!-- 性能配置 -->
        <div class="rounded-xl bg-white/5 border border-white/10 p-6">
          <h3 class="text-base font-semibold text-white mb-4 flex items-center gap-2">
            <svg class="w-5 h-5 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
            </svg>
            性能配置
          </h3>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-white/60 mb-1">最大并发请求数</label>
              <p class="text-xs text-white/30 mb-1">单连接最大 in-flight 请求数</p>
              <input v-model.number="config.inFlight" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">队列深度</label>
              <p class="text-xs text-white/30 mb-1">io_uring SQ 队列大小（2 的幂）</p>
              <input v-model.number="config.queueDepth" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">批量大小</label>
              <p class="text-xs text-white/30 mb-1">每次提交的 SQE 批量大小</p>
              <input v-model.number="config.batchSize" type="number" min="1"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
            <div>
              <label class="block text-sm text-white/60 mb-1">读缓冲区大小</label>
              <p class="text-xs text-white/30 mb-1">单次 recv 的缓冲区大小（字节）</p>
              <input v-model.number="config.readSz" type="number" min="1024"
                class="w-full px-3 py-2 rounded-lg bg-zinc-900 border border-white/10
                       text-white text-sm focus:border-sky-500/50 focus:outline-none transition-colors" />
            </div>
          </div>
        </div>

      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'

interface EngineConfig {
  ip: string
  port: number
  serverThreads: number
  clientThreads: number
  connsPerClient: number
  step: number
  inFlight: number
  queueDepth: number
  batchSize: number
  readSz: number
}

const loading = ref(true)
const saveState = ref<'idle' | 'saving' | 'success' | 'error'>('idle')
const saveError = ref('')

const config = reactive<EngineConfig>({
  ip: '127.0.0.1',
  port: 8089,
  serverThreads: 8,
  clientThreads: 8,
  connsPerClient: 8,
  step: 99999,
  inFlight: 64,
  queueDepth: 4096,
  batchSize: 16,
  readSz: 16384
})

async function loadConfig() {
  loading.value = true
  try {
    const res = await fetch('/api/config')
    if (!res.ok) throw new Error(`${res.status}`)
    const data = await res.json()
    config.ip = data.ip ?? config.ip
    config.port = data.port ?? config.port
    config.serverThreads = data.serverThreads ?? config.serverThreads
    config.clientThreads = data.clientThreads ?? config.clientThreads
    config.connsPerClient = data.connsPerClient ?? config.connsPerClient
    config.step = data.step ?? config.step
    config.inFlight = data.inFlight ?? config.inFlight
    config.queueDepth = data.queueDepth ?? config.queueDepth
    config.batchSize = data.batchSize ?? config.batchSize
    config.readSz = data.readSz ?? config.readSz
  } catch (err) {
    console.error('[Settings] 加载配置失败', err)
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const res = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config)
    })
    if (!res.ok) {
      const msg = await res.text()
      throw new Error(msg || `${res.status}`)
    }
    saveState.value = 'success'
    setTimeout(() => { if (saveState.value === 'success') saveState.value = 'idle' }, 2000)
  } catch (err: any) {
    saveState.value = 'error'
    saveError.value = err.message || '保存失败'
  }
}

onMounted(loadConfig)
</script>
