<!--
  ============================================================
  Login.vue —— 登录页面
  ============================================================
  【通俗理解】
  一个 .vue 文件就是一个"页面组件"。
  它由三部分组成：template（长相）、script（行为）、style（打扮）
  ============================================================
-->
<template>
  <!--
    最外层 div，class="auth-page" 是 CSS 样式名
    作用：让整个页面居中显示，背景灰色
  -->
  <div class="auth-page">
    <!--
      登录卡片，白色背景，圆角阴影
    -->
    <div class="card">
      <!-- 标题 -->
      <h2>登录</h2>

      <!--
        ========================================
        el-form：Element Plus 的表单组件
        ========================================
        :model="form"   → 表单数据绑到 form 这个 JS 变量上
        :rules="rules"  → 表单校验规则（比如"手机号必填"）
        ref="formRef"   → 给表单起个名字，后面 JS 里用 formRef 来操作它
        @keyup.enter    → 按回车键时自动触发 doLogin 函数
        label-width="0" → 表单项前面的标签宽度设为0（我们不显示标签）
      -->
      <el-form
        :model="form"
        :rules="rules"
        ref="formRef"
        label-width="0"
        @keyup.enter="doLogin"
      >
        <!--
          ========================================
          第一行：手机号输入框
          ========================================
          el-form-item：表单里的一行
            prop="phone" → 关联校验规则里的 phone 规则
          el-input：输入框
            v-model="form.phone"  → 双向绑定！
              输入框里打什么字，form.phone 就自动变成什么
              反过来，form.phone 变了，输入框也自动更新
              这就是 Vue 的"双向数据绑定"
            placeholder="请输入手机号" → 输入框里的灰色提示文字
            maxlength="11" → 最多输入11个字符
        -->
        <el-form-item prop="phone">
          <el-input
            v-model="form.phone"
            placeholder="请输入手机号"
            maxlength="11"
          />
        </el-form-item>

        <!--
          ========================================
          第二行：密码输入框
          ========================================
          type="password"     → 密码类型，输入的内容显示为圆点
          show-password       → 右边有个小眼睛图标，点击可切换明文/密文
          placeholder="请输入密码"
        -->
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>

        <!--
          ========================================
          第三行：登录按钮
          ========================================
          type="warning"     → 橙色按钮（Element Plus 的 warning 类型是橙色）
          @click="doLogin"   → 点击按钮时，调用 doLogin 函数
          :loading="loading" → 加载中状态，为 true 时按钮转圈不可点击
          class="submit-btn" → 自定义 CSS 样式
        -->
        <el-form-item>
          <el-button
            type="warning"
            @click="doLogin"
            :loading="loading"
            class="submit-btn"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 底部提示：没有账号？去注册 -->
      <!-- router-link 是 Vue Router 提供的跳转链接，to="/register" 跳转到注册页 -->
      <p class="tip">
        没有账号？<router-link to="/register">去注册</router-link>
      </p>
    </div>
  </div>
</template>

<!--
  ============================================================
  script setup：页面行为逻辑
  ============================================================
  【大白话】这里写 JS 代码，控制页面"做什么"
  比如：点击登录按钮后，要做什么动作？
  这部分下个步骤详细讲解，这里先写最基础的变量定义
-->
<script setup>
// ============================================================
// 导入依赖
// ============================================================
// ref：创建一个"响应式"变量，值变了页面自动更新
// reactive：创建一个"响应式"对象，和 ref 类似，但用于对象
import { ref, reactive } from 'vue'

// useRouter：Vue Router 提供的函数，用来做页面跳转（比如登录成功后跳首页）
import { useRouter } from 'vue-router'

// login：从 api/user.js 导入的登录接口函数
import { login } from '../api/user'

// useUserStore：Pinia 全局仓库，用来存登录用户信息
import { useUserStore } from '../stores/user'

// ElMessage：Element Plus 的消息提示，用来弹出"登录成功"/"登录失败"的提示框
import { ElMessage } from 'element-plus'

// ============================================================
// 初始化变量
// ============================================================
// router：路由器对象，调用 router.push('/home') 就能跳转到首页
const router = useRouter()

