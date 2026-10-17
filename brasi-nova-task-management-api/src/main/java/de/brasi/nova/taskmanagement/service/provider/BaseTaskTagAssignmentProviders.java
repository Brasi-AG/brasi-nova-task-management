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

import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.repository.BaseTaskTagAssignmentRepository;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentModel;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentCreateModel;
import de.brasi.nova.taskmanagement.model.TaskTagAssignmentModels.TaskTagAssignmentUpdateModel;

import lombok.NoArgsConstructor;

import de.brasi.nova.taskmanagement.entity.TaskTag;

/** Auto-generiert: BusinessCriteriaProvider-Sammlung für TaskTagAssignment. */
public class BaseTaskTagAssignmentProviders<T extends TaskTagAssignmentModel> {

    public BaseTaskTagAssignmentProviders() {}

    /** Provider: GetTaskTagAssignmentsByTagIdProvider → ruft BaseTaskTagAssignmentRepository.findTaskTagAssignmentsByTagId(...) */
    @NoArgsConstructor
    public static class GetTaskTagAssignmentsByTagIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskTagAssignmentModel>> {

        @Override
        public CollectionModel<TaskTagAssignmentModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID taskTagUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskTagAssignmentRepository repo = services.getRepository(BaseTaskTagAssignmentRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskTagAssignmentModel, TaskTagAssignment, TaskTagAssignmentCreateModel, TaskTagAssignmentUpdateModel> assembler =
                services.getAssembler(TaskTagAssignmentModel.class, TaskTagAssignment.class, TaskTagAssignmentCreateModel.class, TaskTagAssignmentUpdateModel.class);
           

            Long taskTagId = idMapping.resolveUuidToId(taskTagUuid.toString(), TaskTag.class, null);
            List<TaskTagAssignment> entities = repo.findTaskTagAssignmentsByTagId(taskTagId);

            CollectionModel<TaskTagAssignmentModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
