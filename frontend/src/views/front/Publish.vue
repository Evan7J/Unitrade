<!--
  ============================================================
  Publish.vue —— 发布闲置商品页面
  ============================================================
  【通俗理解】
  用户填写商品信息（标题、价格、分类、描述、图片等），点击发布按钮，
  把数据提交到后端。这个页面涉及两个独立的接口调用：
    1. 图片上传：选图片 → 上传到后端 → 拿到图片 URL
    2. 发布商品：收集所有表单数据 → POST 到后端

  【核心流程】
    选图片 → 上传图片 → 拿到URL存到form.images
    填表单 → 点发布 → 收集form数据 → POST /api/product/publish → 跳转首页
  ============================================================
-->
<template>
  <div class="publish-page">
    <div class="card">
      <h2>发布商品</h2>

      <!--
        ========================================
        el-form：发布表单
        ========================================
        :model="form"  → 表单数据绑定到 form 对象
        label-width="100px" → 标签宽度100px
      -->
      <el-form :model="form" label-width="100px">
        <!--
          ========================================
          第一行：商品图片上传
          ========================================
          el-upload：Element Plus 的文件上传组件
            :action="uploadUrl"   → 上传接口地址 /api/upload/image
            :headers="uploadHeaders" → 上传时带的请求头（含 token）
            list-type="picture-card" → 显示为图片卡片样式
            :on-success="onUploadSuccess" → 上传成功后的回调函数
            :on-error="onUploadError"     → 上传失败后的回调函数
            :on-remove="onRemove"         → 删除图片后的回调函数
            multiple → 允许选择多张图片

          【关键理解】
          这个 el-upload 组件内部自己发请求，不会经过我们封装的 request.js！
          所以需要手动设置 :action 和 :headers。
          但是上传成功后，onSuccess 回调里我们能拿到返回的 URL。
        -->
        <el-form-item label="商品图片">
          <el-upload
            :action="uploadUrl"
            :headers="uploadHeaders"
            list-type="picture-card"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
            :on-remove="onRemove"
            multiple
          >
            <!-- 上传按钮：一个加号图标 -->
            <el-icon><Plus /></el-icon>
          </el-upload>
        </el-form-item>

        <!--
          ========================================
          第二行：商品名称
          ========================================
          v-model="form.title" → 双向绑定到 form.title
          maxlength="30" → 最多30个字符
          show-word-limit → 显示字数统计
        -->
        <el-form-item label="商品名称">
          <el-input
            v-model="form.title"
            maxlength="30"
            show-word-limit
          />
        </el-form-item>

        <!--
          ========================================
          第三行：商品分类（下拉选择）
          ========================================
          el-select：下拉选择框
            v-model="form.categoryId" → 选中的值存到 form.categoryId

          el-option：下拉选项
            v-for="c in categories" → 遍历分类数组
            :key="c.id" → 唯一标识
            :label="c.name" → 显示的文字（"数码产品"）
            :value="c.id" → 选中后存的值（1）
        -->
        <el-form-item label="商品分类">
          <el-select v-model="form.categoryId" placeholder="请选择分类">
            <el-option
              v-for="c in categories"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>

        <!--
          ========================================
          第四行：商品成色（单选）
          ========================================
          el-radio-group：单选按钮组
            v-model="form.productCondition" → 选中的值存到 form.productCondition
          el-radio：单选按钮
            :value="1" → 选中这个按钮时，form.productCondition = 1
        -->
        <el-form-item label="商品成色">
          <el-radio-group v-model="form.productCondition">
            <el-radio :value="1">全新</el-radio>
            <el-radio :value="2">几乎全新</el-radio>
            <el-radio :value="3">轻微使用</el-radio>
            <el-radio :value="4">明显使用</el-radio>
          </el-radio-group>
        </el-form-item>

        <!--
          ========================================
          第五行：商品描述（多行文本）
          ========================================
          type="textarea" → 多行文本输入框
          :rows="4" → 显示4行
        -->
        <el-form-item label="商品描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="描述一下你的商品吧～"
          />
        </el-form-item>

        <!--
          ========================================
          第六行：售价
          ========================================
          type="number" → 数字输入框（只能输入数字）
        -->
        <el-form-item label="售价">
          <el-input
            v-model="form.price"
            type="number"
            placeholder="0.00"
          />
        </el-form-item>

        <!-- 第七行：原价（选填） -->
        <el-form-item label="原价">
          <el-input
            v-model="form.originalPrice"
            type="number"
            placeholder="选填"
          />
        </el-form-item>

        <!--
          ========================================
          第八行：交易方式
          ========================================
          三个选项：无需邮寄、付邮邮寄、包邮
          对应后端 shippingType 字段：1/2/3
        -->
        <el-form-item label="交易方式">
          <el-radio-group v-model="form.shippingType">
            <el-radio :value="1">无需邮寄（校内面交）</el-radio>
            <el-radio :value="2">付邮邮寄</el-radio>
            <el-radio :value="3">包邮</el-radio>
          </el-radio-group>
        </el-form-item>

        <!--
          ========================================
          第九行：邮费（条件显示）
          ========================================
          v-if="form.shippingType === 2" → 只有选"付邮邮寄"时才显示
          这叫"条件渲染"，根据表单状态动态显示/隐藏表单项
        -->
        <el-form-item label="邮费" v-if="form.shippingType === 2">
          <el-input
            v-model="form.shippingFee"
            type="number"
            placeholder="请输入邮费金额"
          />
        </el-form-item>

        <!--
          ========================================
          第十行：发布按钮
          ========================================
          @click="doPublish" → 点击触发发布函数
          :loading="loading" → 提交中按钮转圈
        -->
        <el-form-item>
          <el-button type="warning" @click="doPublish" :loading="loading">
            发布
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
// ============================================================
// 导入依赖
// ============================================================
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

