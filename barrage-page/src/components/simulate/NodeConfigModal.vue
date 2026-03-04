<!--
  Barrage 火力网压测引擎 - 节点配置弹窗
-->

<template>
  <Transition name="fade">
    <div
      v-if="isOpen && editingNode"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
      @click="close"
    >
      <!-- 模态框内容 -->
      <div
        class="relative w-full max-w-2xl mx-4 p-8 rounded-2xl
               bg-zinc-900/90 backdrop-blur-xl
               border border-white/10
               shadow-2xl shadow-sky-500/20
               max-h-[80vh] overflow-y-auto"
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
        <h2 class="text-2xl font-bold text-white mb-6 flex items-center gap-3">
          <span class="text-3xl">{{ getNodeIcon(editingNode.type) }}</span>
          <span>配置{{ getNodeTypeName(editingNode.type) }}</span>
        </h2>

        <!-- 内容 -->
        <div class="space-y-6">

          <!-- 通用字段：节点名称 -->
          <div>
            <label class="block text-sm text-white/60 mb-2">节点名称</label>
            <input
              v-model="localNode.name"
              type="text"
              placeholder="输入节点名称"
              class="w-full px-4 py-3 rounded-xl
                     bg-white/5 backdrop-blur-md
                     border border-white/10
                     text-white placeholder-white/30
                     focus:outline-none focus:border-sky-500/50 focus:bg-white/10
                     transition-all duration-300"
            />
          </div>

          <!-- START 节点配置 -->
          <template v-if="editingNode.type === 'START'">
            <div class="p-4 rounded-xl bg-green-500/10 border border-green-500/30">
              <h3 class="text-green-400 font-semibold mb-3">转换配置</h3>

              <div class="space-y-3">
                <div>
                  <label class="block text-sm text-white/60 mb-2">延迟模式</label>
                  <select
                    v-model="(localNode as StartNode).transition.mode"
                    class="w-full px-4 py-3 rounded-xl
                           bg-white/5 border border-white/10
                           text-white
                           focus:outline-none focus:border-sky-500/50"
                  >
                    <option value="NO_DELAY">无延迟</option>
                    <option value="WAIT_FIXED">固定延迟</option>
                    <option value="WAIT_RANDOM">随机延迟</option>
                  </select>
                </div>

                <div v-if="(localNode as StartNode).transition.mode !== 'NO_DELAY'">
                  <label class="block text-sm text-white/60 mb-2">延迟时间（毫秒）</label>
                  <input
                    v-model="(localNode as StartNode).transition.value"
                    type="number"
                    placeholder="1000"
                    class="w-full px-4 py-3 rounded-xl
                           bg-white/5 border border-white/10
                           text-white placeholder-white/30
                           focus:outline-none focus:border-sky-500/50"
                  />
                </div>
              </div>
            </div>
          </template>

          <!-- HTTP 节点配置 -->
          <template v-if="editingNode.type === 'HTTP'">
            <div class="space-y-4">
              <div>
                <label class="block text-sm text-white/60 mb-2">模板引用</label>
                <div class="relative">
                  <input
                    v-model="(localNode as HttpNode).templateRef"
                    type="text"
                    placeholder="点击右侧按钮选择请求模板"
                    readonly
                    class="w-full px-4 py-3 pr-24 rounded-xl
                           bg-white/5 border border-white/10
                           text-white placeholder-white/30
                           cursor-pointer
                           focus:outline-none focus:border-sky-500/50"
                    @click="showRequestTree = true"
                  />
                  <button
                    @click="showRequestTree = true"
                    type="button"
                    class="absolute right-2 top-1/2 -translate-y-1/2
                           px-4 py-1.5 rounded-lg
                           bg-sky-500/20 text-sky-400 border border-sky-500/30
                           hover:bg-sky-500/30 text-sm
                           transition-all duration-200"
                  >
                    选择
                  </button>
                </div>
              </div>

              <!-- 文件树选择器 -->
              <div
                v-if="showRequestTree"
                class="p-4 rounded-xl bg-white/5 border border-white/10 max-h-60 overflow-y-auto"
              >
                <div class="flex items-center justify-between mb-3">
                  <span class="text-sm text-white/80 font-semibold">选择请求模板</span>
                  <button
                    @click="showRequestTree = false"
                    class="text-white/60 hover:text-white text-sm"
                  >
                    收起
                  </button>
                </div>
                <RequestTreeNode
                  :node="rootNode"
                  :selected-path="(localNode as HttpNode).templateRef"
                  @select="handleRequestSelect"
                />
                <div
                  v-if="!rootNode.children || rootNode.children.length === 0"
                  class="mt-3 text-xs text-white/50"
                >
                  暂无请求模板。请先到「请求管理」或「接口测试」保存请求。
                </div>
              </div>

              <div class="p-4 rounded-xl bg-blue-500/10 border border-blue-500/30">
                <h3 class="text-blue-400 font-semibold mb-3">转换配置</h3>

                <div class="space-y-3">
                  <div>
                    <label class="block text-sm text-white/60 mb-2">延迟模式</label>
                    <select
                      v-model="(localNode as HttpNode).transition.mode"
                      class="w-full px-4 py-3 rounded-xl
                             bg-white/5 border border-white/10
                             text-white
                             focus:outline-none focus:border-sky-500/50"
                    >
                      <option value="NO_DELAY">无延迟</option>
                      <option value="WAIT_FIXED">固定延迟</option>
                      <option value="WAIT_RANDOM">随机延迟</option>
                    </select>
                  </div>

                  <div v-if="(localNode as HttpNode).transition.mode !== 'NO_DELAY'">
                    <label class="block text-sm text-white/60 mb-2">延迟时间（毫秒）</label>
                    <input
                      v-model="(localNode as HttpNode).transition.value"
                      type="number"
                      placeholder="1000"
                      class="w-full px-4 py-3 rounded-xl
                             bg-white/5 border border-white/10
                             text-white placeholder-white/30
                             focus:outline-none focus:border-sky-500/50"
                    />
                  </div>
                </div>
              </div>
            </div>
          </template>

          <!-- CONDITION 节点配置 -->
          <template v-if="editingNode.type === 'CONDITION'">
            <div class="space-y-4">
              <div>
                <label class="block text-sm text-white/60 mb-2">目标变量名</label>
                <input
                  v-model="(localNode as ConditionNode).targetVarName"
                  type="text"
                  placeholder="last_code"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 border border-white/10
                         text-white placeholder-white/30
                         focus:outline-none focus:border-sky-500/50"
                />
              </div>

              <div>
                <label class="block text-sm text-white/60 mb-2">期望值</label>
                <input
                  v-model="(localNode as ConditionNode).expectedValue"
                  type="text"
                  placeholder="200"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 border border-white/10
                         text-white placeholder-white/30
                         focus:outline-none focus:border-sky-500/50"
                />
              </div>

              <div class="p-4 rounded-xl bg-yellow-500/10 border border-yellow-500/30">
                <h3 class="text-yellow-400 font-semibold mb-3">匹配时转换</h3>
                <div>
                  <label class="block text-sm text-white/60 mb-2">延迟模式</label>
                  <select
                    v-model="(localNode as ConditionNode).matchTransition.mode"
                    class="w-full px-4 py-3 rounded-xl
                           bg-white/5 border border-white/10
                           text-white
                           focus:outline-none focus:border-sky-500/50"
                  >
                    <option value="NO_DELAY">无延迟</option>
                    <option value="WAIT_FIXED">固定延迟</option>
                    <option value="WAIT_RANDOM">随机延迟</option>
                  </select>
                </div>
              </div>

              <div class="p-4 rounded-xl bg-red-500/10 border border-red-500/30">
                <h3 class="text-red-400 font-semibold mb-3">不匹配时转换</h3>
                <div>
                  <label class="block text-sm text-white/60 mb-2">延迟模式</label>
                  <select
                    v-model="(localNode as ConditionNode).mismatchTransition.mode"
                    class="w-full px-4 py-3 rounded-xl
                           bg-white/5 border border-white/10
                           text-white
                           focus:outline-none focus:border-sky-500/50"
                  >
                    <option value="NO_DELAY">无延迟</option>
                    <option value="WAIT_FIXED">固定延迟</option>
                    <option value="WAIT_RANDOM">随机延迟</option>
                  </select>
                </div>
              </div>
            </div>
          </template>

          <!-- CHANCE 节点配置 -->
          <template v-if="editingNode.type === 'CHANCE'">
            <div class="p-4 rounded-xl bg-purple-500/10 border border-purple-500/30">
              <h3 class="text-purple-400 font-semibold mb-3">概率分支配置</h3>

              <div class="space-y-3">
                <div
                  v-for="(branch, index) in (localNode as ChanceNode).branches"
                  :key="index"
                  class="p-3 rounded-lg bg-white/5 border border-white/10"
                >
                  <div class="flex items-center gap-3 mb-2">
                    <span class="text-white/60 text-sm">分支 {{ index + 1 }}</span>
                    <button
                      v-if="(localNode as ChanceNode).branches.length > 1"
                      @click="removeBranch(index)"
                      class="ml-auto text-red-400 hover:text-red-300 text-sm"
                    >
                      删除
                    </button>
                  </div>

                  <div>
                    <label class="block text-xs text-white/60 mb-1">权重</label>
                    <input
                      v-model.number="branch.weight"
                      type="number"
                      min="1"
                      placeholder="50"
                      class="w-full px-3 py-2 rounded-lg
                             bg-white/5 border border-white/10
                             text-white placeholder-white/30
                             focus:outline-none focus:border-sky-500/50"
                    />
                  </div>
                </div>

                <button
                  @click="addBranch"
                  class="w-full px-4 py-2 rounded-lg
                         bg-purple-500/20 text-purple-400 border border-purple-500/30
                         hover:bg-purple-500/30
                         transition-all duration-200"
                >
                  + 添加分支
                </button>
              </div>
            </div>
          </template>

          <!-- TERMINAL 节点配置 -->
          <template v-if="editingNode.type === 'TERMINAL'">
            <div class="space-y-4">
              <div>
                <label class="block text-sm text-white/60 mb-2">结果标签</label>
                <input
                  v-model="(localNode as TerminalNode).resultTag"
                  type="text"
                  placeholder="SUCCESS"
                  class="w-full px-4 py-3 rounded-xl
                         bg-white/5 border border-white/10
                         text-white placeholder-white/30
                         focus:outline-none focus:border-sky-500/50"
                />
              </div>

              <div class="flex items-center gap-3">
                <input
                  v-model="(localNode as TerminalNode).saveContext"
                  type="checkbox"
                  id="saveContext"
                  class="w-5 h-5 rounded bg-white/5 border border-white/10
                         text-sky-500 focus:ring-sky-500"
                />
                <label for="saveContext" class="text-white/80 cursor-pointer">
                  保存上下文
                </label>
              </div>
            </div>
          </template>

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
            class="px-6 py-2 rounded-xl
                   bg-gradient-to-r from-sky-500 to-blue-600
                   border border-white/20
                   text-white font-semibold
                   hover:shadow-[0_0_30px_rgba(14,165,233,0.6)]
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
import type { GraphNode, StartNode, HttpNode, ConditionNode, ChanceNode, TerminalNode } from '../../types/simulate'
import { getRootNode } from '../../stores/requestStore'
import RequestTreeNode from './RequestTemplateTreeNode.vue'

