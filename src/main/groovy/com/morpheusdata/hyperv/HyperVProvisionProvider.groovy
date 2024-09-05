package com.morpheusdata.hyperv

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ImportWorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ImportWorkloadResponse
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider, ProvisionProvider.BlockDeviceNameFacet, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet, WorkloadProvisionProvider.ImportWorkloadFacet, ProvisionProvider.HypervisorProvisionFacet {

	public static final String PROVIDER_CODE = 'hyperv.provision'
	public static final String PROVISION_PROVIDER_CODE = 'hyperv'

	protected MorpheusContext context
	protected HyperVPlugin plugin
	private HyperVApiService apiService

	public HyperVProvisionProvider(HyperVPlugin plugin, MorpheusContext context) {
		super()
		this.@context = context
		this.@plugin = plugin
		this.apiService = new HyperVApiService(context)
	}

	/**
	 * Initialize a compute server as a Hypervisor. Common attributes defined in the {@link InitializeHypervisorResponse} will be used
	 * to update attributes on the hypervisor, including capacity information. Additional details can be updated by the plugin provider
	 * using the `context.services.computeServer.save(server)` API.
	 * @param cloud cloud associated to the hypervisor
	 * @param server representing the hypervisor
	 * @return a {@link ServiceResponse} containing an {@link InitializeHypervisorResponse}. The response attributes will be
	 * used to fill in necessary attributes of the server.
	 */
	@Override
	ServiceResponse<InitializeHypervisorResponse> initializeHypervisor(Cloud cloud, ComputeServer server) {
		log.debug("initializeHypervisor: cloud: {}, server: {}", cloud, server)
		ServiceResponse<InitializeHypervisorResponse> rtn = new ServiceResponse<>(new InitializeHypervisorResponse())
		try {
			def opts = HypervOptsUtility.getHypervHypervisorOpts(server)
			def serverInfo = apiService.getHypervServerInfo(opts)
			log.debug("serverInfo: ${serverInfo}")
			if (serverInfo.success == true && serverInfo.hostname) {
				server.hostname = serverInfo.hostname
			}
			def maxStorage = serverInfo?.disks ? serverInfo?.disks.toLong() : 0
			def maxMemory = serverInfo?.memory ? serverInfo?.memory.toLong() : 0
			def usedStorage = 0
			def maxCores = 1

			rtn.data.serverOs = new OsType(code: 'windows.server.2012')
			rtn.data.commType = 'winrm' //ssh, minrm
			rtn.data.maxMemory = maxMemory
			rtn.data.maxCores = maxCores
			rtn.data.maxStorage = maxStorage
			rtn.success = true
			if (server.agentInstalled != true) {
				def prepareResults = apiService.prepareNode(opts)
			}
		} catch (e) {
			log.error("initialize hypervisor error:${e}", e)
		}
		return rtn
	}


/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {@link CloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'provision-circular.svg', darkPath:'provision-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		// TODO: create some option types for provisioning and add them to collection
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []
		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		// TODO: create some storage volume types and add to collection
		return volumeTypes
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		Collection<StorageVolumeType> dataVolTypes = []
		// TODO: create some data volume types and add to collection
		return dataVolTypes
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		// TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
		return plans
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
		log.info ("Ray :: runWorkload: workload: ${workload?.id}")
		log.info ("Ray :: runWorkload: workloadRequest: ${workloadRequest}")
		log.info ("Ray :: runWorkload: opts: ${opts}")
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		def server = workload.server
		log.info ("Ray :: runWorkload: server?.id: ${server?.id}")
		log.info ("Ray :: runWorkload: server?.name: ${server?.name}")
		def cloud = server.cloud
		log.info ("Ray :: runWorkload: cloud: ${cloud}")
		def hypervOpts = [:]
		def snapshotId
		try {
			def containerConfig = workload.getConfigMap()
			log.info ("Ray :: runWorkload: containerConfig: ${containerConfig}")
			def config = server.getConfigMap()
			log.info ("Ray :: runWorkload: config: ${config}")
			//opts.server = container.server
			//opts.zone = zoneService.loadFullZone(container.server.zone)
			//opts.account = opts.server.account
			//opts.server.externalId = opts.server.name
			def zoneConfig = cloud.getConfigMap()
			log.info ("Ray :: runWorkload: zoneConfig: ${zoneConfig}")
			hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, cloud)
			hypervOpts.name = server.name
			log.info ("Ray :: runWorkload: hypervOpts: ${hypervOpts}")
			def imageId
			VirtualImage virtualImage
			//def applianceServerUrl = applianceService.getApplianceUrl(server.zone)
			log.info ("Ray :: runWorkload: containerConfig.hostId: ${containerConfig.hostId}")
			def node = context.async.computeServer.get(containerConfig.hostId?.toLong()).blockingGet()
			log.info ("Ray :: runWorkload: node: ${node}")
			log.info ("Ray :: runWorkload: node?.id: ${node?.id}")
			log.info ("Ray :: runWorkload: node?.name: ${node?.name}")
			node = containerConfig.hostId ? node : pickHypervHypervisor(opts.zone)
			log.info ("Ray :: runWorkload: node1: ${node}")
			log.info ("Ray :: runWorkload: node?.id1: ${node?.id}")
			log.info ("Ray :: runWorkload: node?.name1: ${node?.name}")
			String generation = 'generation1'
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			log.info ("Ray :: runWorkload: hypervOpts1: ${hypervOpts}")
			log.info ("Ray :: runWorkload: containerConfig.imageId: ${containerConfig.imageId}")
			log.info ("Ray :: runWorkload: containerConfig.template: ${containerConfig.template}")
			log.info ("Ray :: runWorkload: server.sourceImage?.id: ${server.sourceImage?.id}")
			if(containerConfig.imageId || containerConfig.template || server.sourceImage?.id/*container.containerType.virtualImage?.id*/) {
				//def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: container.containerType.virtualImage.id)
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
				log.info ("Ray :: runWorkload: virtualImageId: ${virtualImageId}")
				//virtualImage = VirtualImage.get(virtualImageId)
				virtualImage = context.async.virtualImage.get(virtualImageId).blockingGet()
				log.info ("Ray :: runWorkload: virtualImage: ${virtualImage}")
				log.info ("Ray :: runWorkload: virtualImage?.externalId: ${virtualImage?.externalId}")
				log.info ("Ray :: runWorkload: virtualImage?.id: ${virtualImage?.id}")
				log.info ("Ray :: runWorkload: virtualImage?.locations?.size(): ${virtualImage?.locations?.size()}")
				generation = virtualImage.getConfigProperty('generation')
				log.info ("Ray :: runWorkload: generation: ${generation}")
				//imageId = virtualImage.externalId
				imageId = virtualImage.locations.find { it.refType == "ComputeZone" && it.refId == cloud.id }?.externalId
				log.info ("Ray :: runWorkload: imageId: ${imageId}")
				if(!imageId) { //If its userUploaded and still needs uploaded
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					log.info ("Ray :: runWorkload: cloudFiles: ${cloudFiles}")
					log.info ("Ray :: runWorkload: cloudFiles?.size(): ${cloudFiles?.size()}")
					if(cloudFiles?.size() == 0) {
						server.statusMessage = 'Failed to find cloud files'
						provisionResponse.setError("Cloud files could not be found for ${virtualImage}")
						provisionResponse.success = false
					}
					def containerImage =
							[
									name			: virtualImage.name, // ?: workload.containerType.imageCode, // check:
									minDisk			: 5,
									minRam			: 512 * ComputeUtility.ONE_MEGABYTE,
									virtualImageId	: virtualImage.id,
									tags			: 'morpheus, ubuntu',
									imageType		: 'vhd',
									containerType	: 'vhd',
									cloudFiles		: cloudFiles
							]
					log.info ("Ray :: runWorkload: containerImage: ${containerImage}")
					hypervOpts.image = containerImage
					hypervOpts.userId = workload.instance.createdBy?.id
					log.info ("Ray :: runWorkload: hypervOpts.userId: ${hypervOpts.userId}")
					//hypervOpts.applianceServerUrl = applianceServerUrl
					log.debug "hypervOpts: ${hypervOpts}"
					def imageResults = apiService.insertContainerImage(hypervOpts)
					log.info ("Ray :: runWorkload: imageResults: ${imageResults}")
					log.info ("Ray :: runWorkload: imageResults.success: ${imageResults.success}")
					if(imageResults.success == true) {
						log.info ("Ray :: runWorkload: imageResults.imageId: ${imageResults.imageId}")
						imageId = imageResults.imageId
						//virtualImageService.addVirtualImageLocation(virtualImage, imageId, opts.zone.id) // check:
					}
				}
				log.info ("Ray :: runWorkload: imageId1: ${imageId}")
			}
			// part of code
			/*def cloneContainer = context.async.workload.get(opts.cloneContainerId?.toLong()).blockingGet()
			if(opts.cloneContainerId && cloneContainer) {
				//def cloneContainer = Container.get(opts.cloneContainerId)
				def vmId = cloneContainer.server.externalId
				//def snapshot = backupService.getSnapshotForBackupResult(opts.backupSetId, opts.cloneContainerId)
				def snapshots = context.services.backup.backupResult.list(
						new DataQuery().withFilter("backupSetId", opts.backupSetId)
								.withFilter("containerId", opts.cloneContainerId))
				def snapshot = snapshots.find { it.backupSetId == opts.backupSetId }
				hypervOpts.snapshotId = snapshot.snapshotId
				def exportSnapshotResults = apiService.exportSnapshot(hypervOpts, vmId, snapshot.snapshotId)
				if(exportSnapshotResults.success){
					snapshotId = snapshot.snapshotId
					imageId = exportSnapshotResults.diskPath
				}
				def cloneContainerConfig = cloneContainer.getConfigMap()
				def networkId = cloneContainerConfig.networkId
				if(networkId){
					containerConfig.networkId = networkId
					containerConfig.each {
						it -> workload.setConfigProperty(it.key, it.value)
					}
					workload = context.async.workload.save(workload).blockingGet()
				}
			}*/
			// part of code
			/*if(imageId) {
				opts.installAgent = virtualImage ? virtualImage.installAgent : true
				//user config
				//def createdBy = getInstanceCreateUser(container.instance)
				def userGroups = workload.instance.userGroups?.toList() ?: []
				if (workload.instance.userGroup && userGroups.contains(workload.instance.userGroup) == false) {
					userGroups << workload.instance.userGroup
				}
				//opts.userConfig = userGroupService.buildContainerUserGroups(opts.account, virtualImage, userGroups, createdBy, opts)
				//opts.server.sshUsername = opts.userConfig.sshUsername
				//opts.server.sshPassword = opts.userConfig.sshPassword
				server.sourceImage = virtualImage
				//opts.server.externalId = hypervOpts.name
				server.parentServer = node
				server.serverOs = server.serverOs ?: virtualImage.osType
				String platform = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				server.osType = platform
				//def newType = findVmNodeZoneType(opts.server.zone.zoneType, opts.server.osType) // check: provisionTypeCode
				def newType = this.findVmNodeServerTypeForCloud(cloud.id, server.osType, 'hyperv-provision-provider')
				if(newType && server.computeServerType != newType){
					server.computeServerType = newType
				}
				server = saveAndGetMorpheusServer(server, true)
				opts.hostname = server.getExternalHostname()
				opts.domainName = server.getExternalDomain()
				opts.fqdn = opts.hostname
				if(opts.domainName) {
					opts.fqdn += '.' + opts.domainName
				}
				hypervOpts.secureBoot = virtualImage?.uefi ?: false
				hypervOpts.imageId = imageId
				// hypervOpts.diskMap = virtualImageService.getImageDiskMap(virtualImage) // check: how to get disk map
				hypervOpts += HypervOptsUtility.getHypervWorkloadOpts(context, workload)
				hypervOpts.networkConfig = opts.networkConfig
				*//*def cloudConfigOpts = buildCloudConfigOpts(opts.zone, opts.server, opts.installAgent, [doPing:true, sendIp:true, apiKey:opts.server.apiKey,
																									   applianceIp:MorpheusUtils.getUrlHost(applianceServerUrl), hostname:opts.server.getExternalHostname(), applianceUrl:applianceServerUrl,
																									   hostname:container.server.getExternalHostname(), hosts:container.server.getExternalHostname(), disableCloudInit:true, timezone: containerConfig.timezone])*//*
				// check: cloudConfigOpts
				if(virtualImage?.isCloudInit) {
					//opts.installAgent = opts.installAgent && (cloudConfigOpts.installAgent != true) // check:
					//morpheusComputeService.buildCloudNetworkConfig(hypervOpts.platform, virtualImage, cloudConfigOpts, hypervOpts.networkConfig)
					hypervOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null //morpheusComputeService.buildCloudUserData(hypervOpts.platform, opts.userConfig, cloudConfigOpts)
					hypervOpts.cloudConfigMeta = workloadRequest?.cloudConfigMeta ?: null //morpheusComputeService.buildCloudMetaData(hypervOpts.platform, "morpheus-container-${container.id}", cloudConfigOpts.hostname, cloudConfigOpts)
					hypervOpts.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
					//def isoBuffer = IsoUtility.buildCloudIso(hypervOpts.platform, hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser)
					def isoBuffer = context.services.provision.buildIsoOutputStream(
							opts.isSysprep, PlatformType.valueOf(hypervOpts.platform), hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser, hypervOpts.cloudConfigNetwork)
					hypervOpts.cloudConfigBytes = isoBuffer //isoBuffer.toByteArray()
					server.cloudConfigUser = hypervOpts.cloudConfigUser
					server.cloudConfigMeta = hypervOpts.cloudConfigMeta
				} else if (platform == 'windows') {
					//morpheusComputeService.buildCloudNetworkConfig(platform, virtualImage, cloudConfigOpts, hypervOpts.networkConfig)
					hypervOpts.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
					if(virtualImage.isSysprep) {
						hypervOpts.cloudConfigUnattend = morpheusComputeService.buildCloudUserData(platform, opts.userConfig, cloudConfigOpts) // check:
						def isoBuffer = IsoUtility.buildAutoUnattendIso(hypervOpts.cloudConfigUnattend) // check:
						hypervOpts.cloudConfigBytes = isoBuffer.toByteArray()
					}
					opts.unattendCustomized = cloudConfigOpts.unattendCustomized // check:
					opts.createUserList = opts.userConfig.createUsers // check:
				} else {
					opts.createUserList = opts.userConfig.createUsers // check:
				}
				//save it
				opts.server.save(flush:true) // check:
				//create it
				log.debug("create server: ${hypervOpts}")
				def createResults = apiService.cloneServer(hypervOpts)
				log.info("create server results: ${createResults}")
				if(createResults.success == true && createResults.server) {
					server.externalId = createResults.server.externalId
					provisionResponse.externalId = server.externalId // check: added
					server.parentServer = node
					def serverDisks = createResults.server.disks
					if(serverDisks) {
						def storageVolumes = server.volumes
						def rootVolume = storageVolumes.find{ it.rootVolume == true }
						rootVolume.externalId = serverDisks.osDisk?.externalId
						storageVolumes.each { storageVolume ->
							def dataDisk = serverDisks.dataDisks.find{ it.id == storageVolume.id }
							if(dataDisk) {
								storageVolume.externalId = dataDisk.externalId
							}
						}
					}
					//opts.server.save(flush:true)
					server = saveAndGetMorpheusServer(server, true)
					def serverDetails = apiService.getServerDetails(hypervOpts, server.externalId)
					if(serverDetails.success == true) {
						log.info("serverDetail: ${serverDetails}")
						def newIpAddress = serverDetails.server?.ipAddress ?: createResults.server?.ipAddress
						opts.network = applyComputeServerNetworkIp(opts.server, newIpAddress, newIpAddress, null, null, 0, [:]) // check:
						server.osDevice = '/dev/sda'
						server.dataDevice = '/dev/sda'
						server.lvmEnabled = false
						server.sshHost = opts.server.internalIp
						server.managed = true
						//opts.server.save()
						server.capacityInfo = new ComputeCapacityInfo(
								server		: opts.server,
								maxCores	: (hypervOpts.maxCores ?: 1),
								maxMemory	: hypervOpts.maxMemory,
								maxStorage	: hypervOpts.maxTotalStorage
						)
						server.status = 'provisioned'
						server.uniqueId = serverDetails.server?.vmId
						server.powerState = ComputeServer.PowerState.on
						context.async.computeServer.save(server).blockingGet()
						provisionResponse.success = true
						//instanceService.updateInstance(container.instance) // check:
					} else {
						server.statusMessage = 'Failed to run server'
						//context.async.computeServer.save(server).blockingGet()
					}
				} else {
					if(createResults.server?.externalId) {
						// we did create a vm though so we need to bind it to the server
						server.externalId = createResults.server.externalId
					}
					server.statusMessage = 'Failed to create server'
					//context.async.computeServer.save(server).blockingGet()
				}
			} else {
				server.statusMessage = 'Failed to upload image'
				//context.async.computeServer.save(server).blockingGet()
			}*/
		} catch(e) {
			log.error("initializeServer error:${e}", e)
			//server.statusMessage = getStatusMessage("Failed to create server: ${e.message}")
			//server.statusMessage = "Failed to create server: ${e.message}"
			//context.async.computeServer.save(server).blockingGet()
		} finally {
			//if we exported a snapshot for clone/restore, clean it up
			/*if(snapshotId) {
				apiService.deleteExport(hypervOpts, snapshotId)
			}*/
		}
		// part of code
		/*if(provisionResponse.success == false) {
			return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
		} else {
			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
		}*/
		//return rtn




		// TODO: this is where you will implement the work to create the workload in your cloud environment
		return new ServiceResponse<ProvisionResponse>(
			true,
			null, // no message
			null, // no errors
			new ProvisionResponse(success:true)
		)
	}

	def pickHypervHypervisor(Cloud cloud) {
		def hypervisorList = context.services.computeServer.list(new DataQuery()
				.withFilter('zone.id', cloud.id).withFilter('computeServerType.code', 'hypervHypervisor'))
				/*ComputeServer.withCriteria {
			eq('zone', zone)
			computeServerType {
				eq('code', 'hypervHypervisor')
			}
			maxResults(1)
		}*/
		log.info ("Ray :: pickHypervHypervisor: hypervisorList?.size(): ${hypervisorList?.size()}")
		return hypervisorList?.size() > 0 ? hypervisorList.first() : null
	}

	protected ComputeServer saveAndGetMorpheusServer(ComputeServer server, Boolean fullReload=false) {
		def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
		def updatedServer
		if(saveResult.success == true) {
			if(fullReload) {
				updatedServer = getMorpheusServer(server.id)
			} else {
				updatedServer = saveResult.persistedItems.find { it.id == server.id }
			}
		} else {
			updatedServer = saveResult.failedItems.find { it.id == server.id }
			log.warn("Error saving server: ${server?.id}" )
		}
		return updatedServer ?: server
	}

	protected  ComputeServer getMorpheusServer(Long id) {
		return context.services.computeServer.find(
				new DataQuery().withFilter("id", id).withJoin("interfaces.network")
		)
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		def rtn = ServiceResponse.prepare()
		try {
			if (workload.server?.externalId) {
				def hypervOpts = HypervOptsUtility.getAllHypervWorloadOpts(context, workload)
				def results = apiService.stopServer(hypervOpts, hypervOpts.name)
				if (results.success == true) {
					rtn.success = true
				}
			} else {
				rtn.success = false
				rtn.msg = 'externalId not found'
			}
		} catch (e) {
			log.error("stopWorkload error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.debug ("startWorkload: ${workload?.id}")
		def rtn = ServiceResponse.prepare()
		try {
			if (workload.server?.externalId) {
				def hypervOpts = HypervOptsUtility.getAllHypervWorloadOpts(context, workload)
				def results = apiService.startServer(hypervOpts, hypervOpts.name)
				if (results.success == true) {
					rtn.success = true
				}
			} else {
				rtn.success = false
				rtn.msg = 'externalId not found'
			}
		} catch (e) {
			log.error("startWorkload error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		def rtn = [success: false, msg: null]
		try {
			if (computeServer?.externalId){
				def hypervOpts = HypervOptsUtility.getAllHypervServerOpts(context, computeServer)
				def stopResults = apiService.stopServer(hypervOpts, hypervOpts.name)
				if(stopResults.success == true){
					rtn.success = true
				}
			} else {
				rtn.msg = 'vm not found'
			}
		} catch(e) {
			log.error("stopServer error: ${e}", e)
			rtn.msg = e.message
		}
		return new ServiceResponse(rtn)
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: computeServer.id: ${computeServer?.id}")
		def rtn = ServiceResponse.prepare()
		try {
			if (computeServer?.externalId) {
				def hypervOpts = HypervOptsUtility.getAllHypervServerOpts(context, computeServer)
				def results = apiService.startServer(hypervOpts, hypervOpts.name)
				if (results.success == true) {
					rtn.success = true
				}
			} else {
				rtn.msg = 'externalId not found'
			}
		} catch (e) {
			log.error("startServer error:${e}", e)
		}
		return rtn
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'Hyper-V Provisioning'
	}
/**
 * Validate the provided provisioning options for a Docker host server.  A return of success = false will halt the
 * creation and display errors
 * @param server the ComputeServer to validate
 * @param opts options
 * @return Response from API
 */

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		return null
	}

	/**
	 * This method is called before runHost and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runHost. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param server the ComputeServer object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the server
	 * @param hostRequest the HostRequest object containing the various configurations that may be needed
	 *                        in running the server. This will be passed along into runHost
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		return null
	}

	/**
	 * This method is called to provision a Host (i.e. Docker host).
	 * Information associated with the passed ComputeServer object is used to kick off the provision request. Implementations
	 * of this method should populate ProvisionResponse as complete as possible and as quickly as possible. Implementations
	 * may choose to save the externalId on the ComputeServer or pass it back in ProvisionResponse.
	 * @param server the ComputeServer object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the server
	 * @param hostRequest the HostRequest object containing the various configurations that may be needed
	 *                         in running the server.
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		return null
	}

	/**
	 * This method is called after successful completion of runHost and successful completion of waitForHost and provides
	 * an opportunity to perform some final actions during the provisioning process.
	 * For example, ejected CDs, cleanup actions, etc
	 * @param server the ComputeServer object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		return null
	}

	/**
	 * Request to scale the size of the ComputeServer. It is up to implementations to create the volumes, set the memory, etc
	 * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
	 * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment with the requested attributes
	 * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
	 * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket, maxStorage values
	 * defined on ResizeRequest will be set on the ComputeServer upon return of a successful ServiceResponse
	 *
	 * @param server to resize
	 * @param resizeRequest the resize requested parameters
	 * @param opts additional options
	 * @return Response from the API
	 */
	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return null
	}

	/**
	 * Returns a String array of block device names i.e. (['vda','vdb','vdc']) in the order
	 * of the disk index.
	 * @return the String array
	 */
	@Override
	String[] getDiskNameList() {
		return new String[0]
	}

	/**
	 * Import a workload to an image
	 * @param importWorkloadRequest The {@link ImportWorkloadRequest} containing the workload, source image, target image, image base path, and storage bucket
	 * @return A ServiceResponse indicating success or failure
	 */
	@Override
	ServiceResponse<ImportWorkloadResponse> importWorkload(ImportWorkloadRequest importWorkloadRequest) {
		return null
	}

	/**
	 * Request to scale the size of the Workload. Most likely, the implementation will follow that of resizeServer
	 * as the Workload usually references a ComputeServer. It is up to implementations to create the volumes, set the memory, etc
	 * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
	 * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment with the requested attributes
	 * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
	 * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket, maxStorage values
	 * defined on ResizeRequest will be set on the Workload and ComputeServer upon return of a successful ServiceResponse
	 * @param instance to resize
	 * @param workload to resize
	 * @param resizeRequest the resize requested parameters
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		return null
	}
}
