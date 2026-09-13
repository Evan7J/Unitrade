<template>
  <!--
    Assistant.vue —— AI 智能助手对话页面
    功能：用户用自然语言和 AI 对话，AI 能：
      1. 帮搜商品、比价、推荐（导购）
      2. 自动生成商品发布草稿（一键发布助手）
  -->
  <div class="assistant-page">
    <div class="chat-card">
      <!-- 顶部标题栏 -->
      <div class="chat-header">
        <div class="header-title">
          <el-icon :size="18" color="#FF7D00"><ChatDotRound /></el-icon>
          <span>AI 闲置助手</span>
        </div>
        <div class="header-sub">可以帮你找闲置商品，也能一句话生成发布草稿</div>
      </div>

      <!-- 消息滚动区 -->
      <div class="msg-area" ref="msgArea">
        <!-- 空状态：欢迎语 -->
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon">
            <el-icon :size="40" color="#FF7D00"><MagicStick /></el-icon>
          </div>
          <p class="welcome-title">你好，我是 AI 闲置助手</p>
          <p class="welcome-sub">试试对我说：</p>
          <div class="welcome-tags">
            <span class="tag" @click="sendQuick('帮我找一台 2000 以内的平板')">帮我找一台 2000 以内的平板</span>
            <span class="tag" @click="sendQuick('我要卖一台 iPhone 15 128G，用了不到一年，几乎全新，卖 3999')">我要卖一台 iPhone 15 128G</span>
            <span class="tag" @click="sendQuick('有哪些商品分类？')">有哪些商品分类？</span>
          </div>
        </div>

        <!-- 消息列表 -->
        <div
          v-for="(msg, i) in messages"
          :key="i"
          class="msg-row"
          :class="msg.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <div class="avatar" :class="msg.role === 'user' ? 'avatar-user' : 'avatar-ai'">
            <el-icon :size="16"><component :is="msg.role === 'user' ? 'User' : 'ChatDotRound'" /></el-icon>
          </div>
          <div class="bubble-box">
            <div class="bubble">{{ msg.content }}</div>

            <!-- 搜索到的商品卡片列表（可点击跳详情） -->
            <div v-if="msg.products && msg.products.length" class="product-list">
              <div
                v-for="p in msg.products"
                :key="p.id"
                class="product-card"
                @click="goDetail(p.id)"
              >
                <div class="product-cover">
                  <img :src="p.coverImage || '/vite.svg'" :alt="p.title" @error="e => e.target.src = '/vite.svg'" />
                </div>
                <div class="product-info">
                  <p class="product-title">{{ p.title }}</p>
                  <div class="product-meta">
                    <span class="product-price">¥{{ p.price }}</span>
                    <span class="product-condition">{{ conditionText(p.productCondition) }}</span>
                  </div>
                </div>
                <div class="product-go">
                  <el-icon :size="14"><ArrowRight /></el-icon>
                </div>
              </div>
            </div>

            <!-- 发布草稿卡片 -->
            <div v-if="msg.draft" class="draft-card">
              <div class="draft-title">
                <el-icon :size="14"><DocumentChecked /></el-icon>
                <span>已生成发布草稿，请确认</span>
              </div>
              <div class="draft-row"><span class="draft-label">标题</span>{{ msg.draft.title }}</div>
              <div class="draft-row"><span class="draft-label">售价</span>¥{{ msg.draft.price }}</div>
              <div class="draft-row" v-if="msg.draft.originalPrice">
                <span class="draft-label">原价</span>¥{{ msg.draft.originalPrice }}
              </div>
              <div class="draft-row">
                <span class="draft-label">成色</span>{{ conditionText(msg.draft.productCondition) }}
              </div>
              <div class="draft-row" v-if="msg.draft.description">
                <span class="draft-label">描述</span>{{ msg.draft.description }}
              </div>
              <el-button type="warning" size="small" class="draft-btn" @click="goPublish(msg.draft)">
                去发布
              </el-button>
            </div>
          </div>
        </div>

        <!-- 正在输入提示 -->
        <div v-if="loading" class="msg-row is-ai">
          <div class="avatar avatar-ai">
            <el-icon :size="16"><ChatDotRound /></el-icon>
          </div>
          <div class="bubble-box">
            <div class="bubble typing">正在思考…</div>
          </div>
        </div>
      </div>

      <!-- 底部输入区 -->
      <div class="input-bar">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="描述你想找或想卖的商品…"
          @keydown.enter.prevent="handleSend"
        />
        <el-button type="warning" :loading="loading" @click="handleSend">发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { agentStream } from '../../api/agent'

const router = useRouter()

// 消息列表，每条：{ role: 'user'|'assistant', content, draft? }
const messages = ref([])
const input = ref('')
const loading = ref(false)
const msgArea = ref(null)
// 当前会话ID：首次对话后端生成返回，之后带上即可续接多轮上下文
const sessionId = ref(null)

