/**
 * Barrage 模拟测试 - 图形化工作流类型定义
 */

// ==================== 节点类型枚举 ====================

export type NodeType = 'START' | 'HTTP' | 'CONDITION' | 'CHANCE' | 'TERMINAL'

export type TransitionMode = 'NO_DELAY' | 'WAIT_FIXED' | 'WAIT_RANDOM'

// ==================== 节点配置 ====================

/**
 * 转换配置（节点跳转规则）
 */
export interface Transition {
  mode: TransitionMode
  value?: string  // 延迟时间（毫秒）
  next?: string   // 下一个节点ID
}

/**
 * 概率分支
 */
export interface ChanceBranch {
  weight: number
  transition: Transition
}

/**
 * 基础节点接口
 */
export interface BaseNode {
  id: string
  type: NodeType
  name: string
  // UI位置信息
  x: number
  y: number
}

/**
 * 起始节点
 */
export interface StartNode extends BaseNode {
  type: 'START'
  transition: Transition
}

/**
 * HTTP请求节点
 */
export interface HttpNode extends BaseNode {
  type: 'HTTP'
  templateRef: string
  transition: Transition
}

/**
 * 条件分支节点
 */
export interface ConditionNode extends BaseNode {
  type: 'CONDITION'
  targetVarName: string
  expectedValue: string
  matchTransition: Transition
  mismatchTransition: Transition
}

/**
 * 概率分支节点
 */
export interface ChanceNode extends BaseNode {
  type: 'CHANCE'
  branches: ChanceBranch[]
}

/**
 * 终止节点
 */
export interface TerminalNode extends BaseNode {
  type: 'TERMINAL'
  resultTag: string
  saveContext: boolean
}

/**
 * 联合类型：所有节点类型
 */
export type GraphNode = StartNode | HttpNode | ConditionNode | ChanceNode | TerminalNode

// ==================== 连线 ====================

/**
 * 节点间的连接线
 */
export interface Edge {
  id: string
  from: string  // 源节点ID
  to: string    // 目标节点ID
  label?: string // 连线标签（如"匹配"、"不匹配"、"30%"等）
}

// ==================== 图结构 ====================

/**
 * 执行图（完整的工作流）
 */
export interface ExecutionGraph {
  graphName: string
  startNodeId: string
  nodes: GraphNode[]
  edges: Edge[]
}

// ==================== UI 状态 ====================

/**
 * 画布视图状态
 */
export interface CanvasViewport {
  x: number  // 平移X
  y: number  // 平移Y
  scale: number  // 缩放比例
}

/**
 * 节点工具栏配置
 */
export interface NodeToolbarItem {
  type: NodeType
  label: string
  icon: string
  color: string
}
