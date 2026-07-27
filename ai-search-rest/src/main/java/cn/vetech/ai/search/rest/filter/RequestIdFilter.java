package cn.vetech.ai.search.rest.filter;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求生成 requestId 并放入 MDC。
 *
 * <p>集中在这里生成，控制器与异常处理器直接读 —— 否则每个方法都要复制一遍
 * UUID 截断逻辑（feat 里那行出现了 6 次）。</p>
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String KEY = "requestId";

    /** 上游已带 requestId 时沿用，便于跨服务串联日志。 */
    private static final String HEADER = "X-Request-Id";

    public static String current() {
        String value = MDC.get(KEY);
        return value == null ? "-" : value;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(KEY);
        }
    }
}
