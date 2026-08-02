package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Address;
import com.example.unitrade.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping("/list")
    public Result<List<Address>> list() { return Result.success(addressService.listByUser()); }

    @GetMapping("/{id}")
    public Result<Address> getById(@PathVariable Long id) { return Result.success(addressService.getById(id)); }

    @PostMapping("/save")
    public Result<?> save(@RequestBody Address address) {
        addressService.save(address);
        return Result.success("添加成功");
    }

    @PutMapping("/update")
    public Result<?> update(@RequestBody Address address) {
        addressService.update(address);
        return Result.success("更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) {
        addressService.delete(id);
        return Result.success("删除成功");
    }
}