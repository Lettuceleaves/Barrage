<!--
  Barrage 火力网压测引擎 - 接口测试页面
-->

<template>
  <div class="w-full h-full p-8 overflow-y-auto">

    <!-- 页面标题 + 压力模式开关 -->
    <div class="mb-8 flex items-start justify-between">
      <div>
        <h1 class="text-4xl font-bold text-white mb-2">
          {{ stressMode.value ? '压力测试' : '接口测试' }}
        </h1>
        <p class="text-white/60">
          {{ stressMode.value ? '配置并执行高并发压力测试，模拟真实流量' : '配置并执行单个 API 接口的功能测试' }}
        </p>
      </div>

      <!-- 压力模式开关 -->
      <div class="flex items-center gap-4 px-6 py-4 rounded-xl bg-white/5 border border-white/10">
        <div class="flex flex-col items-end">
          <span class="text-sm font-semibold" :class="stressMode.value ? 'text-orange-400' : 'text-white/80'">
            {{ stressMode.value ? '压力模式' : '普通模式' }}
          </span>
          <span class="text-xs text-white/40">
            {{ stressMode.value ? '高并发测试' : '功能测试' }}
          </span>
        </div>
        <button
          @click="toggleStressMode"
          :class="[
            'relative w-16 h-8 rounded-full transition-all duration-300',
            stressMode.value ? 'bg-gradient-to-r from-orange-500 to-red-500' : 'bg-white/20'
          ]"
        >
          <div
            :class="[
              'absolute top-1 w-6 h-6 rounded-full bg-white shadow-lg transition-all duration-300',
              stressMode.value ? 'left-9' : 'left-1'
            ]"
          ></div>
        </button>
      </div>
    </div>

    <div class="grid grid-cols-2 gap-6">

      <!-- 左侧：配置区域 -->
      <div class="space-y-6">

        <!-- 基础配置卡片 -->
        <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <h2 class="text-xl font-semibold text-white mb-6 flex items-center gap-2">
            <svg :class="['w-6 h-6', getIconColorClass()]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            基础配置
          </h2>

          <div class="space-y-4">
            <!-- 请求方法 -->
            <div>
              <label class="block text-sm text-white/60 mb-2">请求方法</label>
              <div class="grid grid-cols-4 gap-2">
                <button
                  v-for="method in httpMethods"
                  :key="method"
                  @click="config.method = method"
                  :class="[
                    'px-4 py-2 rounded-lg font-medium transition-all duration-300',
                    config.method === method
                      ? `bg-${themeColor}-500/20 text-${themeColor}-400 border border-${themeColor}-500/50`
                      : 'bg-white/5 text-white/60 border border-white/10 hover:bg-white/10 hover:text-white'
                  ]"
                  :style="config.method === method ? getThemeStyle() : {}"
                >
                  {{ method }}
                </button>
              </div>
            </div>

            <!-- 请求 URL -->
            <div>
              <label class="block text-sm text-white/60 mb-2">请求 URL</label>
              <input
                v-model="config.url"
                type="text"
                placeholder="https://api.example.com/endpoint"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30
                       focus:bg-white/10
                       transition-all duration-300"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
            </div>

            <!-- 超时时间 -->
            <div>
              <label class="block text-sm text-white/60 mb-2">超时时间 (毫秒)</label>
              <input
                v-model.number="config.timeout"
                type="number"
                placeholder="5000"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30
                       focus:bg-white/10
                       transition-all duration-300"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
            </div>
          </div>
        </div>

        <!-- Headers 配置卡片 -->
        <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <div class="flex items-center justify-between mb-6">
            <h2 class="text-xl font-semibold text-white flex items-center gap-2">
              <svg :class="['w-6 h-6', getIconColorClass()]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z" />
              </svg>
              请求头 (Headers)
            </h2>
            <button
              @click="addHeader"
              class="px-3 py-1.5 rounded-lg text-sm font-medium border transition-all duration-300"
              :style="getThemeStyle()"
            >
              + 添加
            </button>
          </div>

          <div class="space-y-3">
            <div
              v-for="(header, index) in config.headers"
              :key="index"
              class="flex gap-3"
            >
              <input
                v-model="header.key"
                type="text"
                placeholder="Header 名称"
                class="flex-1 px-4 py-2 rounded-lg
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30 text-sm
                       transition-all duration-300"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
              <input
                v-model="header.value"
                type="text"
                placeholder="Header 值"
                class="flex-1 px-4 py-2 rounded-lg
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30 text-sm
                       transition-all duration-300"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
              <button
                @click="removeHeader(index)"
                class="px-3 py-2 rounded-lg
                       bg-red-500/10 border border-red-500/30 text-red-400
                       hover:bg-red-500/20 hover:border-red-500/50
                       transition-all duration-300"
              >
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div v-if="config.headers.length === 0" class="text-center text-white/40 py-8">
              暂无请求头，点击 "添加" 按钮添加
            </div>
          </div>
        </div>

        <!-- Body 配置卡片 -->
        <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <h2 class="text-xl font-semibold text-white mb-6 flex items-center gap-2">
            <svg :class="['w-6 h-6', getIconColorClass()]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            请求体 (Body)
          </h2>

          <div class="space-y-4">
            <!-- Body 类型选择 -->
            <div class="flex gap-2">
              <button
                v-for="type in bodyTypes"
                :key="type.value"
                @click="config.bodyType = type.value"
                :class="[
                  'px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300',
                  config.bodyType === type.value
                    ? ''
                    : 'bg-white/5 text-white/60 border border-white/10 hover:bg-white/10 hover:text-white'
                ]"
                :style="config.bodyType === type.value ? getThemeStyle() : {}"
              >
                {{ type.label }}
              </button>
            </div>

            <!-- JSON Body 编辑器 -->
            <div v-if="config.bodyType === 'json'">
              <textarea
                v-model="config.body"
                placeholder='{"key": "value"}'
                rows="8"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30 font-mono text-sm
                       focus:bg-white/10
                       transition-all duration-300 resize-none"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
            </div>

            <!-- Raw Body 编辑器 -->
            <div v-if="config.bodyType === 'raw'">
              <textarea
                v-model="config.body"
                placeholder="原始请求体内容"
                rows="8"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30 font-mono text-sm
                       focus:bg-white/10
                       transition-all duration-300 resize-none"
                :class="stressMode.value ? 'focus:outline-none focus:border-orange-500/50' : 'focus:outline-none focus:border-sky-500/50'"
              />
            </div>

            <!-- None 提示 -->
            <div v-if="config.bodyType === 'none'" class="text-center text-white/40 py-8">
              无请求体
            </div>
          </div>
        </div>

        <!-- 压力测试配置卡片（仅在压力模式下显示） -->
        <Transition name="fade">
          <div
            v-if="stressMode.value"
            class="relative rounded-xl backdrop-blur-md border p-6"
            :class="[
              'bg-gradient-to-br from-orange-500/10 to-red-500/10',
              'border-orange-500/30'
            ]"
          >
            <h2 class="text-xl font-semibold text-white mb-6 flex items-center gap-2">
              <svg class="w-6 h-6 text-orange-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              压力参数
            </h2>

            <div class="space-y-4">
              <!-- 目标 QPS -->
              <div>
                <label class="block text-sm text-white/60 mb-2">目标 QPS（每秒请求数）</label>
                <input
                  v-model.number="stressConfig.targetQPS"
                  type="number"
                  min="1"
                  placeholder="1000"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 backdrop-blur-md
                         border border-orange-500/30
                         text-white placeholder-white/30
                         focus:outline-none focus:border-orange-500/50 focus:bg-white/10
                         transition-all duration-300"
                />
              </div>

              <!-- 并发线程数 -->
              <div>
                <label class="block text-sm text-white/60 mb-2">并发线程数</label>
                <input
                  v-model.number="stressConfig.threads"
                  type="number"
                  min="1"
                  placeholder="10"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 backdrop-blur-md
                         border border-orange-500/30
                         text-white placeholder-white/30
                         focus:outline-none focus:border-orange-500/50 focus:bg-white/10
                         transition-all duration-300"
                />
              </div>

              <!-- 持续时间 -->
              <div>
                <label class="block text-sm text-white/60 mb-2">持续时间（秒）</label>
                <input
                  v-model.number="stressConfig.duration"
                  type="number"
                  min="1"
                  placeholder="30"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 backdrop-blur-md
                         border border-orange-500/30
                         text-white placeholder-white/30
                         focus:outline-none focus:border-orange-500/50 focus:bg-white/10
                         transition-all duration-300"
                />
              </div>

              <!-- 预热时间 -->
              <div>
                <label class="block text-sm text-white/60 mb-2">预热时间（秒）</label>
                <input
                  v-model.number="stressConfig.warmupTime"
                  type="number"
                  min="0"
                  placeholder="5"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 backdrop-blur-md
                         border border-orange-500/30
                         text-white placeholder-white/30
                         focus:outline-none focus:border-orange-500/50 focus:bg-white/10
                         transition-all duration-300"
                />
              </div>
            </div>
          </div>
        </Transition>

        <!-- 按钮组 -->
        <div class="grid grid-cols-2 gap-4">
          <!-- 保存到请求库按钮 -->
          <button
            @click="showSaveModal = true"
            :disabled="!config.url"
            :class="[
              'relative px-6 py-4 rounded-xl font-semibold text-lg',
              'backdrop-blur-md border',
              'transition-all duration-300',
              'active:scale-95',
              !config.url ? 'opacity-50 cursor-not-allowed' : ''
            ]"
            :style="config.url ? getThemeStyle() : { backgroundColor: 'rgba(255, 255, 255, 0.05)', color: 'rgba(255, 255, 255, 0.6)', borderColor: 'rgba(255, 255, 255, 0.1)' }"
          >
            <svg class="w-5 h-5 inline-block mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
            </svg>
            添加到请求
          </button>

          <!-- 执行测试按钮 -->
          <button
            @click="executeTest"
            :disabled="isExecuting || !config.url"
            :class="[
              'relative px-6 py-4 rounded-xl font-semibold text-lg',
              'text-white shadow-2xl',
              'border border-white/20',
              'transition-all duration-300',
              'active:scale-95',
              getButtonClasses(),
              (isExecuting || !config.url) ? 'opacity-50 cursor-not-allowed' : ''
            ]"
          >
            <span v-if="!isExecuting">
              <svg class="w-6 h-6 inline-block mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              {{ stressMode.value ? '开始压测' : '执行测试' }}
            </span>
            <span v-else class="flex items-center justify-center gap-3">
              <div class="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              {{ stressMode.value ? '压测中...' : '执行中...' }}
            </span>

            <!-- 呼吸光晕层 -->
            <div
              v-if="!isExecuting && config.url"
              class="absolute inset-0 -z-10 rounded-xl opacity-60 animate-pulse-slow"
              :style="stressMode.value
                ? 'background: linear-gradient(90deg, #F97316 0%, #DC2626 50%, #F97316 100%); filter: blur(30px); transform: scale(1.2);'
                : 'background: linear-gradient(90deg, #0EA5E9 0%, #3B82F6 50%, #0EA5E9 100%); filter: blur(30px); transform: scale(1.2);'"
            />
          </button>
        </div>

      </div>

      <!-- 右侧：结果展示区域 -->
      <div class="space-y-6">

        <!-- 执行结果卡片 -->
        <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <h2 class="text-xl font-semibold text-white mb-6 flex items-center gap-2">
            <svg :class="['w-6 h-6', getIconColorClass()]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            执行结果
          </h2>

          <!-- 未执行状态 -->
          <div v-if="!result && !isExecuting" class="text-center py-20">
            <div class="text-6xl mb-4">📋</div>
            <div class="text-white/60">点击 "执行测试" 查看结果</div>
          </div>

          <!-- 执行中状态 -->
          <div v-if="isExecuting" class="text-center py-20">
            <div
              class="w-16 h-16 mx-auto mb-4 border-4 border-white/10 rounded-full animate-spin"
              :class="stressMode.value ? 'border-t-orange-500' : 'border-t-sky-500'"
            />
            <div class="text-white/60">正在执行测试...</div>
          </div>

          <!-- 结果展示 -->
          <div v-if="result && !isExecuting" class="space-y-6">

            <!-- 状态码和响应时间 -->
            <div class="grid grid-cols-2 gap-4">
              <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-4">
                <div class="text-sm text-white/60 mb-2">状态码</div>
                <div class="flex items-baseline gap-2">
                  <span
                    :class="[
                      'text-3xl font-bold',
                      result.status >= 200 && result.status < 300 ? 'text-green-400' :
                      result.status >= 400 ? 'text-red-400' : 'text-yellow-400'
                    ]"
                  >
                    {{ result.status }}
                  </span>
                  <span class="text-sm text-white/60">{{ result.statusText }}</span>
                </div>
              </div>

              <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-4">
                <div class="text-sm text-white/60 mb-2">响应时间</div>
                <div class="flex items-baseline gap-2">
                  <span
                    class="text-3xl font-bold"
                    :class="stressMode.value ? 'text-orange-500' : 'text-sky-500'"
                  >
                    {{ result.responseTime }}
                  </span>
                  <span class="text-lg text-yellow-300">ms</span>
                </div>
              </div>
            </div>

            <!-- 响应头 -->
            <div>
              <div class="text-sm text-white/60 mb-3 font-semibold">响应头</div>
              <div class="rounded-xl bg-white/5 border border-white/10 p-4 max-h-40 overflow-y-auto">
                <div
                  v-for="(value, key) in result.headers"
                  :key="key"
                  class="flex gap-3 mb-2 text-sm"
                >
                  <span
                    class="font-mono"
                    :class="stressMode.value ? 'text-orange-400' : 'text-sky-400'"
                  >{{ key }}:</span>
                  <span class="text-white/80 font-mono">{{ value }}</span>
                </div>
              </div>
            </div>

            <!-- 响应体 -->
            <div>
              <div class="text-sm text-white/60 mb-3 font-semibold">响应体</div>
              <div class="rounded-xl bg-white/5 border border-white/10 p-4 max-h-96 overflow-y-auto">
                <pre class="text-white/80 font-mono text-sm whitespace-pre-wrap">{{ formatResponseBody(result.body) }}</pre>
              </div>
            </div>

          </div>
        </div>

      </div>

    </div>

    <!-- 保存请求模态框 -->
    <SaveRequestModal
      :is-open="showSaveModal"
      :config="config"
      @close="showSaveModal = false"
      @save="handleSaveRequest"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import SaveRequestModal from '../../components/common/SaveRequestModal.vue'
