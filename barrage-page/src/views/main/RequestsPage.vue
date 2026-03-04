<!--
  Barrage 火力网压测引擎 - 请求管理页面
-->

<template>
  <div class="w-full h-full p-8 overflow-y-auto">

    <!-- 页面标题 -->
    <div class="mb-8 flex items-center justify-between">
      <div>
        <h1 class="text-4xl font-bold text-white mb-2">请求管理</h1>
        <p class="text-white/60">管理和组织你的 API 请求库</p>
      </div>
      <div class="flex gap-3">
        <button
          @click="syncToServer"
          :disabled="syncState === 'syncing'"
          :class="[
            'px-4 py-2 rounded-xl font-medium transition-all duration-300 flex items-center gap-2',
            syncState === 'success'
              ? 'bg-green-500/20 border border-green-500/30 text-green-400'
              : syncState === 'error'
                ? 'bg-red-500/20 border border-red-500/30 text-red-400'
                : 'bg-white/5 border border-white/10 text-sky-400 hover:bg-white/10 hover:border-sky-500/50',
            syncState === 'syncing' ? 'opacity-50 cursor-not-allowed' : ''
          ]"
        >
          <svg v-if="syncState === 'syncing'" class="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
          </svg>
          {{ syncState === 'syncing' ? '同步中...' : syncState === 'success' ? '同步成功' : syncState === 'error' ? '同步失败' : '同步到服务器' }}
        </button>
        <button
          @click="showNewFolderModal = true"
          class="px-4 py-2 rounded-xl font-medium
                 bg-white/5 border border-white/10
                 text-sky-400
                 hover:bg-white/10 hover:border-sky-500/50
                 transition-all duration-300
                 flex items-center gap-2"
        >
          <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
            <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
          </svg>
          新建文件夹
        </button>
        <button
          @click="createNewRequest"
          class="px-4 py-2 rounded-xl font-medium
                 bg-gradient-to-r from-sky-500 to-blue-600
                 border border-white/20
                 text-white
                 hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
                 transition-all duration-300
                 flex items-center gap-2"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          新建请求
        </button>
      </div>
    </div>

    <div class="grid grid-cols-3 gap-6">

      <!-- 左侧：文件树 -->
      <div class="col-span-1">
        <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <h2 class="text-xl font-semibold text-white mb-6 flex items-center gap-2">
            <svg class="w-6 h-6 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
            </svg>
            文件树
          </h2>

          <div class="space-y-1">
            <FullTreeNode
              :node="rootNode"
              :selected-id="selectedNodeId"
              :expanded-ids="expandedIds"
              @select="handleSelectNode"
              @toggle="handleToggleNode"
              @context-menu="handleContextMenu"
            />
          </div>
        </div>
      </div>

      <!-- 右侧：详情展示 -->
      <div class="col-span-2">
        <!-- 未选择状态 -->
        <div v-if="!selectedNode" class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-20 text-center">
          <div class="text-6xl mb-4">📁</div>
          <div class="text-white/60 text-lg">选择一个请求查看详情</div>
        </div>

        <!-- 文件夹详情 -->
        <div v-else-if="selectedNode.type === 'folder'" class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
          <h2 class="text-2xl font-semibold text-white mb-6 flex items-center gap-2">
            <svg class="w-8 h-8 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
              <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
            </svg>
            {{ selectedNode.name }}
          </h2>

          <div class="space-y-4">
            <div class="rounded-xl bg-white/5 border border-white/10 p-4">
              <div class="text-sm text-white/60 mb-2">路径</div>
              <div class="text-white font-mono">{{ selectedNode.path }}</div>
            </div>

            <div class="rounded-xl bg-white/5 border border-white/10 p-4">
              <div class="text-sm text-white/60 mb-2">包含项</div>
              <div class="text-3xl font-bold text-sky-500">
                {{ selectedNode.children?.length || 0 }}
              </div>
            </div>

            <div class="flex gap-3">
              <button
                @click="handleRename(selectedNode)"
                class="flex-1 px-4 py-3 rounded-xl
                       bg-white/5 border border-white/10
                       text-sky-400 font-medium
                       hover:bg-white/10 hover:border-sky-500/50
                       transition-all duration-300"
              >
                重命名
              </button>
              <button
                v-if="selectedNode.id !== 'root'"
                @click="handleDelete(selectedNode)"
                class="flex-1 px-4 py-3 rounded-xl
                       bg-red-500/10 border border-red-500/30
                       text-red-400 font-medium
                       hover:bg-red-500/20 hover:border-red-500/50
                       transition-all duration-300"
              >
                删除
              </button>
            </div>
          </div>
        </div>

        <!-- 请求详情 -->
        <div v-else-if="selectedNode.type === 'request' && selectedNode.config" class="space-y-6">
          <!-- 基本信息卡片 -->
          <div class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
            <h2 class="text-2xl font-semibold text-white mb-6 flex items-center gap-2">
              <svg class="w-8 h-8 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
              {{ selectedNode.name }}
            </h2>

            <div class="space-y-4">
              <div class="rounded-xl bg-white/5 border border-white/10 p-4">
                <div class="text-sm text-white/60 mb-2">路径</div>
                <div class="text-white font-mono">{{ selectedNode.path }}</div>
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div class="rounded-xl bg-white/5 border border-white/10 p-4">
                  <div class="text-sm text-white/60 mb-2">请求方法</div>
                  <div
                    :class="[
                      'inline-block px-3 py-1 rounded-lg font-bold',
                      selectedNode.config.method === 'GET' ? 'bg-green-500/20 text-green-400' :
                      selectedNode.config.method === 'POST' ? 'bg-blue-500/20 text-blue-400' :
                      selectedNode.config.method === 'PUT' ? 'bg-yellow-500/20 text-yellow-400' :
                      'bg-red-500/20 text-red-400'
                    ]"
                  >
                    {{ selectedNode.config.method }}
                  </div>
                </div>

                <div class="rounded-xl bg-white/5 border border-white/10 p-4">
                  <div class="text-sm text-white/60 mb-2">超时时间</div>
                  <div class="text-white font-semibold">{{ selectedNode.config.timeout }} ms</div>
                </div>
              </div>

              <div class="rounded-xl bg-white/5 border border-white/10 p-4">
                <div class="text-sm text-white/60 mb-2">URL</div>
                <div class="text-sky-400 font-mono break-all">{{ selectedNode.config.url }}</div>
              </div>
            </div>
          </div>

          <!-- Headers -->
          <div v-if="selectedNode.config.headers.length > 0" class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
            <h3 class="text-lg font-semibold text-white mb-4">请求头</h3>
            <div class="space-y-2">
              <div
                v-for="(header, index) in selectedNode.config.headers"
                :key="index"
                class="flex gap-3 text-sm"
              >
                <span class="text-sky-400 font-mono">{{ header.key }}:</span>
                <span class="text-white/80 font-mono">{{ header.value }}</span>
              </div>
            </div>
          </div>

          <!-- Body -->
          <div v-if="selectedNode.config.bodyType !== 'none'" class="relative rounded-xl bg-white/5 backdrop-blur-md border border-white/10 p-6">
            <h3 class="text-lg font-semibold text-white mb-4">请求体 ({{ selectedNode.config.bodyType.toUpperCase() }})</h3>
            <div class="rounded-xl bg-white/5 border border-white/10 p-4 max-h-64 overflow-y-auto">
              <pre class="text-white/80 font-mono text-sm whitespace-pre-wrap">{{ selectedNode.config.body }}</pre>
            </div>
          </div>

          <!-- 操作按钮 -->
          <div class="flex gap-3">
            <button
              @click="loadRequest(selectedNode)"
              class="flex-1 px-6 py-3 rounded-xl font-semibold
                     bg-gradient-to-r from-sky-500 to-blue-600
                     border border-white/20
                     text-white
                     hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
                     transition-all duration-300"
            >
              加载到接口测试
            </button>
            <button
              @click="handleRename(selectedNode)"
              class="px-6 py-3 rounded-xl font-medium
                     bg-white/5 border border-white/10
                     text-sky-400
                     hover:bg-white/10 hover:border-sky-500/50
                     transition-all duration-300"
            >
              重命名
            </button>
            <button
              @click="handleDelete(selectedNode)"
              class="px-6 py-3 rounded-xl font-medium
                     bg-red-500/10 border border-red-500/30
                     text-red-400
                     hover:bg-red-500/20 hover:border-red-500/50
                     transition-all duration-300"
            >
              删除
            </button>
          </div>
        </div>
      </div>

    </div>

    <!-- 新建文件夹模态框 -->
    <Transition name="fade">
      <div
        v-if="showNewFolderModal"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click="showNewFolderModal = false"
      >
        <div
          class="relative w-full max-w-md mx-4 p-8 rounded-2xl
                 bg-zinc-900/90 backdrop-blur-xl
                 border border-white/10
                 shadow-2xl shadow-sky-500/20"
          @click.stop
        >
          <h2 class="text-2xl font-bold text-white mb-6">新建文件夹</h2>

          <div class="space-y-4">
            <div>
              <label class="block text-sm text-white/60 mb-2">文件夹名称</label>
              <input
                v-model="newFolderName"
                type="text"
                placeholder="输入文件夹名称"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30
                       focus:outline-none focus:border-sky-500/50 focus:bg-white/10
                       transition-all duration-300"
                @keyup.enter="confirmNewFolder"
              />
            </div>

            <div>
              <label class="block text-sm text-white/60 mb-2">创建位置</label>
              <div class="text-white font-mono">{{ selectedNode?.path || '/' }}</div>
            </div>
          </div>

          <div class="flex justify-end gap-3 mt-8">
            <button
              class="px-6 py-2 rounded-xl
                     bg-white/5 border border-white/10
                     text-white/80
                     hover:bg-white/10
                     transition-all duration-300"
              @click="showNewFolderModal = false"
            >
              取消
            </button>
            <button
              :disabled="!newFolderName.trim()"
              class="px-6 py-2 rounded-xl
                     bg-gradient-to-r from-sky-500 to-blue-600
                     border border-white/20
                     text-white font-semibold
                     hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
                     disabled:opacity-50 disabled:cursor-not-allowed
                     transition-all duration-300"
              @click="confirmNewFolder"
            >
              创建
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 重命名模态框 -->
    <Transition name="fade">
      <div
        v-if="showRenameModal && renameTarget"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click="showRenameModal = false"
      >
        <div
          class="relative w-full max-w-md mx-4 p-8 rounded-2xl
                 bg-zinc-900/90 backdrop-blur-xl
                 border border-white/10
                 shadow-2xl shadow-sky-500/20"
          @click.stop
        >
          <h2 class="text-2xl font-bold text-white mb-6">重命名</h2>

          <div class="space-y-4">
            <div>
              <label class="block text-sm text-white/60 mb-2">新名称</label>
              <input
                v-model="renameName"
                type="text"
                placeholder="输入新名称"
                class="w-full px-4 py-3 rounded-xl
                       bg-white/5 backdrop-blur-md
                       border border-white/10
                       text-white placeholder-white/30
                       focus:outline-none focus:border-sky-500/50 focus:bg-white/10
                       transition-all duration-300"
                @keyup.enter="confirmRename"
              />
            </div>

            <div>
              <label class="block text-sm text-white/60 mb-2">原名称</label>
              <div class="text-white/60">{{ renameTarget.name }}</div>
            </div>
          </div>

          <div class="flex justify-end gap-3 mt-8">
            <button
              class="px-6 py-2 rounded-xl
                     bg-white/5 border border-white/10
                     text-white/80
                     hover:bg-white/10
                     transition-all duration-300"
              @click="showRenameModal = false"
            >
              取消
            </button>
            <button
              :disabled="!renameName.trim()"
              class="px-6 py-2 rounded-xl
                     bg-gradient-to-r from-sky-500 to-blue-600
                     border border-white/20
                     text-white font-semibold
                     hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
                     disabled:opacity-50 disabled:cursor-not-allowed
                     transition-all duration-300"
              @click="confirmRename"
            >
              确认
            </button>
          </div>
        </div>
      </div>
    </Transition>

  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getRootNode, createFolder, renameNode, deleteNode, type TreeNode } from '../../stores/requestStore'
