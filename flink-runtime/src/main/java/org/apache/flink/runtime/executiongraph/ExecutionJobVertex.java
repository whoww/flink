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

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.Archiveable;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.api.common.accumulators.AccumulatorHelper;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.core.io.InputSplitSource;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.accumulators.StringifiedAccumulatorResult;
import org.apache.flink.runtime.concurrent.Future;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.instance.SimpleSlot;
import org.apache.flink.runtime.instance.SlotProvider;
import org.apache.flink.runtime.jobgraph.IntermediateDataSet;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedValue;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@code ExecutionJobVertex} is part of the {@link ExecutionGraph}, and the peer 
 * to the {@link JobVertex}.
 * 
 * <p>The {@code ExecutionJobVertex} corresponds to a parallelized operation. It
 * contains an {@link ExecutionVertex} for each parallel instance of that operation.
 */
public class ExecutionJobVertex implements AccessExecutionJobVertex, Archiveable<ArchivedExecutionJobVertex> {

	/** Use the same log for all ExecutionGraph classes */
	private static final Logger LOG = ExecutionGraph.LOG;

	public static final int VALUE_NOT_SET = -1;

	private final Object stateMonitor = new Object();

	private final ExecutionGraph graph;

	private final JobVertex jobVertex;

	/**
	 * The IDs of all operators contained in this execution job vertex.
	 *
	 * The ID's are stored depth-first post-order; for the forking chain below the ID's would be stored as [D, E, B, C, A].
	 *  A - B - D
	 *   \    \
	 *    C    E
	 * This is the same order that operators are stored in the {@code StreamTask}.
	 */
	private final List<OperatorID> operatorIDs;

	/**
	 * The alternative IDs of all operators contained in this execution job vertex.
	 *
	 * The ID's are in the same order as {@link ExecutionJobVertex#operatorIDs}.
	 */
	private final List<OperatorID> userDefinedOperatorIds;

	/**
	 * Not final anymore, as may change due to runtime modifications.
	 */
	private ExecutionVertex[] taskVertices;

	private final IntermediateResult[] producedDataSets;

	private final List<IntermediateResult> inputs;

	private int parallelism;

	private final SlotSharingGroup slotSharingGroup;

	private final CoLocationGroup coLocationGroup;

	private final InputSplit[] inputSplits;

	private final boolean maxParallelismConfigured;

	private int maxParallelism;

	/**
	 * Serialized task information which is for all sub tasks the same. Thus, it avoids to
	 * serialize the same information multiple times in order to create the
	 * TaskDeploymentDescriptors.
	 */
	private SerializedValue<TaskInformation> serializedTaskInformation;

	private InputSplitAssigner splitAssigner;

	/**
	 * Convenience constructor for testing.
	 */
	@VisibleForTesting
	ExecutionJobVertex(
		ExecutionGraph graph,
		JobVertex jobVertex,
		int defaultParallelism,
		Time timeout) throws JobException {

		this(graph, jobVertex, defaultParallelism, timeout, 1L, System.currentTimeMillis());
	}