import { createRequest } from '../../stores/requestStore'
import { getStressMode, toggleStressMode as toggleGlobalStressMode } from '../../stores/themeStore'
import type { HttpMethod, BodyType, Header, TestConfig, TestResult } from '../../types'

// ==================== Props & Emits ====================

interface Props {
  pendingConfig?: TestConfig | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  configLoaded: []
}>()

// ==================== 状态管理 ====================

const httpMethods: HttpMethod[] = ['GET', 'POST', 'PUT', 'DELETE']

const bodyTypes = [
  { label: 'None', value: 'none' as BodyType },
  { label: 'JSON', value: 'json' as BodyType },
  { label: 'Raw', value: 'raw' as BodyType }
]

const config = reactive<TestConfig>({
  method: 'GET',
  url: '',
  timeout: 5000,
  headers: [],
  bodyType: 'none',
  body: ''
})

const isExecuting = ref(false)
const result = ref<TestResult | null>(null)
const showSaveModal = ref(false)

// 使用全局压力测试模式
const stressMode = getStressMode()

// 压力测试配置
const stressConfig = reactive({
  targetQPS: 1000,
  threads: 10,
  duration: 30,
  warmupTime: 5
})

// 动态主题色
const themeColor = computed(() => stressMode.value ? 'orange' : 'sky')

