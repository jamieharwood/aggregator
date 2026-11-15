package com.aggregator.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aggregator.config.ProxyProperties;
import com.aggregator.routing.InvalidWorkflowDefinitionException;
import com.aggregator.routing.WorkflowDefinition;
import com.aggregator.routing.WorkflowDefinition.AsyncFailureMode;
import com.aggregator.routing.WorkflowDefinition.EndpointDefinition;
import com.aggregator.routing.WorkflowDefinition.MatchDefinition;
import com.aggregator.routing.WorkflowDefinitionRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Repository;

/**
 * Reads workflow definitions from MongoDB: one document per workflow, with the workflow name as
 * {@code _id}. See the README for the document shape.
 */
@Repository
public class MongoWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final MongoCollection<Document> collection;

    public MongoWorkflowDefinitionRepository(MongoDatabase database, ProxyProperties properties) {
        this.collection = database.getCollection(properties.workflowStore().collection());
    }

    @Override
    public Map<String, WorkflowDefinition> findAll() {
        Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();
        for (Document document : collection.find().sort(Sorts.ascending("_id"))) {
            Object id = document.get("_id");
            if (!(id instanceof String name) || name.isBlank()) {
                throw new InvalidWorkflowDefinitionException(
                        "Workflow document _id must be a non-empty string (the workflow name) but was: " + id);
            }
            definitions.put(name, toDefinition(name, document));
        }
        return definitions;
    }

    private static WorkflowDefinition toDefinition(String name, Document document) {
        String at = "workflow '" + name + "'";
        Document match = document(at, document, "match");
        MatchDefinition matchDefinition = match == null
                ? null
                : new MatchDefinition(string(at + ": match", match, "path"), strings(at + ": match", match, "methods"));
        return new WorkflowDefinition(matchDefinition,
                endpoints(at, document, "sync"),
                endpoints(at, document, "async"),
                failureMode(at, document));
    }

    private static List<EndpointDefinition> endpoints(String at, Document document, String field) {
        List<EndpointDefinition> endpoints = new ArrayList<>();
        List<?> values = list(at, document, field);
        for (int i = 0; i < values.size(); i++) {
            String endpointAt = at + ": " + field + "[" + i + "]";
            if (!(values.get(i) instanceof Document endpoint)) {
                throw new InvalidWorkflowDefinitionException(endpointAt + " must be an object");
            }
            endpoints.add(new EndpointDefinition(
                    string(endpointAt, endpoint, "name"),
                    string(endpointAt, endpoint, "url"),
                    string(endpointAt, endpoint, "method"),
                    timeout(endpointAt, endpoint),
                    headers(endpointAt, endpoint),
                    strings(endpointAt, endpoint, "forwardHeaders"),
                    bool(endpointAt, endpoint, "appendInboundPath")));
        }
        return endpoints;
    }

    private static AsyncFailureMode failureMode(String at, Document document) {
        String value = string(at, document, "onAsyncFailure");
        if (value == null) {
            return null;
        }
        try {
            return AsyncFailureMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidWorkflowDefinitionException(
                    at + ": onAsyncFailure must be 'partial' or 'fail' but was '" + value + "'");
        }
    }

    /** A timeout is a duration string such as {@code "2s"} or {@code "500ms"}, or a number of milliseconds. */
    private static Duration timeout(String at, Document endpoint) {
        Object value = endpoint.get("timeout");
        if (value == null) {
            return null;
        }
        if (value instanceof Number millis) {
            return Duration.ofMillis(millis.longValue());
        }
        if (value instanceof String text) {
            try {
                return DurationStyle.detectAndParse(text.trim());
            } catch (RuntimeException ex) {
                // Reported below.
            }
        }
        throw new InvalidWorkflowDefinitionException(at + ".timeout must be a duration such as \"2s\" or "
                + "\"500ms\", or a number of milliseconds, but was: " + value);
    }

    private static Map<String, String> headers(String at, Document endpoint) {
        Document headers = document(at, endpoint, "headers");
        if (headers == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (!(value instanceof String text)) {
                throw new InvalidWorkflowDefinitionException(at + ".headers." + key + " must be a string");
            }
            result.put(key, text);
        });
        return result;
    }

    private static Document document(String at, Document parent, String field) {
        Object value = parent.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Document document) {
            return document;
        }
        throw new InvalidWorkflowDefinitionException(at + "." + field + " must be an object");
    }

    private static List<?> list(String at, Document parent, String field) {
        Object value = parent.get(field);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new InvalidWorkflowDefinitionException(at + "." + field + " must be an array");
    }

    private static List<String> strings(String at, Document parent, String field) {
        List<String> result = new ArrayList<>();
        for (Object value : list(at, parent, field)) {
            if (!(value instanceof String text)) {
                throw new InvalidWorkflowDefinitionException(at + "." + field + " must contain only strings");
            }
            result.add(text);
        }
        return result;
    }

    private static String string(String at, Document parent, String field) {
        Object value = parent.get(field);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new InvalidWorkflowDefinitionException(at + "." + field + " must be a string");
    }

    private static boolean bool(String at, Document parent, String field) {
        Object value = parent.get(field);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new InvalidWorkflowDefinitionException(at + "." + field + " must be true or false");
    }
}
