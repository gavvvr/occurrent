package se.haleby.occurrent.changestreamer.mongodb.spring.blocking;

import se.haleby.occurrent.changestreamer.api.blocking.Subscription;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public class MongoDBSpringSubscription implements Subscription {

    private final String subscriptionId;
    private final org.springframework.data.mongodb.core.messaging.Subscription subscription;

    public MongoDBSpringSubscription(String subscriptionId, org.springframework.data.mongodb.core.messaging.Subscription subscription) {
        this.subscriptionId = subscriptionId;
        this.subscription = subscription;
    }

    @Override
    public String id() {
        return subscriptionId;
    }

    @Override
    public void await() {
        try {
            subscription.await(Duration.of(Long.MAX_VALUE, ChronoUnit.CENTURIES));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean await(Duration timeout) {
        try {
            return subscription.await(timeout);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MongoDBSpringSubscription)) return false;
        MongoDBSpringSubscription that = (MongoDBSpringSubscription) o;
        return Objects.equals(subscriptionId, that.subscriptionId) &&
                Objects.equals(subscription, that.subscription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subscriptionId, subscription);
    }

    @Override
    public String toString() {
        return "MongoDBSpringSubscription{" +
                "subscriptionId='" + subscriptionId + '\'' +
                ", subscription=" + subscription +
                '}';
    }
}