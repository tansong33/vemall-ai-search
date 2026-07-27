package cn.vetech.ai.search.fccapi;

import lombok.Data;

import java.io.Serializable;

/**
 * 所有对外接口共用的响应信封。
 */
@Data
public class ApiResponse<T> implements Serializable {

    private boolean success;
    private String code;
    private String message;
    private String requestId;
    private T data;

    public static <T> ApiResponse<T> ok(String requestId, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.code = "OK";
        response.requestId = requestId;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(
            String requestId, ErrorCode code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.code = code.name();
        response.message = message;
        response.requestId = requestId;
        return response;
    }
}
