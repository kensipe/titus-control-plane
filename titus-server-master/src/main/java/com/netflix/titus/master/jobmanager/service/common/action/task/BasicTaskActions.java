/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.master.jobmanager.service.common.action.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.netflix.fenzo.TaskRequest;
import com.netflix.titus.api.jobmanager.TaskAttributes;
import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.jobmanager.model.job.JobFunctions;
import com.netflix.titus.api.jobmanager.model.job.JobModel;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.api.jobmanager.model.job.TaskState;
import com.netflix.titus.api.jobmanager.model.job.TaskStatus;
import com.netflix.titus.api.jobmanager.service.JobManagerConstants;
import com.netflix.titus.api.jobmanager.service.JobManagerException;
import com.netflix.titus.api.jobmanager.service.V3JobOperations;
import com.netflix.titus.api.jobmanager.service.V3JobOperations.Trigger;
import com.netflix.titus.api.jobmanager.store.JobStore;
import com.netflix.titus.api.model.Tier;
import com.netflix.titus.api.model.callmetadata.CallMetadata;
import com.netflix.titus.common.framework.reconciler.EntityHolder;
import com.netflix.titus.common.framework.reconciler.ModelActionHolder;
import com.netflix.titus.common.framework.reconciler.ReconciliationEngine;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.util.CollectionsExt;
import com.netflix.titus.common.util.DateTimeExt;
import com.netflix.titus.common.util.ExceptionExt;
import com.netflix.titus.common.util.rx.ReactorExt;
import com.netflix.titus.common.util.tuple.Pair;
import com.netflix.titus.master.jobmanager.service.JobManagerConfiguration;
import com.netflix.titus.master.jobmanager.service.JobManagerUtil;
import com.netflix.titus.master.jobmanager.service.common.V3QueueableTask;
import com.netflix.titus.master.jobmanager.service.common.action.JobEntityHolders;
import com.netflix.titus.master.jobmanager.service.common.action.TaskRetryers;
import com.netflix.titus.master.jobmanager.service.common.action.TitusChangeAction;
import com.netflix.titus.master.jobmanager.service.common.action.TitusModelAction;
import com.netflix.titus.master.jobmanager.service.event.JobManagerReconcilerEvent;
import com.netflix.titus.master.mesos.kubeapiserver.direct.DirectKubeApiServerIntegrator;
import com.netflix.titus.master.scheduler.SchedulingService;
import com.netflix.titus.master.scheduler.constraint.ConstraintEvaluatorTransformer;
import com.netflix.titus.master.scheduler.constraint.SystemHardConstraint;
import com.netflix.titus.master.scheduler.constraint.SystemSoftConstraint;
import com.netflix.titus.master.service.management.ApplicationSlaManagementService;
import rx.Observable;

public class BasicTaskActions {

    /**
     * Update a task, and write it to store before updating reference and store models.
     * This action is used when handling user initiated updates.
     */
    public static TitusChangeAction updateTaskAndWriteItToStore(String taskId,
                                                                ReconciliationEngine<JobManagerReconcilerEvent> engine,
                                                                Function<Task, Task> changeFunction,
                                                                JobStore jobStore,
                                                                Trigger trigger,
                                                                String reason,
                                                                TitusRuntime titusRuntime,
                                                                CallMetadata callMetadata) {
        return TitusChangeAction.newAction("updateTaskAndWriteItToStore")
                .id(taskId)
                .trigger(trigger)
                .summary(reason)
                .changeWithModelUpdates(self ->
                        JobEntityHolders.expectTask(engine, taskId, titusRuntime)
                                .map(task -> {
                                    Task newTask = changeFunction.apply(task);
                                    TitusModelAction modelUpdate = TitusModelAction.newModelUpdate(self).taskUpdate(newTask, callMetadata);
                                    return jobStore.updateTask(newTask).andThen(Observable.just(ModelActionHolder.referenceAndStore(modelUpdate)));
                                })
                                .orElseGet(() -> Observable.error(JobManagerException.taskNotFound(taskId)))
                );
    }

