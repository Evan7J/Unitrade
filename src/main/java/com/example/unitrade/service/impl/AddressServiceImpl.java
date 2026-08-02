package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.Address;
import com.example.unitrade.mapper.AddressMapper;
import com.example.unitrade.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;

    @Override
    public List<Address> listByUser() {
        Long userId = JwtInterceptor.getCurrentUserId();
        return addressMapper.selectList(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getCreateTime)
        );
    }

    @Override
    public Address getById(Long id) { return addressMapper.selectById(id); }

    @Override
    public void save(Address address) {
        address.setUserId(JwtInterceptor.getCurrentUserId());
        addressMapper.insert(address);
    }

    @Override
    public void update(Address address) {
        Address db = addressMapper.selectById(address.getId());
        if (db == null) throw new BusinessException("地址不存在");
        if (!db.getUserId().equals(JwtInterceptor.getCurrentUserId())) throw new BusinessException("只能修改自己的地址");
        address.setUserId(db.getUserId());
        addressMapper.updateById(address);
    }

    @Override
    public void delete(Long id) {
        Address db = addressMapper.selectById(id);
        if (db == null) throw new BusinessException("地址不存在");
        if (!db.getUserId().equals(JwtInterceptor.getCurrentUserId())) throw new BusinessException("只能删除自己的地址");
        addressMapper.deleteById(id);
    }
}
