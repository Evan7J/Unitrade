package com.example.unitrade.common;


import lombok.Data;

@Data
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code,String message){
        super(message);
        this.code=code;

    }

    public BusinessException(String message){
        super(message);
        this.code=400;
    }





}
