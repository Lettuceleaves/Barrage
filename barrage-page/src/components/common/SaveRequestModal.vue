<!--
  Barrage 火力网压测引擎 - 保存请求模态框
-->

<template>
  <Transition name="fade">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
      @click="close"
    >
      <!-- 模态框内容 -->
      <div
        class="relative w-full max-w-2xl mx-4 p-8 rounded-2xl
               bg-zinc-900/90 backdrop-blur-xl
               border border-white/10
               shadow-2xl shadow-sky-500/20"
        @click.stop
      >
        <!-- 关闭按钮 -->
        <button
          class="absolute top-4 right-4 w-8 h-8 flex items-center justify-center
                 text-white/60 hover:text-white
                 hover:bg-white/10 rounded-lg
                 transition-all duration-200"
          @click="close"
        >
          ✕
        </button>

        <!-- 标题 -->
        <h2 class="text-2xl font-bold text-white mb-6">
          保存到请求库
        </h2>

        <!-- 内容 -->
        <div class="space-y-6">

          <!-- 请求名称输入 -->
          <div>
            <label class="block text-sm text-white/60 mb-2">请求名称</label>
            <input
              v-model="requestName"
              type="text"
              placeholder="输入请求名称（不能以 / 开头）"
              class="w-full px-4 py-3 rounded-xl
                     bg-white/5 backdrop-blur-md
                     border border-white/10
                     text-white placeholder-white/30
                     focus:outline-none focus:border-sky-500/50 focus:bg-white/10
                     transition-all duration-300"
            />
          </div>

          <!-- 文件夹选择 -->
          <div>
            <div class="flex items-center justify-between mb-2">
              <label class="block text-sm text-white/60">选择文件夹</label>
              <button
                @click="showNewFolderInput = true"
                class="px-3 py-1 rounded-lg text-xs font-medium
                       bg-sky-500/20 text-sky-400 border border-sky-500/30
                       hover:bg-sky-500/30 hover:border-sky-500/50
                       transition-all duration-300"
              >
                + 新建文件夹
              </button>
            </div>

            <!-- 文件树 -->
            <div class="rounded-xl bg-white/5 border border-white/10 p-4 max-h-80 overflow-y-auto">
              <FolderTreeNode
                :node="rootNode"
                :selected-path="selectedFolderPath"
                :expanded-paths="expandedPaths"
                :level="0"
                @select="handleSelectFolder"
                @toggle="handleToggleFolder"
              />
            </div>
          </div>

          <!-- 新建文件夹输入 -->
          <Transition name="slide-down">
            <div v-if="showNewFolderInput" class="rounded-xl bg-sky-500/10 border border-sky-500/30 p-4">
              <label class="block text-sm text-sky-400 mb-2">新文件夹名称</label>
              <div class="flex gap-2">
                <input
                  v-model="newFolderName"
                  type="text"
                  placeholder="输入文件夹名称（不能以 / 开头）"
                  class="flex-1 px-4 py-2 rounded-lg
                         bg-white/5 backdrop-blur-md
                         border border-white/10
                         text-white placeholder-white/30
                         focus:outline-none focus:border-sky-500/50
                         transition-all duration-300"
                  @keyup.enter="createNewFolder"
                />
                <button
                  @click="createNewFolder"
                  :disabled="!newFolderName.trim()"
                  class="px-4 py-2 rounded-lg font-medium
                         bg-sky-500/20 text-sky-400 border border-sky-500/30
                         hover:bg-sky-500/30 hover:border-sky-500/50
                         disabled:opacity-50 disabled:cursor-not-allowed
                         transition-all duration-300"
                >
                  创建
                </button>
                <button
                  @click="showNewFolderInput = false; newFolderName = ''"
                  class="px-4 py-2 rounded-lg
                         bg-white/5 border border-white/10 text-white/60
                         hover:bg-white/10
                         transition-all duration-300"
                >
                  取消
                </button>
              </div>
            </div>
          </Transition>

          <!-- 当前选择路径显示 -->
          <div class="rounded-xl bg-white/5 border border-white/10 p-4">
            <div class="text-sm text-white/60 mb-1">保存路径预览</div>
            <div class="text-white font-mono">
              {{ selectedFolderPath }}<span class="text-sky-400">/{{ requestName || '未命名' }}</span>
            </div>
          </div>

        </div>

        <!-- 按钮组 -->
        <div class="flex justify-end gap-3 mt-8">
          <button
            class="px-6 py-2 rounded-xl
                   bg-white/5 border border-white/10
                   text-white/80
                   hover:bg-white/10
                   transition-all duration-300"
            @click="close"
          >
            取消
          </button>
          <button
            :disabled="!requestName.trim()"
            class="px-6 py-2 rounded-xl
                   bg-gradient-to-r from-sky-500 to-blue-600
                   border border-white/20
                   text-white font-semibold
                   hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
                   disabled:opacity-50 disabled:cursor-not-allowed
                   transition-all duration-300"
            @click="handleSave"
          >
            保存
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { getRootNode, createFolder, type TreeNode, type TestConfig } from '../../stores/requestStore'
import FolderTreeNode from '../tree/FolderTreeNode.vue'

