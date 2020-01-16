/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package io.dapr.actors.runtime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import io.dapr.utils.DurationUtils;
import io.dapr.utils.ObjectSerializer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Serializes and deserializes an object.
 */
public class ActorStateSerializer extends ObjectSerializer {

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> String serializeString(T state) throws IOException {
        if (state == null) {
            return null;
        }

        if (state.getClass() == ActorTimer.class) {
            // Special serializer for this internal classes.
            return serialize((ActorTimer) state);
        }

        if (state.getClass() == ActorReminderParams.class) {
            // Special serializer for this internal classes.
            return serialize((ActorReminderParams) state);
        }

        // Is not an special case.
        return super.serializeString(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T deserialize(Object value, Class<T> clazz) throws IOException {
        if (clazz == ActorReminderParams.class) {
            // Special serializer for this internal classes.
            return (T) deserializeActorReminder(value);
        }

        // Is not one the special cases.
        return super.deserialize(value, clazz);
    }

    /**
     * Extracts the response object from a JSON Payload where data is in "data" attribute.
     *
     * @param payload JSON payload containing "data".
     * @param clazz    Expected response class.
     * @param <T>      Expected response type.
     * @return Response object, null or RuntimeException.
     */
    public <T> T unwrapData(final String payload, Class<T> clazz) {
        if (payload == null) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            if (root == null) {
                return null;
            }

            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                return null;
            }

            byte[] data = dataNode.binaryValue();
            if (data == null) {
                return null;
            }

            return this.deserialize(new String(data, StandardCharsets.UTF_8), clazz);
        } catch (IOException e) {
            // Wrap it to make Mono happy.
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds the request to invoke an API for Actors.
     *
     * @param object Object to be serialized and wrapped into the "data" attribute in a JSON object.
     * @return String to be sent to Dapr's API.
     * @throws IOException In case it cannot generate String.
     */
    public String wrapData(final Object object) throws IOException {
        if (object == null) {
            return null;
        }

        byte[] data = this.serialize(object);

        try (Writer writer = new StringWriter()) {
            JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
            generator.writeStartObject();
            if (data != null) {
                generator.writeBinaryField("data", data);
            }
            generator.writeEndObject();
            generator.close();
            writer.flush();
            return writer.toString();
        }
    }

    /**
     * Faster serialization for params of Actor's timer.
     *
     * @param timer Timer's to be serialized.
     * @return JSON String.
     * @throws IOException If cannot generate JSON.
     */
    private String serialize(ActorTimer<?> timer) throws IOException {
        if (timer == null) {
            return null;
        }

        try (Writer writer = new StringWriter()) {
            JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
            generator.writeStartObject();
            generator.writeStringField("dueTime", DurationUtils.ConvertDurationToDaprFormat(timer.getDueTime()));
            generator.writeStringField("period", DurationUtils.ConvertDurationToDaprFormat(timer.getPeriod()));
            generator.writeStringField("callback", timer.getCallback());
            if (timer.getState() != null) {
                generator.writeStringField("data", this.serializeString(timer.getState()));
            }
            generator.writeEndObject();
            generator.close();
            writer.flush();
            return writer.toString();
        }
    }

    /**
     * Faster serialization for Actor's reminder.
     *
     * @param reminder Reminder to be serialized.
     * @return JSON String.
     * @throws IOException If cannot generate JSON.
     */
    private String serialize(ActorReminderParams reminder) throws IOException {
        try (Writer writer = new StringWriter()) {
            JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
            generator.writeStartObject();
            generator.writeStringField("dueTime", DurationUtils.ConvertDurationToDaprFormat(reminder.getDueTime()));
            generator.writeStringField("period", DurationUtils.ConvertDurationToDaprFormat(reminder.getPeriod()));
            if (reminder.getData() != null) {
                generator.writeStringField("data", reminder.getData());
            }
            generator.writeEndObject();
            generator.close();
            writer.flush();
            return writer.toString();
        }
    }

    /**
     * Deserializes an Actor Reminder.
     *
     * @param value Content to be deserialized.
     * @return Actor Reminder.
     * @throws IOException If cannot parse JSON.
     */
    private ActorReminderParams deserializeActorReminder(Object value) throws IOException {
        if (value == null) {
            return null;
        }

        JsonNode node;
        if (value instanceof byte[]) {
            node = OBJECT_MAPPER.readTree((byte[]) value);
        } else {
            node = OBJECT_MAPPER.readTree(value.toString());
        }
        Duration dueTime = DurationUtils.ConvertDurationFromDaprFormat(node.get("dueTime").asText());
        Duration period = DurationUtils.ConvertDurationFromDaprFormat(node.get("period").asText());
        String data = node.get("data") != null ? node.get("data").asText() : null;

        return new ActorReminderParams(data, dueTime, period);
    }

}