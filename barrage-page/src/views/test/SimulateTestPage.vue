<!--
  Barrage 火力网压测引擎 - 模拟测试图形化配置页面
-->

<template>
  <div class="h-full flex flex-col bg-zinc-950">

    <!-- 顶部工具栏 -->
    <div class="flex items-center gap-3 px-6 py-4 bg-zinc-900/80 backdrop-blur-sm border-b border-white/10">
      <!-- 标题 -->
      <h2 class="text-lg font-bold text-white mr-4">模拟测试流程</h2>

      <!-- 节点工具栏 -->
      <div class="flex items-center gap-2">
        <button
          v-for="item in nodeToolbar"
          :key="item.type"
          @click="addNodeToCanvas(item.type)"
          :class="[
            'px-4 py-2 rounded-lg text-sm font-medium',
            'border transition-all duration-200',
            'hover:shadow-lg',
            item.color
          ]"
        >
          <span class="mr-2">{{ item.icon }}</span>
          {{ item.label }}
        </button>
      </div>

      <!-- 右侧操作按钮 -->
      <div class="flex-1"></div>

      <!-- 操作提示 -->
      <div class="px-3 py-1 text-xs text-white/40">
        右键连线 | 双击配置 | 拖拽移动
      </div>

      <button
        @click="clearCanvas"
        class="px-4 py-2 rounded-lg text-sm font-medium
               bg-red-500/20 text-red-400 border border-red-500/30
               hover:bg-red-500/30 transition-all duration-200"
      >
        清空画布
      </button>

      <!-- 保存失败提示 -->
      <span v-if="saveError" class="text-xs text-red-400 max-w-[200px] truncate" :title="saveError">
        ❌ {{ saveError }}
      </span>

      <!-- 保存配置（idle / saving） -->
      <button
        v-if="saveState === 'idle' || saveState === 'saving'"
        @click="saveConfig"
        :disabled="saveState === 'saving'"
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

      <!-- 启动测试（saved / starting） -->
      <button
        v-else-if="saveState === 'saved' || saveState === 'starting'"
        @click="startSimulate"
        :disabled="saveState === 'starting'"
        class="px-5 py-2 rounded-lg text-sm font-semibold
               bg-green-500/20 text-green-400 border border-green-500/30
               hover:bg-green-500/30 hover:shadow-[0_0_20px_rgba(34,197,94,0.4)]
               transition-all duration-200
               disabled:opacity-50 disabled:cursor-not-allowed
               flex items-center gap-2"
      >
        <svg v-if="saveState === 'starting'" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
        <span v-else>▶</span>
        {{ saveState === 'starting' ? '启动中...' : '启动测试' }}
      </button>

      <!-- 运行中（running） -->
      <button
        v-else-if="saveState === 'running'"
        @click="stopSimulate"
        class="px-5 py-2 rounded-lg text-sm font-semibold
               bg-red-500/20 text-red-400 border border-red-500/30
               hover:bg-red-500/30 transition-all duration-200
               flex items-center gap-2"
      >
        <span class="w-2 h-2 rounded-full bg-red-400 animate-pulse"></span>
        停止测试
      </button>
    </div>

    <!-- 画布区域 -->
    <div
      ref="canvasContainer"
      class="flex-1 relative overflow-hidden cursor-grab active:cursor-grabbing"
      @mousedown="handleCanvasMouseDown"
      @mousemove="handleCanvasMouseMove"
      @mouseup="handleCanvasMouseUp"
      @mouseleave="handleCanvasMouseUp"
    >
      <!-- 网格背景 -->
      <div
        class="absolute inset-0"
        :style="{
          backgroundImage: `
            linear-gradient(to right, rgba(255,255,255,0.03) 1px, transparent 1px),
            linear-gradient(to bottom, rgba(255,255,255,0.03) 1px, transparent 1px)
          `,
          backgroundSize: `${GRID_SIZE}px ${GRID_SIZE}px`,
          backgroundPosition: `${viewport.x}px ${viewport.y}px`
        }"
      ></div>

      <!-- SVG 连线层 -->
      <svg
        class="absolute inset-0 pointer-events-none"
        :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`"
      >
        <defs>
          <!-- 每条边的渐变定义 -->
          <linearGradient
            v-for="edge in graph.edges"
            :key="'grad-' + edge.id"
            :id="'edgeGrad-' + edge.id"
            gradientUnits="userSpaceOnUse"
            :x1="getEdgeGradient(edge).x1"
            :y1="getEdgeGradient(edge).y1"
            :x2="getEdgeGradient(edge).x2"
            :y2="getEdgeGradient(edge).y2"
          >
            <stop offset="0%" stop-color="rgba(14,165,233,0.04)" />
            <stop offset="100%" stop-color="rgba(14,165,233,0.35)" />
          </linearGradient>
        </defs>

        <!-- 渲染所有边 -->
        <g v-for="edge in graph.edges" :key="edge.id">
          <path
            :d="getEdgePath(edge)"
            :fill="`url(#edgeGrad-${edge.id})`"
            stroke="rgba(14,165,233,0.15)"
            stroke-width="0.5"
          />
          <!-- 边标签 -->
          <text
            v-if="edge.label"
            :x="getEdgeLabelPosition(edge).x"
            :y="getEdgeLabelPosition(edge).y"
            fill="rgba(14, 165, 233, 0.8)"
            font-size="12"
            text-anchor="middle"
            class="select-none"
          >
            {{ edge.label }}
          </text>
        </g>
      </svg>

      <!-- 节点层 -->
      <div
        v-for="node in graph.nodes"
        :key="node.id"
        :style="{
          position: 'absolute',
          left: (node.x + viewport.x) + 'px',
          top: (node.y + viewport.y) + 'px',
          transform: 'translate(-50%, -50%)'
        }"
        @mousedown.stop="handleNodeMouseDown(node.id, $event)"
        @click.stop="handleNodeLeftClick(node.id)"
        @contextmenu.prevent.stop="handleNodeRightClick(node.id)"
        @dblclick.stop="handleNodeDoubleClick(node.id)"
        :class="[
          'px-6 py-4 rounded-xl cursor-move',
          'border-2',
          'shadow-lg backdrop-blur-sm',
          'select-none pointer-events-auto',
          draggingNodeId === node.id ? '' : 'transition-all duration-100',
          selectedNodeId.value === node.id ? 'ring-2 ring-sky-400' : '',
          firstClickedNodeId.value === node.id ? 'ring-2 ring-yellow-400 ring-offset-2 ring-offset-zinc-950' : '',
          getNodeStyle(node.type)
        ]"
      >
        <!-- 节点内容 -->
        <div class="flex items-center gap-3 min-w-[160px]">
          <span class="text-2xl">{{ getNodeIcon(node.type) }}</span>
          <div class="flex-1">
            <div class="text-xs opacity-60 mb-1">{{ node.type }}</div>
            <div class="font-bold">{{ node.name }}</div>
          </div>
          <!-- 删除按钮 -->
          <button
            @click.stop="removeNodeFromCanvas(node.id)"
            class="w-6 h-6 flex items-center justify-center
                   rounded-full hover:bg-red-500/20 text-red-400
                   transition-all duration-200"
          >
            ✕
          </button>
        </div>

        <!-- 节点详细信息 -->
        <div class="mt-2 text-xs opacity-60">
          <div v-if="node.type === 'HTTP'">
            模板: {{ (node as HttpNode).templateRef || '未设置' }}
          </div>
          <div v-if="node.type === 'CONDITION'">
            条件: {{ (node as ConditionNode).targetVarName }} == {{ (node as ConditionNode).expectedValue }}
          </div>
          <div v-if="node.type === 'TERMINAL'">
            结果: {{ (node as TerminalNode).resultTag }}
          </div>
        </div>
      </div>

      <!-- 右键连线提示 -->
      <div
        v-if="firstClickedNodeId.value"
        class="absolute top-4 left-1/2 -translate-x-1/2
               px-6 py-3 rounded-xl
               bg-yellow-500/20 border border-yellow-500/30
               text-yellow-400 font-medium
               backdrop-blur-sm shadow-lg"
      >
        右键点击目标节点以创建单向连线
      </div>
    </div>

    <!-- 节点配置弹窗 -->
    <NodeConfigModal
      :is-open="isConfigModalOpen"
      :node="configEditingNode"
      @close="closeConfigModal"
      @save="handleNodeConfigSave"
    />

  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import type { NodeType, StartNode, HttpNode, ConditionNode, ChanceNode, TerminalNode, Edge, NodeToolbarItem, GraphNode } from '../../types/simulate'
