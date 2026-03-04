<!--
  Barrage 火力网压测引擎 - 文件夹树节点组件（递归）
-->

<template>
  <div v-if="node.type === 'folder'">
    <!-- 文件夹节点 -->
    <div
      :class="[
        'flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-all duration-200',
        'hover:bg-white/5',
        selectedPath === node.path ? 'bg-sky-500/20 border border-sky-500/30' : 'border border-transparent'
      ]"
      :style="{ paddingLeft: (level * 16 + 12) + 'px' }"
      @click="$emit('select', node.path)"
    >
      <!-- 展开/折叠按钮 -->
      <button
        @click.stop="$emit('toggle', node.path)"
        class="w-4 h-4 flex items-center justify-center text-white/60 hover:text-white transition-colors"
      >
        {{ expandedPaths.has(node.path) ? '▼' : '▶' }}
      </button>

      <!-- 文件夹图标 -->
      <svg class="w-4 h-4 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
        <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
      </svg>

      <!-- 文件夹名称 -->
      <span :class="[
        'text-sm font-medium',
        selectedPath === node.path ? 'text-sky-400' : 'text-white/80'
      ]">
        {{ node.name }}
      </span>
    </div>

    <!-- 子节点（递归） -->
    <div v-if="node.children && expandedPaths.has(node.path)">
      <FolderTreeNode
        v-for="child in node.children.filter(c => c.type === 'folder')"
        :key="child.id"
        :node="child"
        :selected-path="selectedPath"
        :expanded-paths="expandedPaths"
        :level="level + 1"
        @select="$emit('select', $event)"
        @toggle="$emit('toggle', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { TreeNode } from '../../types'

// ==================== Props ====================

interface Props {
  node: TreeNode
  selectedPath: string
  expandedPaths: Set<string>
  level?: number
}

withDefaults(defineProps<Props>(), {
  level: 0
})

// ==================== Emits ====================

defineEmits<{
  select: [path: string]
  toggle: [path: string]
}>()
</script>