// ==================== 监听器 ====================

// 监听待加载的配置
watch(() => props.pendingConfig, (newConfig) => {
  if (newConfig) {
    // 加载配置到表单
    config.method = newConfig.method
    config.url = newConfig.url
    config.timeout = newConfig.timeout
    config.headers = JSON.parse(JSON.stringify(newConfig.headers))
    config.bodyType = newConfig.bodyType
    config.body = newConfig.body

    console.log('✅ 已加载请求配置', newConfig)
    emit('configLoaded')
  }
}, { immediate: true })

// ==================== 方法 ====================

const toggleStressMode = () => {
  toggleGlobalStressMode()
}

// 获取主题样式（内联样式）
const getThemeStyle = () => {
  const colors = stressMode.value
    ? {
        bg: 'rgba(249, 115, 22, 0.2)',      // orange-500/20
        text: 'rgb(251, 146, 60)',          // orange-400
        border: 'rgba(249, 115, 22, 0.5)'   // orange-500/50
      }
    : {
        bg: 'rgba(14, 165, 233, 0.2)',      // sky-500/20
        text: 'rgb(56, 189, 248)',          // sky-400
        border: 'rgba(14, 165, 233, 0.5)'   // sky-500/50
      }

  return {
    backgroundColor: colors.bg,
    color: colors.text,
    borderColor: colors.border
  }
}

