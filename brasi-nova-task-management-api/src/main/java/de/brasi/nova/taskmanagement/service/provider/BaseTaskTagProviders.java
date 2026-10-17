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

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.repository.BaseTaskTagRepository;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagModel;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagCreateModel;
import de.brasi.nova.taskmanagement.model.TaskTagModels.TaskTagUpdateModel;

import lombok.NoArgsConstructor;

import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;

/** Auto-generiert: BusinessCriteriaProvider-Sammlung für TaskTag. */
public class BaseTaskTagProviders<T extends TaskTagModel> {

    public BaseTaskTagProviders() {}

    /** Provider: GetTaskTagsBySeverityIdProvider → ruft BaseTaskTagRepository.findTaskTagsBySeverityId(...) */
    @NoArgsConstructor
    public static class GetTaskTagsBySeverityIdProvider
            implements BusinessCriteriaProvider<CollectionModel<TaskTagModel>> {

        @Override
        public CollectionModel<TaskTagModel> execute(NovaServiceProvider services, Object[] args) throws Exception {
            UUID tagSeverityUuid = (UUID) args[0];

            IdMappingService idMapping = services.getBean(IdMappingService.class);
            BaseTaskTagRepository repo = services.getRepository(BaseTaskTagRepository.class);
            @SuppressWarnings("unchecked")
            NovaAssembler<TaskTagModel, TaskTag, TaskTagCreateModel, TaskTagUpdateModel> assembler =
                services.getAssembler(TaskTagModel.class, TaskTag.class, TaskTagCreateModel.class, TaskTagUpdateModel.class);
           

            Long tagSeverityId = idMapping.resolveUuidToId(tagSeverityUuid.toString(), TagSeverity.class, null);
            List<TaskTag> entities = repo.findTaskTagsBySeverityId(tagSeverityId);

            CollectionModel<TaskTagModel> model = assembler.toCollectionModel(entities);
            return model;
        }
    }

    // <NOVA_CLI_CUSTOM_CODE_START SECTION="Methods">
    
    // <NOVA_CLI_CUSTOM_CODE_END SECTION="Methods">
}
