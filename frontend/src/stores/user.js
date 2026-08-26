/*
 * userStore - 全局用户状态（Pinia）
 *
 * 核心职责：
 * 1. 持久化存储 loginUserId（当前登录用户ID，刷新不丢失）
 * 2. 聊天模块通过 loginUserId 与消息 senderUserId 比对生成 isSelf
 * 3. 登录后调用 setLoginUser 写入，token 过期/退出时调用 clearUser 清除
 *
 * 设计思路（防止身份错乱）：
 * isSelf 的判断唯一依据是 senderUserId === loginUserId
 * loginUserId 存储在 Pinia + localStorage 中，页面刷新后自动恢复
 * 关闭聊天窗口重开时，历史消息重新拉取，isSelf 重新动态计算
 * 不依赖 senderType、不缓存 isSelf、不本地存消息身份
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  /*
   * 初始化 loginUserId（双重保障）：
   * 1. 优先从 localStorage 'loginUserId' 读取（新登录流程写入）
   * 2. 兜底从 JWT token 解析（兼容旧登录流程，老用户首次使用 store 也能正确初始化）
   */
  const initUserId = () => {
    const saved = localStorage.getItem('loginUserId')
    if (saved) return Number(saved)
    // 兜底：从 token 解析（老用户可能只有 token，没有单独存 loginUserId）
    const token = localStorage.getItem('token')
    if (token) {
      try {
        const payload = JSON.parse(atob(token.split('.')[1]))
        const uid = Number(payload.sub)
        if (uid) {
          localStorage.setItem('loginUserId', String(uid))
          return uid
        }
      } catch {}
    }
    return null
  }

  const loginUserId = ref(initUserId())

  const isLoggedIn = computed(() => !!loginUserId.value)

  /*
   * 登录成功后调用：设置全局 loginUserId
   * @param {string} token - JWT token（从中解析 userId）
   */
  const setLoginUser = (token) => {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]))
      const uid = Number(payload.sub)
      loginUserId.value = uid
      localStorage.setItem('loginUserId', String(uid))
    } catch {
      // token 解析失败，不设置
    }
  }

  /*
   * 退出登录 / token 过期时调用
   */
  const clearUser = () => {
    loginUserId.value = null
    localStorage.removeItem('loginUserId')
  }

  return { loginUserId, isLoggedIn, setLoginUser, clearUser }
})