	public ExecutionJobVertex(
		ExecutionGraph graph,
		JobVertex jobVertex,
		int defaultParallelism,
		Time timeout,
		long initialGlobalModVersion,
		long createTimestamp) throws JobException {

		if (graph == null || jobVertex == null) {
			throw new NullPointerException();
		}
		
		this.graph = graph;
		this.jobVertex = jobVertex;

		int vertexParallelism = jobVertex.getParallelism();
		int numTaskVertices = vertexParallelism > 0 ? vertexParallelism : defaultParallelism;

		this.parallelism = numTaskVertices;

		final int configuredMaxParallelism = jobVertex.getMaxParallelism();

		this.maxParallelismConfigured = (VALUE_NOT_SET != configuredMaxParallelism);

		// if no max parallelism was configured by the user, we calculate and set a default
		setMaxParallelismInternal(maxParallelismConfigured ?
				configuredMaxParallelism : KeyGroupRangeAssignment.computeDefaultMaxParallelism(parallelism));

		this.serializedTaskInformation = null;

		this.taskVertices = new ExecutionVertex[numTaskVertices];
		this.operatorIDs = Collections.unmodifiableList(jobVertex.getOperatorIDs());
		this.userDefinedOperatorIds = Collections.unmodifiableList(jobVertex.getUserDefinedOperatorIDs());
		
		this.inputs = new ArrayList<>(jobVertex.getInputs().size());
		
		// take the sharing group
		this.slotSharingGroup = jobVertex.getSlotSharingGroup();
		this.coLocationGroup = jobVertex.getCoLocationGroup();
		
		// setup the coLocation group
		if (coLocationGroup != null && slotSharingGroup == null) {
			throw new JobException("Vertex uses a co-location constraint without using slot sharing");
		}
		
		// create the intermediate results
		this.producedDataSets = new IntermediateResult[jobVertex.getNumberOfProducedIntermediateDataSets()];

		for (int i = 0; i < jobVertex.getProducedDataSets().size(); i++) {
			final IntermediateDataSet result = jobVertex.getProducedDataSets().get(i);

			this.producedDataSets[i] = new IntermediateResult(
					result.getId(),
					this,
					numTaskVertices,
					result.getResultType());
		}

		Configuration jobConfiguration = graph.getJobConfiguration();
		int maxPriorAttemptsHistoryLength = jobConfiguration != null ?
				jobConfiguration.getInteger(JobManagerOptions.MAX_ATTEMPTS_HISTORY_SIZE) :
				JobManagerOptions.MAX_ATTEMPTS_HISTORY_SIZE.defaultValue();

		// create all task vertices
		for (int i = 0; i < numTaskVertices; i++) {
			ExecutionVertex vertex = new ExecutionVertex(
					this,
					i,
					producedDataSets,
					timeout,
					initialGlobalModVersion,
					createTimestamp,
					maxPriorAttemptsHistoryLength);

			this.taskVertices[i] = vertex;
		}

		// sanity check for the double referencing between intermediate result partitions and execution vertices
		for (IntermediateResult ir : this.producedDataSets) {
			if (ir.getNumberOfAssignedPartitions() != parallelism) {
				throw new RuntimeException("The intermediate result's partitions were not correctly assigned.");
			}
		}

		// set up the input splits, if the vertex has any
		try {
			@SuppressWarnings("unchecked")
			InputSplitSource<InputSplit> splitSource = (InputSplitSource<InputSplit>) jobVertex.getInputSplitSource();
			
			if (splitSource != null) {
				Thread currentThread = Thread.currentThread();
				ClassLoader oldContextClassLoader = currentThread.getContextClassLoader();
				currentThread.setContextClassLoader(graph.getUserClassLoader());
				try {
					inputSplits = splitSource.createInputSplits(numTaskVertices);

					if (inputSplits != null) {
						splitAssigner = splitSource.getInputSplitAssigner(inputSplits);
					}
				} finally {
					currentThread.setContextClassLoader(oldContextClassLoader);
				}
			}
			else {
				inputSplits = null;
			}
		}
		catch (Throwable t) {
			throw new JobException("Creating the input splits caused an error: " + t.getMessage(), t);
		}
	}

	public ExecutionVertex increaseDegreeOfParallelism(Time timeout,
													   long initialGlobalModVersion,
													   long createTimestamp,
													   Map<IntermediateDataSetID, IntermediateResult> allIntermediateResults) {

		Configuration jobConfiguration = graph.getJobConfiguration();
		int maxPriorAttemptsHistoryLength = jobConfiguration != null ?
			jobConfiguration.getInteger(JobManagerOptions.MAX_ATTEMPTS_HISTORY_SIZE) :
			JobManagerOptions.MAX_ATTEMPTS_HISTORY_SIZE.defaultValue();

		int newParallelism = this.parallelism + 1;

		LOG.debug("Increasing DoP for {} to {}", this, newParallelism);

		IntermediateResult[] producedDataSets = getProducedDataSets();

		assert producedDataSets.length == 1;

		producedDataSets[0].increaseDegreeOfParallelism(newParallelism);

		this.parallelism = newParallelism;
		ExecutionVertex[] newTaskVertices = new ExecutionVertex[newParallelism];

		System.arraycopy(taskVertices, 0, newTaskVertices, 0, this.taskVertices.length);

		ExecutionVertex vertex = new ExecutionVertex(
			this,
			newParallelism - 1,
			producedDataSets,
			timeout,
			initialGlobalModVersion,
			createTimestamp,
			maxPriorAttemptsHistoryLength);

		newTaskVertices[newParallelism - 1] = vertex;

		this.taskVertices = newTaskVertices;

		try {
			connectToPredecessorsRuntime(allIntermediateResults);
		} catch (JobException e) {
			e.printStackTrace();
			throw new RuntimeException("Failed to connect to intermediate results");
		}

		LOG.debug("Increased DoP for {} by creating a new ExecutionVertex {}", this, vertex);

		return vertex;
	}

