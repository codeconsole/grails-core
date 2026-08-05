/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.datastore.gorm.mongodb.embedded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Starts an embedded MongoDB before the application context refreshes and publishes its
 * connection URL into whichever configuration properties the application reads, so an
 * application runs without a MongoDB installation and without Docker.
 *
 * <p>This is an {@link ApplicationContextInitializer} rather than an auto configuration
 * because the URL has to be in the {@code Environment} before the datastore bean that
 * reads it is created.
 *
 * <p>Configured with:
 * <table>
 * <caption>Supported properties</caption>
 * <tr><td>{@code embedded.mongodb.enabled}</td><td>off unless set to true</td></tr>
 * <tr><td>{@code embedded.mongodb.backend}</td><td>{@code in-memory} or {@code flapdoodle};
 *     defaults to flapdoodle when it is on the classpath, otherwise in-memory</td></tr>
 * <tr><td>{@code embedded.mongodb.property-names}</td><td>comma separated properties to
 *     publish the URL into, {@code grails.mongodb.url} by default</td></tr>
 * <tr><td>{@code embedded.mongodb.port}</td><td>defaults to 27017 offset by however far
 *     {@code server.port} has moved from 8080</td></tr>
 * <tr><td>{@code embedded.mongodb.database}</td><td>defaults to the database already named
 *     in one of the target properties</td></tr>
 * <tr><td>{@code embedded.mongodb.database-dir}</td><td>keeps the data after the server
 *     stops, which only the flapdoodle backend can do</td></tr>
 * <tr><td>{@code embedded.mongodb.version}</td><td>the MongoDB version, where the backend
 *     can choose one</td></tr>
 * </table>
 *
 * <p>Making the target property configurable is what keeps this useful outside Grails
 * Data: point {@code property-names} at {@code spring.data.mongodb.uri} and it serves a
 * plain Spring Boot application, although Spring Data users are generally better served
 * by flapdoodle's own {@code de.flapdoodle.embed.mongo.spring3x} auto configuration,
 * which this deliberately does not duplicate.
 *
 * @author Grails
 * @since 8.0
 */