    /**
     * Write updated task record to a store. If a task is completed, remove it from the scheduling service.
     * This command calls {@link JobStore#updateTask(Task)}, which assumes that the task record was created already.
     */
    public static TitusChangeAction writeReferenceTaskToStore(JobStore titusStore,
                                                              SchedulingService<? extends TaskRequest> schedulingService,
                                                              ReconciliationEngine<JobManagerReconcilerEvent> engine,
                                                              String taskId,
                                                              TitusRuntime titusRuntime) {
        return TitusChangeAction.newAction("writeReferenceTaskToStore")
                .trigger(V3JobOperations.Trigger.Reconciler)
                .id(taskId)
                .summary("Persisting task to the store")
                .changeWithModelUpdate(self -> {
                    Optional<EntityHolder> taskHolder = engine.getReferenceView().findById(taskId);
                    if (!taskHolder.isPresent()) {
                        // Should never happen
                        titusRuntime.getCodeInvariants().inconsistent("Reference task with id %s not found.", taskId);
                        return Observable.empty();
                    }
                    Task referenceTask = taskHolder.get().getEntity();

                    return titusStore.updateTask(referenceTask)
                            .andThen(Observable.fromCallable(() -> {
                                if (referenceTask.getStatus().getState() == TaskState.Finished) {
                                    schedulingService.removeTask(referenceTask.getId());
                                }
                                TitusModelAction modelUpdateAction = TitusModelAction.newModelUpdate(self)
                                        .taskUpdate(storeRoot -> {
                                                    EntityHolder storedHolder = EntityHolder.newRoot(referenceTask.getId(), referenceTask);
                                                    return Pair.of(storeRoot.addChild(storedHolder), storedHolder);
                                                }
                                        );
                                return ModelActionHolder.store(modelUpdateAction);
                            }));
                });
    }

    /**
     * Update a task in the reference and running models. If a task moves to Finished state, add retry delay information
     * to the task, and to task entity holder (see {@link TaskRetryers}).
     */
    public static TitusChangeAction updateTaskInRunningModel(String taskId,
                                                             Trigger trigger,
                                                             JobManagerConfiguration configuration,
                                                             ReconciliationEngine<JobManagerReconcilerEvent> engine,
                                                             Function<Task, Optional<Task>> changeFunction,
                                                             String reason,
                                                             TitusRuntime titusRuntime,
                                                             CallMetadata callMetadata) {
        return TitusChangeAction.newAction("updateTaskInRunningModel")
                .id(taskId)
                .trigger(trigger)
                .summary(reason)
                .applyModelUpdates(self -> {
                            Optional<EntityHolder> taskOptional = JobEntityHolders.expectTaskHolder(engine, taskId, titusRuntime);
                            if (!taskOptional.isPresent()) {
                                return Collections.emptyList();
                            }
                            EntityHolder taskHolder = taskOptional.get();
                            Task oldTask = taskHolder.getEntity();
                            Optional<Task> maybeNewTask = changeFunction.apply(oldTask);
                            if (!maybeNewTask.isPresent()) {
                                return Collections.emptyList();
                            }
                            Task newTask = maybeNewTask.get();

                            // Handle separately reference and runtime models, as only reference model gets retry attributes.
                            List<ModelActionHolder> modelActionHolders = new ArrayList<>();

                            // Add retryer data to task context.
                            if (newTask.getStatus().getState() == TaskState.Finished) {
                                modelActionHolders.add(ModelActionHolder.reference(attachRetryer(self, taskHolder, newTask, callMetadata, configuration, titusRuntime)));
                            } else {
                                modelActionHolders.add(ModelActionHolder.reference(TitusModelAction.newModelUpdate(self).taskUpdate(newTask, callMetadata)));
                            }

                            modelActionHolders.add(ModelActionHolder.running(TitusModelAction.newModelUpdate(self).taskUpdate(newTask, callMetadata)));

                            return modelActionHolders;
                        }
                );
    }

    /**
     * Add a task to {@link SchedulingService}, and create runtime entity holder for it.
     */
    public static TitusChangeAction scheduleTask(ApplicationSlaManagementService capacityGroupService,
                                                 SchedulingService<? extends TaskRequest> schedulingService,
                                                 Job<?> job,
                                                 Task task,
                                                 Supplier<Boolean> opportunisticSchedulingEnabled,
                                                 Supplier<Set<String>> activeTasksGetter,
                                                 ConstraintEvaluatorTransformer<Pair<String, String>> constraintEvaluatorTransformer,
                                                 SystemSoftConstraint systemSoftConstraint,
                                                 SystemHardConstraint systemHardConstraint) {
        return TitusChangeAction.newAction("scheduleTask")
                .task(task)
                .trigger(V3JobOperations.Trigger.Reconciler)
                .summary("Adding task to scheduler")
                .applyModelUpdate(self -> {
                    Pair<Tier, String> tierAssignment = JobManagerUtil.getTierAssignment(job, capacityGroupService);
                    schedulingService.addTask(new V3QueueableTask(
                            tierAssignment.getLeft(),
                            tierAssignment.getRight(),
                            job,
                            task,
                            JobFunctions.getJobRuntimePrediction(job),
                            opportunisticSchedulingEnabled,
                            activeTasksGetter,
                            constraintEvaluatorTransformer,
                            systemSoftConstraint,
                            systemHardConstraint
                    ));

                    TitusModelAction modelUpdateAction = TitusModelAction.newModelUpdate(self)
                            .summary("Creating new task entity holder")
                            .taskMaybeUpdate(jobHolder -> {
                                EntityHolder newTask = EntityHolder.newRoot(task.getId(), task);
                                EntityHolder newRoot = jobHolder.addChild(newTask);
                                return Optional.of(Pair.of(newRoot, newTask));
                            });
                    return ModelActionHolder.running(modelUpdateAction);
                });
    }

