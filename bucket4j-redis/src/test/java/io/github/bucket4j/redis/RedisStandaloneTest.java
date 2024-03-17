package io.github.bucket4j.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandAsyncService;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;
import org.redisson.connection.ConnectionManager;
import org.redisson.liveobject.core.RedissonObjectBuilder;
import org.testcontainers.containers.GenericContainer;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.jedis.cas.JedisBasedProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import io.github.bucket4j.tck.AbstractDistributedBucketTest;
import io.github.bucket4j.tck.ProxyManagerSpec;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.netty.util.internal.ThreadLocalRandom;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * @author Vladimir Bukhtoyarov
 */
public class RedisStandaloneTest extends AbstractDistributedBucketTest {

    private static GenericContainer container;

    // redisson
    private static ConnectionManager connectionManager;
    private static CommandAsyncExecutor commandExecutor;

    // jedis
    private static JedisPool jedisPool;

    private static UnifiedJedis unifiedJedis;
    private static UnifiedJedis unifiedJedisPooled;

    // lettuce
    private static RedisClient redisClient;

    @BeforeAll
    public static void setup() {
        container = startRedisContainer();

        // Redisson
        connectionManager = createRedissonClient(container);
        commandExecutor = createRedissonExecutor(connectionManager);

        // lettuce
        redisClient = createLettuceClient(container);

        // jedis
        jedisPool = createJedisClient(container);
        unifiedJedisPooled = createUnifiedJedisPooledClient(container);
        unifiedJedis = createUnifiedJedisClient(container);

        specs = Arrays.asList(
            // Redisson
            new ProxyManagerSpec<>(
                "RedissonBasedProxyManager_LongKey",
                () -> ThreadLocalRandom.current().nextLong(),
                clientConfig -> RedissonBasedProxyManager.builderFor(commandExecutor)
                    .withKeyMapper(Mapper.LONG)
                    .withClientSideConfig(clientConfig)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "RedissonBasedProxyManager_StringKey",
                () -> UUID.randomUUID().toString(),
                clientConfig -> RedissonBasedProxyManager.builderFor(commandExecutor)
                    .withClientSideConfig(clientConfig)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "RedissonBasedProxyManager_StringKey_RequestTimeout",
                () -> UUID.randomUUID().toString(),
                clientConfig -> RedissonBasedProxyManager.builderFor(commandExecutor)
                    .withClientSideConfig(clientConfig.withRequestTimeout(Duration.ofSeconds(3)))
                    .build()
            ),

            // Letucce
            new ProxyManagerSpec<>(
                "LettuceBasedProxyManager_ByteArrayKey",
                () -> UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                clientConfig -> LettuceBasedProxyManager.builderFor(redisClient)
                    .withClientSideConfig(clientConfig)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "LettuceBasedProxyManager_StringKey",
                () -> UUID.randomUUID().toString(),
                clientConfig -> LettuceBasedProxyManager.builderFor(redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)))
                    .withClientSideConfig(clientConfig)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "LettuceBasedProxyManager_NoExpiration_StringKey_RequestTimeout",
                () -> UUID.randomUUID().toString(),
                clientConfig -> LettuceBasedProxyManager.builderFor(redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)))
                    .withClientSideConfig(clientConfig.withRequestTimeout(Duration.ofSeconds(3)))
                    .build()
            ),

            // Jedis
            new ProxyManagerSpec<>(
                "JedisBasedProxyManager_ByteArrayKey",
                () -> UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                clientConfig -> JedisBasedProxyManager.builderFor(jedisPool)
                    .withClientSideConfig(clientConfig)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "JedisBasedProxyManager_StringKey",
                () -> UUID.randomUUID().toString(),
                clientConfig -> JedisBasedProxyManager.builderFor(jedisPool)
                    .withClientSideConfig(clientConfig)
                    .withKeyMapper(Mapper.STRING)
                    .build()
            ),
            new ProxyManagerSpec<>(
                "JedisBasedProxyManager_unifiedJedisPooled_ByteArrayKey",
                () -> UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                clientConfig -> JedisBasedProxyManager.builderFor(unifiedJedisPooled)
                    .withClientSideConfig(clientConfig)
                    .build()
            )
        );
    }

    @AfterAll
    public static void shutdown() {
        try {
            try {
                try {
                    if (connectionManager != null) {
                        connectionManager.shutdown();
                    }
                } finally {
                    if (redisClient != null) {
                        redisClient.shutdown();
                    }
                }
            } finally {
                try {
                    try {
                        if (jedisPool != null) {
                            jedisPool.close();
                        }
                    } finally {
                        if (unifiedJedis != null) {
                            unifiedJedis.close();
                        }
                    }
                } finally {
                    if (unifiedJedisPooled != null) {
                        unifiedJedisPooled.close();
                    }
                }
            }
        } finally {
            if (container != null) {
                container.close();
            }
        }
    }

    private static ConnectionManager createRedissonClient(GenericContainer container) {
        String redisAddress = container.getContainerIpAddress();
        Integer redisPort = container.getMappedPort(6379);
        String redisUrl = "redis://" + redisAddress + ":" + redisPort;

        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);

        ConnectionManager connectionManager = ConfigSupport.createConnectionManager(config);
        return connectionManager;
    }

    private static CommandAsyncExecutor createRedissonExecutor(ConnectionManager connectionManager) {
        return new CommandAsyncService(connectionManager, null, RedissonObjectBuilder.ReferenceType.DEFAULT);
    }

    private static GenericContainer startRedisContainer() {
        GenericContainer genericContainer = new GenericContainer("redis:4.0.11")
            .withExposedPorts(6379);
        genericContainer.start();
        return genericContainer;
    }

    private static RedisClient createLettuceClient(GenericContainer container) {
        String redisHost = container.getHost();
        Integer redisPort = container.getMappedPort(6379);
        String redisUrl = "redis://" + redisHost + ":" + redisPort;

        return RedisClient.create(redisUrl);
    }

    private static JedisPool createJedisClient(GenericContainer container) {
        String redisHost = container.getHost();
        Integer redisPort = container.getMappedPort(6379);

        return new JedisPool(redisHost, redisPort);
    }

    private static UnifiedJedis createUnifiedJedisPooledClient(GenericContainer container) {
        String redisHost = container.getHost();
        Integer redisPort = container.getMappedPort(6379);

        return new JedisPooled(redisHost, redisPort);
    }

    private static UnifiedJedis createUnifiedJedisClient(GenericContainer container) {
        String redisHost = container.getHost();
        Integer redisPort = container.getMappedPort(6379);

        return new UnifiedJedis(HostAndPort.from(redisHost + ":" + redisPort));
    }

}
