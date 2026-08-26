<template>
  <div class="address-page">
    <div class="page-header">
      <h2>收货地址管理</h2>
      <el-button type="primary" size="small" @click="showDialog = true; editing = null; resetForm()">添加地址</el-button>
    </div>

    <div v-if="list.length > 0" class="address-list">
      <div v-for="addr in list" :key="addr.id" class="address-card" :class="{ default: addr.isDefault === 1 }">
        <div class="addr-info">
          <div class="addr-header">
            <span class="addr-name">{{ addr.receiverName }}</span>
            <span class="addr-phone">{{ addr.receiverPhone }}</span>
            <el-tag v-if="addr.isDefault === 1" size="small" type="warning">默认</el-tag>
          </div>
          <p class="addr-detail">{{ addr.province }}{{ addr.city }}{{ addr.district }} {{ addr.detail }}</p>
        </div>
        <div class="addr-actions">
          <el-button text size="small" @click="openEdit(addr)">编辑</el-button>
          <el-button text size="small" type="danger" @click="doDelete(addr.id)">删除</el-button>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无地址" />

    <!-- 添加/编辑弹窗 -->
    <el-dialog v-model="showDialog" :title="editing ? '编辑地址' : '添加地址'" width="480px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
        <el-form-item label="收件人" prop="receiverName"><el-input v-model="form.receiverName" /></el-form-item>
        <el-form-item label="手机号" prop="receiverPhone"><el-input v-model="form.receiverPhone" maxlength="11" /></el-form-item>
        <el-form-item label="地区">
          <div class="region-row">
            <el-input v-model="form.province" placeholder="省" />
            <el-input v-model="form.city" placeholder="市" />
            <el-input v-model="form.district" placeholder="区" />
          </div>
        </el-form-item>
        <el-form-item label="详细地址"><el-input v-model="form.detail" /></el-form-item>
        <el-form-item label="默认地址">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getList, save, update, del } from '../../api/address'
import { ElMessage, ElMessageBox } from 'element-plus'

const list = ref([])
const showDialog = ref(false)
const editing = ref(null)
const formRef = ref(null)
const form = ref({ receiverName: '', receiverPhone: '', province: '', city: '', district: '', detail: '', isDefault: 0 })

// 手机号校验规则
const rules = {
  receiverName: [{ required: true, message: '请输入收件人姓名', trigger: 'blur' }],
  receiverPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的11位手机号', trigger: 'blur' },
  ],
}

const resetForm = () => {
  form.value = { receiverName: '', receiverPhone: '', province: '', city: '', district: '', detail: '', isDefault: 0 }
}

const openEdit = (addr) => {
  editing.value = addr
  form.value = { ...addr }
  showDialog.value = true
}

const doSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    if (editing.value) {
      await update({ ...form.value, id: editing.value.id })
      ElMessage.success('修改成功')
    } else {
      await save(form.value)
      ElMessage.success('添加成功')
    }
    showDialog.value = false
    fetchList()
  } catch {}
}

const doDelete = async (id) => {
  try { await ElMessageBox.confirm('确认删除？'); await del(id); ElMessage.success('已删除'); fetchList() } catch {}
}

const fetchList = async () => {
  try { list.value = (await getList()).data || [] } catch {}
}

onMounted(fetchList)
</script>

<style scoped>
.address-page { max-width: 800px; margin: 0 auto; padding: 0 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { font-size: 18px; font-weight: 700; }
.address-list { display: flex; flex-direction: column; gap: 10px; }
.address-card { background: #fff; border-radius: 8px; padding: 16px 20px; display: flex; justify-content: space-between; align-items: center; }
.address-card.default { border: 1px solid #FFD39A; }
.addr-header { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.addr-name { font-weight: 600; font-size: 15px; }
.addr-phone { color: #666; font-size: 13px; }
.addr-detail { font-size: 13px; color: #666; }
.region-row { display: flex; gap: 8px; }
</style>
