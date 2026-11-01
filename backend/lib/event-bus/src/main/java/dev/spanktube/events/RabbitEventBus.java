package dev.spanktube.events;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

public class RabbitEventBus implements EventPublisher, EventSubscriber, SmartLifecycle {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitAdmin rabbitAdmin;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final List<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    public RabbitEventBus(RabbitTemplate rabbitTemplate, RabbitAdmin rabbitAdmin,
            ConnectionFactory connectionFactory, ObjectMapper objectMapper, String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
    }

    @Override
    public <T> Mono<Void> publish(DomainEvent<T> event) {
        return Mono.<Void>fromRunnable(() -> {
            byte[] body = objectMapper.writeValueAsBytes(event);
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId(event.eventId());
            properties.setTimestamp(Date.from(event.occurredAt()));
            rabbitTemplate.send(exchange, event.eventType(), new Message(body, properties));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public <T> Mono<Void> subscribe(String queue, String eventType, Class<T> payloadType,
            Function<DomainEvent<T>, Mono<Void>> handler) {
        return Mono.<Void>fromRunnable(() -> {
            TopicExchange topicExchange = new TopicExchange(exchange, true, false);
            Queue durableQueue = new Queue(queue, true);
            rabbitAdmin.declareExchange(topicExchange);
            rabbitAdmin.declareQueue(durableQueue);
            rabbitAdmin.declareBinding(BindingBuilder.bind(durableQueue).to(topicExchange).with(eventType));

            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
            container.setQueueNames(queue);
            container.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
            container.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener) (message, channel) -> {
                long deliveryTag = message.getMessageProperties().getDeliveryTag();
                try {
                    DomainEvent<T> event = objectMapper.readValue(message.getBody(),
                            objectMapper.getTypeFactory().constructParametricType(DomainEvent.class, payloadType));
                    handler.apply(event).block();
                    channel.basicAck(deliveryTag, false);
                } catch (Exception error) {
                    org.slf4j.LoggerFactory.getLogger(RabbitEventBus.class)
                            .warn("Event {} handler failed: {}", eventType, error.toString());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    channel.basicNack(deliveryTag, false, true);
                }
            });
            containers.add(container);
            container.start();
        }).subscribeOn(Schedulers.boundedElastic())
                .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(5))
                        .maxBackoff(java.time.Duration.ofSeconds(30)));
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        containers.forEach(SimpleMessageListenerContainer::stop);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
