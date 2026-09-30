<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="rounded-xl bg-white p-6 shadow-sm">
      <h1 class="text-lg font-semibold text-gray-900">和卖家谈谈价</h1>
      <p class="mt-1 text-sm text-gray-500">商品编号 #{{ productId }}</p>

      <!-- 当前报价 -->
      <div class="mt-5 rounded-lg bg-orange-50 p-4">
        <div class="text-xs text-gray-500">卖家当前报价</div>
        <div class="mt-1 text-3xl font-bold text-orange-600">
          ¥{{ currentQuote != null ? currentQuote : '--' }}
        </div>
        <div class="mt-1 text-xs text-gray-500">
          第 {{ roundNo }} 轮<span v-if="phaseText"> · {{ phaseText }}</span>
        </div>
      </div>

      <!-- 挂起 / 成交提示 -->
      <div v-if="accepted" class="mt-4 rounded-lg bg-green-50 px-4 py-3 text-sm text-green-700">
        已谈成 ¥{{ agreedPrice }}，去下单吧。
      </div>
      <div v-else-if="suspended" class="mt-4 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-700">
        {{ suspendReason || '这个价暂时没法自动处理' }}，已转人工，卖家本人会尽快回复你。
      </div>

      <!-- 议价记录 -->
      <div v-if="history.length" class="mt-5">
        <div class="mb-2 text-sm font-medium text-gray-700">议价记录</div>
        <div class="space-y-2">
          <div v-for="r in history" :key="r.id" class="rounded bg-gray-50 px-3 py-2 text-sm">
            <span class="text-gray-400">R{{ r.roundNo }}</span>
            <span v-if="r.buyerOffer != null" class="ml-2 text-gray-600">你出 ¥{{ r.buyerOffer }}</span>
            <span v-if="r.counterQuote != null" class="ml-2 text-orange-600">对方 ¥{{ r.counterQuote }}</span>
            <div v-if="r.agentReply" class="mt-1 text-gray-500">{{ r.agentReply }}</div>
          </div>
        </div>
      </div>

      <!-- 出价 / 说话 -->
      <template v-if="canBargain">
        <div class="mt-6 flex gap-2">
          <input
            v-model="msgInput"
            type="text"
            placeholder="说点什么，或直接输入你的出价"
            class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-orange-400"
            @keyup.enter="submitMessage"
          />
          <button
            :disabled="loading"
            class="rounded-lg bg-orange-500 px-5 py-2 text-sm font-medium text-white hover:bg-orange-600 disabled:opacity-50"
            @click="submitMessage"
          >
            发送
          </button>
        </div>
        <!--
          ⚠️ 这个提示不是文案装饰：输入框曾经是 type="number"，买家只能填价格，
          于是「你底价多少」「我要投诉」这类消息根本发不出去 ——
          意图识别里 6 类意图永远不会被触发，它就不是"识别不准"，而是"没机会被用上"。
        -->
        <p class="mt-1 text-xs text-gray-400">
          只填数字（如 850）算作出价；填一句话（如「你底价多少」）就是跟卖家说句话。
        </p>
        <div v-if="currentQuote != null" class="mt-2">
          <button :disabled="loading" class="text-sm text-orange-600 underline" @click="acceptQuote">
            接受 ¥{{ currentQuote }} 的价格
          </button>
        </div>
      </template>
      <p v-else-if="!accepted" class="mt-6 text-sm text-gray-400">当前会话无法继续出价。</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as nego from '../../api/negotiation'

const route = useRoute()
const productId = route.params.productId

const sessionNo = ref('')
const currentQuote = ref(null)
const roundNo = ref(0)
const phase = ref('')
const phaseText = ref('')
const suspended = ref(false)
const suspendReason = ref('')
const accepted = ref(false)
const agreedPrice = ref(null)
const history = ref([])
const msgInput = ref('')
const loading = ref(false)

const canBargain = computed(
  () => !!sessionNo.value && phase.value === 'BARGAINING' && !suspended.value && !accepted.value
)

