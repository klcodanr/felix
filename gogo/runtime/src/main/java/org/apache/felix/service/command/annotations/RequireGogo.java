/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.felix.service.command.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.osgi.annotation.bundle.Attribute;
import org.osgi.annotation.bundle.Requirement;

import static java.lang.annotation.RetentionPolicy.CLASS;

import static java.lang.annotation.ElementType.*;

@Retention(CLASS)
@Target({TYPE, PACKAGE})
@Requirement(
    effective = "active",
    namespace = "org.apache.felix.gogo",
    name = "shell.implementation"
)
public @interface RequireGogo {
    String JLINE = "gogo.jline";
    String SHELL = "gogo.shell";

    @Attribute("implementation.name")
    String value() default SHELL;
}
