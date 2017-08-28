package org.apache.flink.streaming.runtime.modification;

import com.google.common.base.Joiner;
import org.apache.commons.lang.StringUtils;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.blob.BlobKey;
import org.apache.flink.runtime.executiongraph.*;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.*;
import org.apache.flink.runtime.messages.modification.AcknowledgeModification;
import org.apache.flink.runtime.messages.modification.DeclineModification;
import org.apache.flink.streaming.runtime.tasks.OneInputStreamTask;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ModificationCoordinator {

	private static final Logger LOG = LoggerFactory.getLogger(ModificationCoordinator.class);

	private static final long DUMMY_MODIFICATION_ID = 123;

	private final ExecutionGraph executionGraph;
	private final Time rpcCallTimeout;
	private final Collection<BlobKey> blobKeys;

	public ModificationCoordinator(ExecutionGraph executionGraph, Time rpcCallTimeout) {
		this.executionGraph = Preconditions.checkNotNull(executionGraph);
		this.rpcCallTimeout = rpcCallTimeout;
		this.blobKeys = new HashSet<>();
	}

	public boolean receiveAcknowledgeMessage(AcknowledgeModification acknowledgeModification) {
		if (acknowledgeModification.getModificationID() == DUMMY_MODIFICATION_ID) {
			LOG.debug("Received successful acknowledge modification message");
			return true;
		} else {
			LOG.debug("Received wrong acknowledge modification message: {}", acknowledgeModification);
			return false;
		}
	}

	public boolean receiveDeclineMessage(DeclineModification declineModification) {
		if (declineModification.getModificationID() == DUMMY_MODIFICATION_ID) {
			LOG.debug("Received successful decline modification message");
			return true;
		} else {
			LOG.debug("Received wrong decline modification message: {}", declineModification);
			return false;
		}
	}

	public void addedNewOperatorJar(Collection<BlobKey> blobKeys) {
		LOG.debug("Adding BlobKeys {} for executionGraph {}.",
			StringUtils.join(blobKeys, ","),
			executionGraph.getJobID());

		this.blobKeys.addAll(blobKeys);
	}

	public void pauseJob() {
		ExecutionJobVertex source = findSource();

		ExecutionJobVertex map = findMap();

		List<JobVertexID> vertexIDS = Collections.singletonList(map.getJobVertexId());

		for (ExecutionVertex executionVertex : source.getTaskVertices()) {
			Execution currentExecutionAttempt = executionVertex.getCurrentExecutionAttempt();

			currentExecutionAttempt.triggerModification(
				DUMMY_MODIFICATION_ID,
				System.currentTimeMillis(),
				vertexIDS);
		}
	}

	public void pauseMapOperator() {

		ExecutionJobVertex source = findMap();

		ExecutionVertex[] taskVertices = source.getTaskVertices();

		for (ExecutionVertex vertex : taskVertices) {
			Execution execution = vertex.getCurrentExecutionAttempt();

			execution.getAssignedResource()
				.getTaskManagerGateway()
				.pauseTask(execution.getAttemptId(), rpcCallTimeout);
		}
	}

	public void resumeMapOperator() {
		ExecutionJobVertex source = findMap();

		ExecutionVertex[] taskVertices = source.getTaskVertices();

		for (ExecutionVertex vertex : taskVertices) {
			Execution execution = vertex.getCurrentExecutionAttempt();

			execution.getAssignedResource()
				.getTaskManagerGateway()
				.resumeTask(execution.getAttemptId(), rpcCallTimeout);
		}
	}

	public void startFilterOperator() {

		ExecutionJobVertex mapOperator = findMap();
		ExecutionJobVertex sourceOperator = findSource();

		List<ExecutionAttemptID> mapAttemptIds = new ArrayList<>();
		for (ExecutionVertex executionVertex : mapOperator.getTaskVertices()) {
			mapAttemptIds.add(executionVertex.getCurrentExecutionAttempt().getAttemptId());
		}

		ExecutionVertex[] sourceTaskVertices = sourceOperator.getTaskVertices();

		if (sourceTaskVertices.length != 1) {
			LOG.info("Found {} ExecutionVertices. Expected {}.", sourceTaskVertices.length, 1);
		} else {
			LOG.info("Found {} ExecutionVertices as expected.", sourceTaskVertices.length);
		}

		ExecutionJobVertex executionJobVertex = buildFilterExecutionJobVertex(sourceOperator);

		for (ExecutionVertex executionVertex : executionJobVertex.getTaskVertices()) {
			boolean successful = executionVertex.getCurrentExecutionAttempt()
				.scheduleForRuntimeExecution(
					executionGraph.getSlotProvider(),
					executionGraph.isQueuedSchedulingAllowed(),
					mapAttemptIds);
		}
	}


	public String getDetails() throws JobException {

		StringBuilder currentPlan = new StringBuilder();

		currentPlan.append(executionGraph.getJsonPlan()).append("\n");

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			currentPlan.append(ejv.generateDebugString()).append("\n");
		}

		JobInformation jobInformation = executionGraph.getJobInformation();

		currentPlan.append("JobInfo: ").append(jobInformation).append(jobInformation.getJobConfiguration()).append("\n");

		for (Map.Entry<JobVertexID, ExecutionJobVertex> vertex : executionGraph.getTasks().entrySet()) {
			currentPlan
				.append("Vertex:")
				.append(vertex.getKey()).append(" - ")
				.append(vertex.getValue().generateDebugString())
				.append(vertex.getValue().getAggregateState())
				.append(" Outputs: ")
				.append(Arrays.toString(vertex.getValue().getProducedDataSets()))
				.append(" JobVertex: ")
				.append(vertex.getValue().getJobVertex())
				.append(" Inputs: ")
				.append(Joiner.on(",").join(vertex.getValue().getInputs()))
				.append(" IntermediateResultPartition: ");

			for (IntermediateResult result : vertex.getValue().getInputs()) {
				for (IntermediateResultPartition partition : result.getPartitions()) {
					currentPlan
						.append(" PartitionId: ")
						.append(partition.getPartitionId())
						.append(" PartitionNumber: ")
						.append(partition.getPartitionNumber())
						.append("\n");
				}
			}

			currentPlan.append("\n");
		}

		for (Map.Entry<ExecutionAttemptID, Execution> exec : executionGraph.getRegisteredExecutions().entrySet()) {
			currentPlan.append(exec.getKey())
				.append(exec.getValue())
				.append(exec.getValue().getVertexWithAttempt())
				.append(exec.getValue().getAssignedResourceLocation())
				.append(" InvokableName: ")
				.append(exec.getValue().getVertex().getJobVertex().getJobVertex().getInvokableClassName())
				.append("\n");
		}

		currentPlan.append("numVerticesTotal: ").append(executionGraph.getTotalNumberOfVertices());
		currentPlan.append("finishedVertices: ").append(executionGraph.getVerticesFinished());

		return currentPlan.toString();
	}

	private ExecutionJobVertex findSource() {

		ExecutionJobVertex executionJobVertex = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			if (ejv.getJobVertex().getName().toLowerCase().contains("source")) {
				executionJobVertex = ejv;
			}
		}

		if (executionJobVertex == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find Source"));
		}

		List<IntermediateDataSet> producedDataSets = executionJobVertex.getJobVertex().getProducedDataSets();

		if (producedDataSets.size() != 1) {
			executionGraph.failGlobal(new ExecutionGraphException("Source has not one producing output dataset"));
		}

		return executionJobVertex;
	}

	private ExecutionJobVertex findMap() {

		ExecutionJobVertex executionJobVertex = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			if (ejv.getJobVertex().getName().toLowerCase().contains("map")) {
				executionJobVertex = ejv;
			}
		}

		if (executionJobVertex == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find map"));
			return null;
		}

		List<JobEdge> producedDataSets = executionJobVertex.getJobVertex().getInputs();

		if (producedDataSets.size() != 1) {
			executionGraph.failGlobal(new ExecutionGraphException("Map has not one consuming input dataset"));
		}

		return executionJobVertex;
	}

	private ExecutionJobVertex buildFilterExecutionJobVertex(ExecutionJobVertex source) {

		JobVertex filterJobVertex = new JobVertex("IntroducedFilterOperator", new JobVertexID());
		filterJobVertex.setParallelism(source.getParallelism());
		filterJobVertex.setInvokableClass(OneInputStreamTask.class);

		IntermediateDataSet intermediateDataSet = new IntermediateDataSet(
			new IntermediateDataSetID(),
			ResultPartitionType.PIPELINED,
			filterJobVertex);

		LOG.info("Created intermediateDataSet with id: " + intermediateDataSet.getId());

		// TODO Add consumer (In this case 2)
		filterJobVertex.createAndAddResultDataSet(intermediateDataSet.getId(), ResultPartitionType.PIPELINED);

		List<IntermediateDataSet> producedDataSets = source.getJobVertex().getProducedDataSets();

		if (producedDataSets.size() != 1) {
			executionGraph.failGlobal(new IllegalStateException("Source has more than one producing dataset"));
		}

		// Connect source IDS as input for FilterOperator
		filterJobVertex.connectDataSetAsInput(producedDataSets.get(0), DistributionPattern.ALL_TO_ALL);

		try {
			ExecutionJobVertex vertex =
				new ExecutionJobVertex(executionGraph,
					filterJobVertex,
					1,
					rpcCallTimeout,
					executionGraph.getGlobalModVersion(),
					System.currentTimeMillis());

			vertex.connectToPredecessorsRuntime(executionGraph.getIntermediateResults());

			ExecutionJobVertex previousTask = executionGraph.getTasks().putIfAbsent(filterJobVertex.getID(), vertex);
			if (previousTask != null) {
				throw new JobException(String.format("Encountered two job vertices with ID %s : previous=[%s] / new=[%s]",
					filterJobVertex.getID(), vertex, previousTask));
			}

			// Add IntermediateResult to ExecutionGraph
			for (IntermediateResult res : vertex.getProducedDataSets()) {
				IntermediateResult previousDataSet = executionGraph.getIntermediateResults().putIfAbsent(res.getId(), res);
				if (previousDataSet != null) {
					throw new JobException(String.format("Encountered two intermediate data set with ID %s : previous=[%s] / new=[%s]",
						res.getId(), res, previousDataSet));
				}
			}

			executionGraph.getVerticesInCreationOrder().add(vertex);
			executionGraph.addNumVertices(vertex.getParallelism());

			// TODO Add to failover strategy

			return vertex;
		} catch (JobException jobException) {
			executionGraph.failGlobal(jobException);
			return null;
		}
	}
}