/**
 * 幂等键。
 *
 * 这里有一个容易被写错的地方：messageId 必须在"用户点发送"时生成一次，
 * 并且在**请求失败重试时复用** —— 所以只在成功后才清空。
 * 如果每次点击都新建一个 id，服务端的幂等去重就完全失效了，
 * 用户手抖点两下就会产生两次真实出价。
 */
let pendingMessageId = null

function newMessageId() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return 'm-' + Date.now() + '-' + Math.random().toString(36).slice(2)
}

function applyResult(d) {
  if (!d) return
  sessionNo.value = d.sessionNo || sessionNo.value
  if (d.quote != null) currentQuote.value = d.quote
  if (d.roundNo != null) roundNo.value = d.roundNo
  suspended.value = !!d.suspended
  if (d.suspendReason || d.toast) suspendReason.value = d.suspendReason || d.toast
  if (d.branch === 'ACCEPTED') {
    accepted.value = true
    agreedPrice.value = d.agreedPrice
    phase.value = 'AGREED'
  }
}

async function refresh() {
  if (!sessionNo.value) return
  const [roundsRes, stateRes] = await Promise.all([
    nego.rounds(sessionNo.value),
    nego.state(sessionNo.value)
  ])
  history.value = roundsRes.data || []
  const s = stateRes.data
  if (s) {
    phase.value = s.phase
    phaseText.value = s.phaseText
    if (s.currentQuote != null) currentQuote.value = s.currentQuote
    if (s.roundNo != null) roundNo.value = s.roundNo
    if (s.agreedPrice != null) {
      accepted.value = true
      agreedPrice.value = s.agreedPrice
    }
    suspended.value = s.phase === 'SUSPENDED'
  }
}

async function init() {
  try {
    const res = await nego.start(productId)
    applyResult(res.data)
    await refresh()
  } catch (e) {
    // 响应拦截器已经弹过提示，这里不重复提示
  }
}

/**
 * 从输入里解析出价；解析不出来就返回 null（表示"这只是一句话"）。
 *
 * ⚠️ 规则刻意收得很紧：只有**整句就是一个价格**才算出价
 * （允许"我出 850"/"850 元"/"850"）。
 * 如果写成"句子里有数字就算出价"，那么"用了2年还能便宜点吗"里的 2
 * 会被当成 2 元出价，直接触发越界挂起 —— 误伤比漏判更糟。
 */
function parseOfferYuan(text) {
  const m = text.match(/^\s*(?:我出|出价|出)?\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*(?:元|块|块钱)?\s*$/)
  if (!m) return null
  const v = Number(m[1])
  return v > 0 ? v : null
}

/**
 * 发送一轮：带价就是出价，不带价就是"说句话"。
 *
 * 两者走的是同一个后端接口 —— 服务端对"带了出价"的请求一律先过授权闸门，
 * 所以"前端少传一个价"最坏只会退化成闲聊，不会产生越界成交。
 */
async function submitMessage() {
  const text = msgInput.value.trim()
  if (!text) {
    ElMessage.warning('说点什么，或输入你的出价')
    return
  }
  if (!pendingMessageId) pendingMessageId = newMessageId()
  loading.value = true
  try {
    const payload = { messageId: pendingMessageId, message: text }
    const offer = parseOfferYuan(text)
    if (offer != null) payload.offer = offer
    const res = await nego.offer(sessionNo.value, payload)
    applyResult(res.data)
    pendingMessageId = null // 成功才清空：失败时保留，重试复用同一个 id
    msgInput.value = ''
    await refresh()
  } catch (e) {
    // 保留 pendingMessageId，用户重点一次会被服务端识别为重复而非二次出价
  } finally {
    loading.value = false
  }
}

async function acceptQuote() {
  if (!pendingMessageId) pendingMessageId = newMessageId()
  loading.value = true
  try {
    const res = await nego.accept(sessionNo.value, pendingMessageId)
    applyResult(res.data)
    pendingMessageId = null
    ElMessage.success('价格已确认')
    await refresh()
  } catch (e) {
    // 同上
  } finally {
    loading.value = false
  }
}

onMounted(init)
</script>
