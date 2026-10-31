package dev.spanktube.events;

import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties(EventProperties.class)
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(prefix = "spanktube.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventBusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RabbitEventBus rabbitEventBus(RabbitTemplate rabbitTemplate, RabbitAdmin rabbitAdmin,
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            ObjectMapper objectMapper, EventProperties properties) {
        return new RabbitEventBus(rabbitTemplate, rabbitAdmin, connectionFactory, objectMapper, properties.exchange());
    }
}
