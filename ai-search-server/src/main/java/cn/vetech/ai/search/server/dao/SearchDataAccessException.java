package cn.vetech.ai.search.server.dao;

/**
 * 搜索数据源访问失败。
 */
public class SearchDataAccessException extends RuntimeException {

    public SearchDataAccessException(String message) {
        super(message);
    }

    public SearchDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
