/**
 * Barrage 模拟测试 - 全局状态管理
 */

import { reactive, watch } from 'vue'
import type { ExecutionGraph, GraphNode, Edge, CanvasViewport } from '../types/simulate'

// ==================== 全局状态（单例） ====================

const STORAGE_KEY = 'barrage_simulate_graph'

const graph = reactive<ExecutionGraph>({
  graphName: '未命名流程',
  startNodeId: '',
  nodes: [],
  edges: []
})

const viewport = reactive<CanvasViewport>({
  x: 0,
  y: 0,
  scale: 1
})

// 当前选中的节点ID
const selectedNodeId = reactive<{ value: string | null }>({ value: null })

// 第一个被点击的节点（用于连线）
const firstClickedNodeId = reactive<{ value: string | null }>({ value: null })

// ==================== 辅助函数 ====================

/**
 * 网格大小
 */
export const GRID_SIZE = 20

/**
 * 吸附到网格
 */
export const snapToGrid = (value: number): number => {
  return Math.round(value / GRID_SIZE) * GRID_SIZE
}

/**
 * 生成唯一ID
 */
let nodeCounter = 0
const generateNodeId = (): string => {
  nodeCounter++
  return `node_${Date.now()}_${nodeCounter}`
}

let edgeCounter = 0
const generateEdgeId = (): string => {
  edgeCounter++
  return `edge_${Date.now()}_${edgeCounter}`
}

// ==================== 节点操作 ====================

/**
 * 添加节点
 */
export const addNode = (node: Omit<GraphNode, 'id'>): GraphNode => {
  const newNode = {
    ...node,
    id: generateNodeId()
  } as GraphNode

  graph.nodes.push(newNode)

  // 如果是第一个START节点，设置为起始节点
  if (newNode.type === 'START' && !graph.startNodeId) {
    graph.startNodeId = newNode.id
  }

  return newNode
}

/**
 * 删除节点
 */
export const removeNode = (nodeId: string): void => {
  const index = graph.nodes.findIndex(n => n.id === nodeId)
  if (index !== -1) {
    graph.nodes.splice(index, 1)
  }

  // 删除相关的边
  graph.edges = graph.edges.filter(e => e.from !== nodeId && e.to !== nodeId)

  // 如果删除的是起始节点，清空startNodeId
  if (graph.startNodeId === nodeId) {
    graph.startNodeId = ''
  }

  // 清空选中状态
  if (selectedNodeId.value === nodeId) {
    selectedNodeId.value = null
  }
  if (firstClickedNodeId.value === nodeId) {
    firstClickedNodeId.value = null
  }
}

/**
 * 更新节点位置
 */
export const updateNodePosition = (nodeId: string, x: number, y: number): void => {
  const node = graph.nodes.find(n => n.id === nodeId)
  if (node) {
    // 吸附到网格
    node.x = snapToGrid(x)
    node.y = snapToGrid(y)
  }
}

/**
 * 更新节点数据
 */
export const updateNode = (nodeId: string, updates: Partial<GraphNode>): void => {
  const index = graph.nodes.findIndex(n => n.id === nodeId)
  if (index !== -1) {
    graph.nodes[index] = { ...graph.nodes[index], ...updates } as GraphNode
  }
}

/**
 * 获取节点
 */
export const getNode = (nodeId: string): GraphNode | undefined => {
  return graph.nodes.find(n => n.id === nodeId)
}

// ==================== 边操作 ====================

/**
 * 添加边（单向连线）
 */
export const addEdge = (from: string, to: string, label?: string): Edge | null => {
  // 防止自连接
  if (from === to) {
    return null
  }

  // 检查是否已存在单向连线（from -> to），如果存在则删除
  const existingEdgeIndex = graph.edges.findIndex(e => e.from === from && e.to === to)
  if (existingEdgeIndex !== -1) {
    console.log(`🗑️ 删除单向连线: ${from} → ${to}`)
    graph.edges.splice(existingEdgeIndex, 1)
    return null // 返回 null 表示删除了边
  }

  const newEdge: Edge = {
    id: generateEdgeId(),
    from,
    to,
    label
  }

  console.log(`✨ 创建单向连线: ${from} → ${to}`)
  graph.edges.push(newEdge)
  return newEdge
}