// ==================== Props & Emits ====================

interface Props {
  isOpen: boolean
  node: GraphNode | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  save: [node: GraphNode]
}>()

// ==================== 状态管理 ====================

const editingNode = ref<GraphNode | null>(null)
const localNode = ref<GraphNode | null>(null)
const showRequestTree = ref(false)
const rootNode = getRootNode()

// ==================== 监听器 ====================

watch(() => props.isOpen, (newVal) => {
  if (newVal && props.node) {
    // 深拷贝节点数据
    editingNode.value = props.node
    localNode.value = JSON.parse(JSON.stringify(props.node))
  }
})

// ==================== 方法 ====================

/**
 * 添加概率分支
 */
const addBranch = () => {
  if (localNode.value && localNode.value.type === 'CHANCE') {
    (localNode.value as ChanceNode).branches.push({
      weight: 50,
      transition: { mode: 'NO_DELAY' }
    })
  }
}

/**
 * 删除概率分支
 */
const removeBranch = (index: number) => {
  if (localNode.value && localNode.value.type === 'CHANCE') {
    (localNode.value as ChanceNode).branches.splice(index, 1)
  }
}

/**
 * 处理请求选择
 */
const handleRequestSelect = (path: string) => {
  if (localNode.value && localNode.value.type === 'HTTP') {
    (localNode.value as HttpNode).templateRef = path
    showRequestTree.value = false
  }
}

/**
 * 保存配置
 */
const handleSave = () => {
  if (localNode.value) {
    emit('save', localNode.value)
    close()
  }
}

/**
 * 关闭弹窗
 */
const close = () => {
  emit('close')
}

/**
 * 获取节点图标
 */
const getNodeIcon = (type: string): string => {
  const icons: Record<string, string> = {
    START: '▶',
    HTTP: '🌐',
    CONDITION: '◆',
    CHANCE: '🎲',
    TERMINAL: '⏹'
  }
  return icons[type] || '●'
}

/**
 * 获取节点类型名称
 */
const getNodeTypeName = (type: string): string => {
  const names: Record<string, string> = {
    START: '开始节点',
    HTTP: 'HTTP请求',
    CONDITION: '条件分支',
    CHANCE: '概率分支',
    TERMINAL: '结束节点'
  }
  return names[type] || '节点'
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
