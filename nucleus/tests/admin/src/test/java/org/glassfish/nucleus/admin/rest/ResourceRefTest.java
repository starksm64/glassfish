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

import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author jasonlee
 */
public class ResourceRefTest extends RestTestBase {
    private static final String URL_CREATE_INSTANCE = "domain/create-instance";
    private static final String URL_JDBC_RESOURCE = "domain/resources/jdbc-resource";
    private static final String URL_RESOURCE_REF = "domain/servers/server/server/resource-ref";

    @Test
    @Disabled
    public void testCreatingResourceRef() {
        final String instanceName = "instance_" + generateRandomString();
        final String jdbcResourceName = "jdbc_" + generateRandomString();
        Map<String, String> newInstance = new HashMap<>() {{
            put("id", instanceName);
            put("node", "localhost-domain1");
        }};
        Map<String, String> jdbcResource = new HashMap<>() {{
            put("id", jdbcResourceName);
            put("connectionpoolid", "DerbyPool");
            put("target", instanceName);
        }};
        Map<String, String> resourceRef = new HashMap<>() {{
            put("id", jdbcResourceName);
            put("target", "server");
        }};

        try {
            Response response = post(URL_CREATE_INSTANCE, newInstance);
            assertEquals(200, response.getStatus());

            response = post(URL_JDBC_RESOURCE, jdbcResource);
            assertEquals(200, response.getStatus());

            response = post(URL_RESOURCE_REF, resourceRef);
            assertEquals(200, response.getStatus());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Response response = delete("domain/servers/server/" + instanceName + "/resource-ref/" + jdbcResourceName, new HashMap<String, String>() {{
                put("target", instanceName);
            }});
            assertEquals(200, response.getStatus());
            response = get("domain/servers/server/" + instanceName + "/resource-ref/" + jdbcResourceName);
            assertEquals(404, response.getStatus());

            response = delete(URL_JDBC_RESOURCE + "/" + jdbcResourceName);
            assertEquals(200, response.getStatus());
            response = get(URL_JDBC_RESOURCE + "/" + jdbcResourceName);
            assertEquals(404, response.getStatus());

            response = delete("domain/servers/server/" + instanceName + "/delete-instance");
            assertEquals(200, response.getStatus());
            response = get("domain/servers/server/" + instanceName);
            assertEquals(404, response.getStatus());
        }
    }
}
