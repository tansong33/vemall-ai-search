package cn.vetech.aimall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 智能商城启动类。
 * 链路：用户输入(文字/图片) -> 意图理解(LLM/VLM) -> 混合召回(语义+关键词+属性过滤)
 *      -> 规则重排 -> 推荐生成 -> (Redis 缓存 / 反馈埋点)
 * 数据访问层为 MyBatis-Plus（mapper 包），@MapperScan 负责扫描注册。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("cn.vetech.aimall.mapper")
public class AiMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMallApplication.class, args);
    }
}
