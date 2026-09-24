/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.grails.scaffolding;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stands in for grails-scaffolding's generator, which the plugin's tests cannot depend on, and
 * records what {@code GenerateScaffoldedViewsTask} hands it: every template, for every domain class,
 * is copied to {@code grails-scaffolded/<domain class>/<template path>} under the output directory.
 * How the real generator expands and names a page is tested in grails-scaffolding.
 */
public final class ScaffoldedPagesGenerator {

    private ScaffoldedPagesGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path templates = Paths.get(args[0]);
        List<String> domains = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        Path output = Paths.get(args[2]);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(templates)) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path file : files) {
            String path = templates.relativize(file).toString().replace(File.separatorChar, '/');
            for (String domain : domains) {
                Path target = output.resolve("grails-scaffolded/" + domain + "/" + path);
                Files.createDirectories(target.getParent());
                Files.write(target, Files.readAllBytes(file));
            }
        }
    }
}
