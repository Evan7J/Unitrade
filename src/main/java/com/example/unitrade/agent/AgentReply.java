package com.example.unitrade.agent;

import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.vo.ProductListVO;
import lombok.Data;

import java.util.List;

/**
 * Agent 对话的统一返回结构
 *
 * reply：模型的文本回复，普通导购对话时只有它
 * draft：一键发布场景下生成的商品发布草稿，非发布场景为 null
 * products：导购搜索到的商品列表（含真实商品 id），前端据此渲染可点击卡片
 */
@Data
public class AgentReply {

    /** 会话ID。首次对话由后端生成并返回，前端保存后下次带上，即可续接多轮上下文 */
    private String sessionId;

    private String reply;

    private ProductPublishDTO draft;

    private List<ProductListVO> products;
}