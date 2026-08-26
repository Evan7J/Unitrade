<template>
  <div class="profile-page">
    <div class="card">
      <h2>个人中心</h2>
      <el-form :model="form" label-width="80px" v-if="form">
        <el-form-item label="头像">
          <el-upload :action="uploadUrl" :headers="uploadHeaders" :show-file-list="false" :on-success="onAvatarSuccess">
            <el-avatar :size="60" :src="form.avatarUrl" />
          </el-upload>
        </el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" disabled /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="学校"><el-input v-model="form.school" /></el-form-item>
        <el-form-item label="个人简介">
          <el-input v-model="form.bio" type="textarea" :rows="3" maxlength="100" show-word-limit placeholder="介绍一下自己吧～" />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" @click="doSave" :loading="loading">保存</el-button>
          <router-link :to="'/front/user?id=' + form.id" class="ml-3 text-sm text-primary hover:underline">预览我的主页</router-link>
          <router-link to="/front/address" class="ml-3 text-sm text-primary hover:underline">收货地址</router-link>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getProfile, updateProfile } from '../../api/user'
import { ElMessage } from 'element-plus'
const form = ref(null)
const loading = ref(false)
const uploadUrl = '/api/upload/image'
const uploadHeaders = { Authorization: 'Bearer ' + localStorage.getItem('token') }
const onAvatarSuccess = (res) => { if (res.code === 200) form.value.avatarUrl = res.data.url }
const doSave = async () => {
  loading.value = true
  try {
    await updateProfile({
      nickname: form.value.nickname,
      avatarUrl: form.value.avatarUrl,
      school: form.value.school,
      bio: form.value.bio || '',
    })
    ElMessage.success('保存成功')
  } catch (e) {}
  loading.value = false
}
onMounted(async () => { try { form.value = (await getProfile()).data } catch (e) {} })
</script>

<style scoped>
.profile-page { max-width: 600px; margin: 0 auto; }
.card { background: #fff; padding: 30px; border-radius: 8px; }
h2 { margin-bottom: 20px; }
</style>