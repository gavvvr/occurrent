/*
 *
 *  Copyright 2021 Johan Haleby
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.occurrent.springboot.mongo.blocking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.occurrent.application.converter.CloudEventConverter;
import org.occurrent.application.converter.jackson.JacksonCloudEventConverter;
import org.occurrent.application.service.blocking.ApplicationService;
import org.occurrent.application.service.blocking.generic.GenericApplicationService;
import org.occurrent.dsl.subscription.blocking.EventNameFromType;
import org.occurrent.dsl.subscription.blocking.Subscriptions;
import org.occurrent.eventstore.api.blocking.EventStore;
import org.occurrent.eventstore.mongodb.spring.blocking.EventStoreConfig;
import org.occurrent.eventstore.mongodb.spring.blocking.SpringMongoEventStore;
import org.occurrent.retry.RetryStrategy;
import org.occurrent.springboot.mongo.blocking.OccurrentProperties.EventStoreProperties;
import org.occurrent.subscription.api.blocking.CompetingConsumerStrategy.CompetingConsumerListener;
import org.occurrent.subscription.api.blocking.Subscribable;
import org.occurrent.subscription.api.blocking.SubscriptionModel;
import org.occurrent.subscription.api.blocking.SubscriptionPositionStorage;
import org.occurrent.subscription.blocking.competingconsumers.CompetingConsumerSubscriptionModel;
import org.occurrent.subscription.blocking.durable.DurableSubscriptionModel;
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoLeaseCompetingConsumerStrategy;
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoSubscriptionModel;
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoSubscriptionPositionStorage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Optional;

import static org.occurrent.subscription.mongodb.spring.blocking.SpringMongoSubscriptionModelConfig.withConfig;

/**
 * Spring auto-configuration support for blocking MongoDB Occurrent
 */
@Configuration
@ConditionalOnClass({SpringMongoEventStore.class, SpringMongoSubscriptionModel.class})
@EnableConfigurationProperties(OccurrentProperties.class)
@AutoConfigureAfter(MongoAutoConfiguration.class)
@Import(MongoDataAutoConfiguration.class)
public class OccurrentMongoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MongoTransactionManager.class)
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean
    @ConditionalOnMissingBean(EventStoreConfig.class)
    public EventStoreConfig occurrentEventStoreConfig(MongoTransactionManager transactionManager, OccurrentProperties occurrentProperties) {
        EventStoreProperties eventStoreProperties = occurrentProperties.getEventStore();
        return new EventStoreConfig.Builder().eventStoreCollectionName(eventStoreProperties.getCollection()).transactionConfig(transactionManager).timeRepresentation(eventStoreProperties.getTimeRepresentation()).build();
    }

    @Bean
    @ConditionalOnMissingBean(SpringMongoEventStore.class)
    public SpringMongoEventStore occurrentSpringMongoEventStore(MongoTemplate template, EventStoreConfig eventStoreConfig) {
        return new SpringMongoEventStore(template, eventStoreConfig);
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionPositionStorage.class)
    public SubscriptionPositionStorage occurrentSubscriptionPositionStorage(MongoTemplate mongoTemplate, OccurrentProperties occurrentProperties) {
        return new SpringMongoSubscriptionPositionStorage(mongoTemplate, occurrentProperties.getSubscription().getCollection());
    }

    @Bean
    @ConditionalOnMissingBean(SpringMongoLeaseCompetingConsumerStrategy.class)
    public SpringMongoLeaseCompetingConsumerStrategy occurrentCompetingConsumerStrategy(MongoTemplate mongoTemplate, List<CompetingConsumerListener> competingConsumerListeners) {
        SpringMongoLeaseCompetingConsumerStrategy strategy = SpringMongoLeaseCompetingConsumerStrategy.withDefaults(mongoTemplate);
        competingConsumerListeners.forEach(strategy::addListener);
        return strategy;
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionModel.class)
    public SubscriptionModel occurrentCompetingDurableSubscriptionModel(MongoTemplate mongoTemplate, SpringMongoLeaseCompetingConsumerStrategy competingConsumerStrategy, SubscriptionPositionStorage storage, OccurrentProperties occurrentProperties) {
        EventStoreProperties eventStoreProperties = occurrentProperties.getEventStore();
        SpringMongoSubscriptionModel mongoSubscriptionModel = new SpringMongoSubscriptionModel(mongoTemplate, withConfig(eventStoreProperties.getCollection(), eventStoreProperties.getTimeRepresentation())
                .restartSubscriptionsOnChangeStreamHistoryLost(occurrentProperties.getSubscription().isRestartOnChangeStreamHistoryLost()));
        DurableSubscriptionModel durableSubscriptionModel = new DurableSubscriptionModel(mongoSubscriptionModel, storage);
        return new CompetingConsumerSubscriptionModel(durableSubscriptionModel, competingConsumerStrategy);
    }


    @Bean
    @ConditionalOnMissingBean(CloudEventConverter.class)
    public CloudEventConverter<?> occurrentCloudEventConverter(Optional<ObjectMapper> objectMapper, OccurrentProperties occurrentProperties) {
        return new JacksonCloudEventConverter<>(objectMapper.orElseGet(ObjectMapper::new), occurrentProperties.getCloudEventConverter().getCloudEventSource());
    }

    @Bean
    @ConditionalOnMissingBean(Subscriptions.class)
    public <T> Subscriptions<T> occurrentSubscriptionDsl(Subscribable subscribable, CloudEventConverter<T> cloudEventConverter, Optional<EventNameFromType<T>> eventNameFromType) {
        return eventNameFromType
                .map(converter -> new Subscriptions<>(subscribable, cloudEventConverter, converter))
                .orElseGet(() -> new Subscriptions<>(subscribable, cloudEventConverter));
    }

    @Bean
    @ConditionalOnMissingBean(ApplicationService.class)
    public ApplicationService<?> occurrentApplicationService(EventStore eventStore, CloudEventConverter<?> cloudEventConverter, OccurrentProperties occurrentProperties) {
        boolean enableDefaultRetryStrategy = occurrentProperties.getApplicationService().isEnableDefaultRetryStrategy();
        return enableDefaultRetryStrategy ? new GenericApplicationService<>(eventStore, cloudEventConverter) : new GenericApplicationService<>(eventStore, cloudEventConverter, RetryStrategy.none());
    }
}