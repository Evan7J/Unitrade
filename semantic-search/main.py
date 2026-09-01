# -*- coding: utf-8 -*-
"""UniTrade 语义检索服务（RAG 的检索端）

功能：
- 商品入库时用本地 embedding 模型向量化，存入 FAISS 索引
- 提供 GET /search 语义召回接口，供 Java 端 Agent 调用
- 首次启动会建模并缓存到磁盘，之后重启无需重复计算

启动：
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8000
"""
import json
import os
from typing import List

from fastapi import FastAPI, Query
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import FAISS

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_FILE = os.path.join(BASE_DIR, "data", "products.json")
INDEX_DIR = os.path.join(BASE_DIR, "data", "index")

# 中文 embedding 模型，本地运行、离线可用
embeddings = HuggingFaceEmbeddings(model_name="text2vec-base-chinese")

app = FastAPI(title="UniTrade Semantic Search")

store = None  # 全局 FAISS 向量库


def _load_products() -> List[dict]:
    """从 data/products.json 读取商品。该文件可用导出脚本或手工生成。"""
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data if isinstance(data, list) else []


def _product_text(p: dict) -> str:
    """把一个商品拼接成一段可向量化的文本。"""
    parts = [p.get("title", "")]
    if p.get("description"):
        parts.append(p.get("description"))
    if p.get("categoryName"):
        parts.append(p.get("categoryName"))
    if p.get("tags"):
        parts.append(p.get("tags"))
    return " ".join(str(x) for x in parts if x)


def build_index():
    """构建 FAISS 索引，优先加载磁盘缓存，否则重新计算。"""
    global store
    products = _load_products()
    if not products:
        return

    # 若已有缓存索引，直接加载，避免每次启动重复向量化
    if os.path.exists(os.path.join(INDEX_DIR, "index.faiss")):
        try:
            db = FAISS.load_local(
                INDEX_DIR, embeddings, allow_dangerous_deserialization=True
            )
            # 元数据对应当前文件里的商品；若商品有变化可删除 data/index 后重启重建
            store = db
            return
        except Exception:
            pass

    texts, metadatas = [], []
    for p in products:
        texts.append(_product_text(p))
        metadatas.append({"product_id": p.get("id"), "title": p.get("title", "")})

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=100, chunk_overlap=10
    )
    docs = splitter.create_documents(texts=texts, metadatas=metadatas)
    if not docs:
        return

    store = FAISS.from_documents(docs, embeddings)
    os.makedirs(INDEX_DIR, exist_ok=True)
    store.save_local(INDEX_DIR)


@app.on_event("startup")
def startup():
    build_index()


@app.get("/health")
def health():
    return {"status": "ok", "indexed": store.index.ntotal if store else 0}


@app.get("/search")
def search(
    q: str = Query(..., description="用户的自然语言查询"),
    top_k: int = Query(8, ge=1, le=50),
):
    """语义召回：返回最相关的商品，附带匹配文本，供 Java 端按 product_id 融合。"""
    if store is None:
        return {"hits": []}
    docs = store.similarity_search_with_score(q, k=top_k)
    hits = []
    for doc, score in docs:
        product_id = doc.metadata.get("product_id")
        if product_id is None:
            continue
        hits.append({
            "productId": product_id,
            "title": doc.metadata.get("title", ""),
            "content": doc.page_content,
            "score": round(float(score), 4),
        })
    return {"hits": hits}