public class EmbeddedMongoInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ENABLED = "embedded.mongodb.enabled";

    public static final String BACKEND = "embedded.mongodb.backend";

    public static final String PROPERTY_NAMES = "embedded.mongodb.property-names";

    public static final String PORT = "embedded.mongodb.port";

    public static final String DATABASE = "embedded.mongodb.database";

    public static final String DATABASE_DIR = "embedded.mongodb.database-dir";

    public static final String VERSION = "embedded.mongodb.version";

    public static final String DEFAULT_PROPERTY_NAME = "grails.mongodb.url";

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMongoInitializer.class);

    private static final String PROPERTY_SOURCE_NAME = "embeddedMongoDB";

    private static final String DEFAULT_DATABASE = "test";

    private static final int DEFAULT_PORT = 27017;

    private static final int DEFAULT_SERVER_PORT = 8080;

    private static final Pattern DATABASE_IN_URL = Pattern.compile("^mongodb(?:\\+srv)?://[^/]+/([^?]+)");

    /**
     * The servers this JVM started, by port. Keyed rather than a single field because two
     * application contexts in one JVM can ask for different ports, and consulted so that a
     * devtools restart reuses its own server without mistaking any other listener for one.
     */
    private static final Map<Integer, RunningEmbeddedMongo> STARTED = new ConcurrentHashMap<>();

    /**
     * Flapdoodle first, so that adding it to an application is all it takes to move from
     * the in-memory reimplementation to a real mongod.
     */
    private final List<EmbeddedMongoBackend> backends;

    public EmbeddedMongoInitializer() {
        this(Arrays.asList(new FlapdoodleMongoBackend(), new InMemoryMongoBackend()));
    }

    EmbeddedMongoInitializer(List<EmbeddedMongoBackend> backends) {
        this.backends = backends;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        if (!Boolean.parseBoolean(environment.getProperty(ENABLED, "false"))) {
            return;
        }

        Set<String> propertyNames = propertyNames(environment);
        int port = resolvePort(environment);
        String database = resolveDatabase(environment, propertyNames);

        String url;
        RunningEmbeddedMongo started = STARTED.get(port);
        if (started != null) {
            // A devtools restart reuses this JVM and this class is loaded from a jar, so it
            // survives in the base classloader along with the server the previous
            // application context started. Only a server this initializer started is reused;
            // anything else holding the port makes the start below fail with an error that
            // says so, rather than publishing a MongoDB url pointing at an unrelated service.
            url = "mongodb://" + started.getHost() + ":" + started.getPort() + "/" + database;
            log.info("Reusing the embedded MongoDB this JVM already started at {}", url);
        }
        else {
            url = start(environment, port, database);
        }

        Map<String, Object> published = new HashMap<>();
        for (String propertyName : propertyNames) {
            published.put(propertyName, url);
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, published));
    }

    /**
     * Failures throw rather than returning quietly: falling through would leave the target
     * properties pointing at whatever the application configured, which is exactly what
     * enabling this was meant to replace.
     */
    private String start(ConfigurableEnvironment environment, int port, String database) {
        EmbeddedMongoBackend backend = selectBackend(environment);
        EmbeddedMongoSettings settings = new EmbeddedMongoSettings(port,
                environment.getProperty(VERSION), environment.getProperty(DATABASE_DIR));

        RunningEmbeddedMongo running;
        try {
            running = backend.start(settings);
        }
        catch (IllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to start the " + backend.getName() +
                    " embedded MongoDB on port " + port + ", which something else may already be using. Set " +
                    PORT + " to a free port, or set " + ENABLED + "=false to use an external MongoDB.", ex);
        }

        STARTED.put(port, running);

        // Registered on the JVM rather than the application context so that it survives a
        // devtools restart and fires only once, when the JVM itself exits.
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop));

        String url = "mongodb://" + running.getHost() + ":" + running.getPort() + "/" + database;
        log.info("Embedded MongoDB started at {} using the {} backend", url, backend.getName());
        return url;
    }

    private EmbeddedMongoBackend selectBackend(ConfigurableEnvironment environment) {
        String requested = environment.getProperty(BACKEND);
        if (requested != null && !requested.isEmpty()) {
            for (EmbeddedMongoBackend backend : this.backends) {
                if (backend.getName().equals(requested)) {
                    if (!backend.isAvailable()) {
                        throw new IllegalStateException(BACKEND + "=" + requested +
                                " but its library is not on the classpath. Add it as a dependency, or choose one of " +
                                availableNames() + ".");
                    }
                    return backend;
                }
            }
            throw new IllegalStateException(BACKEND + "=" + requested + " is not a known backend. Use one of " +
                    this.backends.stream().map(EmbeddedMongoBackend::getName).collect(Collectors.joining(", ")) +
                    ".");
        }

        for (EmbeddedMongoBackend backend : this.backends) {
            if (backend.isAvailable()) {
                return backend;
            }
        }
        throw new IllegalStateException(ENABLED + "=true but no embedded MongoDB backend is on the classpath. " +
                "Add de.bwaldvogel:mongo-java-server for an in-memory server, or " +
                "de.flapdoodle.embed:de.flapdoodle.embed.mongo for a real mongod.");
    }

    private List<String> availableNames() {
        List<String> names = new ArrayList<>();
        for (EmbeddedMongoBackend backend : this.backends) {
            if (backend.isAvailable()) {
                names.add(backend.getName());
            }
        }
        return names;
    }

    private Set<String> propertyNames(ConfigurableEnvironment environment) {
        Set<String> propertyNames = new LinkedHashSet<>();
        for (String propertyName : environment.getProperty(PROPERTY_NAMES, DEFAULT_PROPERTY_NAME).split(",")) {
            String trimmed = propertyName.trim();
            if (!trimmed.isEmpty()) {
                propertyNames.add(trimmed);
            }
        }
        if (propertyNames.isEmpty()) {
            propertyNames.add(DEFAULT_PROPERTY_NAME);
        }
        return propertyNames;
    }

    /**
     * Offsets the MongoDB port by however far the server port has moved, so two
     * applications run side by side without colliding.
     */
    private int resolvePort(ConfigurableEnvironment environment) {
        String configured = environment.getProperty(PORT);
        if (configured != null && !configured.isEmpty()) {
            return Integer.parseInt(configured);
        }
        int serverPort = Integer.parseInt(environment.getProperty("server.port", String.valueOf(DEFAULT_SERVER_PORT)));
        return serverPort == 0 ? DEFAULT_PORT : DEFAULT_PORT + (serverPort - DEFAULT_SERVER_PORT);
    }

    /**
     * Leaves the application's own configuration as the single source of truth for the
     * database name, so enabling this does not silently move an application to a
     * different database.
     */
    private String resolveDatabase(ConfigurableEnvironment environment, Set<String> propertyNames) {
        String configured = environment.getProperty(DATABASE);
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        for (String propertyName : propertyNames) {
            String existing = environment.getProperty(propertyName);
            if (existing != null) {
                Matcher matcher = DATABASE_IN_URL.matcher(existing);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return DEFAULT_DATABASE;
    }

}
