package com.microservice.orderservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addServersItem(new Server().url("/").description("Default Server"))
                .info(new Info()
                        .title("Order Service API")
                        .description("API quản lý đơn hàng")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Phan Kien")
                                .email("phankien@example.com")));
    }
}
