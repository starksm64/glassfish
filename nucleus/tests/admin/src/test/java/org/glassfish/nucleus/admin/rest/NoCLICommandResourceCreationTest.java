/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.nucleus.admin.rest;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

import org.glassfish.admin.rest.client.utils.MarshallingUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Mitesh Meswani
 */
public class NoCLICommandResourceCreationTest extends RestTestBase {
    private static final String URL_SERVER_PROPERTY = "domain/servers/server/server/property";

    @Test
    public void testPropertyCreation() {
        final String propertyKey  = "propertyName" + generateRandomString();
        String propertyValue = generateRandomString();

        //Create a property
        Map<String, String> param = new HashMap<>();
        param.put("name", propertyKey);
        param.put("value",propertyValue);
        Response response = getClient().target(getAddress(URL_SERVER_PROPERTY))
                .request(RESPONSE_TYPE)
                .post(Entity.entity(MarshallingUtils.getXmlForProperties(param), MediaType.APPLICATION_XML), Response.class);
        assertEquals(200, response.getStatus());

        //Verify the property got created
        String propertyURL = URL_SERVER_PROPERTY + "/" + propertyKey;
        response = get (propertyURL);
        assertEquals(200, response.getStatus());
        Map<String, String> entity = getEntityValues(response);
        assertTrue(entity.get("name").equals(propertyKey));
        assertTrue(entity.get("value").equals(propertyValue));

        // Verify property update
        propertyValue = generateRandomString();
        param.put("value", propertyValue);
        response = getClient().target(getAddress(URL_SERVER_PROPERTY))
                .request(RESPONSE_TYPE)
                .put(Entity.entity(MarshallingUtils.getXmlForProperties(param), MediaType.APPLICATION_XML), Response.class);
        assertEquals(200, response.getStatus());
        response = get (propertyURL);
        assertEquals(200, response.getStatus());
        entity = getEntityValues(response);
        assertTrue(entity.get("name").equals(propertyKey));
        assertTrue(entity.get("value").equals(propertyValue));

        //Clean up to leave domain.xml good for next run
        response = delete(propertyURL, new HashMap<String, String>());
        assertEquals(200, response.getStatus());
    }

}
