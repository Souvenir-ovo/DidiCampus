package com.didicampus.infrastructure.config;

import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.infrastructure.message.RocketMqFundEventAdapter;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "true")
public class RocketMqClientConfig {

    @Bean
    public ClientServiceProvider rocketMqClientProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean
    public ClientConfiguration rocketMqClientConfiguration(
            @Value("${didicampus.mq.endpoints:127.0.0.1:8081}") String endpoints,
            @Value("${didicampus.mq.request-timeout-seconds:10}") long timeoutSeconds) {
        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .setRequestTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean(destroyMethod = "close")
    public Producer rocketMqDelayProducer(ClientServiceProvider provider,
                                          ClientConfiguration configuration) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build();
    }

    @Bean
    public FundEventPort rocketMqFundEventPort(ClientServiceProvider provider,
                                               ClientConfiguration configuration,
                                               WalletRepository walletRepository) throws ClientException {
        RocketMqFundEventAdapter adapter =
                new RocketMqFundEventAdapter(provider, walletRepository);
        Producer transactionProducer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTransactionChecker(adapter.transactionChecker())
                .build();
        adapter.bindProducer(transactionProducer);
        return adapter;
    }
}
