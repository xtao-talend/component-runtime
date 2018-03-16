/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.runtime.di.beam.components;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import javax.json.JsonObject;
import javax.json.bind.Jsonb;

import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.junit.jupiter.api.Test;
import org.talend.sdk.component.api.input.Emitter;
import org.talend.sdk.component.api.input.PartitionMapper;
import org.talend.sdk.component.api.input.Producer;
import org.talend.sdk.component.api.processor.ElementListener;
import org.talend.sdk.component.runtime.di.AutoChunkProcessor;
import org.talend.sdk.component.runtime.di.InputsHandler;
import org.talend.sdk.component.runtime.di.JobStateAware;
import org.talend.sdk.component.runtime.di.OutputsHandler;
import org.talend.sdk.component.runtime.input.Input;
import org.talend.sdk.component.runtime.input.Mapper;
import org.talend.sdk.component.runtime.manager.ComponentManager;
import org.talend.sdk.component.runtime.manager.chain.ChainedMapper;
import org.talend.sdk.component.runtime.output.Branches;
import org.talend.sdk.component.runtime.output.InputFactory;
import org.talend.sdk.component.runtime.output.OutputFactory;
import org.talend.sdk.component.runtime.output.Processor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

class DIBatchSimulationTest {

    @Test
    void fromBeam() {
        final ComponentManager manager = ComponentManager.instance();

        final Collection<Object> sourceData = new ArrayList<>();
        final Collection<Object> processorData = new ArrayList<>();

        doDi(manager, sourceData, processorData,
                manager.findProcessor("DIBatchSimulationTest", "passthroughoutput", 1, new HashMap<>()),
                manager.findMapper("DIBatchSimulationTest", "from", 1, new HashMap<>()).map(m -> {
                    assertTrue(QueueMapper.class.isInstance(m), m.getClass().getName());
                    return m;
                }));

        assertEquals(1000, sourceData.size());
        assertEquals(1000, processorData.size());
    }

    @Test
    void toBeam() {
        final ComponentManager manager = ComponentManager.instance();

        final Collection<Object> sourceData = new ArrayList<>();
        final Collection<Object> processorData = new ArrayList<>();
        To.RECORDS.clear();

        doDi(manager, sourceData, processorData,
                manager.findProcessor("DIBatchSimulationTest", "to", 1, new HashMap<>()).map(p -> {
                    assertTrue(QueueOutput.class.isInstance(p), p.getClass().getName());
                    return p;
                }), manager.findMapper("DIBatchSimulationTest", "passthroughinput", 1, new HashMap<>()));

        assertEquals(1000, sourceData.size());
        assertEquals(1000, processorData.size());
        assertEquals(1000, To.RECORDS.size());
    }

    @Test
    void fromBeamToBeam() {
        final ComponentManager manager = ComponentManager.instance();

        final Collection<Object> sourceData = new ArrayList<>();
        final Collection<Object> processorData = new ArrayList<>();
        To.RECORDS.clear();

        doDi(manager, sourceData, processorData,
                manager.findProcessor("DIBatchSimulationTest", "to", 1, new HashMap<>()).map(p -> {
                    assertTrue(QueueOutput.class.isInstance(p), p.getClass().getName());
                    return p;
                }), manager.findMapper("DIBatchSimulationTest", "from", 1, new HashMap<>()));

        assertEquals(1000, sourceData.size());
        assertEquals(1000, processorData.size());
        assertEquals(1000, To.RECORDS.size());
    }

    private void doDi(final ComponentManager manager, final Collection<Object> sourceData,
            final Collection<Object> processorData, final Optional<Processor> proc, final Optional<Mapper> mapper) {
        final Map<String, Object> globalMap = new HashMap<>();
        try {
            final Processor processor = proc.orElseThrow(() -> new IllegalStateException("scanning failed"));
            JobStateAware.init(processor, globalMap);

            final Jsonb jsonbProcessor = Jsonb.class.cast(manager
                    .findPlugin(processor.plugin())
                    .get()
                    .get(ComponentManager.AllServices.class)
                    .getServices()
                    .get(Jsonb.class));
            final AutoChunkProcessor processorProcessor = new AutoChunkProcessor(100, processor);

            processorProcessor.start();
            globalMap.put("processorProcessor", processorProcessor);

            final InputsHandler inputsHandlerProcessor = new InputsHandler(jsonbProcessor);
            inputsHandlerProcessor.addConnection("FLOW", row1Struct.class);

            final OutputsHandler outputHandlerProcessor = new OutputsHandler(jsonbProcessor);

            final InputFactory inputsProcessor = inputsHandlerProcessor.asInputFactory();
            final OutputFactory outputsProcessor = outputHandlerProcessor.asOutputFactory();

            final Mapper tempMapperMapper = mapper.orElseThrow(() -> new IllegalStateException("scanning failed"));
            JobStateAware.init(tempMapperMapper, globalMap);

            doRun(manager, sourceData, processorData, globalMap, processorProcessor, inputsHandlerProcessor,
                    outputHandlerProcessor, inputsProcessor, outputsProcessor, tempMapperMapper);
        } finally {
            doClose(globalMap);
        }
    }