import NodeConfigModal from '../../components/simulate/NodeConfigModal.vue'
import { getRootNode } from '../../stores/requestStore'
import {
  getGraph,
  getViewport,
  getSelectedNodeId,
  getFirstClickedNodeId,
  addNode,
  removeNode,
  updateNodePosition,
  updateNode,
  addEdge,
  selectNode,
  handleNodeClick as storeHandleNodeClick,
  cancelConnection,
  updateViewport,
  updateScale,
  clearGraph,
  exportToYaml,
  GRID_SIZE,
  snapToGrid
} from '../../stores/simulateStore'

// ==================== 状态管理 ====================

const graph = getGraph()
const viewport = getViewport()
const selectedNodeId = getSelectedNodeId()
const firstClickedNodeId = getFirstClickedNodeId()

// ==================== 工具栏配置 ====================

const nodeToolbar: NodeToolbarItem[] = [
  {
    type: 'START',
    label: '开始',
    icon: '▶',
    color: 'bg-green-500/20 text-green-400 border-green-500/30 hover:bg-green-500/30'
  },
  {
    type: 'HTTP',
    label: 'HTTP请求',
    icon: '🌐',
    color: 'bg-blue-500/20 text-blue-400 border-blue-500/30 hover:bg-blue-500/30'
  },
  {
    type: 'CONDITION',
    label: '条件分支',
    icon: '◆',
    color: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30 hover:bg-yellow-500/30'
  },
  {
    type: 'CHANCE',
    label: '概率分支',
    icon: '🎲',
    color: 'bg-purple-500/20 text-purple-400 border-purple-500/30 hover:bg-purple-500/30'
  },
  {
    type: 'TERMINAL',
    label: '结束',
    icon: '⏹',
    color: 'bg-red-500/20 text-red-400 border-red-500/30 hover:bg-red-500/30'
  }
]