// 获取图标颜色类
const getIconColorClass = () => stressMode.value ? 'text-orange-400' : 'text-sky-400'

// 获取按钮类（用于执行按钮）
const getButtonClasses = () => {
  return stressMode.value
    ? 'bg-gradient-to-r from-orange-500 to-red-500 hover:from-orange-600 hover:to-red-600'
    : 'bg-gradient-to-r from-sky-500 to-blue-600 hover:from-sky-600 hover:to-blue-700'
}

const addHeader = () => {
  config.headers.push({ key: '', value: '' })
}

const removeHeader = (index: number) => {
  config.headers.splice(index, 1)
}

const executeTest = async () => {
  if (!config.url || isExecuting.value) return

  isExecuting.value = true
  result.value = null

  try {
    const startTime = performance.now()

    // 构建请求头
    const headers: Record<string, string> = {}
    config.headers.forEach(h => {
      if (h.key && h.value) {
        headers[h.key] = h.value
      }
    })

    // 构建请求配置
    const requestConfig: RequestInit = {
      method: config.method,
      headers,
      signal: AbortSignal.timeout(config.timeout)
    }

    // 添加请求体
    if (config.bodyType !== 'none' && config.body && (config.method === 'POST' || config.method === 'PUT')) {
      if (config.bodyType === 'json') {
        requestConfig.body = config.body
        headers['Content-Type'] = 'application/json'
      } else {
        requestConfig.body = config.body
      }
    }

    // 发送请求
    const response = await fetch(config.url, requestConfig)

    const endTime = performance.now()
    const responseTime = Math.round(endTime - startTime)

    // 解析响应头
    const responseHeaders: Record<string, string> = {}
    response.headers.forEach((value, key) => {
      responseHeaders[key] = value
    })

    // 解析响应体
    const contentType = response.headers.get('content-type')
    let responseBody: any

    if (contentType?.includes('application/json')) {
      responseBody = await response.json()
    } else {
      responseBody = await response.text()
    }

    // 设置结果
    result.value = {
      status: response.status,
      statusText: response.statusText,
      responseTime,
      headers: responseHeaders,
      body: responseBody
    }

    console.log('✅ 接口测试完成', result.value)

  } catch (error: any) {
    console.error('❌ 接口测试失败', error)

    result.value = {
      status: 0,
      statusText: 'Error',
      responseTime: 0,
      headers: {},
      body: {
        error: error.message || '请求失败'
      }
    }
  } finally {
    isExecuting.value = false
  }
}

