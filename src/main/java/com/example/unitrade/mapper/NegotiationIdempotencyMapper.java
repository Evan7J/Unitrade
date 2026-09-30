package com.example.unitrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.unitrade.entity.NegotiationIdempotency;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NegotiationIdempotencyMapper extends BaseMapper<NegotiationIdempotency> {
}