// ==================== 画布配置 ====================

const canvasContainer = ref<HTMLElement | null>(null)
const canvasWidth = ref(2000)
const canvasHeight = ref(2000)
// 使用导入的网格大小常量

// ==================== 拖拽画布 ====================

const isPanning = ref(false)
const panStart = ref({ x: 0, y: 0 })
const hasMoved = ref(false) // 标记是否发生了移动
let animationFrameId: number | null = null

const handleCanvasMouseDown = (e: MouseEvent) => {
  // 只有在画布空白处才能拖拽
  if (e.target === canvasContainer.value || (e.target as HTMLElement).classList.contains('absolute')) {
    isPanning.value = true
    hasMoved.value = false
    panStart.value = {
      x: e.clientX - viewport.x,
      y: e.clientY - viewport.y
    }
  }
}

const handleCanvasMouseMove = (e: MouseEvent) => {
  if (isPanning.value) {
    hasMoved.value = true
    // 使用 requestAnimationFrame 优化性能
    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId)
    }
    animationFrameId = requestAnimationFrame(() => {
      // 吸附视口到网格
      const newX = snapToGrid(e.clientX - panStart.value.x)
      const newY = snapToGrid(e.clientY - panStart.value.y)
      updateViewport(newX, newY)
    })
  } else if (draggingNodeId.value) {
    hasMoved.value = true
    // 拖拽节点 - 直接使用屏幕坐标减去初始偏移
    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId)
    }
    animationFrameId = requestAnimationFrame(() => {
      const x = e.clientX - dragOffset.value.x
      const y = e.clientY - dragOffset.value.y
      // updateNodePosition 内部会自动吸附
      updateNodePosition(draggingNodeId.value!, x - viewport.x, y - viewport.y)
    })
  }
}

