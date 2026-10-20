package com.ape.smarthedge_risk_hub.common;

public class Result<T> {
    private boolean success;  // 是否成功
    private Integer code;     // 状态码
    private String msg;       // 提示信息
    private T data;           // 业务数据
    private int total;        // 总记录数

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    // 私有构造，仅通过静态方法创建
    private Result() {}

    // ========== 核心通用构建方法（内部复用） ==========
    private static <T> Result<T> build(boolean success, Integer code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setSuccess(success);
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }


    /** 成功：带数据+默认提示（状态码200） */
    public static <T> Result<T> success(T data) {
        return build(true, 200, "操作成功", data);
    }


    /** 失败：自定义状态码+提示（数据固定为null） */
    public static <T> Result<T> fail(Integer code, String msg) {
        return build(false, code, msg, null);
    }


    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}