/**
 * 删除边
 */
export const removeEdge = (edgeId: string): void => {
  const index = graph.edges.findIndex(e => e.id === edgeId)
  if (index !== -1) {
    graph.edges.splice(index, 1)
  }
}

// ==================== 视口操作 ====================

/**
 * 更新视口位置
 */
export const updateViewport = (x: number, y: number): void => {
  viewport.x = x
  viewport.y = y
}

/**
 * 更新缩放
 */
export const updateScale = (scale: number): void => {
  viewport.scale = Math.max(0.1, Math.min(3, scale))
}

// ==================== 选择操作 ====================

/**
 * 选中节点
 */
export const selectNode = (nodeId: string | null): void => {
  selectedNodeId.value = nodeId
}

/**
 * 处理节点点击（用于连线）
 */
export const handleNodeClick = (nodeId: string): void => {
  if (firstClickedNodeId.value === null) {
    // 第一次点击
    firstClickedNodeId.value = nodeId
  } else if (firstClickedNodeId.value !== nodeId) {
    // 第二次点击不同节点，创建或删除连线
    addEdge(firstClickedNodeId.value, nodeId)
    firstClickedNodeId.value = null
  } else {
    // 点击同一节点，取消
    firstClickedNodeId.value = null
  }
}

/**
 * 取消连线操作
 */
export const cancelConnection = (): void => {
  firstClickedNodeId.value = null
}

// ==================== 导出/导入 ====================

/**
 * 导出为YAML格式的数据
 */
export const exportToYaml = (): string => {
  // 简化版，实际应用中需要完整转换
  return JSON.stringify(graph, null, 2)
}

/**
 * 从数据加载
 */
export const loadGraph = (data: ExecutionGraph): void => {
  graph.graphName = data.graphName
  graph.startNodeId = data.startNodeId
  graph.nodes = [...data.nodes]
  graph.edges = [...data.edges]
}

/**
 * 清空画布
 */
export const clearGraph = (): void => {
  graph.graphName = '未命名流程'
  graph.startNodeId = ''
  graph.nodes = []
  graph.edges = []
  selectedNodeId.value = null
  firstClickedNodeId.value = null
  localStorage.removeItem(STORAGE_KEY)
}

// ==================== Getters ====================

export const getGraph = () => graph
export const getViewport = () => viewport
export const getSelectedNodeId = () => selectedNodeId
export const getFirstClickedNodeId = () => firstClickedNodeId

// ==================== 持久化 ====================

const saveToStorage = (): void => {
  try {
    const data = {
      graphName: graph.graphName,
      startNodeId: graph.startNodeId,
      nodes: graph.nodes,
      edges: graph.edges,
      viewport: { x: viewport.x, y: viewport.y, scale: viewport.scale }
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch (error) {
    console.error('[SimulateStore] save failed', error)
  }
}

const loadFromStorage = (): void => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      const data = JSON.parse(stored)
      graph.graphName = data.graphName || '未命名流程'
      graph.startNodeId = data.startNodeId || ''
      graph.nodes = data.nodes || []
      graph.edges = data.edges || []
      if (data.viewport) {
        viewport.x = data.viewport.x || 0
        viewport.y = data.viewport.y || 0
        viewport.scale = data.viewport.scale || 1
      }
      // 恢复 nodeCounter 以避免 ID 冲突
      if (graph.nodes.length > 0) {
        nodeCounter = graph.nodes.length + 1
      }
      if (graph.edges.length > 0) {
        edgeCounter = graph.edges.length + 1
      }
    }
  } catch (error) {
    console.error('[SimulateStore] load failed', error)
  }
}

// 初始化：从 localStorage 加载
loadFromStorage()

// 监听变化并自动保存
watch(() => graph, () => saveToStorage(), { deep: true })
watch(() => viewport, () => saveToStorage(), { deep: true })
