/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.io.StreamInputProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * A {@link StreamTask} for executing a {@link OneInputStreamOperator}.
 */
@Internal
public class OneInputStreamTask<IN, OUT> extends StreamTask<OUT, OneInputStreamOperator<IN, OUT>> {

	private static final Logger LOG = LoggerFactory.getLogger(OneInputStreamTask.class);

	private StreamInputProcessor<IN> inputProcessor;

	private volatile boolean running = true;

	@Override
	public void init() throws Exception {
		StreamConfig configuration = getConfiguration();

		// Explicitly set running to true, in case we have been stopped for modification
		running = true;

		TypeSerializer<IN> inSerializer = configuration.getTypeSerializerIn1(getUserCodeClassLoader());
		int numberOfInputs = configuration.getNumberOfInputs();

		if (numberOfInputs > 0) {
			InputGate[] inputGates = getEnvironment().getAllInputGates();

			LOG.info("Init from " + getName() + " - " + Arrays.toString(inputGates));

			inputProcessor = new StreamInputProcessor<>(
					inputGates,
					inSerializer,
					this,
					configuration.getCheckpointMode(),
					getCheckpointLock(),
					getEnvironment().getIOManager(),
					getEnvironment().getTaskManagerInfo().getConfiguration(),
					getStreamStatusMaintainer(),
					this.headOperator);

			// make sure that stream tasks report their I/O statistics
			inputProcessor.setMetricGroup(getEnvironment().getMetricGroup().getIOMetricGroup());
		}
	}

	@Override
	protected void run() throws Exception {
		// cache processor reference on the stack, to make the code more JIT friendly
		final StreamInputProcessor<IN> inputProcessor = this.inputProcessor;

		LOG.info("{} - Starting run() with operator {}", getName(), inputProcessor);

		while (running && inputProcessor.processInput()) {
			// all the work happens in the "processInput" method
		}

		if (pausedForModification) {
			LOG.info("{} - Paused from migration command", getName());
		} else {
			LOG.info("{} - Stopped, but not from migration command", getName());
		}
	}

	@Override
	protected void cleanup() throws Exception {
		if (inputProcessor != null) {
			inputProcessor.cleanup();
		}
	}

	@Override
	protected void cancelTask() {
		running = false;
	}

	@Override
	protected boolean pauseInputs() {

		running = false;
		setPausedForModification(true);

		return true;
	}
}