// ==================== Props & Emits ====================

interface Props {
  isOpen: boolean
  defaultName?: string
  config: TestConfig
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  save: [path: string]
}>()

// ==================== 状态管理 ====================

const rootNode = getRootNode()
const requestName = ref('')
const selectedFolderPath = ref('/')
const expandedPaths = ref<Set<string>>(new Set(['/']))
const showNewFolderInput = ref(false)
const newFolderName = ref('')

// ==================== 监听器 ====================

watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    // 打开时初始化
    requestName.value = extractNameFromUrl(props.defaultName || props.config.url)
    selectedFolderPath.value = '/'
    expandedPaths.value = new Set(['/'])
    showNewFolderInput.value = false
    newFolderName.value = ''

    // 调试信息
    console.log('📂 文件树根节点:', rootNode)
    console.log('📂 文件树子节点数量:', rootNode.children?.length || 0)
  }
})

// ==================== 方法 ====================

/**
 * 从 URL 提取名称
 */
const extractNameFromUrl = (url: string): string => {
  if (!url) return '新请求'

  try {
    const urlObj = new URL(url)
    const pathname = urlObj.pathname

    // 移除开头的 /
    let name = pathname.replace(/^\//, '')

    // 如果路径为空，使用域名
    if (!name) {
      name = urlObj.hostname
    }

    // 移除尾部的 /
    name = name.replace(/\/$/, '')

    // 替换 / 为 -
    name = name.replace(/\//g, '-')

    return name || '新请求'
  } catch {
    return url.substring(0, 30) || '新请求'
  }
}

/**
 * 选择文件夹
 */
const handleSelectFolder = (path: string) => {
  selectedFolderPath.value = path
}

/**
 * 切换文件夹展开/折叠
 */
const handleToggleFolder = (path: string) => {
  if (expandedPaths.value.has(path)) {
    expandedPaths.value.delete(path)
  } else {
    expandedPaths.value.add(path)
  }
}

/**
 * 创建新文件夹
 */
const createNewFolder = () => {
  const folderName = newFolderName.value.trim()
  if (!folderName) return

  const newFolder = createFolder(selectedFolderPath.value, folderName)
  if (newFolder) {
    // 展开父文件夹
    expandedPaths.value.add(selectedFolderPath.value)
    // 选择新文件夹
    selectedFolderPath.value = newFolder.path
    // 展开新文件夹
    expandedPaths.value.add(newFolder.path)
    // 清空输入
    newFolderName.value = ''
    showNewFolderInput.value = false
  }
}

/**
 * 保存请求
 */
const handleSave = () => {
  if (!requestName.value.trim()) return

  const fullPath = `${selectedFolderPath.value === '/' ? '' : selectedFolderPath.value}/${requestName.value}`
  emit('save', fullPath)
  close()
}

/**
 * 关闭模态框
 */
const close = () => {
  emit('close')
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

.slide-down-enter-active,
.slide-down-leave-active {
  transition: all 0.3s ease;
  max-height: 200px;
}

.slide-down-enter-from,
.slide-down-leave-to {
  max-height: 0;
  opacity: 0;
}

/* 自定义滚动条 */
div::-webkit-scrollbar {
  width: 6px;
}

div::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
}

div::-webkit-scrollbar-thumb {
  background: rgba(14, 165, 233, 0.3);
  border-radius: 3px;
}

div::-webkit-scrollbar-thumb:hover {
  background: rgba(14, 165, 233, 0.5);
}
</style>