const handleCanvasMouseUp = (e: MouseEvent) => {
  // 如果是点击空白处（没有移动），取消选中
  if (!hasMoved.value && isPanning.value) {
    selectNode(null)
    cancelConnection()
  }

  isPanning.value = false
  draggingNodeId.value = null
  hasMoved.value = false
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
    animationFrameId = null
  }
}

// ==================== 缩放（暂时禁用） ====================

// const handleWheel = (e: WheelEvent) => {
//   e.preventDefault()
//   const delta = e.deltaY > 0 ? 0.9 : 1.1
//   updateScale(viewport.scale * delta)
// }

// ==================== 拖拽节点 ====================

const draggingNodeId = ref<string | null>(null)
const dragOffset = ref({ x: 0, y: 0 })

const handleNodeMouseDown = (nodeId: string, e: MouseEvent) => {
  draggingNodeId.value = nodeId
  selectNode(nodeId)

  const node = graph.nodes.find(n => n.id === nodeId)
  if (node) {
    // 记录鼠标点击位置相对于节点中心的偏移
    // 因为节点使用 transform: translate(-50%, -50%)，所以 node.x, node.y 是节点中心
    dragOffset.value = {
      x: e.clientX - (node.x + viewport.x),
      y: e.clientY - (node.y + viewport.y)
    }
  }
}

// 左键点击 - 仅用于选中节点
const handleNodeLeftClick = (nodeId: string) => {
  selectNode(nodeId)
}

// 右键点击 - 用于创建/删除连线
const handleNodeRightClick = (nodeId: string) => {
  storeHandleNodeClick(nodeId)
}

// ==================== 节点配置弹窗 ====================

const isConfigModalOpen = ref(false)
const configEditingNode = ref<GraphNode | null>(null)

const handleNodeDoubleClick = (nodeId: string) => {
  const node = graph.nodes.find(n => n.id === nodeId)
  if (node) {
    configEditingNode.value = node
    isConfigModalOpen.value = true
  }
}

const closeConfigModal = () => {
  isConfigModalOpen.value = false
  configEditingNode.value = null
}

const handleNodeConfigSave = (updatedNode: GraphNode) => {
  updateNode(updatedNode.id, updatedNode)
  console.log('✅ 节点配置已保存:', updatedNode)
}

// ==================== 节点操作 ====================

// ==================== 保存 / 启动状态 ====================

type SaveState = 'idle' | 'saving' | 'saved' | 'starting' | 'running'
const saveState = ref<SaveState>('idle')
const saveError = ref('')

/**
 * 从 URL 中提取请求路径（不含 host）
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
 * 收集请求管理树中所有请求节点，转为 HTTP 模板列表
 */
