package com.example.unitrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.unitrade.entity.NegotiationRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface NegotiationRoundMapper extends BaseMapper<NegotiationRound> {

    /**
     * 成本汇总 —— 一条 SQL 聚合出"单次议价成本"。
     *
     * <p>为什么单独写这条 SQL 而不是在 Java 里遍历累加：
     * 成本是个<b>会被反复追问口径</b>的数字。留在 SQL 里，
     * 谁都能自己跑一遍验证；写在 Java 里就变成了只有代码作者才知道的黑盒。
     *
     * <p>⚠️ 为什么加 {@code WHERE IFNULL(prompt_tokens,0) > 0}：
     * 未走 LLM 的轮次（挂起/拒绝/模板兜底）token 是 0 —— 那是真实情况，
     * 但如果把它们算进"每次会话成本"的分母，平均值会被稀释到没有意义。
     * 所以这里统计的是<b>发生过模型调用的会话</b>，口径必须写出来。
     */
    @Select("""
            SELECT COUNT(DISTINCT session_id)  AS sessionCount,
                   COUNT(*)                     AS roundCount,
                   IFNULL(SUM(prompt_tokens),0)     AS promptTokens,
                   IFNULL(SUM(completion_tokens),0) AS completionTokens,
                   IFNULL(SUM(token_cost),0)        AS totalCost,
                   MAX(model_name)              AS modelName
              FROM t_negotiation_round
             WHERE IFNULL(prompt_tokens, 0) > 0
            """)
    Map<String, Object> selectCostSummary();
}
