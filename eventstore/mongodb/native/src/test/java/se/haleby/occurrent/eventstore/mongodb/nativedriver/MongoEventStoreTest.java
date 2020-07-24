package se.haleby.occurrent.eventstore.mongodb.nativedriver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import se.haleby.occurrent.domain.DomainEvent;
import se.haleby.occurrent.domain.Name;
import se.haleby.occurrent.domain.NameDefined;
import se.haleby.occurrent.domain.NameWasChanged;
import se.haleby.occurrent.eventstore.api.DuplicateCloudEventException;
import se.haleby.occurrent.eventstore.api.WriteCondition;
import se.haleby.occurrent.eventstore.api.WriteConditionNotFulfilledException;
import se.haleby.occurrent.eventstore.api.blocking.EventStream;
import se.haleby.occurrent.testsupport.mongodb.FlushMongoDBExtension;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.vavr.API.*;
import static io.vavr.Predicates.is;
import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static se.haleby.occurrent.cloudevents.OccurrentCloudEventExtension.STREAM_ID;
import static se.haleby.occurrent.domain.Composition.chain;
import static se.haleby.occurrent.eventstore.api.Condition.*;
import static se.haleby.occurrent.eventstore.api.WriteCondition.*;
import static se.haleby.occurrent.time.TimeConversion.toLocalDateTime;

