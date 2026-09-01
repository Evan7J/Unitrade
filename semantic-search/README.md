# UniTrade 语义检索服务（RAG 检索端）

给 UniTrade 后台 Agent 的商品搜索提供**语义召回**能力。用户自然语言（如「想买部苹果手机」）即使不直接命中关键词，也能检索到相关商品，替代原先纯 MySQL LIKE + 硬编码同义词的方式。

## 技术栈

- Python + FastAPI + FAISS
- `text2vec-base-chinese` 本地 embedding 模型（离线、无需联网）

## 快速开始

```bash
cd semantic-search
pip install -r requirements.txt          # 首次会下载中文 embedding 模型，约 400MB
python export_products.py                # 从 UniTrade 数据库导出真实商品（可选，样例数据已内置）
uvicorn main:app --host 0.0.0.0 --port 8000
```

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查，返回当前索引商品数 |
| GET | `/search?q=想买部苹果手机&top_k=5` | 语义召回，返回 `[{productId, title, content, score}]` |

## 与 Java 对接

Java 端通过配置 `semantic.search.base-url` 指向本服务。若本服务未启动或调用失败，**Java 端会自动降级回退到原有 MySQL 关键词搜索**，不影响主流程可用性。

## 数据说明

- 内置 `data/products.json` 为演示样例
- 运行 `export_products.py` 可用数据库真实数据覆盖它
- 修改商品数据后，如需强制重建索引，删除 `data/index` 目录并重启服务即可