<template>
  <div>
    <div
      @click="node.type === 'request' ? emit('select', node.path) : toggleExpand()"
      :class="[
        'px-3 py-2 rounded-lg cursor-pointer transition-all duration-200',
        'flex items-center gap-2',
        node.type === 'folder' ? 'hover:bg-white/5' : 'hover:bg-sky-500/20',
        selectedPath === node.path ? 'bg-sky-500/30 text-sky-400' : 'text-white/80'
      ]"
      :style="{ paddingLeft: (level * 16 + 12) + 'px' }"
    >
      <span v-if="node.type === 'folder'" class="text-xs">
        {{ isExpanded ? '📂' : '📁' }}
      </span>
      <span v-else class="text-xs">📄</span>

      <span class="text-sm flex-1">{{ node.name }}</span>

      <span
        v-if="node.type === 'request' && node.config"
        class="text-xs px-2 py-0.5 rounded bg-blue-500/20 text-blue-400"
      >
        {{ node.config.method }}
      </span>
    </div>

    <div v-if="node.type === 'folder' && isExpanded && node.children">
      <RequestTemplateTreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :selected-path="selectedPath"
        :level="level + 1"
        @select="emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { TreeNode } from '../../types'

interface Props {
  node: TreeNode
  selectedPath: string
  level?: number
}

withDefaults(defineProps<Props>(), {
  level: 0
})

const emit = defineEmits<{
  select: [path: string]
}>()

const isExpanded = ref(true)

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}
</script>