	/**
	 * Returns a list containing the IDs of all operators contained in this execution job vertex.
	 *
	 * @return list containing the IDs of all contained operators
	 */
	public List<OperatorID> getOperatorIDs() {
		return operatorIDs;
	}

	/**
	 * Returns a list containing the alternative IDs of all operators contained in this execution job vertex.
	 *
	 * @return list containing alternative the IDs of all contained operators
	 */
	public List<OperatorID> getUserDefinedOperatorIDs() {
		return userDefinedOperatorIds;
	}

	public void setMaxParallelism(int maxParallelismDerived) {

		Preconditions.checkState(!maxParallelismConfigured,
				"Attempt to override a configured max parallelism. Configured: " + this.maxParallelism
						+ ", argument: " + maxParallelismDerived);

		setMaxParallelismInternal(maxParallelismDerived);
	}

	private void setMaxParallelismInternal(int maxParallelism) {
		if (maxParallelism == ExecutionConfig.PARALLELISM_AUTO_MAX) {
			maxParallelism = KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM;
		}

		Preconditions.checkArgument(maxParallelism > 0
						&& maxParallelism <= KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM,
				"Overriding max parallelism is not in valid bounds (1..%s), found: %s",
				KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM, maxParallelism);

		this.maxParallelism = maxParallelism;
	}

	public ExecutionGraph getGraph() {
		return graph;
	}
	
	public JobVertex getJobVertex() {
		return jobVertex;
	}

	@Override
	public String getName() {
		return getJobVertex().getName();
	}

	@Override
	public int getParallelism() {
		return parallelism;
	}

	@Override
	public int getMaxParallelism() {
		return maxParallelism;
	}

	public boolean isMaxParallelismConfigured() {
		return maxParallelismConfigured;
	}

	public JobID getJobId() {
		return graph.getJobID();
	}
	
	@Override
	public JobVertexID getJobVertexId() {
		return jobVertex.getID();
	}
	
	@Override
	public ExecutionVertex[] getTaskVertices() {
		return taskVertices;
	}
	
	public IntermediateResult[] getProducedDataSets() {
		return producedDataSets;
	}
	
	public InputSplitAssigner getSplitAssigner() {
		return splitAssigner;
	}
	
	public SlotSharingGroup getSlotSharingGroup() {
		return slotSharingGroup;
	}
	
	public CoLocationGroup getCoLocationGroup() {
		return coLocationGroup;
	}
	
	public List<IntermediateResult> getInputs() {
		return inputs;
	}

	public SerializedValue<TaskInformation> getSerializedTaskInformation() throws IOException {

		// TODO Masterthesis Find better solution
//		if (null == serializedTaskInformation) {

			int parallelism = getParallelism();
			int maxParallelism = getMaxParallelism();

			if (LOG.isDebugEnabled()) {
				LOG.debug("Creating task information for " + generateDebugString());
			}

			serializedTaskInformation = new SerializedValue<>(
					new TaskInformation(
							jobVertex.getID(),
							jobVertex.getName(),
							parallelism,
							maxParallelism,
							jobVertex.getInvokableClassName(),
							jobVertex.getConfiguration()));
//		}

		return serializedTaskInformation;
	}

	@Override
	public ExecutionState getAggregateState() {
		int[] num = new int[ExecutionState.values().length];
		for (ExecutionVertex vertex : this.taskVertices) {
			num[vertex.getExecutionState().ordinal()]++;
		}

		return getAggregateJobVertexState(num, parallelism);
	}

