/**
 * Copyright (C) 2006-2020 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.server.front;

import static java.util.Collections.emptyMap;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.inject.Inject;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import org.apache.meecrowave.junit5.MonoMeecrowaveConfig;
import org.junit.jupiter.api.Test;
import org.talend.sdk.component.api.service.healthcheck.HealthCheckStatus;
import org.talend.sdk.component.server.front.model.ActionItem;
import org.talend.sdk.component.server.front.model.ActionList;
import org.talend.sdk.component.server.front.model.ErrorDictionary;
import org.talend.sdk.component.server.front.model.error.ErrorPayload;

@MonoMeecrowaveConfig
class ActionResourceImplTest {

    @Inject
    private WebTarget base;

    @Test
    void index() {
        final ActionList index = base.path("action/index").request(APPLICATION_JSON_TYPE).get(ActionList.class);
        assertEquals(10, index.getItems().size());

        final List<ActionItem> items = new ArrayList<>(index.getItems());
        items.sort(Comparator.comparing(ActionItem::getName));

        final Iterator<ActionItem> it = items.iterator();
        assertAction("proc", "user", "another-test-component-14.11.1986.jarAction", 1, it.next());
        it.next(); // skip jdbc custom
        it.next(); // skip backend
        assertAction("chain", "healthcheck", "default", 6, it.next());
    }

    @Test
    void indexFiltered() {
        final ActionList index = base
                .path("action/index")
                .queryParam("type", "healthcheck")
                .queryParam("family", "chain")
                .request(APPLICATION_JSON_TYPE)
                .get(ActionList.class);
        assertEquals(2, index.getItems().size());
    }

    @Test
    void executeReturns520OnCallbackError() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "healthcheck")
                .queryParam("family", "chain")
                .queryParam("action", "default")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>(), APPLICATION_JSON_TYPE));
        assertEquals(520, error.getStatus());
        assertEquals(ErrorDictionary.ACTION_ERROR, error.readEntity(ErrorPayload.class).getCode());
        assertEquals("Action execution failed with: simulating an unexpected error",
                error.readEntity(ErrorPayload.class).getDescription());
    }

    @Test
    void testUnknownException() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "user")
                .queryParam("family", "custom")
                .queryParam("action", "unknownException")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>(), APPLICATION_JSON_TYPE));
        assertEquals(520, error.getStatus());
        assertEquals(ErrorDictionary.ACTION_ERROR, error.readEntity(ErrorPayload.class).getCode());
        assertEquals("Action execution failed with: unknown exception",
                error.readEntity(ErrorPayload.class).getDescription());
    }

    @Test
    void testUserException() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "user")
                .queryParam("family", "custom")
                .queryParam("action", "userException")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>(), APPLICATION_JSON_TYPE));
        assertEquals(400, error.getStatus());
        assertEquals(ErrorDictionary.ACTION_ERROR, error.readEntity(ErrorPayload.class).getCode());
        assertEquals("Action execution failed with: user exception",
                error.readEntity(ErrorPayload.class).getDescription());
    }

    @Test
    void testBackendException() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "user")
                .queryParam("family", "custom")
                .queryParam("action", "backendException")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>(), APPLICATION_JSON_TYPE));
        assertEquals(456, error.getStatus());
        assertEquals(ErrorDictionary.ACTION_ERROR, error.readEntity(ErrorPayload.class).getCode());
        assertEquals("Action execution failed with: backend exception",
                error.readEntity(ErrorPayload.class).getDescription());
    }

    @Test
    void executeWithEnumParam() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "user")
                .queryParam("family", "jdbc")
                .queryParam("action", "custom")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>() {

                    {
                        put("enum", "V1");
                    }
                }, APPLICATION_JSON_TYPE));
        assertEquals(200, error.getStatus());
        assertEquals("V1", error.readEntity(new GenericType<Map<String, String>>() {
        }).get("value"));
    }

    @Test
    void i18n() {
        final Function<String, String> call = lang -> {
            final Response error = base
                    .path("action/execute")
                    .queryParam("type", "user")
                    .queryParam("family", "jdbc")
                    .queryParam("action", "i18n")
                    .queryParam("lang", lang)
                    .request(APPLICATION_JSON_TYPE)
                    .post(Entity.entity(emptyMap(), APPLICATION_JSON_TYPE));
            return error.readEntity(new GenericType<Map<String, String>>() {
            }).get("value");
        };
        assertEquals("God save the queen", call.apply("en"));
        assertEquals("Liberté, égalité, fraternité", call.apply("fr"));
    }

    @Test
    void execute() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "healthcheck")
                .queryParam("family", "chain")
                .queryParam("action", "default")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(new HashMap<String, String>() {

                    {
                        put("dataSet.urls[0]", "empty");
                    }
                }, APPLICATION_JSON_TYPE));
        assertEquals(200, error.getStatus());
        assertEquals(HealthCheckStatus.Status.OK, error.readEntity(HealthCheckStatus.class).getStatus());
    }

    @Test
    void checkLangIsAvailable() {
        final Response error = base
                .path("action/execute")
                .queryParam("type", "healthcheck")
                .queryParam("family", "chain")
                .queryParam("action", "langtest")
                .queryParam("lang", "fr")
                .request(APPLICATION_JSON_TYPE)
                .post(Entity.entity(emptyMap(), APPLICATION_JSON_TYPE));
        assertEquals(200, error.getStatus());
        assertEquals("fr", error.readEntity(HealthCheckStatus.class).getComment());
    }

    private void assertAction(final String component, final String type, final String name, final int params,
            final ActionItem value) {
        assertEquals(component, value.getComponent());
        assertEquals(type, value.getType());
        assertEquals(name, value.getName());
        assertEquals(params, value.getProperties().size());
    }
}
