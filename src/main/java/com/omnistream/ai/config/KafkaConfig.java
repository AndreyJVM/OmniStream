package com.omnistream.ai.config;

import org.springframework.context.annotation.Profile;
import com.omnistream.ai.service.DeadLetterRecovery;
import com.omnistream.ai.service.KafkaProducerService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Profile("!hosted")
@Configuration
public class KafkaConfig {
    @Bean
    KafkaAdmin.NewTopics topics(@Value("${omnistream.replication-factor}") int replicas) {
        return new KafkaAdmin.NewTopics(topic(KafkaProducerService.RAW, replicas), topic(KafkaProducerService.DLQ, replicas));
    }

    private NewTopic topic(String name, int replicas) {
        return TopicBuilder.name(name).partitions(3).replicas(replicas).build();
    }

    @Bean(destroyMethod = "close")
    Admin clusterAdmin(KafkaAdmin kafkaAdmin) {
        return Admin.create(kafkaAdmin.getConfigurationProperties());
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterRecovery recovery) {
        var delay = new ExponentialBackOffWithMaxRetries(3);
        delay.setInitialInterval(250);
        delay.setMultiplier(2.0);
        var handler = new DefaultErrorHandler(recovery::recover, delay);
        handler.setCommitRecovered(true);
        return handler;
    }
}
