<template>
  <div class="auth-page">
    <div class="card">
      <h2>注册</h2>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="0" @keyup.enter="doRegister">
        <!-- 手机号 -->
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" />
        </el-form-item>

        <!-- 昵称 -->
        <el-form-item prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称（2-12个字）" maxlength="12" />
        </el-form-item>

        <!-- 密码 -->
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请设置密码" show-password />
          <!-- 密码规则灰色提示 -->
          <p class="password-hint">8-20位，需包含大写字母、小写字母和数字</p>
        </el-form-item>

        <el-form-item>
          <el-button type="warning" @click="doRegister" :loading="loading" class="submit-btn">
            注册
          </el-button>
        </el-form-item>
      </el-form>
      <p class="tip">已有账号？<router-link to="/login">去登录</router-link></p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { register } from '../api/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref(null)
const form = reactive({ phone: '', nickname: '', password: '' })
const loading = ref(false)

/*
 * 表单校验规则
 * 手机号：11位中国大陆手机号（1开头，第二位3-9）
 * 密码：8-20位，必须包含大写字母+小写字母+数字
 */
const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的11位手机号', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 12, message: '昵称需2-12个字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { min: 8, max: 20, message: '密码需8-20位', trigger: 'blur' },
    {
      pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,20}$/,
      message: '密码需包含大写字母、小写字母和数字',
      trigger: 'blur',
    },
  ],
}

const doRegister = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await register({ ...form })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch {}
  loading.value = false
}
</script>

<style scoped>
.auth-page {
  display: flex; justify-content: center; align-items: center;
  min-height: 100vh; background: #F5F7FA;
}

.card {
  background: #fff; padding: 40px 36px; border-radius: 8px;
  width: 420px; box-shadow: 0 2px 16px rgba(0,0,0,0.08);
}

h2 {
  text-align: center; margin-bottom: 28px; font-size: 22px;
  font-weight: 700; color: #333;
}

/* 密码规则提示：灰色小字，放在输入框下方 */
.password-hint {
  font-size: 12px; color: #bbb; margin-top: 4px; line-height: 1.4;
}

.submit-btn {
  width: 100%; background: #FF7D00 !important; border-color: #FF7D00 !important;
  font-size: 15px; height: 42px;
}
.submit-btn:hover { background: #E56E00 !important; border-color: #E56E00 !important; }

.tip { text-align: center; color: #999; font-size: 13px; }
.tip a { color: #FF7D00; font-weight: 500; }
</style>
