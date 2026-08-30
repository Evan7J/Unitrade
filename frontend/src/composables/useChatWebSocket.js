// ============================================================
// useChatWebSocket.js —— WebSocket 聊天连接管理
// ============================================================
// 【通俗理解】
// 这个文件负责管理 WebSocket 连接的"生命周期"：
//   1. 建立连接（connect）：像拨电话号码
//   2. 发送消息（send）：像对着话筒说话
//   3. 接收消息（onmessage）：像听筒听到声音
//   4. 断线重连：像电话断了自动重拨
//   5. 关闭连接（cleanup）：像挂电话
//
// 【WebSocket 和 HTTP 的区别（再说一遍）】
//   HTTP：  前端问 → 后端答，一问一答，像寄信
//   WebSocket：建立连接后，双方随时发消息，像打电话
//
// 【后端对应】
//   ChatEndpoint.java:
//     @ServerEndpoint("/ws/chat/{token}")  ← 注意是 ws:// 不是 http://
//     onOpen()   → 连接建立时触发
//     onMessage() → 收到消息时触发
//     onClose()  → 连接断开时触发
//     onError()  → 出错时触发
// ============================================================

import { ref, onUnmounted } from 'vue'

export function useChatWebSocket() {
  // ws：WebSocket 实例对象，就是那个"电话"
  const ws = ref(null)

  // isConnected：连接状态，true = 已接通，false = 未接通
  const isConnected = ref(false)

  // reconnectTimer：重连定时器
  const reconnectTimer = ref(null)

  // 最大重试次数
  const maxRetries = 5
  let retryCount = 0

  // ============================================================
  // connect：建立 WebSocket 连接
  // ============================================================
  // @param {Function} onMessage - 收到消息时的回调函数
  //   当后端推送消息过来时，会调用这个函数，把消息数据传进去
  //
  // 【执行流程】
  //   1. 从 localStorage 取 token
  //   2. 清理旧连接（如果有的话）
  //   3. new WebSocket('ws://localhost:8080/ws/chat/{token}')
  //     注意：是 ws:// 不是 http://！
  //   4. 注册 onopen / onmessage / onclose / onerror 四个事件
  const connect = (onMessage) => {
    // 第一步：取 token
    const token = localStorage.getItem('token')
    if (!token) return  // 没登录就不连

    // 第二步：清理旧连接
    cleanup()

    try {
      // 第三步：创建 WebSocket 连接
      //   ws://localhost:8080/ws/chat/{token}
      //   后端 @ServerEndpoint("/ws/chat/{token}") 匹配这个路径
      //   {token} 是路径参数，后端 ChatEndpoint 用 @PathParam 接收
      ws.value = new WebSocket(`ws://localhost:8080/ws/chat/${token}`)

      // ============================================================
      // 事件1：onopen —— 连接建立成功
      // ============================================================
      // 类比：电话接通了
      ws.value.onopen = () => {
        isConnected.value = true
        retryCount = 0  // 连接成功，重置重试次数
      }

      // ============================================================
      // 事件2：onmessage —— 收到消息
      // ============================================================
      // 类比：电话那头有人说话，你听到了
      //
      // event.data 是后端发来的 JSON 字符串：
      //   {"id":1,"senderId":2,"content":"你好","productId":null,"messageType":"text","createTime":"..."}
      //
      // 用 JSON.parse() 转成 JS 对象，然后调用 onMessage 回调
      // 回调函数会把消息传给 Chat.vue 页面，页面再追加到消息列表
      ws.value.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)  // JSON 字符串 → JS 对象
          if (onMessage) onMessage(data)       // 通知页面
        } catch {
          // JSON 解析失败，忽略（可能是乱码）
        }
      }

      // ============================================================
      // 事件3：onclose —— 连接断开
      // ============================================================
      // 类比：电话挂断了
      //
      // 断线自动重连（指数退避）：
      //   第1次断开：1秒后重连
      //   第2次断开：2秒后重连
      //   第3次断开：4秒后重连
      //   第4次断开：8秒后重连
      //   第5次断开：15秒后重连
      //   超过5次：放弃重连
      ws.value.onclose = () => {
        isConnected.value = false
        if (retryCount < maxRetries) {
          const delay = Math.min(1000 * Math.pow(2, retryCount), 15000)
          retryCount++
          reconnectTimer.value = setTimeout(() => connect(onMessage), delay)
        }
      }

      // ============================================================
      // 事件4：onerror —— 连接出错
      // ============================================================
      // 出错时主动关闭连接，触发 onclose 进行重连
      ws.value.onerror = () => {
        ws.value?.close()
      }
    } catch {
      // 连接失败
    }
  }

  const send = (receiverId, content, productId = '') => {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) return false

    const msg = JSON.stringify({
      receiverId: Number(receiverId),
      content,
      productId: productId ? Number(productId) : null,
      messageType: 'text'
    })
    ws.value.send(msg)
    return true
  }

  // ============================================================
  // cleanup：关闭连接并清理资源
  // ============================================================
  const cleanup = () => {
    // 清除重连定时器
    if (reconnectTimer.value) {
      clearTimeout(reconnectTimer.value)
      reconnectTimer.value = null
    }
    // 关闭 WebSocket 连接
    if (ws.value) {
      try { ws.value.close() } catch {}
      ws.value = null
    }
    isConnected.value = false
  }

  // 组件卸载时自动清理（比如离开聊天页面）
  onUnmounted(cleanup)

  // 导出给 Chat.vue 使用
  return { ws, isConnected, connect, send, cleanup }
}