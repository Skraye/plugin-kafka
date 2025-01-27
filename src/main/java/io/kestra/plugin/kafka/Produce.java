package io.kestra.plugin.kafka;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.Serializer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@io.swagger.v3.oas.annotations.media.Schema(
    title = "Produce message in a Kafka topic"
)
@Plugin(
    examples = {
        @Example(
            title = "Read a csv, transform it to right format & produce it to Kafka",
            full = true,
            code = {
                "id: produce",
                "namespace: io.kestra.tests",
                "inputs:",
                "  - type: FILE",
                "    name: file",
                "",
                "tasks:",
                "  - id: csvReader",
                "    type: io.kestra.plugin.serdes.csv.CsvReader",
                "    from: \"{{ inputs.file }}\"",
                "  - id: fileTransform",
                "    type: io.kestra.plugin.scripts.nashorn.FileTransform",
                "    from: \"{{ outputs.csvReader.uri }}\"",
                "    script: |",
                "      var result = {",
                "        \"key\": row.id,",
                "        \"value\": {",
                "          \"username\": row.username,",
                "          \"tweet\": row.tweet",
                "        },",
                "        \"timestamp\": row.timestamp,",
                "        \"headers\": {",
                "          \"key\": \"value\"",
                "        }",
                "      };",
                "      row = result",
                "  - id: produce",
                "    type: io.kestra.plugin.kafka.Produce",
                "    from: \"{{ outputs.fileTransform.uri }}\"",
                "    keySerializer: STRING",
                "    properties:",
                "      bootstrap.servers: local:9092",
                "    serdeProperties:",
                "      schema.registry.url: http://local:8085",
                "    topic: test_kestra",
                "    valueAvroSchema: |",
                "      {\"type\":\"record\",\"name\":\"twitter_schema\",\"namespace\":\"io.kestra.examples\",\"fields\":[{\"name\":\"username\",\"type\":\"string\"},{\"name\":\"tweet\",\"type\":\"string\"}]}",
                "    valueSerializer: AVRO\n"
            }
        )
    }
)
public class Produce extends AbstractKafkaConnection implements RunnableTask<Produce.Output> {
    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Kafka topic where to send message"
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private String topic;

    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Source of message send",
        description = "Can be an internal storage uri, a map or a list." +
            "with the following format: key, value, partition, timestamp, headers"
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private Object from;

    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Serializer used for the key"
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private SerdeType keySerializer;

    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Serializer used for the value"
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private SerdeType valueSerializer;

    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Avro Schema if key is `AVRO` type"
    )
    @PluginProperty(dynamic = true)
    private String keyAvroSchema;

    @io.swagger.v3.oas.annotations.media.Schema(
        title = "Avro Schema if value is `AVRO` type"
    )
    @PluginProperty(dynamic = true)
    private String valueAvroSchema;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Output run(RunContext runContext) throws Exception {
        // ugly hack to force use of Kestra plugins classLoader
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        Properties properties = this.createProperties(this.properties, runContext);
        Properties serdesProperties = this.createProperties(this.serdeProperties, runContext);

        Serializer keySerial = this.getTypedSerializer(this.keySerializer);
        Serializer valSerial = this.getTypedSerializer(this.valueSerializer);

        keySerial.configure(serdesProperties, true);
        valSerial.configure(serdesProperties, false);

        KafkaProducer<Object, Object> producer = new KafkaProducer<Object, Object>(properties, keySerial, valSerial);

        Integer count = 1;

        if (this.from instanceof String || this.from instanceof List) {
            Flowable<Object> flowable;
            Flowable<Integer> resultFlowable;
            if (this.from instanceof String) {
                URI from = new URI(runContext.render((String) this.from));
                try (BufferedReader inputStream = new BufferedReader(new InputStreamReader(runContext.uriToInputStream(from)))) {
                    flowable = Flowable.create(FileSerde.reader(inputStream), BackpressureStrategy.BUFFER);
                    resultFlowable = this.buildFlowable(flowable, runContext, producer);

                    count = resultFlowable
                        .reduce(Integer::sum)
                        .blockingGet();
                }
            } else {
                flowable = Flowable.fromArray(((List<Object>) this.from).toArray());
                resultFlowable = this.buildFlowable(flowable, runContext, producer);

                count = resultFlowable
                    .reduce(Integer::sum)
                    .blockingGet();
            }
        } else {
            producer.send(this.producerRecord(runContext, (Map<String, Object>) this.from));
        }

        runContext.metric(Counter.of("records", count));

        producer.flush();
        producer.close();

        return Output.builder()
            .messagesCount(count)
            .build();
    }

    private GenericRecord buildAvroRecord(RunContext runContext, String dataSchema, Map<String, Object> map) throws Exception {
        Schema.Parser parser = new Schema.Parser();
        Schema schema = parser.parse(runContext.render(dataSchema));
        GenericRecord avroRecord = new GenericData.Record(schema);
        for (String k : map.keySet()) {
            avroRecord.put(k, map.get(k));
        }
        return avroRecord;
    }

    @SuppressWarnings("unchecked")
    private Flowable<Integer> buildFlowable(Flowable<Object> flowable, RunContext runContext, KafkaProducer<Object, Object> producer) {
        return flowable
            .map(row -> {
                producer.send(this.producerRecord(runContext, (Map<String, Object>) row));
                return 1;
            });
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<Object, Object> producerRecord(RunContext runContext, Map<String, Object> map) throws Exception {
        Object key;
        Object value;

        if (this.keySerializer == SerdeType.AVRO) {
            key = buildAvroRecord(runContext, this.keyAvroSchema, (Map<String, Object>) map.get("key"));
        } else {
            key = map.get("key");
        }

        if (this.valueSerializer == SerdeType.AVRO) {
            value = buildAvroRecord(runContext, this.valueAvroSchema, (Map<String, Object>) map.get("value"));
        } else {
            value = map.get("value");
        }

        return new ProducerRecord<>(
            this.topic,
            (Integer) map.get("partition"),
            this.processTimestamp(map.get("timestamp")),
            key,
            value,
            this.processHeaders(map.get("headers"))
        );
    }

    private Long processTimestamp(Object timestamp) {
        if (timestamp == null) {
            return null;
        }

        if (timestamp instanceof Long) {
            return (Long) timestamp;
        }

        if (timestamp instanceof ZonedDateTime) {
            return ((ZonedDateTime) timestamp).toInstant().toEpochMilli();
        }

        if (timestamp instanceof Instant) {
            return ((Instant) timestamp).toEpochMilli();
        }

        if (timestamp instanceof LocalDateTime) {
            return ((LocalDateTime) timestamp).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }

        if (timestamp instanceof String) {
            try {
                return ZonedDateTime.parse((String) timestamp).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                return Instant.parse((String) timestamp).toEpochMilli();
            }
        }

        throw new IllegalArgumentException("Invalid type of timestamp with type '" + timestamp.getClass() + "'");
    }

    private Iterable<Header> processHeaders(Object headers) {
        if (headers == null) {
            return null;
        }

        if (headers instanceof Map) {
            return ((Map<?, ?>) headers)
                .entrySet()
                .stream()
                .map(o -> new RecordHeader((String)o.getKey(), ((String)o.getValue()).getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.toList());
        }

        throw new IllegalArgumentException("Invalid type of headers with type '" + headers.getClass() + "'");
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @io.swagger.v3.oas.annotations.media.Schema(
            title = "Number of message produced"
        )
        private final Integer messagesCount;
    }
}
