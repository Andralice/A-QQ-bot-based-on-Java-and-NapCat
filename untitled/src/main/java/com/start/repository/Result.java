package com.start.repository;

// 临时定义（建议替换为你自己的 Result）
class Result<T> {
    private final boolean success;
    private final T data;
    private final String message;

    private Result(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, data, null);
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(false, null, message);
    }

    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public String getMessage() { return message; }
}