	public String generateDebugString() {

		return "ExecutionJobVertex" +
				"(" + jobVertex.getName() + " | " + jobVertex.getID() + ")" +
				"{" +
				"parallelism=" + parallelism +
				", maxParallelism=" + getMaxParallelism() +
				", maxParallelismConfigured=" + maxParallelismConfigured +
				'}';
	}


	//---------------------------------------------------------------------------------------------
	
	public void connectToPredecessors(Map<IntermediateDataSetID, IntermediateResult> intermediateDataSets) throws JobException {
		
		List<JobEdge> inputs = jobVertex.getInputs();
		
		LOG.debug(String.format("Connecting ExecutionJobVertex %s (%s) to %d predecessors.", jobVertex.getID(), jobVertex.getName(), inputs.size()));
		
		for (int num = 0; num < inputs.size(); num++) {
			JobEdge edge = inputs.get(num);
			
			if (LOG.isDebugEnabled()) {
				if (edge.getSource() == null) {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via ID %s.", 
							num, jobVertex.getID(), jobVertex.getName(), edge.getSourceId()));
				} else {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via predecessor %s (%s).",
							num, jobVertex.getID(), jobVertex.getName(), edge.getSource().getProducer().getID(), edge.getSource().getProducer().getName()));
				}
			}
			
			// fetch the intermediate result via ID. if it does not exist, then it either has not been created, or the order
			// in which this method is called for the job vertices is not a topological order
			IntermediateResult ires = intermediateDataSets.get(edge.getSourceId());
			if (ires == null) {
				throw new JobException("Cannot connect this job graph to the previous graph. No previous intermediate result found for ID "
						+ edge.getSourceId());
			}
			
			this.inputs.add(ires);
			
			int consumerIndex = ires.registerConsumer();
			
			for (int i = 0; i < parallelism; i++) {
				ExecutionVertex ev = taskVertices[i];
				ev.connectSource(num, ires, edge, consumerIndex);
			}
		}
	}

	public void connectToPredecessorsRuntime(Map<IntermediateDataSetID, IntermediateResult> intermediateDataSets) throws JobException {

		List<JobEdge> inputs = jobVertex.getInputs();

		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("Connecting ExecutionJobVertex %s (%s) to %d predecessors.", jobVertex.getID(), jobVertex.getName(), inputs.size()));
		}

		for (int num = 0; num < inputs.size(); num++) {
			JobEdge edge = inputs.get(num);

			if (LOG.isDebugEnabled()) {
				if (edge.getSource() == null) {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via ID %s.",
						num, jobVertex.getID(), jobVertex.getName(), edge.getSourceId()));
				} else {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via predecessor %s (%s).",
						num, jobVertex.getID(), jobVertex.getName(), edge.getSource().getProducer().getID(), edge.getSource().getProducer().getName()));
				}
			}

			// fetch the intermediate result via ID. if it does not exist, then it either has not been created, or the order
			// in which this method is called for the job vertices is not a topological order
			IntermediateResult ires = intermediateDataSets.get(edge.getSourceId());
			if (ires == null) {
				throw new JobException("Cannot connect this job graph to the previous graph. No previous intermediate result found for ID "
					+ edge.getSourceId());
			}

			this.inputs.add(ires);

			int consumerIndex = ires.registerConsumerRuntime();

			for (int i = 0; i < parallelism; i++) {
				ExecutionVertex ev = taskVertices[i];
				ev.connectSource(num, ires, edge, consumerIndex);
			}
		}
	}
	
	//---------------------------------------------------------------------------------------------
	//  Actions
	//---------------------------------------------------------------------------------------------
	
	public void scheduleAll(SlotProvider slotProvider, boolean queued) {
		
		final ExecutionVertex[] vertices = this.taskVertices;

		// kick off the tasks
		for (ExecutionVertex ev : vertices) {
			ev.scheduleForExecution(slotProvider, queued);
		}
	}

	/**
	 * Acquires a slot for all the execution vertices of this ExecutionJobVertex. The method returns
	 * pairs of the slots and execution attempts, to ease correlation between vertices and execution
	 * attempts.
	 * 
	 * <p>If this method throws an exception, it makes sure to release all so far requested slots.
	 * 
	 * @param resourceProvider The resource provider from whom the slots are requested.
	 */
	public ExecutionAndSlot[] allocateResourcesForAll(SlotProvider resourceProvider, boolean queued) {
		final ExecutionVertex[] vertices = this.taskVertices;
		final ExecutionAndSlot[] slots = new ExecutionAndSlot[vertices.length];

		// try to acquire a slot future for each execution.
		// we store the execution with the future just to be on the safe side
		for (int i = 0; i < vertices.length; i++) {

			// we use this flag to handle failures in a 'finally' clause
			// that allows us to not go through clumsy cast-and-rethrow logic
			boolean successful = false;

			try {
				// allocate the next slot (future)
				final Execution exec = vertices[i].getCurrentExecutionAttempt();
				final Future<SimpleSlot> future = exec.allocateSlotForExecution(resourceProvider, queued);
				slots[i] = new ExecutionAndSlot(exec, future);
				successful = true;
			}
			finally {
				if (!successful) {
					// this is the case if an exception was thrown
					for (int k = 0; k < i; k++) {
						ExecutionGraphUtils.releaseSlotFuture(slots[k].slotFuture);
					}
				}
			}
		}

		// all good, we acquired all slots
		return slots;
	}

	/**
	 * Cancels all currently running vertex executions.
	 */
	public void cancel() {
		for (ExecutionVertex ev : getTaskVertices()) {
			ev.cancel();
		}
	}

	/**
	 * Cancels all currently running vertex executions.
	 * 
	 * @return A future that is complete once all tasks have canceled.
	 */
	public Future<Void> cancelWithFuture() {
		// we collect all futures from the task cancellations
		ArrayList<Future<ExecutionState>> futures = new ArrayList<>(parallelism);

		// cancel each vertex
		for (ExecutionVertex ev : getTaskVertices()) {
			futures.add(ev.cancel());
		}

		// return a conjunct future, which is complete once all individual tasks are canceled
		return FutureUtils.waitForAll(futures);
	}

	public void fail(Throwable t) {
		for (ExecutionVertex ev : getTaskVertices()) {
			ev.fail(t);
		}
	}

	public void resetForNewExecution(final long timestamp, final long expectedGlobalModVersion)
			throws GlobalModVersionMismatch {

		synchronized (stateMonitor) {
			// check and reset the sharing groups with scheduler hints
			if (slotSharingGroup != null) {
				slotSharingGroup.clearTaskAssignment();
			}

			for (int i = 0; i < parallelism; i++) {
				taskVertices[i].resetForNewExecution(timestamp, expectedGlobalModVersion);
			}

			// set up the input splits again
			try {
				if (this.inputSplits != null) {
					// lazy assignment
					@SuppressWarnings("unchecked")
					InputSplitSource<InputSplit> splitSource = (InputSplitSource<InputSplit>) jobVertex.getInputSplitSource();
					this.splitAssigner = splitSource.getInputSplitAssigner(this.inputSplits);
				}
			}
			catch (Throwable t) {
				throw new RuntimeException("Re-creating the input split assigner failed: " + t.getMessage(), t);
			}

			// Reset intermediate results
			for (IntermediateResult result : producedDataSets) {
				result.resetForNewExecution();
			}
		}
	}

	// --------------------------------------------------------------------------------------------
	//  Accumulators / Metrics
	// --------------------------------------------------------------------------------------------

	public StringifiedAccumulatorResult[] getAggregatedUserAccumulatorsStringified() {
		Map<String, Accumulator<?, ?>> userAccumulators = new HashMap<String, Accumulator<?, ?>>();

		for (ExecutionVertex vertex : taskVertices) {
			Map<String, Accumulator<?, ?>> next = vertex.getCurrentExecutionAttempt().getUserAccumulators();
			if (next != null) {
				AccumulatorHelper.mergeInto(userAccumulators, next);
			}
		}

		return StringifiedAccumulatorResult.stringifyAccumulatorResults(userAccumulators);
	}

	// --------------------------------------------------------------------------------------------
	//  Archiving
	// --------------------------------------------------------------------------------------------

	@Override
	public ArchivedExecutionJobVertex archive() {
		return new ArchivedExecutionJobVertex(this);
	}

	// ------------------------------------------------------------------------
	//  Static Utilities
	// ------------------------------------------------------------------------

	/**
	 * A utility function that computes an "aggregated" state for the vertex.
	 * 
	 * <p>This state is not used anywhere in the  coordination, but can be used for display
	 * in dashboards to as a summary for how the particular parallel operation represented by
	 * this ExecutionJobVertex is currently behaving.
	 * 
	 * <p>For example, if at least one parallel task is failed, the aggregate state is failed.
	 * If not, and at least one parallel task is cancelling (or cancelled), the aggregate state
	 * is cancelling (or cancelled). If all tasks are finished, the aggregate state is finished,
	 * and so on.
	 * 
	 * @param verticesPerState The number of vertices in each state (indexed by the ordinal of
	 *                         the ExecutionState values).
	 * @param parallelism The parallelism of the ExecutionJobVertex
	 * 
	 * @return The aggregate state of this ExecutionJobVertex. 
	 */
	public static ExecutionState getAggregateJobVertexState(int[] verticesPerState, int parallelism) {
		if (verticesPerState == null || verticesPerState.length != ExecutionState.values().length) {
			throw new IllegalArgumentException("Must provide an array as large as there are execution states.");
		}

		if (verticesPerState[ExecutionState.FAILED.ordinal()] > 0) {
			return ExecutionState.FAILED;
		}
		if (verticesPerState[ExecutionState.CANCELING.ordinal()] > 0) {
			return ExecutionState.CANCELING;
		}
		else if (verticesPerState[ExecutionState.CANCELED.ordinal()] > 0) {
			return ExecutionState.CANCELED;
		}
		else if (verticesPerState[ExecutionState.RUNNING.ordinal()] > 0) {
			return ExecutionState.RUNNING;
		}
		else if (verticesPerState[ExecutionState.FINISHED.ordinal()] > 0) {
			return verticesPerState[ExecutionState.FINISHED.ordinal()] == parallelism ?
					ExecutionState.FINISHED : ExecutionState.RUNNING;
		}
		else {
			// all else collapses under created
			return ExecutionState.CREATED;
		}
	}

	public static Map<JobVertexID, ExecutionJobVertex> includeLegacyJobVertexIDs(
			Map<JobVertexID, ExecutionJobVertex> tasks) {

		Map<JobVertexID, ExecutionJobVertex> expanded = new HashMap<>(2 * tasks.size());
		// first include all new ids
		expanded.putAll(tasks);

		// now expand and add legacy ids
		for (ExecutionJobVertex executionJobVertex : tasks.values()) {
			if (null != executionJobVertex) {
				JobVertex jobVertex = executionJobVertex.getJobVertex();
				if (null != jobVertex) {
					List<JobVertexID> alternativeIds = jobVertex.getIdAlternatives();
					for (JobVertexID jobVertexID : alternativeIds) {
						ExecutionJobVertex old = expanded.put(jobVertexID, executionJobVertex);
						Preconditions.checkState(null == old || old.equals(executionJobVertex),
								"Ambiguous jobvertex id detected during expansion to legacy ids.");
					}
				}
			}
		}

		return expanded;
	}

	public static Map<OperatorID, ExecutionJobVertex> includeAlternativeOperatorIDs(
			Map<OperatorID, ExecutionJobVertex> operatorMapping) {

		Map<OperatorID, ExecutionJobVertex> expanded = new HashMap<>(2 * operatorMapping.size());
		// first include all existing ids
		expanded.putAll(operatorMapping);

		// now expand and add user-defined ids
		for (ExecutionJobVertex executionJobVertex : operatorMapping.values()) {
			if (executionJobVertex != null) {
				JobVertex jobVertex = executionJobVertex.getJobVertex();
				if (jobVertex != null) {
					for (OperatorID operatorID : jobVertex.getUserDefinedOperatorIDs()) {
						if (operatorID != null) {
							expanded.put(operatorID, executionJobVertex);
						}
					}
				}
			}
		}

		return expanded;
	}
}