function collectAllTemplates() {
  const root = getRootNode()
  const templates: { name: string; method: string; path: string; body: string; headers: string }[] = []

  const traverse = (node: any) => {
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

  traverse(root)
  return templates
}

/**
 * 将前端图转为后端 ScenarioConfigDTO 格式（根据 edges 填充 transition.next）
 */
function graphToScenarioDto() {
  const g = getGraph()
  const startNode = g.nodes.find(n => n.type === 'START')

  const nodes = g.nodes.map(node => {
    const outEdges = g.edges.filter(e => e.from === node.id)
    const dto: Record<string, any> = { id: node.id, type: node.type, name: node.name }

    switch (node.type) {
      case 'START': {
        const n = node as StartNode
        dto.transition = { mode: n.transition.mode, value: n.transition.value ?? null, next: outEdges[0]?.to ?? null }
        break
      }
      case 'HTTP': {
        const n = node as HttpNode
        dto.templateRef = n.templateRef
        dto.transition = { mode: n.transition.mode, value: n.transition.value ?? null, next: outEdges[0]?.to ?? null }
        break
      }
      case 'CONDITION': {
        const n = node as ConditionNode
        dto.expectedValue = n.expectedValue
        const matchEdge    = outEdges.find(e => e.label === 'match')    ?? outEdges[0]
        const mismatchEdge = outEdges.find(e => e.label === 'mismatch') ?? outEdges[1]
        dto.matchTransition    = { mode: n.matchTransition.mode,    value: n.matchTransition.value    ?? null, next: matchEdge?.to    ?? null }
        dto.mismatchTransition = { mode: n.mismatchTransition.mode, value: n.mismatchTransition.value ?? null, next: mismatchEdge?.to ?? null }
        break
      }
      case 'CHANCE': {
        const n = node as ChanceNode
        dto.branches = n.branches.map((b, i) => ({
          weight:     b.weight,
          transition: { mode: b.transition.mode, value: b.transition.value ?? null, next: outEdges[i]?.to ?? null }
        }))
        break
      }
      case 'TERMINAL': {
        const n = node as TerminalNode
        dto.resultTag    = n.resultTag
        dto.saveContext  = n.saveContext
        break
      }
    }
    return dto
  })

  return {
    fileName:    'scenario.yaml',
    graphName:   g.graphName,
    startNodeId: startNode?.id ?? '',
    nodes
  }
}

/**
 * 保存配置：先刷新 http.toml，再保存 scenario.yaml
 */
async function saveConfig() {
  if (graph.nodes.length === 0) {
    alert('画布为空，请先添加节点')
    return
  }
  saveState.value = 'saving'
  saveError.value = ''

  try {
    // Step 1: 刷新所有 HTTP 模板到 http.toml
    const templates = collectAllTemplates()
    const r1 = await fetch('/api/config/templates/batch', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(templates)
    })
    if (!r1.ok) {
      const msg = await r1.text()
      throw new Error(`HTTP模板保存失败 (${r1.status}): ${msg}`)
    }

    // Step 2: 保存执行流程图到 scenario.yaml
    const dto = graphToScenarioDto()
    const r2 = await fetch('/api/simulate/scenarios', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(dto)
    })
    if (!r2.ok) {
      const msg = await r2.text()
      throw new Error(`场景保存失败 (${r2.status}): ${msg}`)
    }

    saveState.value = 'saved'
    console.log('[SimulateTest] 配置保存成功，已就绪')
  } catch (err: any) {
    saveState.value = 'idle'
    saveError.value = err.message || '保存失败'
    alert('保存失败: ' + saveError.value)
  }
}

/**
 * 启动模拟测试
 */
async function startSimulate() {
  saveState.value = 'starting'
  try {
    const r = await fetch('/api/simulate/start?scenario=scenario.yaml', { method: 'POST' })
    if (!r.ok) {
      const msg = await r.text()
      throw new Error(`(${r.status}): ${msg}`)
    }
    saveState.value = 'running'
    console.log('[SimulateTest] 模拟测试已启动')
  } catch (err: any) {
    saveState.value = 'saved'
    alert('启动失败: ' + (err.message || '未知错误'))
  }
}

/**
 * 停止模拟测试
 */
async function stopSimulate() {
  try {
    await fetch('/api/simulate/stop', { method: 'POST' })
    saveState.value = 'saved'
    console.log('[SimulateTest] 模拟测试已停止')
  } catch (err) {
    console.error('[SimulateTest] 停止失败', err)
  }
}

let nodePositionCounter = 0

const addNodeToCanvas = (type: NodeType) => {
  // 计算新节点的位置（螺旋排列）
  const angle = nodePositionCounter * 0.5
  const radius = 50 + nodePositionCounter * 30
  const centerX = 400
  const centerY = 300

  // 计算位置并吸附到网格
  const rawX = centerX + Math.cos(angle) * radius
  const rawY = centerY + Math.sin(angle) * radius

  // 吸附到网格
  const x = snapToGrid(rawX)
  const y = snapToGrid(rawY)

  nodePositionCounter++

  // 根据类型创建不同的节点
  const baseNode = {
    type,
    name: getDefaultNodeName(type),
    x,
    y
  }

  switch (type) {
    case 'START':
      addNode({
        ...baseNode,
        type: 'START',
        transition: { mode: 'WAIT_FIXED', value: '1000' }
      })
      break
    case 'HTTP':
      addNode({
        ...baseNode,
        type: 'HTTP',
        templateRef: '',
        transition: { mode: 'WAIT_FIXED', value: '1000' }
      })
      break
    case 'CONDITION':
      addNode({
        ...baseNode,
        type: 'CONDITION',
        targetVarName: 'last_code',
        expectedValue: '200',
        matchTransition: { mode: 'NO_DELAY' },
        mismatchTransition: { mode: 'NO_DELAY' }
      })
      break
    case 'CHANCE':
      addNode({
        ...baseNode,
        type: 'CHANCE',
        branches: [
          { weight: 50, transition: { mode: 'NO_DELAY' } },
          { weight: 50, transition: { mode: 'NO_DELAY' } }
        ]
      })
      break
    case 'TERMINAL':
      addNode({
        ...baseNode,
        type: 'TERMINAL',
        resultTag: 'SUCCESS',
        saveContext: true
      })
      break
  }
}

