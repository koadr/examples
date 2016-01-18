package io.confluent.examples.streams;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreaming;
import org.apache.kafka.streams.StreamingConfig;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster;
import io.confluent.examples.streams.utils.SystemTimestampExtractor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Streams is able to read data from an input topic and write the same data (as-is) to
 * a new output topic.
 */
public class PassThroughIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(PassThroughIntegrationTest.class);

  private static EmbeddedSingleNodeKafkaCluster cluster = null;
  private static String inputTopic = "inputTopic";
  private static String outputTopic = "outputTopic";

  @BeforeClass
  public static void startKafkaCluster() throws Exception {
    cluster = new EmbeddedSingleNodeKafkaCluster();
    cluster.createTopic(inputTopic);
    cluster.createTopic(outputTopic);
  }

  @AfterClass
  public static void stopKafkaCluster() throws IOException {
    if (cluster != null) {
      cluster.stop();
    }
  }

  @Test
  public void shouldWriteTheInputDataAsIsToTheOutputTopic() throws Exception {
    List<String> inputLines = Arrays.asList(
        "hello world",
        "the world is not enough",
        "the world of the stock market is coming to an end"
    );

    //
    // Step 1: Configure and start the Streams job.
    //
    KStreamBuilder builder = new KStreamBuilder();

    // Write the input data as-is to the output topic.
    builder.stream(inputTopic).to(outputTopic);

    Properties streamingConfiguration = new Properties();
    streamingConfiguration.put(StreamingConfig.JOB_ID_CONFIG, "noop-test-streams");
    streamingConfiguration.put(StreamingConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
    streamingConfiguration.put(StreamingConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    streamingConfiguration.put(StreamingConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    streamingConfiguration.put(StreamingConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    streamingConfiguration.put(StreamingConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    streamingConfiguration.put(StreamingConfig.TIMESTAMP_EXTRACTOR_CLASS_CONFIG, SystemTimestampExtractor.class);
    // You can also define consumer configuration settings.
    //streamingConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    KafkaStreaming kafkaStreaming = new KafkaStreaming(builder, new StreamingConfig(streamingConfiguration));
    kafkaStreaming.start();

    // Wait briefly for the streaming job to be fully up and running (otherwise it might miss
    // some or all of the input data we produce below).
    Thread.sleep(1000);

    //
    // Step 2: Produce some input data to the input topic.
    //
    Properties producerConfig = new Properties();
    producerConfig.put("bootstrap.servers", cluster.bootstrapServers());
    producerConfig.put("acks", "all");
    producerConfig.put("retries", 0);
    producerConfig.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    producerConfig.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

    Producer<String, String> producer = new KafkaProducer<>(producerConfig);
    for (String line : inputLines) {
      log.debug("Producing message with value '{}' to topic {}", line, inputTopic);
      Future<RecordMetadata> f = producer.send(new ProducerRecord<>(inputTopic, line));
      f.get();
    }
    producer.flush();
    producer.close();

    // Give the streaming job some time to do its work.
    Thread.sleep(1000);
    kafkaStreaming.close();

    //
    // Step 3: Verify the job's output data.
    //
    Properties consumerConfig = new Properties();
    consumerConfig.put("bootstrap.servers", cluster.bootstrapServers());
    consumerConfig.put("group.id", "noop-test-standard-consumer");
    consumerConfig.put("auto.offset.reset", "earliest");
    consumerConfig.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    consumerConfig.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig);
    consumer.subscribe(Collections.singletonList(outputTopic));
    int pollIntervalMs = 100;
    int maxTotalPollTimeMs = 2000;
    int totalPollTimeMs = 0;
    List<String> consumedLines = new LinkedList<>();
    while (totalPollTimeMs < maxTotalPollTimeMs && consumedLines.size() < inputLines.size()) {
      totalPollTimeMs += pollIntervalMs;
      ConsumerRecords<String, String> records = consumer.poll(pollIntervalMs);
      for (ConsumerRecord<String, String> record : records) {
        log.debug("Received message with offset = {}, key = {}, value = {}",
            record.offset(), record.key(), record.value());
        consumedLines.add(record.value());
      }
    }

    assertThat(consumedLines).isEqualTo(inputLines);
  }

}