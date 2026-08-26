<template>
  <!--
    MsgBubble - 消息气泡组件
    核心规则：**只通过 isSelf 判断左右位置**
    - isSelf === true  → 靠右，暖橙#FF7D00背景，白字，三角朝右
    - isSelf === false → 靠左，浅灰#F5F7FA背景，黑字#333，三角朝左
    senderType 仅做身份标签展示，不参与布局判断
  -->
  <div
    class="msg-row"
    :class="msg.isSelf ? 'msg-self' : 'msg-other'"
  >
    <!-- ===== 对方消息：头像在左，气泡在右 ===== -->
    <template v-if="!msg.isSelf">
      <el-avatar :size="34" :src="avatar" class="msg-avatar shrink-0">
        <el-icon :size="16"><User /></el-icon>
      </el-avatar>
      <div class="msg-main">
        <div class="msg-bubble bubble-other">
          {{ msg.content }}
        </div>
        <span class="msg-time time-other">{{ formatTime(msg.msgTime) }}</span>
      </div>
    </template>

    <!-- ===== 自己消息：气泡在左，头像在右 ===== -->
    <template v-else>
      <div class="msg-main msg-main-self">
        <div class="msg-bubble bubble-self">
          {{ msg.content }}
        </div>
        <!-- 时间 + 已读状态 -->
        <div class="msg-meta-self">
          <span class="msg-read-status" v-if="msg.readStatus === 'READ'">已读</span>
          <span class="msg-read-status is-unread" v-else>未读</span>
          <span class="msg-time">{{ formatTime(msg.msgTime) }}</span>
        </div>
      </div>
      <el-avatar :size="34" :src="avatar" class="msg-avatar shrink-0">
        <el-icon :size="16"><User /></el-icon>
      </el-avatar>
    </template>
  </div>
</template>

<script setup>
/*
 * Props:
 * msg      - 消息对象 { msgId, content, msgTime, senderId, senderType, isSelf, readStatus }
 * avatar   - 对方头像 URL（自己的消息显示自己头像，通过 isSelf 区分布局）
 */
defineProps({
  msg: { type: Object, required: true },
  avatar: { type: String, default: '' },
})

/*
 * 聊天消息时间格式化规则：
 * - 今天 → 只显示 HH:mm
 * - 今年但不是今天 → 显示 MM/DD（月/日）
 * - 不在今年 → 显示 YYYY/MM/DD（年/月/日）
 */
const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) {
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}/${d.getDate()}`
  }
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}
</script>

<style scoped>
/* 消息行 */
.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

/* 自己消息：靠右 */
.msg-self {
  justify-content: flex-end;
}

/* 对方消息：靠左 */
.msg-other {
  justify-content: flex-start;
}

.msg-avatar {
  border: 1px solid #e8e8e8;
  margin-top: 14px;
}

/* 消息主体（气泡+时间+标签） */
.msg-main {
  max-width: 65%;
  display: flex;
  flex-direction: column;
}

.msg-main-self {
  align-items: flex-end;
}

/* 气泡 */
.msg-bubble {
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.55;
  word-break: break-word;
  transition: opacity 0.2s;
}

/* 对方气泡：浅灰底，左上角直角（三角朝左效果） */
.bubble-other {
  background: #F5F7FA;
  color: #333;
  border-top-left-radius: 4px;
}

/* 自己气泡：暖橙底，右上角直角（三角朝右效果） */
.bubble-self {
  background: #FF7D00;
  color: #fff;
  border-top-right-radius: 4px;
}

/* 时间 */
.msg-time {
  font-size: 11px;
  color: #bbb;
}

.time-other {
  align-self: flex-start;
  margin-top: 3px;
}

/* 自己消息的元信息行（时间+已读状态） */
.msg-meta-self {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  justify-content: flex-end;
}

/* 已读/未读状态：灰色虚化小字 */
.msg-read-status {
  font-size: 10px;
  color: #c0c0c0;
  user-select: none;
}

.msg-read-status.is-unread {
  color: #d0d0d0;
  font-weight: 500;
}
</style>