    /**
     * Create pod for a task.
     */
    public static TitusChangeAction launchTaskInKube(DirectKubeApiServerIntegrator kubeApiServerIntegrator,
                                                     JobManagerConfiguration configuration,
                                                     ReconciliationEngine<JobManagerReconcilerEvent> engine,
                                                     Job<?> job,
                                                     Task task,
                                                     CallMetadata callMetadata,
                                                     TitusRuntime titusRuntime) {
        return TitusChangeAction.newAction("launchTaskInKube")
                .task(task)
                .trigger(V3JobOperations.Trigger.Reconciler)
                .summary("Adding task to Kube")
                .changeWithModelUpdates(self -> {
                    EntityHolder taskHolder = JobEntityHolders.expectTaskHolder(engine, task.getId(), titusRuntime).orElse(null);
                    if (taskHolder == null) {
                        // This should never happen.
                        return Observable.just(Collections.emptyList());
                    }

                    return ReactorExt.toCompletable(kubeApiServerIntegrator.launchTask(job, task).then())
                            .andThen(Observable.fromCallable(() -> {
                                TaskStatus taskStatus = JobModel.newTaskStatus()
                                        .withState(TaskState.Accepted)
                                        .withReasonCode(TaskStatus.REASON_POD_CREATED)
                                        .withReasonMessage("Created pod in Kube")
                                        .withTimestamp(titusRuntime.getClock().wallTime())
                                        .build();
                                Task taskWithPod = task.toBuilder()
                                        .withTaskContext(CollectionsExt.copyAndAdd(task.getTaskContext(), TaskAttributes.TASK_ATTRIBUTES_POD_CREATED, "true"))
                                        .withStatus(taskStatus)
                                        .withStatusHistory(CollectionsExt.copyAndAdd(task.getStatusHistory(), task.getStatus()))
                                        .build();

                                TitusModelAction modelUpdateAction = TitusModelAction.newModelUpdate(self).taskUpdate(taskWithPod, callMetadata);
                                return ModelActionHolder.referenceAndRunning(modelUpdateAction);
                            }))
                            .onErrorReturn(error -> {
                                // Move task to the finished state after we failed to create a pod object for it.
                                String reasonCode = kubeApiServerIntegrator.resolveReasonCode(error);
                                Task finishedTask = JobFunctions.changeTaskStatus(task, JobModel.newTaskStatus()
                                        .withState(TaskState.Finished)
                                        .withReasonCode(reasonCode)
                                        .withReasonMessage("Failed to create pod: " + ExceptionExt.toMessageChain(error))
                                        .withTimestamp(titusRuntime.getClock().wallTime())
                                        .build()
                                );

                                List<ModelActionHolder> modelActionHolders = new ArrayList<>();
                                modelActionHolders.add(ModelActionHolder.reference(attachRetryer(self, taskHolder, finishedTask, callMetadata, configuration, titusRuntime)));
                                modelActionHolders.add(ModelActionHolder.running(TitusModelAction.newModelUpdate(self).taskUpdate(finishedTask, callMetadata)));

                                return modelActionHolders;
                            });
                });
    }

    private static TitusModelAction attachRetryer(TitusChangeAction.Builder self,
                                                  EntityHolder taskHolder,
                                                  Task updatedTask,
                                                  CallMetadata callMetadata,
                                                  JobManagerConfiguration configuration,
                                                  TitusRuntime titusRuntime) {
        long retryDelayMs = TaskRetryers.getCurrentRetryerDelayMs(
                taskHolder, configuration.getMinRetryIntervalMs(), configuration.getTaskRetryerResetTimeMs(), titusRuntime.getClock()
        );
        String retryDelayString = DateTimeExt.toTimeUnitString(retryDelayMs);

        updatedTask = updatedTask.toBuilder()
                .addToTaskContext(TaskAttributes.TASK_ATTRIBUTES_RETRY_DELAY, retryDelayString)
                .build();
        EntityHolder newTaskHolder = taskHolder.
                setEntity(updatedTask)
                .addTag(JobManagerConstants.JOB_MANAGER_ATTRIBUTE_CALLMETADATA, callMetadata)
                .addTag(TaskRetryers.ATTR_TASK_RETRY_DELAY_MS, retryDelayMs);

        return TitusModelAction.newModelUpdate(self)
                .summary("Setting retry delay on task in Finished state: %s", retryDelayString)
                .addTaskHolder(newTaskHolder);
    }
}
