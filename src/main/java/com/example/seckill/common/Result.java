package com.example.seckill.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result <T>{
    private int code;
    private String message;
    private T data;


    public static Result<Void> success(){
        return new Result<Void>(ResultCode.SUCCESS,"操作成功",null);
    }

    public static<T> Result<T> success(T data){
        return new Result<T>(ResultCode.SUCCESS, "操作成功", data);
    }

    public static Result<Void> error(int code, String message){
        return new Result<>(code,message,null);
    }
}
