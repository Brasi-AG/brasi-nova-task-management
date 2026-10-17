/*
 * Copyright (C) 2026 Keel GmbH. Alle Rechte vorbehalten.
 */
package de.brasi.nova.taskmanagement.service.provider;

import java.util.List;
import java.util.UUID;

import org.springframework.hateoas.CollectionModel;

import io.keel.framework.api.cache.IdMappingService;
import io.keel.framework.api.assembler.NovaAssembler;
import io.keel.framework.api.service.provider.BusinessCriteriaProvider;
import io.keel.framework.api.service.provider.NovaServiceProvider;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskModel;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskCreateModel;
import de.brasi.nova.taskmanagement.model.TaskModels.TaskUpdateModel;

import lombok.NoArgsConstructor;

import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;

/** Auto-generiert: BusinessCriteriaProvider-Sammlung für Task. */
public class BaseTaskProviders<T extends TaskModel> {

    public BaseTaskProviders() {}

    /** Provider: GetTasksByDirectionIdProvider → ruft BaseTaskRepository.findTasksByDirectionId(...) */
    @NoArgsConstructor
    public static class GetTasksByDirectionIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskModel>> {

        @Override
        public CollectionModel<TaskModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID taskDirectionUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskRepository repo = services.getRepository(BaseTaskRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskModel, Task, TaskCreateModel, TaskUpdateModel> assembler =
                services.getAssembler(TaskModel.class, Task.class, TaskCreateModel.class, TaskUpdateModel.class);
           

            Long taskDirectionId = idMapping.resolveUuidToId(taskDirectionUuid.toString(), TaskDirection.class, null);
            List<Task> entities = repo.findTasksByDirectionId(taskDirectionId);

            CollectionModel<TaskModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }
    /** Provider: GetTasksByPriorityIdProvider → ruft BaseTaskRepository.findTasksByPriorityId(...) */
    @NoArgsConstructor
    public static class GetTasksByPriorityIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskModel>> {

        @Override
        public CollectionModel<TaskModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID taskPriorityUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskRepository repo = services.getRepository(BaseTaskRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskModel, Task, TaskCreateModel, TaskUpdateModel> assembler =
                services.getAssembler(TaskModel.class, Task.class, TaskCreateModel.class, TaskUpdateModel.class);
           

            Long taskPriorityId = idMapping.resolveUuidToId(taskPriorityUuid.toString(), TaskPriority.class, null);
            List<Task> entities = repo.findTasksByPriorityId(taskPriorityId);

            CollectionModel<TaskModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }
    /** Provider: GetTasksByTaskStatusIdProvider → ruft BaseTaskRepository.findTasksByTaskStatusId(...) */
    @NoArgsConstructor
    public static class GetTasksByTaskStatusIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskModel>> {

        @Override
        public CollectionModel<TaskModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID taskStatusUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskRepository repo = services.getRepository(BaseTaskRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskModel, Task, TaskCreateModel, TaskUpdateModel> assembler =
                services.getAssembler(TaskModel.class, Task.class, TaskCreateModel.class, TaskUpdateModel.class);
           

            Long taskStatusId = idMapping.resolveUuidToId(taskStatusUuid.toString(), TaskStatus.class, null);
            List<Task> entities = repo.findTasksByTaskStatusId(taskStatusId);

            CollectionModel<TaskModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
