/**
 * Barrage 火力网压测引擎 - TypeScript 类型定义
 */

// ==================== HTTP 相关类型 ====================

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'
export type BodyType = 'none' | 'json' | 'raw'

export interface Header {
  key: string
  value: string
}

export interface TestConfig {
  method: HttpMethod
  url: string
  timeout: number
  headers: Header[]
  bodyType: BodyType
  body: string
}

export interface TestResult {
  status: number
  statusText: string
  responseTime: number
  headers: Record<string, string>
  body: any
}

// ==================== 树节点相关类型 ====================

export interface TreeNode {
  id: string
  name: string
  type: 'folder' | 'request'
  path: string
  config?: TestConfig
  children?: TreeNode[]
}

// ==================== 页面路由类型 ====================

export type PageName = 'api-test' | 'simulation-test' | 'results' | 'requests' | 'users' | 'settings'

