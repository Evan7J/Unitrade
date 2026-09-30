package com.example.unitrade.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion（RRF）—— 把多路召回结果融合成一个统一排序。
 *
 * <h2>为什么需要它，而不是"把两路结果拼起来"</h2>
 * 最直觉的做法是「关键词结果在前、向量结果补在后面、按 id 去重」。
 * 那样做有一个致命问题：<b>第二路几乎没有话语权</b> ——
 * 关键词路返回 20 条就把位置占满了，向量路辛苦召回的长尾商品
 * 只能排在 20 名开外，等于白召。
 *
 * <p>RRF 换了个思路：<b>不看分数，只看排名</b>。
 * <pre>
 *   score(doc) = Σ 1 / (k + rank_i(doc))
 * </pre>
 * 其中 {@code rank_i} 是该文档在第 i 路的排名（从 1 开始）。
 * 于是「两路都排进前 5」的文档会稳定压过「只在某一路排第 1」的文档 ——
 * 这正是我们想要的：<b>奖励多路共识</b>。
 *
 * <h2>为什么必须用排名而不是分数</h2>
 * BM25 分数可能是 5.20，余弦相似度是 0.88，<b>量纲完全不同</b>。
 * 要加权求和就得先归一化，而归一化受异常分数、以及每次查询的分数分布影响，很脆。
 * RRF 只用排名，天然规避了这个问题。
 *
 * <h2>k 在控制什么（这是最常被问倒的一点）</h2>
 * k 的作用是<b>压平头部差异</b>：
 * <ul>
 *   <li>{@code k=1} 时，某一路的第 1 名得分 0.5，而"两路都排第 5"只有 0.333 ——
 *       单路第一<b>一票通吃</b>；</li>
 *   <li>k 变大之后，"两路都在前 5"稳定胜过"单路第一"。</li>
 * </ul>
 * 60 这个值来自 Cormack 等人 2009 年的原始论文，是经验值，不是推导出来的。
 *
 * <h2>两处刻意的实现选择</h2>
 * <ol>
 *   <li><b>同一路内部的重复文档只算第一次出现的位置</b>：
 *       重复项不是"另一个结果"，不该把别的文档挤下去。</li>
 *   <li><b>平局用"首次出现顺序"打破</b>：排序必须<b>确定</b>，
 *       否则同一份输入两次可能给出不同顺序，评测就没法复现。</li>
 * </ol>
 *
 * <p>注：这里用 {@code double} 是刻意的 —— RRF 分数是<b>排序用的相对量，不是金额</b>，
 * 不存在对账精度问题。项目里"钱必须用 long 分 / BigDecimal"的规矩不适用于这里。
 */
public final class RrfFuser {

    /** 论文经验值。调小会让单路冠军更容易一票通吃，调大会更偏向"多路共识"。 */
    public static final int DEFAULT_K = 60;

    private RrfFuser() {
    }

    /**
     * 用默认 k 融合多路召回结果。
     *
     * @param rankedLists 每一路的有序结果，越靠前越相关（列表内的元素必须可做 Map key）
     * @return 融合后的有序文档列表（去重）
     */
    public static <T> List<T> fuse(List<List<T>> rankedLists) {
        return fuse(rankedLists, DEFAULT_K);
    }

    /**
     * 融合多路召回结果。
     *
     * @param rankedLists 每一路的有序结果；允许为 null 或空（会被跳过）
     * @param k           平滑常数，必须 &gt;= 1；一般直接用 {@link #DEFAULT_K}
     */
    public static <T> List<T> fuse(List<List<T>> rankedLists, int k) {
        if (rankedLists == null) {
            return List.of();
        }
        if (k < 1) {
            throw new IllegalArgumentException("k 必须 >= 1，实际=" + k);
        }

        Map<T, Double> scoreByDoc = new HashMap<>();
        Map<T, Integer> firstSeenOrder = new HashMap<>();
        int order = 0;

        for (List<T> rawList : rankedLists) {
            if (rawList == null || rawList.isEmpty()) {
                continue;
            }
            // 先在同路内去重：重复项不该占掉别的文档的排名
            Set<T> deduped = new LinkedHashSet<>();
            for (T doc : rawList) {
                if (doc != null) {
                    deduped.add(doc);
                }
            }

            int rank = 0;
            for (T doc : deduped) {
                rank++;
                scoreByDoc.merge(doc, 1.0 / (k + rank), Double::sum);
                if (!firstSeenOrder.containsKey(doc)) {
                    firstSeenOrder.put(doc, order++);
                }
            }
        }

        List<T> fused = new ArrayList<>(scoreByDoc.keySet());
        fused.sort(Comparator
                // 1) 分数高的在前
                .comparingDouble((T doc) -> scoreByDoc.get(doc)).reversed()
                // 2) 平局用首次出现顺序，保证排序确定、可复现
                .thenComparingInt(doc -> firstSeenOrder.get(doc)));
        return fused;
    }

    /**
     * 计算单个文档的 RRF 得分：{@code Σ 1 / (k + rank)}。
     *
     * <p>参数含义：该文档在<b>各路</b>里的排名，每一路最多贡献一次
     * （同一路内部的重复由 {@link #fuse} 的去重阶段处理掉了，不会传到这里）。
     * 因此这里<b>不做跨路去重</b> —— 两路都排第 5，就是两份贡献。
     *
     * <p>这个方法只用于调试与测试断言，正常调用走 {@link #fuse}。
     */
    public static double scoreOf(List<Integer> ranks, int k) {
        double sum = 0;
        for (Integer rank : ranks) {
            if (rank != null && rank >= 1) {
                sum += 1.0 / (k + rank);
            }
        }
        return sum;
    }
}
