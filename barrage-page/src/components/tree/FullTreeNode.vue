<!--
  Barrage 火力网压测引擎 - 完整树节点组件（递归，包含文件夹和请求）
-->

<template>
  <div>
    <!-- 文件夹节点 -->
    <div
      v-if="node.type === 'folder'"
      :class="[
        'flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-all duration-200',
        'hover:bg-white/5',
        selectedId === node.id ? 'bg-sky-500/20 border border-sky-500/30' : 'border border-transparent'
      ]"
      :style="{ paddingLeft: (level * 16 + 12) + 'px' }"
      @click="$emit('select', node.id)"
      @contextmenu.prevent="$emit('contextMenu', $event, node)"
    >
      <!-- 展开/折叠按钮 -->
      <button
        @click.stop="$emit('toggle', node.id)"
        class="w-4 h-4 flex items-center justify-center text-white/60 hover:text-white transition-colors"
      >
        {{ expandedIds.has(node.id) ? '▼' : '▶' }}
      </button>

      <!-- 文件夹图标 -->
      <svg class="w-4 h-4 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
        <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
      </svg>

      <!-- 文件夹名称 -->
      <span :class="[
        'text-sm font-medium flex-1',
        selectedId === node.id ? 'text-sky-400' : 'text-white/80'
      ]">
        {{ node.name }}
      </span>
    </div>

    <!-- 请求节点 -->
    <div
      v-else
      :class="[
        'flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-all duration-200',
        'hover:bg-white/5',
        selectedId === node.id ? 'bg-sky-500/20 border border-sky-500/30' : 'border border-transparent'
      ]"
      :style="{ paddingLeft: (level * 16 + 12) + 'px' }"
      @click="$emit('select', node.id)"
      @contextmenu.prevent="$emit('contextMenu', $event, node)"
    >
      <!-- 占位空间 -->
      <div class="w-4"></div>

      <!-- 请求图标 -->
      <svg class="w-4 h-4 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
      </svg>

      <!-- 请求名称 -->
      <span :class="[
        'text-sm font-medium flex-1',
        selectedId === node.id ? 'text-sky-400' : 'text-white/80'
      ]">
        {{ node.name }}
      </span>

      <!-- 方法标签 -->
      <span v-if="node.config" :class="[
        'text-xs px-2 py-0.5 rounded font-bold',
        node.config.method === 'GET' ? 'bg-green-500/20 text-green-400' :
        node.config.method === 'POST' ? 'bg-blue-500/20 text-blue-400' :
        node.config.method === 'PUT' ? 'bg-yellow-500/20 text-yellow-400' :
        'bg-red-500/20 text-red-400'
      ]">
        {{ node.config.method }}
      </span>
    </div>

    <!-- 子节点（递归，仅文件夹有子节点） -->
    <div v-if="node.children && expandedIds.has(node.id)">
      <FullTreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :selected-id="selectedId"
        :expanded-ids="expandedIds"
        :level="level + 1"
        @select="$emit('select', $event)"
        @toggle="$emit('toggle', $event)"
        @context-menu="(e, n) => $emit('contextMenu', e, n)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { TreeNode } from '../../types'

// ==================== Props ====================

interface Props {
  node: TreeNode
  selectedId: string | null
  expandedIds: Set<string>
  level?: number
}

withDefaults(defineProps<Props>(), {
  level: 0
})

// ==================== Emits ====================

defineEmits<{
  select: [id: string]
  toggle: [id: string]
  contextMenu: [event: MouseEvent, node: TreeNode]
}>()
</script>
