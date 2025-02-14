/*
 * Copyright 2021-2023 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.data.local.register

import ca.uhn.fhir.rest.gclient.DateClientParam
import ca.uhn.fhir.rest.gclient.NumberClientParam
import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import ca.uhn.fhir.rest.gclient.StringClientParam
import ca.uhn.fhir.rest.gclient.TokenClientParam
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.logicalId
import com.google.android.fhir.search.Search
import com.google.android.fhir.search.filter.ReferenceParamFilterCriterion
import com.google.android.fhir.search.filter.TokenParamFilterCriterion
import com.google.android.fhir.search.has
import java.util.LinkedList
import javax.inject.Inject
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.configuration.ConfigType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.profile.ProfileConfiguration
import org.smartregister.fhircore.engine.configuration.register.ActiveResourceFilterConfig
import org.smartregister.fhircore.engine.configuration.register.RegisterConfiguration
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.domain.model.FhirResourceConfig
import org.smartregister.fhircore.engine.domain.model.RelatedResourceCount
import org.smartregister.fhircore.engine.domain.model.RepositoryResourceData
import org.smartregister.fhircore.engine.domain.model.ResourceConfig
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.domain.model.SortConfig
import org.smartregister.fhircore.engine.domain.repository.Repository
import org.smartregister.fhircore.engine.rulesengine.ConfigRulesExecutor
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.filterBy
import timber.log.Timber

class RegisterRepository
@Inject
constructor(
  override val fhirEngine: FhirEngine,
  override val dispatcherProvider: DispatcherProvider,
  override val sharedPreferencesHelper: SharedPreferencesHelper,
  override val configurationRegistry: ConfigurationRegistry,
  override val configService: ConfigService,
  val configRulesExecutor: ConfigRulesExecutor
) :
  Repository,
  DefaultRepository(
    fhirEngine = fhirEngine,
    dispatcherProvider = dispatcherProvider,
    sharedPreferencesHelper = sharedPreferencesHelper,
    configurationRegistry = configurationRegistry,
    configService = configService
  ) {

  override suspend fun loadRegisterData(
    currentPage: Int,
    registerId: String,
    paramsMap: Map<String, String>?
  ): List<RepositoryResourceData> {
    val registerConfiguration = retrieveRegisterConfiguration(registerId, paramsMap)
    return searchResourcesRecursively(
      filterActiveResources = registerConfiguration.activeResourceFilters,
      fhirResourceConfig = registerConfiguration.fhirResource,
      secondaryResourceConfigs = registerConfiguration.secondaryResources,
      currentPage = currentPage,
      pageSize = registerConfiguration.pageSize,
      configRules = registerConfiguration.configRules
    )
  }

  private suspend fun searchResourcesRecursively(
    filterActiveResources: List<ActiveResourceFilterConfig>?,
    fhirResourceConfig: FhirResourceConfig,
    secondaryResourceConfigs: List<FhirResourceConfig>?,
    currentPage: Int? = null,
    pageSize: Int? = null,
    configRules: List<RuleConfig>?
  ): List<RepositoryResourceData> {
    val baseResourceConfig = fhirResourceConfig.baseResource
    val relatedResourcesConfig = fhirResourceConfig.relatedResources
    val configComputedRuleValues = configRules.configRulesComputedValues()
    val search =
      Search(type = baseResourceConfig.resource).apply {
        applyConfiguredSortAndFilters(
          resourceConfig = baseResourceConfig,
          filterActiveResources = filterActiveResources,
          sortData = true,
          configComputedRuleValues = configComputedRuleValues
        )
        if (currentPage != null && pageSize != null) {
          count = pageSize
          from = currentPage * pageSize
        }
      }

    val baseFhirResources =
      kotlin
        .runCatching {
          withContext(dispatcherProvider.io()) { fhirEngine.search<Resource>(search) }
        }
        .onFailure { Timber.e(it, "Error retrieving resources. Empty list returned by default") }
        .getOrDefault(emptyList())

    return baseFhirResources.map { baseFhirResource ->
      val retrievedRelatedResources =
        withContext(dispatcherProvider.io()) {
          retrieveRelatedResources(
            resources = listOf(baseFhirResource),
            relatedResourcesConfigs = relatedResourcesConfig,
            relatedResourceWrapper = RelatedResourceWrapper(),
            configComputedRuleValues = configComputedRuleValues
          )
        }
      RepositoryResourceData(
        resourceRulesEngineFactId = baseResourceConfig.id ?: baseResourceConfig.resource.name,
        resource = baseFhirResource,
        relatedResourcesMap = retrievedRelatedResources.relatedResourceMap,
        relatedResourcesCountMap = retrievedRelatedResources.relatedResourceCountMap,
        secondaryRepositoryResourceData =
          withContext(dispatcherProvider.io()) {
            secondaryResourceConfigs.retrieveSecondaryRepositoryResourceData(filterActiveResources)
          }
      )
    }
  }

  /** This function fetches other resources that are not linked to the base/primary resource. */
  private suspend fun List<FhirResourceConfig>?.retrieveSecondaryRepositoryResourceData(
    filterActiveResources: List<ActiveResourceFilterConfig>?
  ): LinkedList<RepositoryResourceData> {
    val secondaryRepositoryResourceDataLinkedList = LinkedList<RepositoryResourceData>()
    this?.forEach {
      secondaryRepositoryResourceDataLinkedList.addAll(
        searchResourcesRecursively(
          fhirResourceConfig = it,
          filterActiveResources = filterActiveResources,
          secondaryResourceConfigs = null,
          configRules = null
        )
      )
    }
    return secondaryRepositoryResourceDataLinkedList
  }

  private suspend fun retrieveRelatedResources(
    resources: List<Resource>,
    relatedResourcesConfigs: List<ResourceConfig>?,
    relatedResourceWrapper: RelatedResourceWrapper,
    configComputedRuleValues: Map<String, Any>
  ): RelatedResourceWrapper {

    // Forward include related resources e.g. Members (Patient) referenced in Group resource
    val forwardIncludeResourceConfigs =
      relatedResourcesConfigs?.revIncludeRelatedResourceConfigs(false)
    if (!forwardIncludeResourceConfigs.isNullOrEmpty()) {
      searchWithRevInclude(
        isRevInclude = false,
        relatedResourcesConfigs = forwardIncludeResourceConfigs,
        resources = resources,
        relatedResourceWrapper = relatedResourceWrapper,
        configComputedRuleValues = configComputedRuleValues
      )
    }

    val countResourceConfigs = relatedResourcesConfigs?.filter { it.resultAsCount }
    countResourceConfigs?.forEach { resourceConfig ->
      if (resourceConfig.searchParameter.isNullOrEmpty()) {
        Timber.e("Search parameter require to perform count query. Current config: $resourceConfig")
      }

      // Count for each related resource or aggregate total count in one query; as configured
      if (resourceConfig.resultAsCount && !resourceConfig.searchParameter.isNullOrEmpty()) {
        if (resourceConfig.countResultConfig?.sumCounts == true) {
          val search =
            Search(resourceConfig.resource).apply {
              val filters =
                resources.map {
                  val apply: ReferenceParamFilterCriterion.() -> Unit = {
                    value = it.logicalId.asReference(it.resourceType).reference
                  }
                  apply
                }
              filter(ReferenceClientParam(resourceConfig.searchParameter), *filters.toTypedArray())
              applyConfiguredSortAndFilters(
                resourceConfig = resourceConfig,
                sortData = false,
                configComputedRuleValues = configComputedRuleValues
              )
            }
          val key = resourceConfig.id ?: resourceConfig.resource.name
          search.count(
            onSuccess = {
              relatedResourceWrapper.relatedResourceCountMap[key] =
                LinkedList<RelatedResourceCount>().apply { add(RelatedResourceCount(count = it)) }
            },
            onFailure = {
              Timber.e(
                it,
                "Error retrieving total count for all related resourced identified by $key"
              )
            }
          )
        } else {
          computeCountForEachRelatedResource(
            resources = resources,
            resourceConfig = resourceConfig,
            relatedResourceWrapper = relatedResourceWrapper,
            configComputedRuleValues = configComputedRuleValues
          )
        }
      }
    }

    // Reverse include related resources e.g. All CarePlans, Immunization for Patient resource
    val reverseIncludeResourceConfigs =
      relatedResourcesConfigs?.revIncludeRelatedResourceConfigs(true)
    if (!reverseIncludeResourceConfigs.isNullOrEmpty()) {
      searchWithRevInclude(
        isRevInclude = true,
        relatedResourcesConfigs = reverseIncludeResourceConfigs,
        resources = resources,
        relatedResourceWrapper = relatedResourceWrapper,
        configComputedRuleValues = configComputedRuleValues
      )
    }

    return relatedResourceWrapper
  }

  suspend fun Search.count(
    onSuccess: (Long) -> Unit = {},
    onFailure: (Throwable) -> Unit = { throwable -> Timber.e(throwable, "Error counting data") }
  ): Long =
    kotlin
      .runCatching { withContext(dispatcherProvider.io()) { fhirEngine.count(this@count) } }
      .onSuccess { count -> onSuccess(count) }
      .onFailure { throwable -> onFailure(throwable) }
      .getOrDefault(0)

  private suspend fun computeCountForEachRelatedResource(
    resources: List<Resource>,
    resourceConfig: ResourceConfig,
    relatedResourceWrapper: RelatedResourceWrapper,
    configComputedRuleValues: Map<String, Any>
  ) {
    val relatedResourceCountLinkedList = LinkedList<RelatedResourceCount>()
    val key = resourceConfig.id ?: resourceConfig.resource.name
    resources.forEach { baseResource ->
      val search =
        Search(type = resourceConfig.resource).apply {
          filter(
            ReferenceClientParam(resourceConfig.searchParameter),
            { value = baseResource.logicalId.asReference(baseResource.resourceType).reference }
          )
          applyConfiguredSortAndFilters(
            resourceConfig = resourceConfig,
            sortData = false,
            configComputedRuleValues = configComputedRuleValues
          )
        }
      search.count(
        onSuccess = {
          relatedResourceCountLinkedList.add(
            RelatedResourceCount(
              relatedResourceType = resourceConfig.resource,
              parentResourceId = baseResource.logicalId,
              count = it
            )
          )
        },
        onFailure = {
          Timber.e(
            it,
            "Error retrieving count for ${baseResource.logicalId.asReference(baseResource.resourceType)} for related resource identified ID $key"
          )
        }
      )
    }

    // Add each related resource count query result to map
    relatedResourceWrapper.relatedResourceCountMap[key] = relatedResourceCountLinkedList
  }

  private fun Search.applyConfiguredSortAndFilters(
    resourceConfig: ResourceConfig,
    filterActiveResources: List<ActiveResourceFilterConfig>? = null,
    sortData: Boolean,
    configComputedRuleValues: Map<String, Any>
  ) {
    val activeResource = filterActiveResources?.find { it.resourceType == resourceConfig.resource }
    if (!filterActiveResources.isNullOrEmpty() && activeResource?.active == true) {
      filter(TokenClientParam(ACTIVE), { value = of(true) })
    }

    resourceConfig.dataQueries?.forEach { dataQuery ->
      filterBy(dataQuery = dataQuery, configComputedRuleValues = configComputedRuleValues)
    }

    resourceConfig.nestedSearchResources?.forEach {
      has(it.resourceType, ReferenceClientParam((it.referenceParam))) {
        it.dataQueries?.forEach { dataQuery ->
          filterBy(dataQuery = dataQuery, configComputedRuleValues = configComputedRuleValues)
        }
      }
    }

    if (sortData) sort(resourceConfig.sortConfigs)
  }

  /**
   * If [isRevInclude] is set to false, the forward include search API will be used; otherwise
   * reverse include is used to retrieve related resources. [relatedResourceWrapper] is a data class
   * that wraps the maps used to store Search Query results. The [relatedResourcesConfigs]
   * configures which resources to load.
   */
  private suspend fun searchWithRevInclude(
    isRevInclude: Boolean,
    relatedResourcesConfigs: List<ResourceConfig>?,
    resources: List<Resource>,
    relatedResourceWrapper: RelatedResourceWrapper,
    configComputedRuleValues: Map<String, Any>
  ) {
    val relatedResourcesConfigsMap = relatedResourcesConfigs?.groupBy { it.resource }

    if (!relatedResourcesConfigsMap.isNullOrEmpty()) {
      if (resources.isEmpty()) return

      val firstResourceType = resources.first().resourceType
      val search =
        Search(firstResourceType).apply {
          val filters =
            resources.map {
              val apply: TokenParamFilterCriterion.() -> Unit = { value = of(it.logicalId) }
              apply
            }
          filter(Resource.RES_ID, *filters.toTypedArray())
        }

      relatedResourcesConfigs.forEach { resourceConfig ->
        search.apply {
          applyConfiguredSortAndFilters(
            resourceConfig = resourceConfig,
            sortData = true,
            configComputedRuleValues = configComputedRuleValues
          )
          if (isRevInclude) {
            revInclude(
              resourceConfig.resource,
              ReferenceClientParam(resourceConfig.searchParameter)
            )
          } else {
            include(ReferenceClientParam(resourceConfig.searchParameter), resourceConfig.resource)
          }
        }
      }

      searchRelatedResources(
        isRevInclude = isRevInclude,
        search = search,
        relatedResourcesConfigsMap = relatedResourcesConfigsMap,
        relatedResourceWrapper = relatedResourceWrapper,
        configComputedRuleValues = configComputedRuleValues
      )
    }
  }

  private suspend fun searchRelatedResources(
    isRevInclude: Boolean,
    search: Search,
    relatedResourcesConfigsMap: Map<ResourceType, List<ResourceConfig>>,
    relatedResourceWrapper: RelatedResourceWrapper,
    configComputedRuleValues: Map<String, Any>
  ) {
    kotlin
      .runCatching { fhirEngine.searchWithRevInclude<Resource>(isRevInclude, search) }
      .onSuccess { searchResult ->
        searchResult.values.forEach { theRelatedResourcesMap: Map<ResourceType, List<Resource>> ->
          theRelatedResourcesMap.forEach { entry ->
            val currentResourceConfigs = relatedResourcesConfigsMap[entry.key]

            val key = // Use configured id as key otherwise default to ResourceType
              if (relatedResourcesConfigsMap.containsKey(entry.key)) {
                currentResourceConfigs?.firstOrNull()?.id ?: entry.key.name
              } else entry.key.name

            // All nested resources flattened to one map by adding to existing list
            relatedResourceWrapper.relatedResourceMap[key] =
              relatedResourceWrapper
                .relatedResourceMap
                .getOrPut(key) { LinkedList() }
                .plus(entry.value)

            currentResourceConfigs?.forEach { resourceConfig ->
              if (resourceConfig.relatedResources.isNotEmpty())
                retrieveRelatedResources(
                  resources = entry.value,
                  relatedResourcesConfigs = resourceConfig.relatedResources,
                  relatedResourceWrapper = relatedResourceWrapper,
                  configComputedRuleValues = configComputedRuleValues
                )
            }
          }
        }
      }
      .onFailure {
        Timber.e(it, "Error fetching configured related resources: $relatedResourcesConfigsMap")
      }
  }

  private fun List<ResourceConfig>.revIncludeRelatedResourceConfigs(isRevInclude: Boolean) =
    if (isRevInclude) this.filter { it.isRevInclude && !it.resultAsCount }
    else this.filter { !it.isRevInclude && !it.resultAsCount }

  private fun Search.sort(sortConfigs: List<SortConfig>) {
    sortConfigs.forEach { sortConfig ->
      when (sortConfig.dataType) {
        DataType.INTEGER -> sort(NumberClientParam(sortConfig.paramName), sortConfig.order)
        DataType.DATE -> sort(DateClientParam(sortConfig.paramName), sortConfig.order)
        DataType.STRING -> sort(StringClientParam(sortConfig.paramName), sortConfig.order)
        else -> {
          /*Unsupported data type*/
        }
      }
    }
  }

  /** Count register data for the provided [registerId]. Use the configured base resource filters */
  override suspend fun countRegisterData(
    registerId: String,
    paramsMap: Map<String, String>?
  ): Long {
    val registerConfiguration = retrieveRegisterConfiguration(registerId, paramsMap)
    val baseResourceConfig = registerConfiguration.fhirResource.baseResource
    val configComputedRuleValues = registerConfiguration.configRules.configRulesComputedValues()
    val search =
      Search(baseResourceConfig.resource).apply {
        applyConfiguredSortAndFilters(
          resourceConfig = baseResourceConfig,
          sortData = false,
          filterActiveResources = registerConfiguration.activeResourceFilters,
          configComputedRuleValues = configComputedRuleValues
        )
      }
    return search.count(
      onFailure = {
        Timber.e(it, "Error counting register data for register id: ${registerConfiguration.id}")
      }
    )
  }

  override suspend fun loadProfileData(
    profileId: String,
    resourceId: String,
    fhirResourceConfig: FhirResourceConfig?,
    paramsList: Array<ActionParameter>?
  ): RepositoryResourceData {
    val paramsMap: Map<String, String> =
      paramsList
        ?.asSequence()
        ?.filter {
          (it.paramType == ActionParameterType.PARAMDATA ||
            it.paramType == ActionParameterType.UPDATE_DATE_ON_EDIT) && it.value.isNotEmpty()
        }
        ?.associate { it.key to it.value }
        ?: emptyMap()

    val profileConfiguration = retrieveProfileConfiguration(profileId, paramsMap)
    val resourceConfig = fhirResourceConfig ?: profileConfiguration.fhirResource
    val baseResourceConfig = resourceConfig.baseResource

    val baseResource: Resource =
      withContext(dispatcherProvider.io()) {
        fhirEngine.get(baseResourceConfig.resource, resourceId.extractLogicalIdUuid())
      }

    val configComputedRuleValues = profileConfiguration.configRules.configRulesComputedValues()

    val retrievedRelatedResources =
      withContext(dispatcherProvider.io()) {
        retrieveRelatedResources(
          resources = listOf(baseResource),
          relatedResourcesConfigs = resourceConfig.relatedResources,
          relatedResourceWrapper = RelatedResourceWrapper(),
          configComputedRuleValues = configComputedRuleValues
        )
      }
    return RepositoryResourceData(
      resourceRulesEngineFactId = baseResourceConfig.id ?: baseResourceConfig.resource.name,
      resource = baseResource,
      relatedResourcesMap = retrievedRelatedResources.relatedResourceMap,
      relatedResourcesCountMap = retrievedRelatedResources.relatedResourceCountMap,
      secondaryRepositoryResourceData =
        withContext(dispatcherProvider.io()) {
          profileConfiguration.secondaryResources.retrieveSecondaryRepositoryResourceData(
            profileConfiguration.filterActiveResources
          )
        }
    )
  }

  fun retrieveProfileConfiguration(profileId: String, paramsMap: Map<String, String>) =
    configurationRegistry.retrieveConfiguration<ProfileConfiguration>(
      configType = ConfigType.Profile,
      configId = profileId,
      paramsMap = paramsMap
    )

  fun retrieveRegisterConfiguration(
    registerId: String,
    paramsMap: Map<String, String>?
  ): RegisterConfiguration =
    configurationRegistry.retrieveConfiguration(ConfigType.Register, registerId, paramsMap)

  private fun List<RuleConfig>?.configRulesComputedValues(): Map<String, Any> {
    if (this == null) return emptyMap()
    val configRules = configRulesExecutor.generateRules(this)
    return configRulesExecutor.fireRules(configRules)
  }
  /**
   * A wrapper data class to hold search results. All related resources are flattened into one Map
   * including the nested related resources as required by the Rules Engine facts.
   */
  private data class RelatedResourceWrapper(
    val relatedResourceMap: MutableMap<String, List<Resource>> = mutableMapOf(),
    val relatedResourceCountMap: MutableMap<String, List<RelatedResourceCount>> = mutableMapOf()
  )

  companion object {
    const val ACTIVE = "active"
  }
}