// 成色数字转文字
const conditionText = (c) => {
  const map = { 1: '全新', 2: '几乎全新', 3: '轻微使用', 4: '明显使用' }
  return map[c] || '未填写'
}

// 滚动到底部
const scrollToBottom = () => {
  nextTick(() => {
    const el = msgArea.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 发送消息（流式打字机）
const handleSend = async () => {
  const text = input.value.trim()
  if (!text || loading.value) return

  // 追加用户消息
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  scrollToBottom()

  // 先推一条空的 AI 消息，流式内容逐步累积进去
  const aiMsg = reactive({ role: 'assistant', content: '', draft: null, products: null })
  messages.value.push(aiMsg)
  loading.value = true

  try {
    await agentStream(text, sessionId.value, {
      onToken: (tok) => {
        aiMsg.content += tok
        scrollToBottom()
      },
      // 流结束后到达：携带会话ID/草稿/商品卡片
      onMeta: (meta) => {
        if (!meta) return
        if (meta.sessionId) sessionId.value = meta.sessionId
        aiMsg.draft = meta.draft || null
        aiMsg.products = meta.products || null
        if (aiMsg.draft || aiMsg.products) scrollToBottom()
      },
    })
    if (!aiMsg.content) aiMsg.content = '抱歉，我没理解你的意思。'
  } catch (e) {
    aiMsg.content = aiMsg.content || '网络开小差了，请稍后再试。'
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// 跳转到商品详情页
const goDetail = (id) => {
  router.push(`/front/goodsDetail?id=${id}`)
}

// 点击欢迎语快捷标签
const sendQuick = (text) => {
  input.value = text
  handleSend()
}

// 去发布：把草稿存到 sessionStorage，跳转发布页回填
const goPublish = (draft) => {
  sessionStorage.setItem('assistantDraft', JSON.stringify(draft))
  router.push('/front/publish')
}

onMounted(() => {
  scrollToBottom()
})
</script>

<style scoped>
.assistant-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 16px;
}

.chat-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  height: calc(100vh - 160px);
  min-height: 480px;
  overflow: hidden;
}

/* 顶部 */
.chat-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}
.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.header-sub {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

/* 消息区 */
.msg-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #F7F8FA;
}

/* 欢迎语 */
.welcome {
  text-align: center;
  padding: 40px 0;
}
.welcome-icon {
  margin-bottom: 12px;
}
.welcome-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}
.welcome-sub {
  font-size: 13px;
  color: #999;
  margin: 12px 0;
}
.welcome-tags {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.tag {
  display: inline-block;
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 20px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}
.tag:hover {
  color: #FF7D00;
  border-color: #FF7D00;
  background: #FFF8F0;
}

/* 消息行 */
.msg-row {
  display: flex;
  margin-bottom: 16px;
  gap: 10px;
}
.msg-row.is-user {
  flex-direction: row-reverse;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
}
.avatar-user { background: #B0B8C4; }
.avatar-ai { background: #FF7D00; }

.bubble-box {
  max-width: 70%;
}
.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.is-user .bubble {
  background: #FF7D00;
  color: #fff;
  border-top-right-radius: 2px;
}
.is-ai .bubble {
  background: #fff;
  color: #333;
  border-top-left-radius: 2px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.bubble.typing {
  color: #bbb;
}

/* 商品卡片列表 */
.product-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.product-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 8px 10px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.product-card:hover {
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
  border-color: #FFD9B3;
}
.product-cover {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
  background: #f5f5f5;
}
.product-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.product-info {
  flex: 1;
  min-width: 0;
}
.product-title {
  font-size: 13px;
  color: #333;
  margin: 0 0 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.product-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.product-price {
  color: #FF7D00;
  font-weight: 600;
  font-size: 14px;
}
.product-condition {
  font-size: 12px;
  color: #999;
}
.product-go {
  color: #bbb;
  flex-shrink: 0;
}
/* 草稿卡片 */
.draft-card {
  margin-top: 8px;
  background: #fff;
  border: 1px solid #FFE0C2;
  border-radius: 8px;
  padding: 12px 14px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.draft-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #FF7D00;
  margin-bottom: 8px;
}
.draft-row {
  font-size: 13px;
  color: #555;
  line-height: 1.8;
  display: flex;
}
.draft-label {
  width: 40px;
  color: #999;
  flex-shrink: 0;
}
.draft-btn {
  margin-top: 10px;
}

/* 底部输入 */
.input-bar {
  display: flex;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  background: #fff;
  align-items: flex-end;
}

@media (max-width: 768px) {
  .chat-card { height: calc(100vh - 100px); }
  .bubble-box { max-width: 82%; }
}
</style>