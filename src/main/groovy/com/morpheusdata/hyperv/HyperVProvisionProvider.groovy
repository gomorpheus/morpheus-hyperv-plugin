package com.morpheusdata.hyperv

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, ProvisionProvider.HypervisorProvisionFacet, HostProvisionProvider.ResizeFacet, ProvisionProvider.BlockDeviceNameFacet {
	public static final String PROVIDER_CODE = 'hyperv.provision'
	public static final String PROVISION_PROVIDER_CODE = 'hyperv'
	public static final diskNames = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg', 'sdh', 'sdi', 'sdj', 'sdk', 'sdl']

	protected MorpheusContext context
	protected Plugin plugin
	private HyperVApiService apiService

	public HyperVProvisionProvider(Plugin plugin, MorpheusContext context) {
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
		// TODO: this is where you will implement the work to create the workload in your cloud environment
		return new ServiceResponse<ProvisionResponse>(
			true,
			null, // no message
			null, // no errors
			new ProvisionResponse(success:true)
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
		log.debug("removeWorkload: opts: ${opts}")
		ServiceResponse response = ServiceResponse.prepare()
		try {
			log.debug("Removing container: ${workload?.dump()}")
			if (workload.server?.externalId) {
				def hypervOpts = HypervOptsUtility.getAllHypervWorloadOpts(context, workload)
				def stopResults = apiService.stopServer(hypervOpts + [turnOff: true], hypervOpts.name)
				if (stopResults.success == true) {
					def removeResults = apiService.removeServer(hypervOpts, hypervOpts.name)
					if (removeResults.success == true) {
						def deleteResults = apiService.deleteServer(hypervOpts)
						log.debug "deleteResults: ${deleteResults?.dump()}"
						if (deleteResults.success == true) {
							response.success = true
						} else {
							response.msg = 'Failed to remove vm'
						}
					}
				}
			} else {
				response.msg = 'vm not found'
			}
		} catch (e) {
			log.error("removeWorkload error: ${e}", e)
			response.error = e.message
		}
		return response
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
		return ServiceResponse.create(rtn)
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

	@Override
	String[] getDiskNameList() {
		return diskNames
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
		def updatedServer
		if(saveResult.success == true) {
			updatedServer = saveResult.persistedItems.find { it.id == server.id }
		} else {
			updatedServer = saveResult.failedItems.find { it.id == server.id }
			log.warn("Error saving server: ${server?.id}" )
		}
		return updatedServer ?: server
	}

	def getUniqueDataDiskName(ComputeServer server, index = 1) {
		def nameExists = true
		def volumes = server.volumes
		def diskName
		def diskIndex = index ?: server.volumes?.size()
		while(nameExists) {
			diskName = "dataDisk${diskIndex}.vhd"
			nameExists = volumes.find{ it.externalId == diskName }
			diskIndex++
		}

		return diskName
	}

	def buildStorageVolume(computeServer, volumeAdd, addDiskResults, newCounter) {
		def newVolume = new StorageVolume(
				refType			: 'ComputeZone',
				refId			: computeServer.cloud.id,
				regionCode		: computeServer.region?.regionCode,
				account			: computeServer.account,
				maxStorage		: volumeAdd.maxStorage?.toLong(),
				maxIOPS			: volumeAdd.maxIOPS?.toInteger(),
//				internalId 		: addDiskResults.volume?.uuid, // This is used in embedded
//				deviceName		: addDiskResults.volume?.deviceName,
				name			: volumeAdd.name,
				displayOrder	: newCounter,
				status			: 'provisioned',
//				unitNumber		: addDiskResults.volume?.deviceIndex?.toString(),
				deviceDisplayName : getDiskDisplayName(newCounter)
		)
		return newVolume
	}

	def getDiskConfig(ComputeServer server, StorageVolume volume) {
		def rtn = [success:true]
		def hypervOpts = HypervOptsUtility.getAllHypervServerOpts(context, server)
		def vmId = server.externalId
		def diskResults = apiService.getServerDisks(hypervOpts, vmId)
		if(diskResults?.success == true) {
			def diskName = volume.externalId
			def diskData = diskResults?.disks?.find{ it.path.contains("${diskName}") }
			if(diskData){
				rtn += diskData
			}
		}

		return rtn
	}

	@Override
	ServiceResponse resizeServer(ComputeServer computeServer, ResizeRequest resizeRequest, Map map) {
		log.info("resizeServer calling resizeWorkloadAndServer")
		return resizeWorkloadAndServer(null, null, server, resizeRequest, opts, false)
	}

	private ServiceResponse resizeWorkloadAndServer (Long instanceId, Workload workload, ComputeServer server, ResizeRequest resizeRequest, Map opts, Boolean isWorkload) {
		log.debug("resizeWorkloadAndServer ${workload ? "workload" : "server"}.id: ${workload?.id ?: server?.id} - opts: ${opts}")

		ServiceResponse rtn = ServiceResponse.success()
		ComputeServer computeServer = context.async.computeServer.get(server.id).blockingGet()
		try {
			computeServer.status = 'resizing'
			computeServer = saveAndGet(computeServer)

			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores

			def currentMemory
			def currentCores
			if (isWorkload) {
				currentMemory = workload.maxMemory ?: workload.getConfigProperty('maxMemory')?.toLong()
				currentCores = workload.maxCores ?: 1
			} else {
				currentMemory = computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong()
				currentCores = server.maxCores ?: 1
			}
			def neededMemory = requestedMemory - currentMemory
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)

			def vmId = server.externalId
			def hypervOpts = HypervOptsUtility.getAllHypervServerOpts(context, computeServer)
			def stopResults = stopServer(computeServer)
			if (stopResults.success == true) {
				if(neededMemory != 0 || neededCores != 0) {
					def resizeOpts = [:]
					if(neededMemory != 0)
						resizeOpts.maxMemory = requestedMemory
					if(neededCores != 0)
						resizeOpts.maxCores = requestedCores
					def resizeResults = apiService.updateServer(hypervOpts, vmId, resizeOpts)
					log.debug("resize results: ${resizeResults}")
					if(resizeResults.success == true) {
						computeServer.plan = plan
						computeServer.maxCores = (requestedCores ?: 1).toLong()
						computeServer.maxMemory = requestedMemory.toLong()
						computeServer = saveAndGet(computeServer)
					} else {
						rtn.error = resizeResults.error ?: 'Failed to resize server'
					}
				} else {
					log.debug "Same plan.. not updating"
				}

				if (opts.volumes && !rtn.error) {
					def newCounter = computeServer.volumes?.size()
					resizeRequest.volumesUpdate?.each { volumeUpdate ->
						StorageVolume existing = volumeUpdate.existingModel
						Map updateProps = volumeUpdate.updateProps
						if (updateProps.maxStorage > existing.maxStorage) {
							def storageVolumeId = existing.id
							def volumeId = existing.externalId
							def diskSize = ComputeUtility.parseGigabytesToBytes(updateProps.size)
							def diskPath = "${hypervOpts.diskRoot}\\${hypervOpts.serverFolder}\\${volumeId}"
							def resizeResults = apiService.resizeDisk(hypervOpts, diskPath, diskSize)
							if(resizeResults.success == true) {
								StorageVolume existingVolume = context.services.storageVolume.get(storageVolumeId)
								existingVolume.maxStorage = diskSize
								context.services.storageVolume.save(existingVolume)
							} else {
								log.error "Error in resizing volume: ${resizeResults}"
								rtn.error = resizeResults.error ?: "Error in resizing volume"
							}
						}
					}

					resizeRequest.volumesAdd.each { volumeAdd ->
						//new disk add it
						def diskSize = ComputeUtility.parseGigabytesToBytes(volumeUpdate.volume.size)
						def diskName = getUniqueDataDiskName(computeServer, newCounter++)
						def diskPath = "${hypervOpts.diskRoot}\\${hypervOpts.serverFolder}\\${diskName}"
						def diskResults = apiService.createDisk(hypervOpts, diskPath, diskSize)
						log.debug("create disk: ${diskResults.success}")
						if(diskResults.success == true && diskResults.error != true) {
							def	attachResults = apiService.attachDisk(hypervOpts, vmId, diskPath)
							log.debug("attach: ${attachResults.success}")
							if (attachResults.success == true && attachResults.error != true) {
								def newVolume = buildStorageVolume(computeServer, volumeAdd, diskResults, newCounter)
								newVolume.externalId = diskName
								context.services.storageVolume.create([newVolume], computeServer)
								computeServer = context.services.computeServer.get(computeServer.id)
								newCounter++
							} else {
								log.error "Error in attaching volume: ${attachResults}"
								rtn.error = "Error in attaching volume"
							}
						} else {
							log.error "Error in creating the volume: ${diskResults}"
							rtn.error = "Error in creating the volume"
						}
					}

					resizeRequest.volumesDelete.each { volume ->
						log.debug "Deleting volume : ${volume.externalId}"
						def diskName = volume.externalId
						def diskPath = "${hypervOpts.diskRoot}\\${hypervOpts.serverFolder}\\${diskName}"
						def diskConfig = volume.config ?: getDiskConfig(server, volume)
						def detachResults = apiService.detachDisk(hypervOpts, vmId, diskConfig.controllerType, diskConfig.controllerNumber, diskConfig.controllerLocation)
						if(detachResults.success == true) {
							apiService.deleteDisk(hypervOpts, diskName)
							context.async.storageVolume.remove([volume], computeServer, true).blockingGet()
							computeServer = context.async.computeServer.get(computeServer.id).blockingGet()
						}
					}
				}
			} else {
				rtn.error = 'Server never stopped so resize could not be performed'
				rtn.success = false
			}

			computeServer.status = 'provisioned'
			computeServer = saveAndGet(computeServer)
			rtn.success = true
		} catch (e) {
			log.error("Unable to resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			if (!isWorkload)
				computeServer.statusMessage = "Unable to resize server: ${e.message}"
			computeServer = saveAndGet(computeServer)
			rtn.success = false
			rtn.errors << "${e}"
		}
		return rtn
	}
}
