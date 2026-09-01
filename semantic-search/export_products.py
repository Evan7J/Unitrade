# -*- coding: utf-8 -*-
"""把 UniTrade MySQL 里的真实商品导出成 semantic-search 需要的 products.json。

用法（在 semantic-search 目录下）：
    pip install pymysql
    python export_products.py

会自动把 t_product（在售）与 t_category 关联后写入 data/products.json。
之后重启检索服务（或删除 data/index 让它重建）即可索引真实数据。
"""
import json
import os

import pymysql

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": os.environ.get("DB_PASSWORD", ""),
    "database": "campus_trade",
    "charset": "utf8mb4",
}


def export():
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT p.id, p.title, p.description, c.name AS category_name
                FROM t_product p
                LEFT JOIN t_category c ON p.category_id = c.id
                WHERE p.status = 1
                """
            )
            rows = []
            for pid, title, desc, cat in cur.fetchall():
                rows.append({
                    "id": pid,
                    "title": title or "",
                    "description": desc or "",
                    "categoryName": cat or "",
                    "tags": "",
                })
        out = os.path.join(BASE_DIR, "data", "products.json")
        with open(out, "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=2)
        print(f"已导出 {len(rows)} 条商品到 {out}")
    finally:
        conn.close()


if __name__ == "__main__":
    export()