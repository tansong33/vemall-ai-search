package cn.vetech.ai.search.fccapi;

import java.io.Serializable;

/**
 * 所有对外接口共用的响应信封。
 */
public class ApiResponse<T> implements Serializable {

    private boolean success;
    private String code;
    private String message;
    private String requestId;
    private T data;

    public static <T> ApiResponse<T> ok(String requestId, T data) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.success = true;
        response.code = "OK";
        response.requestId = requestId;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(
            String requestId, ErrorCode code, String message) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.success = false;
        response.code = code.name();
        response.message = message;
        response.requestId = requestId;
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
