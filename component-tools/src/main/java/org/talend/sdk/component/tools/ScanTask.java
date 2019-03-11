/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.tools;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.apache.xbean.finder.AnnotationFinder;
import org.apache.xbean.finder.archive.Archive;
import org.apache.xbean.finder.archive.ClasspathArchive;
import org.apache.xbean.finder.archive.CompositeArchive;
import org.apache.xbean.finder.filter.ExcludeIncludeFilter;
import org.apache.xbean.finder.filter.Filter;
import org.apache.xbean.finder.filter.Filters;
import org.apache.xbean.finder.filter.IncludeExcludeFilter;
import org.talend.sdk.component.api.input.Emitter;
import org.talend.sdk.component.api.input.PartitionMapper;
import org.talend.sdk.component.api.internationalization.Internationalized;
import org.talend.sdk.component.api.processor.Processor;
import org.talend.sdk.component.api.service.Service;
import org.talend.sdk.component.api.service.http.Request;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ScanTask implements Runnable {

    private final Collection<File> scannedFiles;

    private final List<String> excludes;

    private final List<String> includes;

    private final String filterStrategy;

    private final File output;

    @Override
    public void run() {
        output.getParentFile().mkdirs();
        try (final OutputStream stream = new FileOutputStream(output)) {
            final Properties properties = new Properties();
            properties.setProperty("classes.list", scanList().collect(joining(",")));
            properties.store(stream, "generated by " + getClass() + " at " + new Date());
        } catch (final IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private Stream<String> scanList() {
        final AnnotationFinder finder = newFinder();
        final Filter filter = newFilter();
        return Stream
                .concat(Stream
                        .of(PartitionMapper.class, Processor.class, Emitter.class, Service.class,
                                Internationalized.class)
                        .flatMap(it -> finder.findAnnotatedClasses(it).stream()),
                        Stream
                                .of(Request.class)
                                .flatMap(it -> finder.findAnnotatedMethods(it).stream())
                                .map(Method::getDeclaringClass))
                .distinct()
                .map(Class::getName)
                .sorted()
                .filter(filter::accept);
    }

    private Filter newFilter() {
        final Filter accept = ofNullable(includes)
                .filter(it -> it.size() > 0)
                .map(i -> i.toArray(new String[0]))
                .map(Filters::patterns)
                .orElseGet(() -> name -> true);
        final Filter reject = ofNullable(excludes)
                .filter(it -> it.size() > 0)
                .map(i -> i.toArray(new String[0]))
                .map(Filters::patterns)
                .orElseGet(() -> name -> false);
        if ("include-exclude".equals(filterStrategy)) {
            return new IncludeExcludeFilter(accept, reject);
        }
        return new ExcludeIncludeFilter(accept, reject);
    }

    private AnnotationFinder newFinder() {
        return new AnnotationFinder(new CompositeArchive(scannedFiles.stream().map(c -> {
            try {
                return ClasspathArchive.archive(Thread.currentThread().getContextClassLoader(), c.toURI().toURL());
            } catch (final MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }).toArray(Archive[]::new)));
    }
}
