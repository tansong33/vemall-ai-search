package cn.vetech.aimall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 智能商城启动类。
 * 链路：用户文字 -> 规则/词典 NER -> MySQL 有界召回 -> 规则精排 -> 返回商品。
 * 数据访问层为 MyBatis-Plus（mapper 包），@MapperScan 负责扫描注册。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("cn.vetech.aimall.mapper")
@EnableScheduling
public class AiMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMallApplication.class, args);
    }
}
