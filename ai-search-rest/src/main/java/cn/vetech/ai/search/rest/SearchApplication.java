package cn.vetech.ai.search.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 应用入口。scanBasePackages 必须覆盖 server 模块，否则扫不到它的 Bean。 */
@SpringBootApplication(scanBasePackages = "cn.vetech.ai.search")
@ConfigurationPropertiesScan("cn.vetech.ai.search")
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