// 发布商品接口
import { publish } from '../../api/product'
// 获取分类列表接口（用于下拉框选项）
import { getList } from '../../api/category'
// 消息提示
import { ElMessage } from 'element-plus'

const router = useRouter()

// ============================================================
// 分类数据（下拉框的选项）
// ============================================================
// 页面加载时从后端获取
const categories = ref([])

// ============================================================
// 表单数据
// ============================================================
// form 对象包含所有表单项的值
// 注意：字段名必须和后端 ProductPublishDTO 的字段名一致！
const form = ref({
  title: '',            // 商品名称
  categoryId: null,     // 分类ID
  productCondition: 1,  // 成色，默认"全新"
  description: '',      // 商品描述
  price: '',            // 售价
  originalPrice: '',    // 原价（选填）
  shippingType: 1,      // 交易方式，默认"无需邮寄"
  shippingFee: '',      // 邮费（选填）
  images: ''            // 图片URL，多张逗号分隔，如 "/uploads/a.jpg,/uploads/b.jpg"
})

// 按钮加载状态
const loading = ref(false)

// ============================================================
// 图片上传配置
// ============================================================
// 【重要】el-upload 组件不会经过 request.js 的拦截器
// 所以需要手动设置上传地址和请求头（含 token）
const uploadUrl = '/api/upload/image'  // 上传接口地址
const uploadHeaders = {
  // 手动从 localStorage 取 token 拼到请求头
  // 因为不经过 request.js 拦截器，token 不会自动带
  Authorization: 'Bearer ' + localStorage.getItem('token')
}

// ============================================================
// 图片上传成功回调
// ============================================================
// 【执行流程】
//   1. 用户选择图片 → el-upload 自动发 POST /api/upload/image
//   2. 后端保存文件，返回 { code: 200, data: { url: "/uploads/abc.jpg" } }
//   3. el-upload 调用 onUploadSuccess(res)
//   4. 从 res.data.url 取出图片 URL
//   5. 拼到 form.images 里（多张用逗号分隔）
const onUploadSuccess = (res) => {
  // 判断上传是否成功
  if (res.code === 200) {
    // 如果 form.images 已经有值，就在后面追加逗号和新URL
    // 如果 form.images 是空字符串，就直接赋值
    form.value.images = form.value.images
      ? form.value.images + ',' + res.data.url
      : res.data.url
  }
}

