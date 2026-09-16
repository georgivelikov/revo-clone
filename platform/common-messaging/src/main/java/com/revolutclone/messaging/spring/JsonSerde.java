package com.revolutclone.messaging.spring;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 backed {@link Serde} for services that want a typed Kafka producer/consumer
 * directly, rather than going through the outbox's raw-JSON-string path (see
 * {@link KafkaEventPublisher}, {@link KafkaEventHandlerAdapter}).
 */
public class JsonSerde<T> implements Serde<T> {

    private final Class<T> targetType;
    private final JsonMapper jsonMapper;

    public JsonSerde(Class<T> targetType, JsonMapper jsonMapper) {
        this.targetType = targetType;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> data == null ? null : jsonMapper.writeValueAsBytes(data);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, data) -> data == null ? null : jsonMapper.readValue(data, targetType);
    }
}