import FullTreeNode from '../../components/tree/FullTreeNode.vue'

// ==================== Props & Emits ====================

const emit = defineEmits<{
  loadRequest: [config: any]
  newRequest: []
}>()

// ==================== 状态管理 ====================

const rootNode = getRootNode()
const selectedNodeId = ref<string | null>(null)
const expandedIds = ref<Set<string>>(new Set(['root']))

const showNewFolderModal = ref(false)
const newFolderName = ref('')

const showRenameModal = ref(false)
const renameTarget = ref<TreeNode | null>(null)
const renameName = ref('')

// 同步状态
type SyncState = 'idle' | 'syncing' | 'success' | 'error'
const syncState = ref<SyncState>('idle')

// ==================== 计算属性 ====================

const selectedNode = computed(() => {
  if (!selectedNodeId.value) return null
  return findNodeById(selectedNodeId.value, rootNode)
})

// ==================== 方法 ====================

/**
 * 从 URL 中提取请求路径
 */
function extractPath(url: string): string {
  if (!url) return '/'
  try {
    const full = url.startsWith('http') ? url : `http://placeholder${url}`
    return new URL(full).pathname
  } catch {
    return url.startsWith('/') ? url : '/' + url
  }
}

/**
 * 同步所有请求到服务器 (http.toml)
 */
