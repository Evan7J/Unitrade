<template>
  <!--
    编辑商品页
    复用发布页表单结构，预填充已有数据
  -->
  <div class="edit-page">
    <div class="card">
      <h2>编辑商品</h2>
      <el-form :model="form" label-width="100px" v-if="form.id">
        <el-form-item label="商品图片">
          <el-upload
            :action="uploadUrl"
            :headers="uploadHeaders"
            list-type="picture-card"
            :file-list="fileList"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
            :on-remove="onRemove"
            multiple
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
        </el-form-item>
        <el-form-item label="商品名称">
          <el-input v-model="form.title" maxlength="30" show-word-limit />
        </el-form-item>
        <el-form-item label="商品分类">
          <el-select v-model="form.categoryId" placeholder="请选择">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品成色">
          <el-radio-group v-model="form.productCondition">
            <el-radio :value="1">全新</el-radio>
            <el-radio :value="2">几乎全新</el-radio>
            <el-radio :value="3">轻微使用</el-radio>
            <el-radio :value="4">明显使用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="商品描述">
          <el-input v-model="form.description" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="价格">
          <el-input v-model="form.price" type="number" placeholder="0.00" />
        </el-form-item>
        <el-form-item label="原价">
          <el-input v-model="form.originalPrice" type="number" placeholder="选填" />
        </el-form-item>
        <el-form-item label="交易方式">
          <el-radio-group v-model="form.shippingType">
            <el-radio :value="1">无需邮寄（校内面交）</el-radio>
            <el-radio :value="2">付邮邮寄</el-radio>
            <el-radio :value="3">包邮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="邮费" v-if="form.shippingType === 2">
          <el-input v-model="form.shippingFee" type="number" placeholder="请输入邮费金额" />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" @click="doUpdate" :loading="loading">保存修改</el-button>
          <el-button @click="router.back()">取消</el-button>
          <el-button type="danger" plain @click="doOffline" :loading="offlineLoading" class="!ml-auto">
            下架商品
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDetail, update, offline as offlineProduct } from '../../api/product'
import { getList } from '../../api/category'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()

const form = ref({
  id: null, title: '', categoryId: null, productCondition: 1,
  description: '', price: '', originalPrice: '', shippingType: 1, shippingFee: '', images: '',
})
const categories = ref([])
const fileList = ref([])
const loading = ref(false)
const offlineLoading = ref(false)

const uploadUrl = '/api/upload/image'
const uploadHeaders = { Authorization: 'Bearer ' + localStorage.getItem('token') }

// 加载商品已有数据
onMounted(async () => {
  const id = route.query.id
  if (!id) { ElMessage.error('缺少商品ID'); router.back(); return }
  try {
    const res = await getDetail(id)
    const d = res.data
    form.value = {
      id: d.id, title: d.title, categoryId: d.categoryId,
      productCondition: d.productCondition, description: d.description || '',
      price: d.price, originalPrice: d.originalPrice || '',
      shippingType: d.shippingType || 1, shippingFee: d.shippingFee || '',
      images: d.images?.join(',') || '',
    }
    // 构建已上传图片的文件列表（给 el-upload 回显）
    if (d.images) {
      fileList.value = d.images.map((url, i) => ({
        name: `image-${i}`, url, response: { code: 200, data: { url } },
      }))
    }
    categories.value = (await getList()).data || []
  } catch { ElMessage.error('加载失败'); router.back() }
})

const onUploadSuccess = (res) => {
  if (res.code === 200) {
    form.value.images = form.value.images ? form.value.images + ',' + res.data.url : res.data.url
  }
}
const onUploadError = () => ElMessage.error('图片上传失败')
const onRemove = (file) => {
  const url = file.response?.data?.url || file.url
  if (url) {
    form.value.images = form.value.images
      .replace(url, '').replace(',,', ',').replace(/^,|,$/g, '')
  }
}

// 保存修改
const doUpdate = async () => {
  if (!form.value.title || !form.value.categoryId || !form.value.price) {
    ElMessage.warning('请填写必填项'); return
  }
  loading.value = true
  try {
    await update({
      id: form.value.id, title: form.value.title, description: form.value.description,
      price: parseFloat(form.value.price),
      originalPrice: form.value.originalPrice ? parseFloat(form.value.originalPrice) : null,
      productCondition: form.value.productCondition, categoryId: form.value.categoryId,
      shippingType: form.value.shippingType,
      shippingFee: form.value.shippingFee ? parseFloat(form.value.shippingFee) : null,
      images: form.value.images,
    })
    ElMessage.success('修改成功')
    router.push('/front/user?id=' + form.value.id + '&self=1')
  } catch {}
  loading.value = false
}

// 下架商品
const doOffline = async () => {
  try {
    await ElMessageBox.confirm('确认下架该商品？下架后其他用户将无法看到。', '确认下架', {
      confirmButtonText: '确认下架', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  offlineLoading.value = true
  try {
    await offlineProduct(form.value.id)
    ElMessage.success('已下架')
    router.push('/front/user?id=' + form.value.id + '&self=1')
  } catch {}
  offlineLoading.value = false
}
</script>

<style scoped>
.edit-page { max-width: 800px; margin: 0 auto; }
.card { background: #fff; padding: 30px; border-radius: 8px; }
h2 { margin-bottom: 20px; }
</style>
