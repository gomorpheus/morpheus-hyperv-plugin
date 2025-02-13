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
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider, ProvisionProvider.HypervisorProvisionFacet, HostProvisionProvider.ResizeFacet, WorkloadProvisionProvider.ResizeFacet, ProvisionProvider.BlockDeviceNameFacet {
	public static final String PROVIDER_CODE = 'hyperv.provision'
	public static final String PROVISION_TYPE_CODE = 'hyperv'
	public static final diskNames = ['sda', 'sdb', 'sdc', 'sdd', 'sde', 'sdf', 'sdg', 'sdh', 'sdi', 'sdj', 'sdk', 'sdl']

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
		return PROVISION_TYPE_CODE
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
		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.hyperv.noAgent',
				category: 'provisionType.hyperv',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'noAgent',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldGroup:'Advanced Options',
				displayOrder: 4,
				required: false,
				enabled: true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
				defaultValue:null,
				custom:false,
				fieldClass:null
		)
		options << new OptionType(
				name: 'host',
				code: 'provisionType.hyperv.host',
				category: 'provisionType.hyperv',
				inputType: OptionType.InputType.SELECT,
				fieldName: 'hypervHostId',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.Host',
				fieldLabel: 'Host',
				fieldGroup:'Options',
				displayOrder: 10,
				required: true,
				enabled: true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				fieldClass:null
		)

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
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.template',
				inputType: OptionType.InputType.SELECT,
				name:'template',
				category:'provisionType.hyperv',
				fieldName:'template',
				fieldCode: 'gomorpheus.optiontype.Template',
				fieldLabel:'Template',
				fieldContext:'config',
				fieldGroup:'Options',
				required:false,
				enabled:true,
				optionSource:'hypervImage',
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				displayOrder:8,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.port',
				inputType: OptionType.InputType.TEXT,
				name:'port',
				category:'provisionType.hyperv',
				fieldName:'port',
				fieldCode: 'gomorpheus.optiontype.Ports',
				fieldLabel:'Ports',
				fieldContext:'config',
				fieldGroup:'Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				displayOrder:9,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.host',
				inputType: OptionType.InputType.SELECT,
				name:'host',
				category:'provisionType.hyperv',
				fieldName:'hypervHostId',
				fieldCode: 'gomorpheus.optiontype.Host',
				fieldLabel:'Host',
				fieldContext:'config',
				fieldGroup:'Options',
				required:true,
				enabled:true,
				optionSource:'hypervHost',
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				displayOrder:10,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.containerType.virtualImageId',
				inputType: OptionType.InputType.SELECT,
				name:'virtual image',
				category:'provisionType.hyperv.custom',
				optionSource:'hypervVirtualImages',
				fieldName:'virtualImageId',
				fieldCode: 'gomorpheus.optiontype.VirtualImage',
				fieldLabel:'Virtual Image',
				fieldContext:'containerType',
				fieldGroup:'Hyper-V VM Options',
				required:true,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				displayOrder:1,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.containerType.config.logVolume',
				inputType: OptionType.InputType.TEXT,
				name:'log volume',
				category:'provisionType.hyperv.custom',
				fieldName:'logVolume',
				fieldCode: 'gomorpheus.optiontype.LogVolume',
				fieldLabel:'Log Volume',
				fieldContext:'containerType.config',
				fieldGroup:'Hyper-V VM Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:null,
				custom:false,
				displayOrder:2,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.instanceType.backupType',
				inputType: OptionType.InputType.HIDDEN,
				name:'backup type',
				category:'provisionType.hyperv.custom',
				fieldName:'backupType',
				fieldCode: 'gomorpheus.optiontype.BackupType',
				fieldLabel:'Backup Type',
				fieldContext:'instanceType',
				fieldGroup:'Hyper-V VM Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'hypervSnapshot',
				custom:false,
				displayOrder:4,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.containerType.statTypeCode',
				inputType: OptionType.InputType.HIDDEN,
				name:'stat type code',
				category:'provisionType.hyperv.custom',
				fieldName:'statTypeCode',
				fieldCode: 'gomorpheus.optiontype.StatTypeCode',
				fieldLabel:'Stat Type Code',
				fieldContext:'containerType',
				fieldGroup:'Hyper-V VM Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'hyperv',
				custom:false,
				displayOrder:6,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.containerType.logTypeCode',
				inputType: OptionType.InputType.HIDDEN,
				name:'log type code',
				category:'provisionType.hyperv.custom',
				fieldName:'logTypeCode',
				fieldCode: 'gomorpheus.optiontype.LogTypeCode',
				fieldLabel:'Log Type Code',
				fieldContext:'containerType',
				fieldGroup:'Hyper-V VM Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'hyperv',
				custom:false,
				displayOrder:7,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.hyperv.custom.instanceTypeLayout.description',
				inputType: OptionType.InputType.HIDDEN,
				name:'layout description',
				category:'provisionType.hyperv.custom',
				fieldName:'description',
				fieldCode: 'gomorpheus.optiontype.LayoutDescription',
				fieldLabel:'Layout Description',
				fieldContext:'instanceTypeLayout',
				fieldGroup:'Hyper-V VM Options',
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'This will provision a single vm container',
				custom:false,
				displayOrder:9,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				name: 'osType',
				category:'provisionType.hyperv.custom',
				code: 'provisionType.hyperv.custom.containerType.osTypeId',
				fieldContext: 'domain',
				fieldName: 'osType.id',
				fieldCode: 'gomorpheus.label.osType',
				fieldLabel: 'OsType',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder:15,
				fieldClass:null,
				required: false,
				editable: true,
				noSelection: 'Select',
				optionSource: 'osTypes'
		)

		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []

		servicePlans << new ServicePlan([code:'hyperv-512', editable:true, name:'Hyper-V Nano (1 vCPU, 512MB Memory)', description:'Hyper-V Nano (1 vCPU, 512MB Memory)', sortOrder:0,
										 maxCores:1, maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-1024', editable:true, name:'1 Core, 1GB Memory', description:'1 Core, 1GB Memory', sortOrder:1,
										 maxCores:1, maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-2048', editable:true, name:'1 Core, 2GB Memory', description:'1 Core, 2GB Memory', sortOrder:2,
										 maxCores:1, maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-4096', editable:true, name:'1 Core, 4GB Memory', description:'1 Core, 4GB Memory', sortOrder:3,
										 maxCores:1, maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-8192', editable:true, name:'2 Core, 8GB Memory', description:'2 Core, 8GB Memory', sortOrder:4,
										 maxCores:2, maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-16384', editable:true, name:'2 Core, 16GB Memory', description:'2 Core, 16GB Memory', sortOrder:5,
										 maxCores:2, maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-24576', editable:true, name:'4 Core, 24GB Memory', description:'4 Core, 24GB Memory', sortOrder:6,
										 maxCores:4, maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-32768', editable:true, name:'4 Core, 32GB Memory', description:'4 Core, 32GB Memory', sortOrder:7,
										 maxCores:4, maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'hyperv-hypervisor', editable:false, name:'Hyperv hypervisor', description:'custom hypervisor plan', sortOrder:100, hidden:true,
										 maxCores:1, maxCpu:1, maxStorage:20l * 1024l * 1024l * 1024l, maxMemory:(long)(1l * 1024l * 1024l * 1024l), active:true,
										 customCores:true, customMaxStorage:true, customMaxDataStorage:true, customMaxMemory:true])

		servicePlans
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
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		def server = workload.server
		def cloud = server.cloud
		def hypervOpts = [:]
		def snapshotId
		try {
			def imageId
			def containerConfig = workload.getConfigMap()
			hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, cloud)
			hypervOpts.name = server.name
			VirtualImage virtualImage
			def node = context.async.computeServer.get(containerConfig.hostId?.toLong()).blockingGet()
			node = containerConfig.hostId ? node : pickHypervHypervisor(cloud)
			String generation = 'generation1'
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			hypervOpts.hypervisor = node
			if (containerConfig.imageId || containerConfig.template || workload.workloadType.virtualImage?.id) {
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
				virtualImage = context.async.virtualImage.get(virtualImageId).blockingGet()
				generation = virtualImage.getConfigProperty('generation')
				imageId = virtualImage.locations.find { it.refType == "ComputeZone" && it.refId == cloud.id }?.externalId
				if (!imageId) {
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					if (cloudFiles?.size() == 0) {
						server.statusMessage = 'Failed to find cloud files'
						provisionResponse.setError("Cloud files could not be found for ${virtualImage}")
						provisionResponse.success = false
					}
					def containerImage =
							[
									name          : virtualImage.name ?: workload.workloadType.imageCode,
									minDisk       : 5,
									minRam        : 512 * ComputeUtility.ONE_MEGABYTE,
									virtualImageId: virtualImage.id,
									tags          : 'morpheus, ubuntu',
									imageType     : 'vhd',
									containerType : 'vhd',
									cloudFiles    : cloudFiles
							]
					hypervOpts.image = containerImage
					hypervOpts.userId = workload.instance.createdBy?.id
					hypervOpts.user = workload.instance.createdBy
					hypervOpts.virtualImage = virtualImage
					hypervOpts.zone = cloud
					log.debug "hypervOpts: ${hypervOpts}"
					def imageResults = apiService.insertContainerImage(hypervOpts)
					log.debug("imageResults: ${imageResults}")
					if (imageResults.success == true) {
						imageId = imageResults.imageId
						def locationConfig = [
								virtualImage: virtualImage,
								code        : "hyperv.image.${cloud.id}.${virtualImage.externalId}",
								internalId  : virtualImage.externalId,
								externalId  : virtualImage.externalId,
								imageName   : virtualImage.name
						]
						VirtualImageLocation location = new VirtualImageLocation(locationConfig)
						context.services.virtualImage.location.create(location)
					} else {
						provisionResponse.success = false
					}
				}
			}
			def cloneContainer = context.async.workload.get(opts.cloneContainerId?.toLong()).blockingGet()
			if (opts.cloneContainerId && cloneContainer) {
				def vmId = cloneContainer.server.externalId
				def snapshots = context.services.backup.backupResult.list(new DataQuery()
						.withFilter("backupSetId", opts.backupSetId)
						.withFilter("containerId", opts.cloneContainerId))
				def snapshot = snapshots.find { it.backupSetId == opts.backupSetId }
				hypervOpts.snapshotId = snapshot.snapshotId
				def exportSnapshotResults = apiService.exportSnapshot(hypervOpts, vmId, snapshot.snapshotId)
				log.debug("exportSnapshotResults: ${exportSnapshotResults}")
				if (exportSnapshotResults.success) {
					snapshotId = snapshot.snapshotId
					imageId = exportSnapshotResults.diskPath
				}
				def cloneContainerConfig = cloneContainer.getConfigMap()
				def networkId = cloneContainerConfig.networkId
				if (networkId) {
					containerConfig.networkId = networkId
					containerConfig.each {
						it -> workload.setConfigProperty(it.key, it.value)
					}
					workload = context.async.workload.save(workload).blockingGet()
				}
			}

			log.debug("imageid: ${imageId}")
			if (imageId) {
				opts.installAgent = virtualImage ? virtualImage.installAgent : true
				def userGroups = workload.instance.userGroups?.toList() ?: []
				if (workload.instance.userGroup && userGroups.contains(workload.instance.userGroup) == false) {
					userGroups << workload.instance.userGroup
				}
				server.sourceImage = virtualImage
				server.externalId = hypervOpts.name
				server.parentServer = node
				server.serverOs = server.serverOs ?: virtualImage.osType
				String platform = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				server.osType = platform
				def newType = this.findVmNodeServerTypeForCloud(cloud.id, server.osType, 'hyperv')
				if (newType && server.computeServerType != newType) {
					server.computeServerType = newType
				}
				//opts.server.save(flush:true)
				server = saveAndGetMorpheusServer(server, true)
				opts.hostname = server.getExternalHostname()
				opts.domainName = server.getExternalDomain()
				opts.fqdn = opts.hostname
				if (opts.domainName) {
					opts.fqdn += '.' + opts.domainName
				}
				hypervOpts.secureBoot = virtualImage?.uefi ?: false
				hypervOpts.imageId = imageId
				hypervOpts.diskMap = context.services.virtualImage.getImageDiskMap(virtualImage)
				hypervOpts += HypervOptsUtility.getHypervWorkloadOpts(context, workload)
				hypervOpts.networkConfig = opts.networkConfig
				def cloudConfigOpts = context.services.provision.buildCloudConfigOptions(cloud, server, opts.installAgent, opts)
				log.debug("virtualImage.isSysprep: ${virtualImage.isSysprep}")
				if (virtualImage?.isCloudInit) {
					opts.installAgent = opts.installAgent && (cloudConfigOpts.installAgent != true)
					hypervOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
					hypervOpts.cloudConfigMeta = workloadRequest?.cloudConfigMeta ?: null
					hypervOpts.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
					def isoBuffer = context.services.provision.buildIsoOutputStream(virtualImage.isSysprep, PlatformType.valueOf(hypervOpts.platform), hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser, hypervOpts.cloudConfigNetwork)
					hypervOpts.cloudConfigBytes = isoBuffer
					server.cloudConfigUser = hypervOpts.cloudConfigUser
					server.cloudConfigMeta = hypervOpts.cloudConfigMeta
				} else if (platform == 'windows') {
					if (virtualImage.isSysprep) {
						hypervOpts.cloudConfigUnattend = context.services.provision.buildCloudUserData(PlatformType.valueOf(platform), workloadRequest.usersConfiguration, cloudConfigOpts)
						def isoBuffer = context.services.provision.buildIsoOutputStream(virtualImage.isSysprep, PlatformType.valueOf(platform), hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUnattend, hypervOpts.cloudConfigNetwork)
						hypervOpts.cloudConfigBytes = isoBuffer
					}
					opts.unattendCustomized = cloudConfigOpts.unattendCustomized
				}
				//save it
				server = saveAndGetMorpheusServer(server, true)
				//create it
				hypervOpts.newServer = server
				def createResults = apiService.cloneServer(hypervOpts)
				log.debug("createResults: ${createResults}")
				if (createResults.success == true && createResults.server) {
					server.externalId = createResults.server.externalId
					provisionResponse.externalId = server.externalId
					server.parentServer = node
					def serverDisks = createResults.server.disks
					if (serverDisks) {
						def storageVolumes = server.volumes
						def rootVolume = storageVolumes.find { it.rootVolume == true }
						rootVolume.externalId = serverDisks.osDisk?.externalId
						storageVolumes.each { storageVolume ->
							def dataDisk = serverDisks.dataDisks.find { it.id == storageVolume.id }
							if (dataDisk) {
								storageVolume.externalId = dataDisk.externalId
							}
						}
					}
					server = saveAndGetMorpheusServer(server, true)
					def serverDetails = apiService.getServerDetails(hypervOpts, server.externalId)
					log.debug("runWorkload: serverDetails: ${serverDetails}")
					if (serverDetails.success == true) {
						def newIpAddress = serverDetails.server?.ipAddress ?: createResults.server?.ipAddress
						def macAddress = serverDetails.server?.macAddress
						opts.network = applyComputeServerNetworkIp(server, newIpAddress, newIpAddress, 0, macAddress)
						server = getMorpheusServer(server.id)
						server.osDevice = '/dev/sda'
						server.dataDevice = '/dev/sda'
						server.lvmEnabled = false
						//server.sshHost = opts.server.internalIp
						server.sshHost = server.internalIp
						server.managed = true
						server.capacityInfo = new ComputeCapacityInfo(
								maxCores: hypervOpts.maxCores ?: 1,
								maxMemory: hypervOpts.maxMemory,
								maxStorage: hypervOpts.maxTotalStorage)
						server.status = 'provisioned'
						server.uniqueId = serverDetails.server?.vmId
						server.powerState = ComputeServer.PowerState.on
						context.async.computeServer.save(server).blockingGet()
						provisionResponse.success = true
						log.debug("provisionResponse.success: ${provisionResponse.success}")
					} else {
						server.statusMessage = 'Failed to run server'
						context.async.computeServer.save(server).blockingGet()
						provisionResponse.success = false
					}
				} else {
					if (createResults.server?.externalId) {
						// we did create a vm though so we need to bind it to the server
						server.externalId = createResults.server.externalId
						//opts.server.save(flush:true)
					}
					server.statusMessage = 'Failed to create server'
					context.async.computeServer.save(server).blockingGet()
					provisionResponse.success = false
				}
			} else {
				server.statusMessage = 'Failed to upload image'
				context.async.computeServer.save(server).blockingGet()
			}
			provisionResponse.noAgent = opts.noAgent ?: false
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error("initializeServer error:${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		} finally {
			//if we exported a snapshot for clone/restore, clean it up
			if (snapshotId) {
				apiService.deleteExport(hypervOpts, snapshotId)
			}
		}
	}

	private applyComputeServerNetworkIp(ComputeServer server, privateIp, publicIp, index, macAddress) {
		ComputeServerInterface netInterface
		if (privateIp) {
			privateIp = privateIp?.toString().contains("\n") ? privateIp.toString().replace("\n", "") : privateIp.toString()
			def newInterface = false
			server.internalIp = privateIp
			server.sshHost = privateIp
			server.macAddress = macAddress
			log.debug("Setting private ip on server:${server.sshHost}")
			netInterface = server.interfaces?.find { it.ipAddress == privateIp }

			if (netInterface == null) {
				if (index == 0)
					netInterface = server.interfaces?.find { it.primaryInterface == true }
				if (netInterface == null)
					netInterface = server.interfaces?.find { it.displayOrder == index }
				if (netInterface == null)
					netInterface = server.interfaces?.size() > index ? server.interfaces[index] : null
			}
			if (netInterface == null) {
				def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
				netInterface = new ComputeServerInterface(
						name: interfaceName,
						ipAddress: privateIp,
						primaryInterface: true,
						displayOrder: (server.interfaces?.size() ?: 0) + 1
						//externalId		: networkOpts.externalId
				)
				netInterface.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: privateIp)
				newInterface = true
			} else {
				netInterface.ipAddress = privateIp
			}
			if (publicIp) {
				publicIp = publicIp?.toString().contains("\n") ? publicIp.toString().replace("\n", "") : publicIp.toString()
				netInterface.publicIpAddress = publicIp
				server.externalIp = publicIp
			}
			netInterface.macAddress = macAddress
			if (newInterface == true)
				context.async.computeServer.computeServerInterface.create([netInterface], server).blockingGet()
			else
				context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
		}
		saveAndGetMorpheusServer(server, true)
		return netInterface
	}

	def pickHypervHypervisor(Cloud cloud) {
		def hypervisorList = context.services.computeServer.list(new DataQuery()
				.withFilter('zone.id', cloud.id).withFilter('computeServerType.code', 'hypervHypervisor'))
		return hypervisorList?.size() > 0 ? hypervisorList.first() : null
	}

	protected ComputeServer saveAndGetMorpheusServer(ComputeServer server, Boolean fullReload = false) {
		def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
		def updatedServer
		if (saveResult.success == true) {
			if (fullReload) {
				updatedServer = getMorpheusServer(server.id)
			} else {
				updatedServer = saveResult.persistedItems.find { it.id == server.id }
			}
		} else {
			updatedServer = saveResult.failedItems.find { it.id == server.id }
			log.warn("Error saving server: ${server?.id}")
		}
		return updatedServer ?: server
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
		return 'Hyper-V'
	}

	@Override
	String getDefaultInstanceTypeDescription() {
		return 'Spin up any VM Image on your hyper-v based infrastructure.'
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean canAddVolumes() {
		return true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}
	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	@Override
	HostType getHostType() {
		return HostType.vm
	}

	@Override
	String serverType() {
		return HostType.vm
	}

	@Override
	Boolean supportsCustomServicePlans() {
		return true;
	}

	@Override
	Boolean multiTenant() {
		return false
	}

	@Override
	Boolean aclEnabled() {
		return false
	}

	@Override
	Boolean customSupported() {
		return true;
	}

	@Override
	Boolean lvmSupported() {
		return true
	}

	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	@Override
	String getNodeFormat() {
		return HostType.vm
	}

	@Override
	Boolean hasSecurityGroups() {
		return false
	}

	@Override
	Boolean hasNodeTypes() {
		return true;
	}

	@Override
	String getHostDiskMode() {
		return 'lvm'
	}

	@Override
	String[] getDiskNameList() {
		return diskNames
	}

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts=[:]) {
		log.debug("validateServiceConfiguration:$opts")
		def rtn =  ServiceResponse.success()
		try {
			def cloudId = server?.cloud?.id ?: opts.siteZoneId
			def cloud = cloudId ? context.services.cloud.get(cloudId?.toLong()) : null
			if(server.computeServerType?.vmHypervisor == true) {
				rtn =  ServiceResponse.success()
			} else {
				def validationOpts = [
						networkInterfaces: opts?.networkInterfaces
				]
				if(opts?.config?.containsKey('hypervHostId')){
					validationOpts.hostId = opts.config.hypervHostId
				}
				if(opts?.config?.containsKey('nodeCount')){
					validationOpts.nodeCount = opts.config.nodeCount
				}
				def validationResults = apiService.validateServerConfig(validationOpts)
				if(!validationResults.success) {
					rtn.success = false
					rtn.errors += validationResults.errors
				}
			}
		} catch(e) {
			log.error("error in validateServerConfig:${e.message}", e)
		}
		return rtn
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
		log.info("resizeWorkload calling resizeWorkloadAndServer")
		return resizeWorkloadAndServer(workload, null, resizeRequest, opts, true)
	}

	@Override
	ServiceResponse resizeServer(ComputeServer computeServer, ResizeRequest resizeRequest, Map opts) {
		log.info("resizeServer calling resizeWorkloadAndServer")
		return resizeWorkloadAndServer(null, computeServer, resizeRequest, opts, false)
	}

	private ServiceResponse resizeWorkloadAndServer(Workload workload, ComputeServer server, ResizeRequest resizeRequest, Map opts, Boolean isWorkload) {
		log.debug("resizeWorkloadAndServer ${workload ? "workload" : "server"}.id: ${workload?.id ?: server?.id} - opts: ${opts}")

		ServiceResponse rtn = ServiceResponse.success()
		ComputeServer computeServer = isWorkload ? getMorpheusServer(workload.server?.id) : getMorpheusServer(server.id)

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

			def vmId = computeServer.externalId
			def hypervOpts = isWorkload ? HypervOptsUtility.getAllHypervWorloadOpts(context, workload) : HypervOptsUtility.getAllHypervServerOpts(context, computeServer)
			def stopResults = isWorkload ? stopWorkload(workload) : stopServer(computeServer)
			if (stopResults.success == true) {
				if (neededMemory != 0 || neededCores != 0) {
					def resizeOpts = [:]
					if (neededMemory != 0)
						resizeOpts.maxMemory = requestedMemory
					if (neededCores != 0)
						resizeOpts.maxCores = requestedCores
					def resizeResults = apiService.updateServer(hypervOpts, vmId, resizeOpts)
					log.debug("resize results: ${resizeResults}")
					if (resizeResults.success == true) {
						//computeServer.plan = plan
						computeServer.maxCores = (requestedCores ?: 1).toLong()
						computeServer.maxMemory = requestedMemory.toLong()
						computeServer = saveAndGet(computeServer)
						if (isWorkload) {
							workload.maxCores = (requestedCores ?: 1).toLong()
							workload.maxMemory = requestedMemory.toLong()
							workload = context.services.workload.save(workload)
						}
					} else {
						rtn.error = resizeResults.error ?: (isWorkload ? 'Failed to resize container' : 'Failed to resize server')
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
							def diskSize = ComputeUtility.parseGigabytesToBytes(updateProps.size?.toLong())
							def diskPath = "${hypervOpts.diskRoot}\\${hypervOpts.serverFolder}\\${volumeId}"
							def resizeResults = apiService.resizeDisk(hypervOpts, diskPath, diskSize)
							if (resizeResults.success == true) {
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
						def diskSize = ComputeUtility.parseGigabytesToBytes(volumeAdd.size?.toLong())
						def diskName = getUniqueDataDiskName(computeServer, newCounter++)
						def diskPath = "${hypervOpts.diskRoot}\\${hypervOpts.serverFolder}\\${diskName}"
						def diskResults = apiService.createDisk(hypervOpts, diskPath, diskSize)
						log.debug("create disk: ${diskResults.success}")
						if (diskResults.success == true && diskResults.error != true) {
							def attachResults = apiService.attachDisk(hypervOpts, vmId, diskPath)
							log.debug("attach: ${attachResults.success}")
							if (attachResults.success == true && attachResults.error != true) {
								def newVolume = buildStorageVolume(computeServer, volumeAdd, diskResults, newCounter)
								newVolume.maxStorage = volumeAdd.size.toInteger() * ComputeUtility.ONE_GIGABYTE
								newVolume.externalId = diskName
								context.async.storageVolume.create([newVolume], computeServer).blockingGet()
								computeServer = getMorpheusServer(computeServer.id)
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

						def diskConfig = volume.config ?: getDiskConfig(computeServer, volume, hypervOpts)
						def detachResults = apiService.detachDisk(hypervOpts, vmId, diskConfig.ControllerType, diskConfig.ControllerNumber, diskConfig.ControllerLocation)
						log.debug ("detachResults.success: ${detachResults.success}")
						if (detachResults.success == true) {
							apiService.deleteDisk(hypervOpts, diskName)
							context.async.storageVolume.remove([volume], computeServer, true).blockingGet()
							computeServer = getMorpheusServer(computeServer.id)
						}
					}
				}
			} else {
				rtn.error = 'Server never stopped so resize could not be performed'
				rtn.success = false
			}

			computeServer.status = 'provisioned'
			computeServer = saveAndGet(computeServer)
			if (stopResults) {
				def startResults = isWorkload ? startWorkload(workload) : startServer(computeServer)
			}
			rtn.success = true
		} catch (e) {
			log.error("Unable to resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			computeServer.statusMessage = isWorkload ? "Unable to resize container: ${e.message}" : "Unable to resize server: ${e.message}"
			computeServer = saveAndGet(computeServer)
			rtn.success = false
			rtn.setError("${e}")
		}
		return rtn
	}

	protected ComputeServer getMorpheusServer(Long id) {
		return context.services.computeServer.find(
				new DataQuery().withFilter("id", id).withJoin("interfaces.network")
		)
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
		def updatedServer
		if (saveResult.success == true) {
			updatedServer = saveResult.persistedItems.find { it.id == server.id }
		} else {
			updatedServer = saveResult.failedItems.find { it.id == server.id }
			log.warn("Error saving server: ${server?.id}")
		}
		return updatedServer ?: server
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: true])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)
		if(server.sourceImage){
			rtn.success = true
			return rtn
		}

		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
					virtualImage = workloadType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		return rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")
		ProvisionResponse provisionResponse = new ProvisionResponse()
		try {
			def config = server.getConfigMap()
			Cloud cloud = server.cloud
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, cloud)
			def imageType = config.templateTypeSelect ?: 'default'
			def imageId
			def virtualImage
			def node = config.hostId ? context.services.computeServer.get(config.hostId.toLong()) : pickHypervHypervisor(cloud)
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			def layout = server.layout
			def typeSet = server.typeSet
			if(layout && typeSet) {
				virtualImage = typeSet.workloadType.virtualImage
				imageId = virtualImage.externalId
			} else if(imageType == 'custom' && config.template) {
				def virtualImageId = config.template?.toLong()
				virtualImage = morpheus.services.virtualImage.get(virtualImageId)
				imageId = virtualImage.externalId
			} else {
				virtualImage = new VirtualImage(code: 'hyperv.image.morpheus.ubuntu.16.04.3-v1.ubuntu.16.04.3.amd64') //better this later
			}

			if(!imageId) { //If its userUploaded and still needs uploaded
				def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()

				def containerImage = [
						name			: virtualImage.name ?: typeSet.workloadType.imageCode,
						minDisk			: 5,
						minRam			: 512l * ComputeUtility.ONE_MEGABYTE,
						virtualImageId	: virtualImage.id,
						tags			: 'morpheus, ubuntu',
						imageType		: 'vhd',
						containerType	: 'vhd',
						cloudFiles		: cloudFiles,
				]

				hypervOpts.image = containerImage
				hypervOpts.userId = server.createdBy?.id
				hypervOpts.user = server.createdBy
				hypervOpts.virtualImage = virtualImage

				log.debug "hypervOpts:${hypervOpts}"
				def imageResults = apiService.insertContainerImage(hypervOpts)
				if(imageResults.success == true) {
					imageId = imageResults.imageId
				}
			}

			if(imageId) {
				server.sourceImage = virtualImage
				server.serverOs = server.serverOs ?: virtualImage.osType
				server.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' :'linux') ?: virtualImage.platform
				hypervOpts.secureBoot = virtualImage?.uefi ?: false
				hypervOpts.imageId = imageId
				hypervOpts.diskMap = context.services.virtualImage.getImageDiskMap(virtualImage)
				server = saveAndGetMorpheusServer(server, true)
				hypervOpts += HypervOptsUtility.getHypervServerOpts(context, server)

				hypervOpts.networkConfig = hostRequest.networkConfiguration
				hypervOpts.cloudConfigUser = hostRequest.cloudConfigUser
				hypervOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
				hypervOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
				hypervOpts.isSysprep = virtualImage?.isSysprep

				def isoBuffer = context.services.provision.buildIsoOutputStream(
						hypervOpts.isSysprep, PlatformType.valueOf(server.osType), hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser, hypervOpts.cloudConfigNetwork)

				hypervOpts.cloudConfigBytes = isoBuffer
				server.cloudConfigUser = hypervOpts.cloudConfigUser
				server.cloudConfigMeta = hypervOpts.cloudConfigMeta

				//save the server
				server = saveAndGetMorpheusServer(server, true)
				hypervOpts.newServer = server

				//create it in hyperv
				def createResults = apiService.cloneServer(hypervOpts)
				log.debug("create server results:${createResults}")
				if(createResults.success == true) {
					def instance = createResults.server
					if(instance) {
						server.externalId = instance.id
						server.parentServer = node
						server = saveAndGetMorpheusServer(server, true)

						def serverDetails = apiService.getServerDetails(hypervOpts, server.externalId)
						if(serverDetails.success == true) {
							//fill in ip address.
							def newIpAddress = serverDetails.server?.ipAddress ?: createResults.server?.ipAddress
							def macAddress = serverDetails.server?.macAddress
							opts.network = applyComputeServerNetworkIp(server, newIpAddress, newIpAddress, 0, macAddress)
							server = getMorpheusServer(server.id)
							server.osDevice = '/dev/sda'
							server.dataDevice = '/dev/sdb'
							server.managed = true

							server.capacityInfo = new ComputeCapacityInfo(maxCores:hypervOpts.maxCores, maxMemory:hypervOpts.memory, maxStorage:hypervOpts.maxTotalStorage)
							context.async.computeServer.save(server).blockingGet()
							provisionResponse.success = true
						} else {
							//no server detail
							server.statusMessage = 'Error loading server details'
						}
					} else {
						//no reservation
						server.statusMessage = 'Error loading created server'
					}
				} else {
					if(createResults.server?.id) {
						// we did create a vm though so we need to bind it to the server
						server.externalId = createResults.server.id
						context.async.computeServer.save(server).blockingGet()
					}
					server.statusMessage = 'Error creating server'
					//tell someone :)
				}
			} else {
				server.statusMessage = 'Error creating server'
			}
			if(provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}

		} catch(Exception e) {
			log.error("Error in runHost method: ${e.message}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server){
		log.debug("waitForHost: ${server}")
		def provisionResponse = new ProvisionResponse()
		ServiceResponse<ProvisionResponse> rtn = ServiceResponse.prepare(provisionResponse)
		try {
			def config = server.getConfigMap()
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, server.cloud)
			def node = config.hostId ? context.services.computeServer.get(config.hostId.toLong()) : pickHypervHypervisor(server.cloud)
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			def serverDetail = apiService.checkServerReady(hypervOpts, server.externalId)
			if (serverDetail.success == true) {
				provisionResponse.privateIp = serverDetail.server.ipAddress
				provisionResponse.publicIp = serverDetail.server.ipAddress
				provisionResponse.externalId = server.externalId
				def finalizeResults = finalizeHost(server)
				if(finalizeResults.success == true) {
					provisionResponse.success = true
					rtn.success = true
				}
			}
		} catch (e){
			log.error("Error waitForHost: ${e.message}", e)
			rtn.success = false
			rtn.msg = "Error in waiting for Host: ${e}"
		}
		return rtn
	}

	def isValidIpv6Address(String address) {
		// validate the ipv6 address is an ipv6 address. There is no separate validation for ipv6 addresses, so validate that its not an ipv4 address and it is a valid ip address
		return address && NetworkUtility.validateIpAddr(address, false) == false && NetworkUtility.validateIpAddr(address, true) == true
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		ServiceResponse rtn = ServiceResponse.success()
		log.debug("finalizeHost: ${server?.id}")
		try {
			def config = server.getConfigMap()
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, server.cloud)
			def node = config.hostId ? context.services.computeServer.get(config.hostId.toLong()) : pickHypervHypervisor(server.cloud)
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			def serverDetail = apiService.checkServerReady(hypervOpts, server.externalId)
			if (serverDetail.success == true){
				rtn.success = true
				def newIpAddress = serverDetail.server?.ipAddress
				def macAddress = serverDetail.server?.macAddress
				applyComputeServerNetworkIp(server, newIpAddress, newIpAddress, 0, macAddress)
				context.async.computeServer.save(server).blockingGet()
				rtn.success = true
			}
		} catch (e){
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeHost: ${e.message}", e)
		}
		return rtn
	}

	def getUniqueDataDiskName(ComputeServer server, index = 1) {
		def nameExists = true
		def volumes = server.volumes
		def diskName
		def diskIndex = index ?: server.volumes?.size()
		while (nameExists) {
			diskName = "dataDisk${diskIndex}.vhd"
			nameExists = volumes.find { it.externalId == diskName }
			diskIndex++
		}

		return diskName
	}

	def buildStorageVolume(computeServer, volumeAdd, addDiskResults, newCounter) {
		def newVolume = new StorageVolume(
				refType: 'ComputeZone',
				refId: computeServer.cloud.id,
				regionCode: computeServer.region?.regionCode,
				account: computeServer.account,
				maxStorage: volumeAdd.maxStorage?.toLong(),
				maxIOPS: volumeAdd.maxIOPS?.toInteger(),
				//internalId 		: addDiskResults.volume?.uuid,
				//deviceName		: addDiskResults.volume?.deviceName,
				name: volumeAdd.name,
				displayOrder: newCounter,
				status: 'provisioned',
				//unitNumber		: addDiskResults.volume?.deviceIndex?.toString(),
				deviceDisplayName: getDiskDisplayName(newCounter)
		)
		return newVolume
	}

	def getDiskConfig(ComputeServer server, StorageVolume volume, hypervOpts) {
		def rtn = [success: true]
		def vmId = server.externalId
		def diskResults = apiService.getServerDisks(hypervOpts, vmId)
		if (diskResults?.success == true) {
			def diskName = volume.externalId
			def diskData = diskResults?.disks?.find { it.Path.contains("${diskName}") }
			if (diskData) {
				rtn += diskData
			}
		}
		return rtn
	}
}
