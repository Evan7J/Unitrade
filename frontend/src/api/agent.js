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