    private void doRun(final ComponentManager manager, final Collection<Object> sourceData,
            final Collection<Object> processorData, final Map<String, Object> globalMap,
            final AutoChunkProcessor processorProcessor, final InputsHandler inputsHandlerProcessor,
            final OutputsHandler outputHandlerProcessor, final InputFactory inputsProcessor,
            final OutputFactory outputsProcessor, final Mapper tempMapperMapper) {
        row1Struct row1 = new row1Struct();

        tempMapperMapper.start();
        final ChainedMapper mapperMapper;
        try {
            final List<Mapper> splitMappersMapper = tempMapperMapper.split(tempMapperMapper.assess());
            mapperMapper = new ChainedMapper(tempMapperMapper, splitMappersMapper.iterator());
            mapperMapper.start();
            globalMap.put("mapperMapper", mapperMapper);
        } finally {
            try {
                tempMapperMapper.stop();
            } catch (final RuntimeException re) {
                re.printStackTrace();
            }
        }

        final Input inputMapper = mapperMapper.create();
        inputMapper.start();
        globalMap.put("inputMapper", inputMapper);

        final Jsonb jsonbMapper = Jsonb.class.cast(manager
                .findPlugin(mapperMapper.plugin())
                .get()
                .get(ComponentManager.AllServices.class)
                .getServices()
                .get(Jsonb.class));

        Object dataMapper;
        while ((dataMapper = inputMapper.next()) != null) {
            final String jsonValueMapper = javax.json.JsonValue.class.isInstance(dataMapper)
                    ? javax.json.JsonValue.class.cast(dataMapper).toString()
                    : jsonbMapper.toJson(dataMapper);
            row1 = jsonbMapper.fromJson(jsonValueMapper, row1.getClass());
            sourceData.add(row1);

            inputsHandlerProcessor.reset();
            inputsHandlerProcessor.setInputValue("FLOW", row1);

            outputHandlerProcessor.reset();
            processorProcessor.onElement(name -> {
                assertEquals(Branches.DEFAULT_BRANCH, name);
                final Object read = inputsProcessor.read(name);
                processorData.add(read);
                return read;
            }, outputsProcessor);
        }
    }

    private void doClose(final Map<String, Object> globalMap) {
        final Mapper mapperMapper = Mapper.class.cast(globalMap.remove("mapperMapper"));
        final Input inputMapper = Input.class.cast(globalMap.remove("inputMapper"));
        try {
            if (inputMapper != null) {
                inputMapper.stop();
            }
        } catch (final RuntimeException re) {
            fail(re.getMessage());
        } finally {
            try {
                if (mapperMapper != null) {
                    mapperMapper.stop();
                }
            } catch (final RuntimeException re) {
                fail(re.getMessage());
            }
        }

        final AutoChunkProcessor processorProcessor =
                AutoChunkProcessor.class.cast(globalMap.remove("processorProcessor"));
        try {
            if (processorProcessor != null) {
                processorProcessor.stop();
            }
        } catch (final RuntimeException re) {
            fail(re.getMessage());
        }
    }

    @Getter
    @ToString
    public static class row1Struct {

        public String id;

        public String name;
    }

    @PartitionMapper(name = "from", family = "DIBatchSimulationTest")
    public static class From extends PTransform<PBegin, PCollection<Record>> {

        @Override
        public PCollection<Record> expand(final PBegin input) {
            return input.apply(Create.of(
                    IntStream.range(0, 1000).mapToObj(i -> new Record("id_" + i, "record_" + i)).collect(toList())));
        }
    }

    @org.talend.sdk.component.api.processor.Processor(name = "passthroughoutput", family = "DIBatchSimulationTest")
    public static class PassthroughOutput implements Serializable {

        @ElementListener
        public void onElement(final JsonObject object) {
            // no-op
        }
    }

    @Emitter(name = "passthroughinput", family = "DIBatchSimulationTest")
    public static class PassthroughInput implements Serializable {

        private final PrimitiveIterator.OfInt stream = IntStream.range(0, 1000).iterator();

        @Producer
        public Record next() {
            return stream.hasNext() ? new Record("id_" + stream.next(), "record") : null;
        }
    }

    @org.talend.sdk.component.api.processor.Processor(name = "to", family = "DIBatchSimulationTest")
    public static class To extends PTransform<PCollection<JsonObject>, PDone> {

        static final Collection<JsonObject> RECORDS = new CopyOnWriteArrayList<>();

        @Override
        public PDone expand(final PCollection<JsonObject> input) {
            input.apply(ParDo.of(new DoFn<JsonObject, Void>() {

                @ProcessElement
                public void onElement(final ProcessContext context) {
                    RECORDS.add(context.element());
                }
            }));
            return PDone.in(input.getPipeline());
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Record implements Serializable {

        private String id;

        private String name;
    }
}