async function syncToServer() {
  syncState.value = 'syncing'

  const templates: { name: string; method: string; path: string; body: string; headers: string }[] = []
  const traverse = (node: TreeNode) => {
    if (node.type === 'request' && node.config) {
      const cfg = node.config
      templates.push({
        name:    node.path,
        method:  cfg.method || 'GET',
        path:    extractPath(cfg.url),
        body:    cfg.body || '',
        headers: (cfg.headers || [])
          .filter((h: any) => h.key)
          .map((h: any) => `${h.key}:${h.value}`)
          .join(',')
      })
    }
    if (node.children) node.children.forEach(traverse)
  }
  traverse(rootNode)

  try {
    const res = await fetch('/api/config/templates/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(templates)
    })
    if (!res.ok) throw new Error(await res.text())

    syncState.value = 'success'
    setTimeout(() => { syncState.value = 'idle' }, 2000)
  } catch (err: any) {
    syncState.value = 'error'
    setTimeout(() => { syncState.value = 'idle' }, 3000)
    console.error('sync failed:', err)
  }
}

const findNodeById = (id: string, node: TreeNode): TreeNode | null => {
  if (node.id === id) return node
  if (node.children) {
    for (const child of node.children) {
      const found = findNodeById(id, child)
      if (found) return found
    }
  }
  return null
}