const removeNodeFromCanvas = (nodeId: string) => {
  removeNode(nodeId)
}

const clearCanvas = () => {
  if (confirm('确定要清空画布吗？所有节点和连接将被删除。')) {
    clearGraph()
    nodePositionCounter = 0
    if (saveState.value === 'saved') saveState.value = 'idle'
    saveError.value = ''
  }
}

const exportGraph = () => {
  const yaml = exportToYaml()
  console.log('导出的图结构:', yaml)

  // 创建下载
  const blob = new Blob([yaml], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${graph.graphName}_${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}

// ==================== 辅助函数 ====================

const getDefaultNodeName = (type: NodeType): string => {
  const names = {
    START: '开始节点',
    HTTP: 'HTTP请求',
    CONDITION: '条件判断',
    CHANCE: '概率分支',
    TERMINAL: '结束节点'
  }
  return names[type]
}

const getNodeIcon = (type: NodeType): string => {
  const icons = {
    START: '▶',
    HTTP: '🌐',
    CONDITION: '◆',
    CHANCE: '🎲',
    TERMINAL: '⏹'
  }
  return icons[type]
}

const getNodeStyle = (type: NodeType): string => {
  const styles = {
    START: 'bg-green-500/20 border-green-500 text-green-400',
    HTTP: 'bg-blue-500/20 border-blue-500 text-blue-400',
    CONDITION: 'bg-yellow-500/20 border-yellow-500 text-yellow-400',
    CHANCE: 'bg-purple-500/20 border-purple-500 text-purple-400',
    TERMINAL: 'bg-red-500/20 border-red-500 text-red-400'
  }
  return styles[type]
}

// ==================== 连线绘制（四顶点三角形渐变） ====================

const NODE_HALF_WIDTH = 104  // 节点大约宽 208px（min-w-[160px] + px-6 两侧）
const NODE_HALF_HEIGHT = 40  // 节点大约高 80px（py-4 + 内容）

/**
 * 获取三角形连线路径：
 * 起始端 = 源节点矩形四个顶点（完整矩形轮廓）
 * 终止端 = 目标节点中心（一个点）
 * 形状 = 凸包（矩形 + 目标点），曲线优化两侧边缘
 */
const getEdgePath = (edge: Edge): string => {
  const fromNode = graph.nodes.find(n => n.id === edge.from)
  const toNode = graph.nodes.find(n => n.id === edge.to)
  if (!fromNode || !toNode) return ''

  const fx = fromNode.x + viewport.x
  const fy = fromNode.y + viewport.y
  const tx = toNode.x + viewport.x
  const ty = toNode.y + viewport.y

  const halfW = NODE_HALF_WIDTH
  const halfH = NODE_HALF_HEIGHT

  // 矩形四个顶点（顺时针：TL, TR, BR, BL）
  const rect = [
    { x: fx - halfW, y: fy - halfH }, // 0: TL
    { x: fx + halfW, y: fy - halfH }, // 1: TR
    { x: fx + halfW, y: fy + halfH }, // 2: BR
    { x: fx - halfW, y: fy + halfH }, // 3: BL
  ]

  // 从目标点看源矩形，找出张角最大的两个"轮廓顶点"
  const angles = rect.map((c, i) => ({
    idx: i,
    angle: Math.atan2(c.y - ty, c.x - tx)
  }))
  const sorted = [...angles].sort((a, b) => a.angle - b.angle)

  // 找最大角度间隙（目标点所在的方向）
  let maxGap = -Infinity
  let gapAfter = 0
  for (let i = 0; i < 4; i++) {
    const next = (i + 1) % 4
    let gap = sorted[next].angle - sorted[i].angle
    if (next === 0) gap += 2 * Math.PI
    if (gap > maxGap) {
      maxGap = gap
      gapAfter = i
    }
  }

  const rightIdx = sorted[gapAfter].idx           // 间隙右侧顶点
  const leftIdx = sorted[(gapAfter + 1) % 4].idx  // 间隙左侧顶点

  // 从 leftIdx 顺时针遍历矩形到 rightIdx（背面轮廓）
  const path: { x: number; y: number }[] = []
  let i = leftIdx
  while (true) {
    path.push(rect[i])
    if (i === rightIdx) break
    i = (i + 1) % 4
  }

  // 矩形直线部分
  let d = `M ${path[0].x} ${path[0].y}`
  for (let j = 1; j < path.length; j++) {
    d += ` L ${path[j].x} ${path[j].y}`
  }

  // 两侧贝塞尔曲线连接矩形顶点到目标点
  const rc = rect[rightIdx]
  const lc = rect[leftIdx]

  // 切线方向：沿矩形边缘延伸，保证 C1 连续
  const prevCorner = path.length >= 2 ? path[path.length - 2] : lc
  const nextCorner = path.length >= 2 ? path[1] : rc

  // 右侧顶点切线（沿矩形边方向）
  const rtx = rc.x - prevCorner.x
  const rty = rc.y - prevCorner.y
  const rtLen = Math.sqrt(rtx * rtx + rty * rty) || 1

  // 左侧顶点切线（沿矩形边方向）
  const ltx = nextCorner.x - lc.x
  const lty = nextCorner.y - lc.y
  const ltLen = Math.sqrt(ltx * ltx + lty * lty) || 1

  const dist = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy))
  const k = dist * 0.3

  // 右顶点 → 目标（二次贝塞尔，沿切线延伸）
  const q1x = rc.x + (rtx / rtLen) * k
  const q1y = rc.y + (rty / rtLen) * k
  d += ` Q ${q1x} ${q1y}, ${tx} ${ty}`

  // 目标 → 左顶点（二次贝塞尔，沿切线回收）
  const q2x = lc.x - (ltx / ltLen) * k
  const q2y = lc.y - (lty / ltLen) * k
  d += ` Q ${q2x} ${q2y}, ${lc.x} ${lc.y}`

  d += ' Z'
  return d
}

