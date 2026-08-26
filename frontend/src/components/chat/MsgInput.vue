<!--
  ============================================================
  MsgInput.vue —— 聊天底部输入区组件
  ============================================================
  【通俗理解】
  聊天页面底部的输入框 + 发送按钮。
  用户打字 → 点发送/按回车 → emit('send', 消息内容) → 父组件 Chat.vue 接收到
  → Chat.vue 调用 WebSocket 的 send() 方法 → 消息发到后端
  ============================================================
-->
<template>
  <div class="msg-input-bar">
    <!-- 图片按钮（预留） -->
    <el-button class="upload-btn" circle :icon="Picture" size="small" @click="handleUpload" />

    <!--
      ========================================
      消息输入框
      ========================================
      v-model="text" → 双向绑定输入内容
      type="textarea" → 多行文本（虽然只显示1行，但可以自动扩展）
      @keyup.enter.exact="handleSend" → 按回车键发送
        .exact 修饰符：只有单独按回车才触发，Shift+回车不触发
    -->
    <el-input
      v-model="text"
      placeholder="输入消息..."
      :rows="1"
      type="textarea"
      resize="none"
      class="text-input"
      @keyup.enter.exact="handleSend"
    />

    <!--
      ========================================
      发送按钮
      ========================================
      @click="handleSend" → 点击发送
      :disabled="!canSend" → 输入框为空时按钮灰色不可点
    -->
    <el-button type="primary" size="small" class="send-btn" @click="handleSend" :disabled="!canSend">
      发送
    </el-button>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

// ============================================================
// emit：子组件向父组件传消息的"信使"
// ============================================================
// 【通俗理解】
// 子组件（MsgInput）不能直接操作父组件（Chat.vue）的数据。
// 它只能通过 emit 发信号："爸爸，用户点发送了，消息内容是xxx"。
// 父组件用 @send="handleSendMsg" 监听这个信号。
//
// defineEmits(['send', 'upload']) → 声明这个组件可以发出 'send' 和 'upload' 两种信号
const emit = defineEmits(['send', 'upload'])

// 输入框内容
const text = ref('')

// canSend：计算属性，判断是否可以发送
//   去除首尾空格后，如果内容长度 > 0，就可以发送
//   computed 会自动追踪依赖，text 变了它就自动重新计算
const canSend = computed(() => text.value.trim().length > 0)

// ============================================================
// handleSend：发送消息
// ============================================================
// 【执行流程】
//   1. 取出输入内容，去掉首尾空格
//   2. 如果是空字符串，提示用户并返回
//   3. emit('send', content) → 通知父组件"发送消息，内容是xxx"
//   4. 清空输入框
const handleSend = () => {
  const content = text.value.trim()
  if (!content) {
    ElMessage.warning('不能发送空消息')
    return
  }
  // emit('send', content) 触发父组件 Chat.vue 里的 @send="handleSendMsg"
  // handleSendMsg(content) 会调用 WebSocket 的 send() 方法
  emit('send', content)
  text.value = ''  // 清空输入框
}

// 图片上传（预留）
const handleUpload = () => {
  emit('upload')
}
</script>

<style scoped>
.msg-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 16px;
  background: #f7f7f7;
  border-top: 1px solid #e8e8e8;
  flex-shrink: 0;
}

.upload-btn {
  flex-shrink: 0;
  color: #999;
}

.text-input :deep(.el-textarea__inner) {
  border-radius: 8px;
  background: #fff;
  font-size: 14px;
  padding: 8px 12px;
  line-height: 1.45;
  resize: none;
}

.send-btn {
  flex-shrink: 0;
  min-width: 64px;
  background: #FF7D00 !important;
  border-color: #FF7D00 !important;
  font-size: 13px;
}
.send-btn:hover {
  background: #E56E00 !important;
  border-color: #E56E00 !important;
}
</style>