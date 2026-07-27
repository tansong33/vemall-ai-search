package cn.vetech.ai.search.rest.handler;

import cn.vetech.ai.search.fccapi.ApiResponse;
import cn.vetech.ai.search.fccapi.ErrorCode;
import cn.vetech.ai.search.rest.filter.RequestIdFilter;
import cn.vetech.ai.search.server.dao.SearchDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolationException;

/** 统一异常出口。控制器不 catch，全部在这里转成 ApiResponse 与恰当的 HTTP 状态。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 500 不回传内部异常信息，否则会泄漏 ES 集群地址一类的细节。 */
    private static final String INTERNAL_MESSAGE = "系统繁忙，请稍后重试";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> onInvalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() == null
                ? "参数校验失败"
                : e.getBindingResult().getFieldError().getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error(RequestIdFilter.current(), ErrorCode.INVALID_ARGUMENT, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> onConstraintViolation(ConstraintViolationException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return ApiResponse.error(RequestIdFilter.current(),
                ErrorCode.INVALID_ARGUMENT, e.getMessage());
    }

    /** 请求体不是合法 JSON（含编码错误）属于客户端问题，不能落到 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> onUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ApiResponse.error(RequestIdFilter.current(),
                ErrorCode.INVALID_ARGUMENT, "请求体不是合法的 JSON（请确认使用 UTF-8 编码）");
    }

    /**
     * 方法用错是客户端问题，必须回 405。
     *
     * <p>下面的 {@code Exception.class} 兜底会连 Spring MVC 自己抛的
     * {@link HttpRequestMethodNotSupportedException} 一起吃掉，把 405 变成 500 ——
     * 本类不继承 {@code ResponseEntityExceptionHandler}，没有任何东西会先于兜底拦住它，
     * 所以这些标准异常必须逐个显式声明。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return ApiResponse.error(RequestIdFilter.current(), ErrorCode.METHOD_NOT_ALLOWED,
                "接口不支持 " + e.getMethod() + " 方法");
    }

    /** 同上：Content-Type 不对属于客户端问题，回 415 而不是 500。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求 Content-Type 不支持: {}", e.getMessage());
        return ApiResponse.error(RequestIdFilter.current(), ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "请求 Content-Type 必须是 application/json");
    }

    @ExceptionHandler(SearchDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> onSearchUnavailable(SearchDataAccessException e) {
        log.warn("搜索后端不可用: {}", e.getMessage());
        return ApiResponse.error(RequestIdFilter.current(),
                ErrorCode.SEARCH_UNAVAILABLE, "搜索服务暂时不可用");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> onUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return ApiResponse.error(RequestIdFilter.current(),
                ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
    }
}
