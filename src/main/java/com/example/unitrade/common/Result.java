package com.example.unitrade.common;


import lombok.Data;

@Data
public class Result<T>{
    /**
     * Result同意返回类
     */
    private int code;
    private String msg;
    private T data;

    //静态工厂模式，有点不理解，后续打通
    private Result(){}

    private Result(int code,String msg,T data){
        this.code=code;
        this.msg=msg;
        this.data=data;

    }

    public static <T> Result<T> success(){
        return new Result<>(200,"操作成功",null);
    }


    public static <T> Result<T> success(T data){
        return new Result<>(200,"操作成功",data);
    }

    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(200, msg, data);
    }


    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

}
