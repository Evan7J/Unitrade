package com.example.unitrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.unitrade.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 助手对话历史 Mapper
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
}