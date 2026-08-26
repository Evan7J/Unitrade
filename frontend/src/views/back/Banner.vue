<template>
  <div class="page">
    <h2>轮播图管理</h2>
    <el-button type="primary" @click="openAdd">新增轮播图</el-button>

    <el-table :data="list" style="margin-top:15px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column label="预览" width="140">
        <template #default="{ row }">
          <img :src="row.imageUrl" :style="{ objectPosition: row.objectPosition || 'center' }" style="width:120px;height:70px;object-fit:cover;border-radius:4px" />
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" />
      <el-table-column prop="createTime" label="时间" width="160" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="isEdit ? '编辑轮播图' : '新增轮播图'" v-model="dialogVisible" width="520px">
      <el-form :model="form">
        <el-form-item label="图片">
          <el-input v-model="form.imageUrl" placeholder="图片URL" />
          <el-upload :action="uploadUrl" :headers="uploadHeaders" :show-file-list="false" :on-success="onUpload" style="margin-top:6px">
            <el-button size="small">上传新图片</el-button>
          </el-upload>
        </el-form-item>

        <el-form-item label="图片位置调整">
          <div class="pos-preview">
            <img :src="form.imageUrl || '/vite.svg'" :style="{ objectPosition: posStyle }" class="pos-preview-img" />
          </div>
          <div class="pos-controls">
            <div class="pos-row">
              <span class="pos-label">左 ← → 右</span>
              <el-slider v-model="posX" :min="0" :max="100" :step="5" show-input class="pos-slider" />
            </div>
            <div class="pos-row">
              <span class="pos-label">上 ↑ → 下</span>
              <el-slider v-model="posY" :min="0" :max="100" :step="5" show-input class="pos-slider" />
            </div>
            <el-button size="small" @click="posX = 50; posY = 50">重置居中</el-button>
          </div>
        </el-form-item>

        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({ imageUrl: '', sortOrder: 0, objectPosition: 'center' })
const posX = ref(50)
const posY = ref(50)

const uploadUrl = '/api/upload/image'
const uploadHeaders = { Authorization: 'Bearer ' + localStorage.getItem('token') }

// CSS object-position 字符串
const posStyle = computed(() => `${posX.value}% ${posY.value}%`)

const onUpload = (res) => { if (res.code === 200) form.value.imageUrl = res.data.url }

const openAdd = () => {
  form.value = { imageUrl: '', sortOrder: 0, objectPosition: 'center' }
  posX.value = 50; posY.value = 50
  isEdit.value = false; dialogVisible.value = true
}

const openEdit = (row) => {
  form.value = { ...row }
  const pos = (row.objectPosition || '50% 50%').replace(/%/g, '').split(/\s+/)
  posX.value = parseInt(pos[0]) || 50
  posY.value = parseInt(pos[1]) || 50
  isEdit.value = true; dialogVisible.value = true
}

const fetchData = async () => {
  try { list.value = (await request.get('/banner/list')).data || [] } catch {}
}

const doSave = async () => {
  form.value.objectPosition = posStyle.value
  try {
    if (isEdit.value) await request.put('/banner/update', form.value)
    else await request.post('/banner/save', form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false; fetchData()
  } catch {}
}

const doDel = async (id) => {
  try { await request.delete('/banner/delete/' + id); ElMessage.success('已删除'); fetchData() } catch {}
}

onMounted(fetchData)
</script>

<style scoped>
.page { padding: 20px; }
h2 { margin-bottom: 15px; }

.pos-preview {
  width: 100%; height: 180px; border-radius: 8px;
  overflow: hidden; background: #f0f0f0; margin-bottom: 12px;
}
.pos-preview-img {
  width: 100%; height: 100%; object-fit: cover;
}

.pos-controls {
  width: 100%;
}
.pos-row {
  display: flex; align-items: center; gap: 12px; margin-bottom: 8px;
}
.pos-label {
  font-size: 12px; color: #999; width: 80px; flex-shrink: 0;
}
.pos-slider {
  flex: 1;
}
</style>