// userStore：用户状态仓库，调用 userStore.setLoginUser(token) 存用户信息
const userStore = useUserStore()

// formRef：表单的引用，后面用来做表单校验
const formRef = ref(null)

// form：表单数据对象
//   reactive({}) 创建一个响应式对象
//   初始值：phone 和 password 都是空字符串
//   当用户在输入框里打字时，v-model 会自动更新这里的值
const form = reactive({
  phone: '',     // 手机号
  password: ''   // 密码
})

// loading：按钮加载状态
//   false → 按钮正常显示"登录"
//   true  → 按钮转圈，显示"登录中..."，不可点击
const loading = ref(false)

// ============================================================
// 表单校验规则
// ============================================================
// rules 对象里的 key（phone、password）对应 el-form-item 的 prop 属性
// 每个规则是一个数组，可以有多个校验条件
const rules = {
  // 手机号校验：必填，失去焦点时触发校验
  phone: [
    {
      required: true,                              // 必填
      message: '请输入手机号',                       // 不填时的提示文字
      trigger: 'blur'                              // 触发时机：blur = 光标离开输入框时
    }
  ],
  // 密码校验：必填
  password: [
    {
      required: true,                              // 必填
      message: '请输入密码',                         // 不填时的提示文字
      trigger: 'blur'                              // 光标离开时触发
    }
  ]
}

// ============================================================
// doLogin 函数：登录按钮点击后执行
// ============================================================
// 【下个步骤详细讲解】
const doLogin = async () => {
  // 第一步：表单校验，不通过就直接 return（不往下执行）
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 第二步：开启按钮加载状态
  loading.value = true

  try {
    // 第三步：调用后端登录接口
    //   login(form) 相当于 login({ phone: 'xxx', password: 'xxx' })
    //   函数定义在 api/user.js 里
    const res = await login({ ...form })

    // 第四步：登录成功，存 token 和角色到 localStorage
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('role', res.data.role || 'user')

    // 第五步：存用户信息到 Pinia 全局仓库
    userStore.setLoginUser(res.data.token)

    // 第六步：弹出成功提示
    ElMessage.success('登录成功')

    // 第七步：根据角色跳转不同页面
    //   管理员 → 后台管理页面
    //   普通用户 → 前台首页
    const role = res.data.role || 'user'
    router.push(role === 'admin' ? '/back/dashboard' : '/front/home')
  } catch (e) {
    // 登录失败：不做任何处理
    // 因为 request.js 的响应拦截器已经自动弹出了错误提示
  }

  // 第八步：关闭按钮加载状态
  loading.value = false
}
</script>

<!--
  ============================================================
  style scoped：页面样式
  ============================================================
  scoped 表示这些样式只对当前页面生效，不会影响其他页面
-->
<style scoped>
/* 整个页面的背景：灰色，内容居中 */
.auth-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;           /* 最小高度占满整个屏幕 */
  background: #F5F7FA;        /* 浅灰色背景 */
}

/* 登录卡片：白色背景，圆角，阴影 */
.card {
  background: #fff;
  padding: 40px 36px;          /* 内边距：上下40px，左右36px */
  border-radius: 8px;          /* 圆角 */
  width: 420px;                /* 卡片宽度 */
  box-shadow: 0 2px 16px rgba(0,0,0,0.08); /* 阴影 */
}

/* 标题样式 */
h2 {
  text-align: center;
  margin-bottom: 28px;
  font-size: 22px;
  font-weight: 700;
  color: #333;
}

/* 登录按钮样式 */
.submit-btn {
  width: 100%;                          /* 按钮占满一行 */
  background: #FF7D00 !important;       /* 橙色背景 */
  border-color: #FF7D00 !important;     /* 橙色边框 */
  font-size: 15px;
  height: 42px;
}

/* 鼠标悬停时按钮颜色变深 */
.submit-btn:hover {
  background: #E56E00 !important;
  border-color: #E56E00 !important;
}

/* 底部提示文字 */
.tip {
  text-align: center;
  color: #999;
  font-size: 13px;
}

/* 提示文字里的链接（"去注册"）用橙色 */
.tip a {
  color: #FF7D00;
  font-weight: 500;
}
</style>