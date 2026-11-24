package io.konzert.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * The collaboration bus (ADR-0001). One consumer thread, one producer, and a
 * bounded "read what is already there" helper.
 *
 * Note what is NOT here: any filtering. Everything this class delivers goes
 * through the agent's intake pipeline before it is allowed to cost anything.
 * Keeping the two apart is what makes the funnel honest — the "seen" counter is
 * genuinely everything that arrived.
 */
@ApplicationScoped
public class KafkaBus {

    private static final Logger LOG = Logger.getLogger(KafkaBus.class);

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "kafka.bootstrap", defaultValue = "konzert-kafka-kafka-bootstrap.kafka.svc:9092")
    String bootstrap;
    /**
     * Optional, not defaultValue="": SmallRye treats an empty string as "no
     * value at all" and fails the conversion rather than handing back "".
     * Unset means a fresh random group, which is what an observer wants.
     */
    @ConfigProperty(name = "kafka.group-id")
    Optional<String> groupId;

    private volatile KafkaProducer<String, String> producer;
    private volatile boolean running;
    private ExecutorService loop;

    private Properties common() {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        return p;
    }

    // --------------------------------------------------------------- publish

    public void publish(String topic, CloudEvent event) {
        publish(topic, event.correlationId(), event.toJson(mapper));
    }

    public synchronized void publish(String topic, String key, String json) {
        if (producer == null) {
            producer = new KafkaProducer<>(common());
        }
        producer.send(new ProducerRecord<>(topic, key, json), (md, ex) -> {
            if (ex != null) {
                // A demo must not die because the bus hiccuped mid-incident.
                LOG.errorf("publish to %s failed: %s", topic, ex.getMessage());
            }
        });
    }

    // --------------------------------------------------------------- consume

    /**
     * Starts one consumer thread for the given topics. The callback gets
     * (topic, event) for every record — filtering happens downstream.
     */
    public synchronized void consume(List<String> topics, BiConsumer<String, JsonNode> onEvent) {
        if (running) {
            LOG.warn("consumer already running; ignoring second start");
            return;
        }
        if (topics == null || topics.isEmpty()) {
            LOG.warn("no topics to subscribe to — the agent will hear nothing");
            return;
        }
        running = true;
        loop = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kafka-consume");
            t.setDaemon(true);
            return t;
        });
        loop.submit(() -> pollLoop(topics, onEvent));
        LOG.infof("subscribed to %s as %s", topics, effectiveGroupId());
    }

    private void pollLoop(List<String> topics, BiConsumer<String, JsonNode> onEvent) {
        Properties p = common();
        p.put("group.id", effectiveGroupId());
        p.put("auto.offset.reset", "latest");
        p.put("enable.auto.commit", "true");
        // Pinned, not left to the client default: replicas of one agent share a
        // group, and the range assignor gives partition N of every subscribed
        // topic to the same replica. With records keyed by correlation ID and
        // topics co-partitioned, one incident stays on one replica across topics.
        p.put("partition.assignment.strategy", "org.apache.kafka.clients.consumer.RangeAssignor");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(topics);
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        onEvent.accept(rec.topic(), mapper.readTree(rec.value()));
                    } catch (Exception e) {
                        // One malformed message must not stop the ear.
                        LOG.warnf("skipping unparseable message on %s: %s", rec.topic(), e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "consumer loop stopped");
        }
    }

    /**
     * Reads what is already on the topics, newest-last, without joining a
     * consumer group. This is what the panel's history and the intake dry run
     * are built on: real traffic, replayed, costing nothing.
     */
    public List<Sample> readRecent(List<String> topics, int maxPerTopic, long lookbackMillis) {
        List<Sample> out = new ArrayList<>();
        Properties p = common();
        p.put("group.id", "replay-" + UUID.randomUUID());
        p.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> parts = new ArrayList<>();
            for (String topic : topics) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic);
                if (infos == null) {
                    continue;
                }
                infos.forEach(i -> parts.add(new TopicPartition(topic, i.partition())));
            }
            if (parts.isEmpty()) {
                return out;
            }
            consumer.assign(parts);

            // Start from a timestamp when we can; fall back to the beginning.
            long since = System.currentTimeMillis() - lookbackMillis;
            Map<TopicPartition, Long> query = new HashMap<>();
            parts.forEach(tp -> query.put(tp, since));
            var offsets = consumer.offsetsForTimes(query);
            for (TopicPartition tp : parts) {
                var off = offsets.get(tp);
                if (off != null) {
                    consumer.seek(tp, off.offset());
                } else {
                    consumer.seekToBeginning(List.of(tp));
                }
            }

            long deadline = System.currentTimeMillis() + 4000;
            int budget = maxPerTopic * Math.max(1, topics.size());
            // The first polls are usually empty while the fetch session is set
            // up; only a run of empty polls means the topic is actually drained.
            int emptyPolls = 0;
            while (System.currentTimeMillis() < deadline && out.size() < budget) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(400));
                if (records.isEmpty()) {
                    if (++emptyPolls >= 3) {
                        break;
                    }
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        out.add(new Sample(rec.topic(), rec.timestamp(), mapper.readTree(rec.value())));
                    } catch (Exception ignored) {
                        // not our problem during a replay
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("replay failed: %s", e.getMessage());
        }
        out.sort(java.util.Comparator.comparingLong(Sample::timestamp));
        return out;
    }

    private String effectiveGroupId() {
        return groupId.filter(s -> !s.isBlank())
                .orElseGet(() -> "agent-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @PreDestroy
    void stop() {
        running = false;
        if (loop != null) {
            loop.shutdownNow();
        }
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
    }

    public record Sample(String topic, long timestamp, JsonNode event) {}
}
