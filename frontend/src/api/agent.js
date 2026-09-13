// ============================================================
// api/agent.js —— AI 助手接口方法
// ============================================================
// 【通俗理解】
// 这个文件封装了和 AI 助手对话的接口。
// 后端 AgentChatController 会调用 DeepSeek 大模型，
// 大模型根据你说的话自动决定：
//   1. 帮你搜商品（调用 searchProducts 工具）
//   2. 告诉你有哪些分类（调用 listCategories 工具）
//   3. 帮你生成发布草稿（调用 draftProduct 工具）
//
// 【后端对应】
//   AgentChatController.java:
//     @PostMapping("/chat")
//     public AgentReply chat(@RequestBody Map<String, String> request)
//
// 【返回结构】
//   {
//     "reply": "我已经帮你找到这些商品……",   // 模型的文字回复
//     "draft": { ... }                       // 发布草稿（只有要发布时才不为 null）
//   }
// ============================================================

import request from '../utils/request'

// 和 AI 助手对话
// @param {string} message - 用户输入的自然语言
// @returns {Promise<{reply: string, draft: object|null}>}
export const agentChat = (message) =>
  request.post('/agent/chat', { message }, { timeout: 60000 })

// 和 AI 助手流式对话（SSE 逐字输出）
// 参数：message 用户输入；sessionId 已有会话ID（新会话传 null，后端生成后通过 meta 返回）
// callbacks: { onToken(content), onMeta({sessionId,draft,products}), onError(err) }
// onMeta 在流结束后触发（sessionId 用于续接多轮，draft/products 可能为 null）
export const agentStream = (message, sessionId, { onToken, onMeta, onError } = {}) =>
  new Promise((resolve) => {
    const base = (request.defaults && request.defaults.baseURL) || '/api'
    let meta = null

    fetch(`${base}/agent/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, sessionId }),
    })
      .then((resp) => {
        if (!resp.ok || !resp.body) throw new Error('stream request failed')
        const reader = resp.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''

        const pump = () =>
          reader
            .read()
            .then(({ done, value }) => {
              if (done) {
                onMeta && onMeta(meta)
                resolve()
                return
              }
              buffer += decoder.decode(value, { stream: true })
              buffer = consumeSSE(buffer)
              pump()
            })
            .catch((err) => {
              onError && onError(err)
              onMeta && onMeta(meta)
              resolve()
            })

        pump()
      })
      .catch((err) => {
        onError && onError(err)
        onMeta && onMeta(meta)
        resolve()
      })

    const consumeSSE = (buf) => {
      const parts = buf.split('\n\n')
      parts.slice(0, -1).forEach((part) => {
        part.split('\n').forEach((line) => {
          if (!line.startsWith('data:')) return
          const data = line.slice(5).replace(/^ /, '')
          if (!data) return
          if (data.startsWith('__META__')) {
            meta = JSON.parse(data.slice('__META__'.length))
          } else {
            onToken && onToken(data)
          }
        })
      })
      return parts[parts.length - 1]
    }
  })