// ============================================================
// 图片上传失败回调
// ============================================================
const onUploadError = (err) => {
  console.error('图片上传失败:', err)
  ElMessage.error('图片上传失败，请检查网络后重试')
}

// ============================================================
// 删除图片回调
// ============================================================
// 用户点击图片上的叉号删除图片时触发
// 需要从 form.images 里移除对应的 URL
const onRemove = (file) => {
  if (file.response && file.response.data) {
    // 把对应的 URL 从 images 字符串中移除
    form.value.images = form.value.images
      .replace(file.response.data.url, '')  // 删除URL
      .replace(',,', ',')                    // 清理多余逗号
      .replace(/^,|,$/g, '')                 // 清理首尾逗号
  }
}

// ============================================================
// 发布商品按钮点击事件
// ============================================================
// 【执行流程】
//   1. 检查必填项（标题、分类、价格）
//   2. 按钮转圈
//   3. 收集表单数据，处理价格类型（字符串→数字）
//   4. 调用 publish() 接口
//   5. 成功 → 弹提示 + 跳转首页
//   6. 失败 → 响应拦截器自动弹错误提示
//   7. 按钮恢复
const doPublish = async () => {
  // 第一步：检查必填项
  //   如果标题、分类、价格任一为空，提示用户并返回
  if (!form.value.title || !form.value.categoryId || !form.value.price) {
    ElMessage.warning('请填写必填项（商品名称、分类、售价）')
    return
  }

  // 第二步：开启按钮加载状态
  loading.value = true

  try {
    // 第三步：调用发布接口
    //   注意：v-model 绑定的 price 是字符串类型（因为 input type="number"）
    //   后端 ProductPublishDTO.price 是 BigDecimal 类型
    //   所以需要用 parseFloat() 把字符串转成数字
    //   Spring 的 @RequestBody 反序列化时会自动处理类型转换
    await publish({
      ...form.value,                                                    // 展开 form 对象的所有属性
      price: parseFloat(form.value.price),                              // 售价：字符串 → 数字
      originalPrice: form.value.originalPrice                           // 原价：如果填了就转数字
        ? parseFloat(form.value.originalPrice)
        : null,                                                         // 没填就传 null
      shippingFee: form.value.shippingFee                               // 邮费：同理
        ? parseFloat(form.value.shippingFee)
        : null
    })

    // 第四步：成功提示
    ElMessage.success('发布成功')

    // 第五步：跳转到首页
    router.push('/front/home')
  } catch (e) {
    // 第六步：失败处理
    // 响应拦截器已经自动弹了错误提示，这里不需要额外处理
  }

  // 第七步：关闭按钮加载状态
  loading.value = false
}

// ============================================================
// 页面加载时获取分类列表
// ============================================================
onMounted(async () => {
  try {
    // 获取分类列表，用于下拉框选项
    const res = await getList()
    categories.value = res.data || []
  } catch (e) {
    // 分类获取失败，下拉框为空
  }

  // 如果是从 AI 助手跳转过来的，读取草稿并回填表单
  const draftStr = sessionStorage.getItem('assistantDraft')
  if (draftStr) {
    try {
      const d = JSON.parse(draftStr)
      form.value.title = d.title || ''
      form.value.description = d.description || ''
      form.value.price = d.price || ''
      form.value.originalPrice = d.originalPrice || ''
      form.value.productCondition = d.productCondition ?? 1
      form.value.categoryId = d.categoryId ?? null
      form.value.shippingType = d.shippingType ?? 1
      ElMessage.success('已根据你的描述生成草稿，请确认后发布')
    } catch (e) {
      // 草稿解析失败，忽略
    }
    // 用完即删，避免下次进发布页又触发回填
    sessionStorage.removeItem('assistantDraft')
  }
})
</script>

<style scoped>
/* 页面容器：最大宽度800px，居中 */
.publish-page {
  max-width: 800px;
  margin: 0 auto;
}

/* 白色卡片 */
.card {
  background: #fff;
  padding: 30px;
  border-radius: 8px;
}

/* 标题 */
h2 {
  margin-bottom: 20px;
}
</style>