@Testcontainers
class MongoEventStoreTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.2.7");
    private static final URI NAME_SOURCE = URI.create("http://name");
    private MongoEventStore eventStore;

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl()));
    private ObjectMapper objectMapper;

    @BeforeEach
    void create_mongo_event_store() {
        StreamConsistencyGuarantee consistency = StreamConsistencyGuarantee.transactional("consistency");
        eventStore = newMongoEventStore(consistency);
        objectMapper = new ObjectMapper();
    }

    @Test
    void can_read_and_write_single_event_to_mongo_event_store() {
        LocalDateTime now = LocalDateTime.now();

        // When
        List<DomainEvent> events = Name.defineName(UUID.randomUUID().toString(), now, "John Doe");
        persist("name", events);

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isEqualTo(1),
                () -> assertThat(readEvents).hasSize(1),
                () -> assertThat(readEvents).containsExactlyElementsOf(events)
        );
    }

    @Test
    void can_read_and_write_multiple_events_at_once_to_mongo_event_store() {
        LocalDateTime now = LocalDateTime.now();
        List<DomainEvent> events = chain(Name.defineName(UUID.randomUUID().toString(), now, "Hello World"), es -> Name.changeName(es, UUID.randomUUID().toString(), now, "John Doe"));

        // When
        persist("name", events);

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isEqualTo(1),
                () -> assertThat(readEvents).hasSize(2),
                () -> assertThat(readEvents).containsExactlyElementsOf(events)
        );
    }

    @Test
    void can_read_and_write_multiple_events_at_different_occasions_to_mongo_event_store() {
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        // When
        persist("name", streamVersionEq(0), nameDefined);
        persist("name", streamVersionEq(1), nameWasChanged1);
        persist("name", streamVersionEq(2), nameWasChanged2);

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isEqualTo(3),
                () -> assertThat(readEvents).hasSize(3),
                () -> assertThat(readEvents).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2)
        );
    }

    @Test
    void can_read_events_with_skip_and_limit() {
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        // When
        persist("name", streamVersionEq(0), nameDefined);
        persist("name", streamVersionEq(1), nameWasChanged1);
        persist("name", streamVersionEq(2), nameWasChanged2);

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name", 1, 1);
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isEqualTo(3),
                () -> assertThat(readEvents).hasSize(1),
                () -> assertThat(readEvents).containsExactly(nameWasChanged1)
        );
    }

    @Test
    void any_write_condition_may_be_explicitly_specified_when_stream_consistency_guarantee_is_none() {
        eventStore = newMongoEventStore(StreamConsistencyGuarantee.none());
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");

        // When
        persist("name", anyStreamVersion(), nameDefined);

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isZero(),
                () -> assertThat(readEvents).hasSize(1),
                () -> assertThat(readEvents).containsExactly(nameDefined)
        );
    }

    @Test
    void read_skew_is_not_allowed_for_native_implementation_when_stream_consistency_guarantee_is_transactional() {
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        persist("name", streamVersionEq(0), nameDefined);
        persist("name", streamVersionEq(1), nameWasChanged1);
        // When
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        persist("name", streamVersionEq(2), nameWasChanged2);

        // Then
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(eventStream.version()).isEqualTo(2),
                () -> assertThat(readEvents).hasSize(2),
                () -> assertThat(readEvents).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @Test
    void events_that_are_inserted_before_duplicate_event_in_batch_is_written_when_stream_consistency_guarantee_is_none() {
        eventStore = newMongoEventStore(StreamConsistencyGuarantee.none());
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        // When
        Throwable throwable = catchThrowable(() -> persist("name", Stream.of(nameDefined, nameWasChanged1, nameWasChanged1, nameWasChanged2)));

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(eventStream.version()).isZero(),
                // MongoDB inserts all events up until the error but ignores events after the failed events..
                () -> assertThat(readEvents).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @Test
    void events_that_are_inserted_before_the_duplicate_event_in_batch_are_retained_when_stream_consistency_guarantee_is_none() {
        eventStore = newMongoEventStore(StreamConsistencyGuarantee.none());
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        persist("name", Stream.of(nameDefined, nameWasChanged1));

        // When
        Throwable throwable = catchThrowable(() -> persist("name", Stream.of(nameWasChanged2, nameWasChanged1)));


        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(eventStream.version()).isZero(),
                () -> assertThat(readEvents).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2)
        );
    }

    @Test
    void no_events_are_inserted_when_batch_contains_duplicate_events_when_stream_consistency_guarantee_is_transactional() {
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        // When
        Throwable throwable = catchThrowable(() -> persist("name", streamVersionEq(0), Stream.of(nameDefined, nameWasChanged1, nameWasChanged1, nameWasChanged2)));

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(eventStream.version()).isEqualTo(0),
                () -> assertThat(readEvents).isEmpty()
        );
    }

    @Test
    void no_events_are_inserted_when_batch_contains_event_that_has_already_been_persisted_when_stream_consistency_guarantee_is_transactional() {
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        persist("name", streamVersionEq(0), Stream.of(nameDefined, nameWasChanged1));

        // When
        Throwable throwable = catchThrowable(() -> persist("name", streamVersionEq(1), Stream.of(nameWasChanged2, nameWasChanged1)));

        // Then
        EventStream<CloudEvent> eventStream = eventStore.read("name");
        List<DomainEvent> readEvents = deserialize(eventStream.events());

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(eventStream.version()).isEqualTo(1),
                () -> assertThat(readEvents).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @SuppressWarnings("ConstantConditions")
    @Nested
    @DisplayName("deletion when stream consistency guarantee is transactional")
    class DeleteWhenStreamConsistencyGuaranteeIsTransactional {

        private MongoDatabase database;

        @BeforeEach
        void createMongoDatabase() {
            ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
            database = MongoClients.create(connectionString).getDatabase(connectionString.getDatabase());
        }

        @Test
        void deleteAllEventsInEventStream_deletes_all_events_but_retains_metadata() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteAllEventsInEventStream("name");

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isEqualTo(1),
                    () -> assertThat(readEvents).isEmpty(),
                    () -> assertThat(database.getCollection("consistency").countDocuments(Filters.eq("_id", "name"))).isNotZero()
            );
        }

        @Test
        void deleteEventStream_deletes_all_events_in_event_stream_when_stream_consistency_guarantee_is_transactional() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteEventStream("name");

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isZero(),
                    () -> assertThat(readEvents).isEmpty(),
                    () -> assertThat(eventStore.exists("name")).isFalse(),
                    () -> assertThat(database.getCollection("events").countDocuments(Filters.eq(STREAM_ID, "name"))).isZero()
            );
        }

        @Test
        void deleteEvent_deletes_only_specific_event_in_event_stream() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteEvent(nameWasChanged1.getEventId(), NAME_SOURCE);

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isEqualTo(1),
                    () -> assertThat(readEvents).containsExactly(nameDefined),
                    () -> assertThat(eventStore.exists("name")).isTrue(),
                    () -> assertThat(database.getCollection("events").countDocuments(Filters.eq(STREAM_ID, "name"))).isNotZero()
            );
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Nested
    @DisplayName("deletion when stream consistency guarantee is none")
    class DeleteWhenStreamConsistencyGuaranteeIsNone {

        private MongoDatabase database;

        @BeforeEach
        void init() {
            ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
            database = MongoClients.create(connectionString).getDatabase(connectionString.getDatabase());
            eventStore = newMongoEventStore(StreamConsistencyGuarantee.none());
        }

        @Test
        void deleteAllEventsInEventStream_deletes_all_events() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteAllEventsInEventStream("name");

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isZero(),
                    () -> assertThat(readEvents).isEmpty()
            );
        }

        @Test
        void deleteEventStream_deletes_all_events_in_event_stream_when_stream_consistency_guarantee_is_transactional() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteEventStream("name");

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isZero(),
                    () -> assertThat(readEvents).isEmpty(),
                    () -> assertThat(eventStore.exists("name")).isFalse(),
                    () -> assertThat(database.getCollection("events").countDocuments(Filters.eq(STREAM_ID, "name"))).isZero()
            );
        }

        @Test
        void deleteEvent_deletes_only_specific_event_in_event_stream() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Stream.of(nameDefined, nameWasChanged1));

            // When
            eventStore.deleteEvent(nameWasChanged1.getEventId(), NAME_SOURCE);

            // Then
            EventStream<CloudEvent> eventStream = eventStore.read("name");
            List<DomainEvent> readEvents = deserialize(eventStream.events());
            assertAll(
                    () -> assertThat(eventStream.version()).isZero(),
                    () -> assertThat(readEvents).containsExactly(nameDefined),
                    () -> assertThat(eventStore.exists("name")).isTrue(),
                    () -> assertThat(database.getCollection("events").countDocuments(Filters.eq(STREAM_ID, "name"))).isNotZero()
            );
        }
    }

    @Nested
    @DisplayName("Conditionally Write to Mongo Event Store")
    class ConditionallyWriteToMongoEventStore {

        LocalDateTime now = LocalDateTime.now();

        @Nested
        @DisplayName("eq")
        class Eq {

            @Test
            void writes_events_when_stream_version_matches_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1);

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersionEq(eventStream1.version()), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_does_not_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersionEq(10), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be equal to 10 but was 1.");
            }
        }

        @Nested
        @DisplayName("ne")
        class Ne {

            @Test
            void writes_events_when_stream_version_does_not_match_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(ne(20L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(ne(1L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to not be equal to 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("lt")
        class Lt {

            @Test
            void writes_events_when_stream_version_is_less_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(lt(10L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(lt(0L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than 0 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(lt(1L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("gt")
        class Gt {

            @Test
            void writes_events_when_stream_version_is_greater_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(gt(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(gt(100L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than 100 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(gt(1L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("lte")
        class Lte {

            @Test
            void writes_events_when_stream_version_is_less_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(lte(10L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }


            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(lte(1L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(lte(0L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than or equal to 0 but was 1.");
            }
        }

        @Nested
        @DisplayName("gte")
        class Gte {

            @Test
            void writes_events_when_stream_version_is_greater_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(gte(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(gte(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(gte(100L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 100 but was 1.");
            }
        }

        @Nested
        @DisplayName("and")
        class And {

            @Test
            void writes_events_when_stream_version_is_when_all_conditions_match_and_expression() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(and(gte(0L), lt(100L), ne(40L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_any_of_the_operations_in_the_and_expression_is_not_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(and(gte(0L), lt(100L), ne(1L))), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 0 and to be less than 100 and to not be equal to 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("or")
        class Or {

            @Test
            void writes_events_when_stream_version_is_when_any_condition_in_or_expression_matches() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(or(gte(100L), lt(0L), ne(40L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_none_of_the_operations_in_the_and_expression_is_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(or(gte(100L), lt(1L))), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 100 or to be less than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("not")
        class Not {

            @Test
            void writes_events_when_stream_version_is_not_matching_condition() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = eventStore.read("name");
                persist(eventStream1.id(), streamVersion(not(eq(100L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2.events())).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_condition_is_fulfilled_but_should_not_be_so() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", streamVersion(not(eq(1L))), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version not to be equal to 1 but was 1.");
            }
        }
    }

    private List<DomainEvent> deserialize(Stream<CloudEvent> events) {
        return events
                .map(CloudEvent::getData)
                // @formatter:off
                .map(unchecked(data -> objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {})))
                // @formatter:on
                .map(event -> {
                    Instant instant = Instant.ofEpochMilli((long) event.get("time"));
                    LocalDateTime time = LocalDateTime.ofInstant(instant, UTC);
                    String eventId = (String) event.get("eventId");
                    String name = (String) event.get("name");
                    return Match(event.get("type")).of(
                            Case($(is(NameDefined.class.getSimpleName())), e -> new NameDefined(eventId, time, name)),
                            Case($(is(NameWasChanged.class.getSimpleName())), e -> new NameWasChanged(eventId, time, name))
                    );
                })
                .collect(Collectors.toList());

    }

    private void persist(String eventStreamId, WriteCondition writeCondition, DomainEvent event) {
        List<DomainEvent> events = new ArrayList<>();
        events.add(event);
        persist(eventStreamId, writeCondition, events);
    }

    private void persist(String eventStreamId, WriteCondition writeCondition, List<DomainEvent> events) {
        persist(eventStreamId, writeCondition, events.stream());
    }

    private void persist(String eventStreamId, WriteCondition writeCondition, Stream<DomainEvent> events) {
        eventStore.write(eventStreamId, writeCondition, events.map(convertDomainEventToCloudEvent()));
    }

    private void persist(String eventStreamId, DomainEvent event) {
        List<DomainEvent> events = new ArrayList<>();
        events.add(event);
        persist(eventStreamId, events);
    }

    private void persist(String eventStreamId, List<DomainEvent> events) {
        persist(eventStreamId, events.stream());
    }

    private void persist(String eventStreamId, Stream<DomainEvent> events) {
        eventStore.write(eventStreamId, events.map(convertDomainEventToCloudEvent()));
    }

    @NotNull
    private Function<DomainEvent, CloudEvent> convertDomainEventToCloudEvent() {
        return e -> CloudEventBuilder.v1()
                .withId(e.getEventId())
                .withSource(NAME_SOURCE)
                .withType(e.getClass().getSimpleName())
                .withTime(toLocalDateTime(e.getTimestamp()).atZone(UTC))
                .withSubject(e.getName())
                .withDataContentType("application/json")
                .withData(serializeEvent(e))
                .build();
    }

    private byte[] serializeEvent(DomainEvent e) {
        try {
            return objectMapper.writeValueAsBytes(new HashMap<String, Object>() {{
                put("type", e.getClass().getSimpleName());
                put("eventId", e.getEventId());
                put("name", e.getName());
                put("time", e.getTimestamp().getTime());
            }});
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }
    }

    private MongoEventStore newMongoEventStore(StreamConsistencyGuarantee consistency) {
        ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        return new MongoEventStore(connectionString, consistency);
    }
}