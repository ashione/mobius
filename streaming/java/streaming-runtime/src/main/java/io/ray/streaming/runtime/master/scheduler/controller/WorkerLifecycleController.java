package io.ray.streaming.runtime.master.scheduler.controller;

import io.ray.api.BaseActorHandle;
import io.ray.api.ObjectRef;
import io.ray.api.Ray;
import io.ray.api.WaitResult;
import io.ray.api.function.PyActorClass;
import io.ray.api.id.ActorId;
import io.ray.streaming.api.Language;
import io.ray.streaming.runtime.core.graph.executiongraph.ExecutionGraph;
import io.ray.streaming.runtime.core.graph.executiongraph.ExecutionVertex;
import io.ray.streaming.runtime.generated.RemoteCall;
import io.ray.streaming.runtime.python.GraphPbBuilder;
import io.ray.streaming.runtime.rpc.RemoteCallWorker;
import io.ray.streaming.runtime.util.RemoteCallUtils;
import io.ray.streaming.runtime.worker.JobWorker;
import io.ray.streaming.runtime.worker.context.JobWorkerContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Worker lifecycle controller is used to control JobWorker's creation, initiation and so on. */
public class WorkerLifecycleController {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerLifecycleController.class);

  public boolean createWorkers(ExecutionGraph executionGraph) {
    LOG.info(
        "Use placement group: {} to create workers({}).",
        executionGraph.getExecutionGroups() == null ? "empty" : executionGraph.getExecutionGroups(),
        executionGraph.getAllNewbornVertices().size());
    executionGraph.buildPlacementGroupToAllVertices();
    List<ExecutionVertex> executionVertices = executionGraph.getAllNewbornVertices();

    //    // Set worker config
    //    executionVertices.forEach(executionVertex -> {
    //      Map<String, String> conf = setUpWorkerConfig(jobConfig.getWorkerConfigTemplate(),
    // executionVertex);
    //      LOG.info("Worker {} conf is {}.", executionVertex.getExecutionVertexName(), conf);
    //    });

    LOG.info("Begin creating workers, size: {}.", executionVertices.size());
    long now = System.currentTimeMillis();

    boolean result = RemoteCallUtils.asyncBatchExecute(this::createWorker, executionVertices);

    long cost = System.currentTimeMillis() - now;
    LOG.info("Finish workers' creation request. Cost {} ms.", cost);

    return result;
  }

  private boolean createWorker(ExecutionVertex executionVertex) {
    LOG.info(
        "Start to create worker actor for vertex: {} with resource: {}, workeConfig: {}.",
        executionVertex.getExecutionVertexName(),
        executionVertex.getResource(),
        executionVertex.getJobConfig());

    Language language = executionVertex.getLanguage();

    BaseActorHandle actor;
    if (Language.JAVA == language) {
      actor =
          Ray.actor(JobWorker::new, executionVertex)
              .setResources(executionVertex.getResource())
              .setMaxRestarts(-1)
              .remote();
    } else {
      RemoteCall.ExecutionVertexContext.ExecutionVertex vertexPb =
          new GraphPbBuilder().buildVertex(executionVertex);
      actor =
          Ray.actor(
                  PyActorClass.of("raystreaming.runtime.worker", "JobWorker"),
                  vertexPb.toByteArray())
              .setResources(executionVertex.getResource())
              .setMaxRestarts(-1)
              .remote();
    }

    if (null == actor) {
      LOG.error("Create worker actor failed.");
      return false;
    }

    executionVertex.setActor(actor);

    LOG.info(
        "Worker actor created, actor: {}, vertex: {}.",
        executionVertex.getWorkerActorId(),
        executionVertex.getExecutionVertexName());
    return true;
  }

  /**
   * Using context to init JobWorker.
   *
   * @param vertexToContextMap target JobWorker actor
   * @param timeout timeout for waiting, unit: ms
   * @return initiation result
   */
  public boolean initWorkers(
      Map<ExecutionVertex, JobWorkerContext> vertexToContextMap, int timeout) {
    LOG.info("Begin initiating workers: {}.", vertexToContextMap);
    long startTime = System.currentTimeMillis();

    Map<ObjectRef<Boolean>, ActorId> rayObjects = new HashMap<>();
    vertexToContextMap
        .entrySet()
        .forEach(
            (entry -> {
              ExecutionVertex vertex = entry.getKey();
              rayObjects.put(
                  RemoteCallWorker.initWorker(vertex.getActor(), entry.getValue()),
                  vertex.getWorkerActorId());
            }));

    List<ObjectRef<Boolean>> objectRefList = new ArrayList<>(rayObjects.keySet());

    LOG.info("Waiting for workers' initialization.");
    WaitResult<Boolean> result = Ray.wait(objectRefList, objectRefList.size(), timeout);
    if (result.getReady().size() != objectRefList.size()) {
      LOG.error("Initializing workers timeout[{} ms].", timeout);
      return false;
    }

    LOG.info("Finished waiting workers' initialization.");
    LOG.info("Workers initialized. Cost {} ms.", System.currentTimeMillis() - startTime);
    return true;
  }

  /**
   * Start JobWorkers to run task.
   *
   * @param executionGraph physical plan
   * @param timeout timeout for waiting, unit: ms
   * @return starting result
   */
  public boolean startWorkers(ExecutionGraph executionGraph, long lastCheckpointId, int timeout) {
    LOG.info("Begin starting workers.");
    long startTime = System.currentTimeMillis();
    List<ObjectRef<Object>> objectRefs = new ArrayList<>();

    // start source actors 1st
    executionGraph
        .getSourceActors()
        .forEach(actor -> objectRefs.add(RemoteCallWorker.rollback(actor, lastCheckpointId)));

    // then start non-source actors
    executionGraph
        .getNonSourceActors()
        .forEach(actor -> objectRefs.add(RemoteCallWorker.rollback(actor, lastCheckpointId)));

    WaitResult<Object> result = Ray.wait(objectRefs, objectRefs.size(), timeout);
    if (result.getReady().size() != objectRefs.size()) {
      LOG.error("Starting workers timeout[{} ms].", timeout);
      return false;
    }

    LOG.info("Workers started. Cost {} ms.", System.currentTimeMillis() - startTime);
    return true;
  }

  /**
   * Stop and destroy JobWorkers' actor.
   *
   * @param executionVertices target vertices
   * @return destroy result
   */
  public boolean destroyWorkers(List<ExecutionVertex> executionVertices) {
    return RemoteCallUtils.asyncBatchExecute(this::destroyWorker, executionVertices);
  }

  private boolean destroyWorker(ExecutionVertex executionVertex) {
    if (null == executionVertex.getActor()) {
      LOG.error("Execution vertex does not have an actor!");
      return false;
    }

    BaseActorHandle rayActor = executionVertex.getActor();
    LOG.info(
        "Begin destroying worker[vertex={}, actor={}].",
        executionVertex.getExecutionVertexName(),
        rayActor.getId());

    boolean destroyResult = RemoteCallWorker.shutdownWithoutReconstruction(rayActor);

    if (!destroyResult) {
      LOG.error(
          "Failed to destroy JobWorker[{}]'s actor: {}.",
          executionVertex.getExecutionVertexName(),
          rayActor);
      return false;
    }

    LOG.info("Worker destroyed, actor: {}.", rayActor);
    return true;
  }

  public boolean destroyWorkersDirectly(List<ExecutionVertex> executionVertices) {
    return RemoteCallUtils.asyncBatchExecute(this::destroyWorkerDirectly, executionVertices);
  }

  private boolean destroyWorkerDirectly(ExecutionVertex executionVertex) {
    if (null == executionVertex.getActor()) {
      LOG.error("Execution vertex does not have an actor!");
      return false;
    }

    LOG.info(
        "Start to destroy JobWorker actor directly for vertex: {}.",
        executionVertex.getExecutionVertexName());
    if (!Ray.getRuntimeContext().isLocalMode()) {
      executionVertex.getActor().kill();
    }

    LOG.info(
        "Destroy JobWorker directly succeeded, actor: {}.", executionVertex.getWorkerActorId());
    return true;
  }
}
