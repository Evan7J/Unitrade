package com.example.unitrade.service;

import com.example.unitrade.entity.Address;
import java.util.List;

/**
 * 收货地址服务接口
 */
public interface AddressService {
    List<Address> listByUser();
    Address getById(Long id);
    void save(Address address);
    void update(Address address);
    void delete(Long id);
}
