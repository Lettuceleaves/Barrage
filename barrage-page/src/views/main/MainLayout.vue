<!--
  Barrage 火力网压测引擎 - 主界面布局
-->

<template>
  <div class="relative w-full h-full overflow-hidden bg-zinc-950">
    <!-- ==================== 背景层 ==================== -->

    <!-- 噪点纹理 -->
    <div
      class="absolute inset-0 opacity-[0.015] pointer-events-none"
      style="background-image: url('data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iMzAwIj48ZmlsdGVyIGlkPSJhIiB4PSIwIiB5PSIwIj48ZmVUdXJidWxlbmNlIGJhc2VGcmVxdWVuY3k9Ii43NSIgc3RpdGNoVGlsZXM9InN0aXRjaCIgdHlwZT0iZnJhY3RhbE5vaXNlIi8+PGZlQ29sb3JNYXRyaXggdHlwZT0ic2F0dXJhdGUiIHZhbHVlcz0iMCIvPjwvZmlsdGVyPjxwYXRoIGQ9Ik0wIDBoMzAwdjMwMEgweiIgZmlsdGVyPSJ1cmwoI2EpIiBvcGFjaXR5PSIuMDUiLz48L3N2Zz4=')"
    />

    <!-- 径向渐变 -->
    <div
      class="absolute inset-0 pointer-events-none"
      style="background: radial-gradient(ellipse at center, rgba(24, 24, 27, 0.6) 0%, rgba(9, 9, 11, 0.95) 70%, rgba(0, 0, 0, 1) 100%)"
    />

    <!-- 极光光晕 - 左上角 -->
    <div
      class="absolute -top-40 -left-40 w-[600px] h-[600px] rounded-full opacity-20 pointer-events-none transition-all duration-1000"
      :style="stressMode.value
        ? 'background: radial-gradient(circle, #F97316 0%, transparent 70%); filter: blur(200px);'
        : 'background: radial-gradient(circle, #0EA5E9 0%, transparent 70%); filter: blur(200px);'"
    />

    <!-- 极光光晕 - 右下角 -->
    <div
      class="absolute -bottom-40 -right-40 w-[600px] h-[600px] rounded-full opacity-15 pointer-events-none transition-all duration-1000"
      :style="stressMode.value
        ? 'background: radial-gradient(circle, #DC2626 0%, transparent 70%); filter: blur(200px);'
        : 'background: radial-gradient(circle, #FCD34D 0%, transparent 70%); filter: blur(200px);'"
    />

    <!-- ==================== 主内容层 ==================== -->
    <div class="relative z-10 flex w-full h-full">

      <!-- 左侧导航栏 -->
      <aside class="w-64 h-full border-r border-white/10 bg-white/5 backdrop-blur-md flex flex-col">

        <!-- Logo 区域 -->
        <div class="p-6 border-b border-white/10">
          <div class="flex items-center gap-3">
            <div
              class="w-10 h-10 rounded-lg flex items-center justify-center transition-all duration-500"
              :class="stressMode.value ? 'bg-gradient-to-br from-orange-500 to-red-600' : 'bg-gradient-to-br from-sky-500 to-blue-600'"
            >
              <svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <div>
              <div class="text-white font-bold text-lg">BARRAGE</div>
              <div class="text-white/40 text-xs">火力网压测引擎</div>
            </div>
          </div>
        </div>

        <!-- 导航菜单 -->
        <nav class="flex-1 p-4 overflow-y-auto">

          <!-- 测试菜单组 -->
          <div class="mb-6">
            <div class="text-white/40 text-xs font-semibold mb-3 px-3">测试</div>

            <button
              @click="currentPage = 'api-test'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'api-test'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
              <span class="font-medium">接口测试</span>
              <div
                v-if="currentPage === 'api-test'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>

            <button
              @click="currentPage = 'simulation-test'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'simulation-test'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
              </svg>
              <span class="font-medium">模拟测试</span>
              <div
                v-if="currentPage === 'simulation-test'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>
          </div>

          <!-- 其他菜单项 -->
          <div>
            <div class="text-white/40 text-xs font-semibold mb-3 px-3">管理</div>

            <button
              @click="currentPage = 'results'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'results'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              <span class="font-medium">结果</span>
              <div
                v-if="currentPage === 'results'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>

            <button
              @click="currentPage = 'requests'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'requests'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              <span class="font-medium">请求</span>
              <div
                v-if="currentPage === 'requests'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>

            <button
              @click="currentPage = 'users'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'users'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
              </svg>
              <span class="font-medium">用户</span>
              <div
                v-if="currentPage === 'users'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>

            <button
              @click="currentPage = 'settings'"
              :class="[
                'w-full text-left px-4 py-3 rounded-lg mb-1 transition-all duration-300',
                'flex items-center gap-3',
                currentPage === 'settings'
                  ? (stressMode.value ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-sky-500/20 text-sky-400 border border-sky-500/30')
                  : 'text-white/60 hover:bg-white/5 hover:text-white border border-transparent'
              ]"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              <span class="font-medium">设置</span>
              <div
                v-if="currentPage === 'settings'"
                class="ml-auto w-1.5 h-1.5 rounded-full animate-pulse"
                :class="stressMode.value ? 'bg-orange-400' : 'bg-sky-400'"
              />
            </button>
          </div>
        </nav>

        <!-- 底部用户信息 -->
        <div class="p-4 border-t border-white/10">
          <div class="flex items-center gap-3 px-3 py-2 rounded-lg bg-white/5">
            <div
              class="w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-bold transition-all duration-500"
              :class="stressMode.value ? 'bg-gradient-to-br from-orange-500 to-red-600' : 'bg-gradient-to-br from-sky-500 to-blue-600'"
            >
              U
            </div>
            <div class="flex-1">
              <div class="text-white text-sm font-medium">测试用户</div>
              <div class="text-white/40 text-xs">test@barrage.io</div>
            </div>
          </div>
        </div>
      </aside>

      <!-- 主内容区域 -->
      <main class="flex-1 h-full overflow-auto">
        <ApiTest
          v-if="currentPage === 'api-test'"
          :pending-config="pendingRequestConfig"
          @config-loaded="handleConfigLoaded"
        />
        <RequestsPage
          v-else-if="currentPage === 'requests'"
          @load-request="handleLoadRequest"
          @new-request="handleNewRequest"
        />
        <SimulateTestPage
          v-else-if="currentPage === 'simulation-test'"
        />
        <SettingsPage
          v-else-if="currentPage === 'settings'"
        />
        <component v-else :is="currentPageComponent" />
      </main>

    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import ApiTest from '../test/ApiTest.vue'
