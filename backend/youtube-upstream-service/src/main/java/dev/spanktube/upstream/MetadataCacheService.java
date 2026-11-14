package dev.spanktube.upstream;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.spanktube.egress.EgressProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Service
class MetadataCacheService {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EgressProperties egressProperties;
    private final ConcurrentHashMap<String, Mono<?>> inFlight = new ConcurrentHashMap<>();

    MetadataCacheService(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper, EgressProperties egressProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.egressProperties = egressProperties;
    }

    <T> Mono<T> get(String namespace, String id, Class<T> type, Supplier<T> loader, Duration ttl) {
        return get(namespace, id, type, loader, ignored -> ttl);
    }

    @SuppressWarnings("unchecked")
    <T> Mono<T> get(String namespace, String id, Class<T> type, Supplier<T> loader, Function<T, Duration> ttl) {
        String key = cacheKey(namespace, id);
        return redis.opsForValue().get(key)
                .map(json -> objectMapper.readValue(json, type))
                .switchIfEmpty(Mono.defer(() -> {
                    Mono<T> shared = (Mono<T>) inFlight.computeIfAbsent(key, ignored -> loadAndCache(key, loader, ttl));
                    return shared.doFinally(signal -> inFlight.remove(key));
                }));
    }

    Mono<Void> evict(String namespace, String id) {
        return redis.delete(cacheKey(namespace, id)).then();
    }

    private <T> Mono<T> loadAndCache(String key, Supplier<T> loader, Function<T, Duration> ttl) {
        return Mono.fromCallable(loader::get)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(value -> Mono.fromCallable(() -> objectMapper.writeValueAsString(value))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(json -> redis.opsForValue().set(key, json, ttl.apply(value)).thenReturn(value)))
                .cache();
    }

    private String cacheKey(String namespace, String id) {
        String mode = egressProperties.mode().name().toLowerCase(Locale.ROOT);
        return "spanktube:" + namespace + ":" + mode + ":" + id;
    }
}
