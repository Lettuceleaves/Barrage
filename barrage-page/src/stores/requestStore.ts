/**
 * Barrage 火力网压测引擎 - 请求管理状态存储（全局单例）
 */

import { reactive, watch } from 'vue'
import type { HttpMethod, BodyType, Header, TestConfig, TreeNode } from '../types'

// 导出类型供其他模块使用
export type { HttpMethod, BodyType, Header, TestConfig, TreeNode }

// ==================== 全局状态（单例） ====================

const STORAGE_KEY = 'barrage_request_tree'

// 使用 reactive 创建响应式根节点（全局单例）
const rootNode = reactive<TreeNode>({
  id: 'root',
  name: '根目录',
  type: 'folder',
  path: '/',
  children: []
})

// ==================== 工具函数 ====================

/**
 * 生成唯一 ID
 */
const generateId = (): string => {
  return `${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

/**
 * 验证名称合法性
 */
const validateName = (name: string): { valid: boolean; error?: string } => {
  if (!name || !name.trim()) {
    return { valid: false, error: '名称不能为空' }
  }

  if (name.startsWith('/')) {
    return { valid: false, error: '名称不能以 / 开头' }
  }

  if (name.includes('/')) {
    return { valid: false, error: '名称不能包含 / 字符' }
  }

  return { valid: true }
}

/**
 * 从 localStorage 加载数据
 */
const loadFromStorage = (): void => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      const data = JSON.parse(stored)
      // 将加载的数据复制到 reactive 对象中
      Object.assign(rootNode, data)
      console.log('✅ 已从本地存储加载请求树', rootNode)
    }
  } catch (error) {
    console.error('❌ 加载请求树失败', error)
  }
}

/**
 * 保存到 localStorage
 */
const saveToStorage = (): void => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(rootNode))
    console.log('✅ 已保存请求树到本地存储')
  } catch (error) {
    console.error('❌ 保存请求树失败', error)
  }
}

/**
 * 根据路径查找节点
 */
const findNodeByPath = (path: string, node: TreeNode = rootNode): TreeNode | null => {
  if (node.path === path) return node

  if (node.children) {
    for (const child of node.children) {
      const found = findNodeByPath(path, child)
      if (found) return found
    }
  }

  return null
}

/**
 * 根据 ID 查找节点
 */
const findNodeById = (id: string, node: TreeNode = rootNode): TreeNode | null => {
  if (node.id === id) return node

  if (node.children) {
    for (const child of node.children) {
      const found = findNodeById(id, child)
      if (found) return found
    }
  }

  return null
}

/**
 * 查找节点的父节点
 */
const findParentNode = (targetId: string, node: TreeNode = rootNode): TreeNode | null => {
  if (node.children) {
    for (const child of node.children) {
      if (child.id === targetId) return node
      const found = findParentNode(targetId, child)
      if (found) return found
    }
  }
  return null
}

/**
 * 构建完整路径
 */
const buildPath = (parentPath: string, name: string): string => {
  if (parentPath === '/') {
    return `/${name}`
  }
  return `${parentPath}/${name}`
}

/**
 * 检查名称是否在父节点中重复
 */
const isDuplicateName = (parentPath: string, name: string): boolean => {
  const parent = findNodeByPath(parentPath)
  if (!parent || !parent.children) return false

  return parent.children.some(child => child.name === name)
}

// ==================== API 方法 ====================

/**
 * 创建文件夹
 */
export const createFolder = (parentPath: string, folderName: string): TreeNode | null => {
  // 验证名称
  const validation = validateName(folderName)
  if (!validation.valid) {
    console.error('❌', validation.error)
    alert(validation.error)
    return null
  }

  const parent = findNodeByPath(parentPath)
  if (!parent || parent.type !== 'folder') {
    console.error('❌ 父节点不存在或不是文件夹')
    return null
  }

  // 检查重名
  if (isDuplicateName(parentPath, folderName)) {
    console.error('❌ 文件夹名称已存在')
    alert('文件夹名称已存在')
    return null
  }

  const newFolder: TreeNode = {
    id: generateId(),
    name: folderName,
    type: 'folder',
    path: buildPath(parentPath, folderName),
    children: []
  }

  if (!parent.children) {
    parent.children = []
  }

  parent.children.push(newFolder)
  saveToStorage()

  console.log('✅ 创建文件夹', newFolder)
  return newFolder
}

/**
 * 创建请求
 */
export const createRequest = (parentPath: string, requestName: string, config: TestConfig): TreeNode | null => {
  // 验证名称
  const validation = validateName(requestName)
  if (!validation.valid) {
    console.error('❌', validation.error)
    alert(validation.error)
    return null
  }

  const parent = findNodeByPath(parentPath)
  if (!parent || parent.type !== 'folder') {
    console.error('❌ 父节点不存在或不是文件夹')
    return null
  }

  // 检查重名
  if (isDuplicateName(parentPath, requestName)) {
    console.error('❌ 请求名称已存在')
    alert('请求名称已存在')
    return null
  }

  const newRequest: TreeNode = {
    id: generateId(),
    name: requestName,
    type: 'request',
    path: buildPath(parentPath, requestName),
    config
  }

  if (!parent.children) {
    parent.children = []
  }

  parent.children.push(newRequest)
  saveToStorage()

  console.log('✅ 创建请求', newRequest)
  return newRequest
}

/**
 * 重命名节点
 */
export const renameNode = (nodeId: string, newName: string): boolean => {
  // 验证名称
  const validation = validateName(newName)
  if (!validation.valid) {
    console.error('❌', validation.error)
    alert(validation.error)
    return false
  }

  const node = findNodeById(nodeId)
  if (!node || node.id === 'root') {
    console.error('❌ 节点不存在或不允许重命名根节点')
    return false
  }

  const parent = findParentNode(nodeId)
  if (!parent) {
    console.error('❌ 找不到父节点')
    return false
  }

  // 检查重名
  if (parent.children && parent.children.some(child => child.id !== nodeId && child.name === newName)) {
    console.error('❌ 名称已存在')
    alert('名称已存在')
    return false
  }

  const oldPath = node.path
  node.name = newName
  node.path = buildPath(parent.path, newName)

  // 递归更新子节点路径
  const updateChildrenPath = (n: TreeNode, oldPrefix: string, newPrefix: string) => {
    if (n.children) {
      n.children.forEach(child => {
        child.path = child.path.replace(oldPrefix, newPrefix)
        updateChildrenPath(child, oldPrefix, newPrefix)
      })
    }
  }

  updateChildrenPath(node, oldPath, node.path)
  saveToStorage()

  console.log('✅ 重命名节点', node)
  return true
}

/**
 * 移动节点
 */
export const moveNode = (nodeId: string, targetParentPath: string): boolean => {
  const node = findNodeById(nodeId)
  if (!node || node.id === 'root') {
    console.error('❌ 节点不存在或不允许移动根节点')
    return false
  }

  const oldParent = findParentNode(nodeId)
  const newParent = findNodeByPath(targetParentPath)

  if (!oldParent || !newParent || newParent.type !== 'folder') {
    console.error('❌ 父节点无效')
    return false
  }

  // 不能移动到自己或自己的子节点
  if (newParent.path.startsWith(node.path)) {
    console.error('❌ 不能移动到自己或子节点')
    return false
  }

  // 检查重名
  if (isDuplicateName(targetParentPath, node.name)) {
    console.error('❌ 目标位置已存在同名项')
    alert('目标位置已存在同名项')
    return false
  }

  // 从旧父节点移除
  if (oldParent.children) {
    const index = oldParent.children.findIndex(c => c.id === nodeId)
    if (index !== -1) {
      oldParent.children.splice(index, 1)
    }
  }

  // 添加到新父节点
  if (!newParent.children) {
    newParent.children = []
  }
  newParent.children.push(node)

  // 更新路径
  const oldPath = node.path
  node.path = buildPath(targetParentPath, node.name)

  // 递归更新子节点路径
  const updateChildrenPath = (n: TreeNode, oldPrefix: string, newPrefix: string) => {
    if (n.children) {
      n.children.forEach(child => {
        child.path = child.path.replace(oldPrefix, newPrefix)
        updateChildrenPath(child, oldPrefix, newPrefix)
      })
    }
  }

  updateChildrenPath(node, oldPath, node.path)
  saveToStorage()

  console.log('✅ 移动节点', node)
  return true
}

/**
 * 删除节点
 */
export const deleteNode = (nodeId: string): boolean => {
  if (nodeId === 'root') {
    console.error('❌ 不允许删除根节点')
    return false
  }

  const parent = findParentNode(nodeId)
  if (!parent || !parent.children) {
    console.error('❌ 找不到父节点')
    return false
  }

  const index = parent.children.findIndex(c => c.id === nodeId)
  if (index !== -1) {
    parent.children.splice(index, 1)
    saveToStorage()
    console.log('✅ 删除节点')
    return true
  }

  return false
}

/**
 * 获取根节点（返回响应式对象）
 */
export const getRootNode = () => rootNode

/**
 * 获取所有文件夹（用于选择器）
 */
export const getAllFolders = (node: TreeNode = rootNode): TreeNode[] => {
  const folders: TreeNode[] = []

  if (node.type === 'folder') {
    folders.push(node)
  }

  if (node.children) {
    node.children.forEach(child => {
      folders.push(...getAllFolders(child))
    })
  }

  return folders
}

/**
 * 根据路径获取请求配置
 */
export const getRequestConfig = (path: string): TestConfig | null => {
  const node = findNodeByPath(path)
  if (node && node.type === 'request' && node.config) {
    return node.config
  }
  return null
}

// ==================== 初始化 ====================

// 加载数据
loadFromStorage()

// 监听变化并自动保存（深度监听）
watch(() => rootNode, () => {
  saveToStorage()
}, { deep: true })

console.log('✅ 请求管理系统已初始化（全局单例模式）')