const handleSelectNode = (id: string) => {
  selectedNodeId.value = id
}

const handleToggleNode = (id: string) => {
  if (expandedIds.value.has(id)) {
    expandedIds.value.delete(id)
  } else {
    expandedIds.value.add(id)
  }
}

const handleContextMenu = (event: MouseEvent, node: TreeNode) => {
  event.preventDefault()
  // 可以在这里实现右键菜单
  console.log('右键菜单', node)
}

const handleRename = (node: TreeNode) => {
  renameTarget.value = node
  renameName.value = node.name
  showRenameModal.value = true
}

const confirmRename = () => {
  if (!renameTarget.value || !renameName.value.trim()) return

  const success = renameNode(renameTarget.value.id, renameName.value)
  if (success) {
    console.log('✅ 重命名成功')
    showRenameModal.value = false
    renameTarget.value = null
    renameName.value = ''
  }
}

const handleDelete = (node: TreeNode) => {
  if (node.id === 'root') {
    console.error('❌ 不能删除根节点')
    return
  }

  if (confirm(`确定要删除 "${node.name}" 吗？${node.type === 'folder' ? '这将同时删除文件夹内的所有内容。' : ''}`)) {
    const success = deleteNode(node.id)
    if (success) {
      console.log('✅ 删除成功')
      if (selectedNodeId.value === node.id) {
        selectedNodeId.value = null
      }
    }
  }
}

const confirmNewFolder = () => {
  if (!newFolderName.value.trim()) return

  const parentPath = selectedNode.value?.type === 'folder' ? selectedNode.value.path : '/'
  const newFolder = createFolder(parentPath, newFolderName.value)

  if (newFolder) {
    console.log('✅ 创建文件夹成功', newFolder)
    expandedIds.value.add(parentPath)
    expandedIds.value.add(newFolder.path)
    selectedNodeId.value = newFolder.id
    showNewFolderModal.value = false
    newFolderName.value = ''
  }
}

const createNewRequest = () => {
  emit('newRequest')
}

const loadRequest = (node: TreeNode) => {
  if (node.type === 'request' && node.config) {
    emit('loadRequest', node.config)
  }
}
</script>


<style scoped>
/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* GPU 加速优化 */
.relative,
.absolute {
  will-change: transform;
}

/* 自定义滚动条 */
div::-webkit-scrollbar,
pre::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

div::-webkit-scrollbar-track,
pre::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
}

div::-webkit-scrollbar-thumb,
pre::-webkit-scrollbar-thumb {
  background: rgba(14, 165, 233, 0.3);
  border-radius: 3px;
}

div::-webkit-scrollbar-thumb:hover,
pre::-webkit-scrollbar-thumb:hover {
  background: rgba(14, 165, 233, 0.5);
}
</style>