/**
 * 渐变坐标（源中心 → 目标中心）
 */
const getEdgeGradient = (edge: Edge) => {
  const fromNode = graph.nodes.find(n => n.id === edge.from)
  const toNode = graph.nodes.find(n => n.id === edge.to)
  if (!fromNode || !toNode) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  return {
    x1: fromNode.x + viewport.x,
    y1: fromNode.y + viewport.y,
    x2: toNode.x + viewport.x,
    y2: toNode.y + viewport.y
  }
}

const getEdgeLabelPosition = (edge: Edge) => {
  const fromNode = graph.nodes.find(n => n.id === edge.from)
  const toNode = graph.nodes.find(n => n.id === edge.to)
  if (!fromNode || !toNode) return { x: 0, y: 0 }

  const x1 = fromNode.x + viewport.x
  const y1 = fromNode.y + viewport.y
  const x2 = toNode.x + viewport.x
  const y2 = toNode.y + viewport.y

  // 标签位于三角形中部偏源端（30%处）
  return {
    x: x1 + (x2 - x1) * 0.3,
    y: y1 + (y2 - y1) * 0.3 - 10
  }
}

// ==================== 生命周期 ====================

onMounted(() => {
  if (canvasContainer.value) {
    canvasWidth.value = canvasContainer.value.clientWidth
    canvasHeight.value = canvasContainer.value.clientHeight
  }
})

onUnmounted(() => {
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
  }
})
</script>

<style scoped>
/* 自定义滚动条 */
div::-webkit-scrollbar {
  width: 6px;
  height: 6px;
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