const formatResponseBody = (body: any): string => {
  if (typeof body === 'string') {
    return body
  }
  return JSON.stringify(body, null, 2)
}

const handleSaveRequest = (fullPath: string) => {
  // 解析路径
  const lastSlashIndex = fullPath.lastIndexOf('/')
  const parentPath = fullPath.substring(0, lastSlashIndex) || '/'
  const requestName = fullPath.substring(lastSlashIndex + 1)

  // 创建请求的配置副本
  const configCopy: TestConfig = {
    method: config.method,
    url: config.url,
    timeout: config.timeout,
    headers: JSON.parse(JSON.stringify(config.headers)),
    bodyType: config.bodyType,
    body: config.body
  }

  // 保存到请求树
  const saved = createRequest(parentPath, requestName, configCopy)

  if (saved) {
    console.log('✅ 请求已保存', saved)
    // 可以添加成功提示
  } else {
    console.error('❌ 保存请求失败')
    // 可以添加错误提示
  }
}
</script>

<style scoped>
/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: all 0.3s ease;
}

.fade-enter-from {
  opacity: 0;
  transform: translateY(-10px);
}

.fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

/* GPU 加速优化 */
.relative,
.absolute {
  will-change: transform;
}

/* 自定义滚动条 */
textarea::-webkit-scrollbar,
pre::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

textarea::-webkit-scrollbar-track,
pre::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
}

textarea::-webkit-scrollbar-thumb,
pre::-webkit-scrollbar-thumb {
  background: rgba(14, 165, 233, 0.3);
  border-radius: 3px;
}

textarea::-webkit-scrollbar-thumb:hover,
pre::-webkit-scrollbar-thumb:hover {
  background: rgba(14, 165, 233, 0.5);
}
</style>