import RequestsPage from './RequestsPage.vue'
import SimulateTestPage from '../test/SimulateTestPage.vue'
import SettingsPage from './SettingsPage.vue'
import type { PageName } from '../../types'
import { getStressMode } from '../../stores/themeStore'

// ==================== 状态管理 ====================

const currentPage = ref<PageName>('api-test')
const pendingRequestConfig = ref<any>(null)
const stressMode = getStressMode()

// 占位组件
const PlaceholderPage = {
  template: `
    <div class="flex items-center justify-center w-full h-full">
      <div class="text-center">
        <div class="text-6xl mb-4">🚧</div>
        <div class="text-2xl text-white/80 mb-2">页面开发中</div>
        <div class="text-white/40">该功能正在开发中，敬请期待</div>
      </div>
    </div>
  `
}

// ==================== 方法 ====================

const handleLoadRequest = (config: any) => {
  pendingRequestConfig.value = config
  currentPage.value = 'api-test'
  console.log('✅ 加载请求到接口测试', config)
}

const handleNewRequest = () => {
  pendingRequestConfig.value = null
  currentPage.value = 'api-test'
  console.log('✅ 跳转到接口测试页面创建新请求')
}

const handleConfigLoaded = () => {
  pendingRequestConfig.value = null
}

// ==================== 计算属性 ====================

const currentPageComponent = computed(() => {
  const pageMap: Record<PageName, any> = {
    'api-test': ApiTest,
    'simulation-test': SimulateTestPage,
    'results': PlaceholderPage,
    'requests': RequestsPage,
    'users': PlaceholderPage,
    'settings': SettingsPage
  }

  return pageMap[currentPage.value] || PlaceholderPage
})
</script>

<style scoped>
.relative,
.absolute {
  will-change: transform;
}

/* 自定义滚动条 */
aside::-webkit-scrollbar,
main::-webkit-scrollbar {
  width: 6px;
}

aside::-webkit-scrollbar-track,
main::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
}

aside::-webkit-scrollbar-thumb,
main::-webkit-scrollbar-thumb {
  background: rgba(14, 165, 233, 0.3);
  border-radius: 3px;
}

aside::-webkit-scrollbar-thumb:hover,
main::-webkit-scrollbar-thumb:hover {
  background: rgba(14, 165, 233, 0.5);
}
</style>
