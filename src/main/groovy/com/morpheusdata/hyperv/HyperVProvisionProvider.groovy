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
import com.morpheusdata.response.InitializeHypervisorResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider, ProvisionProvider.HypervisorProvisionFacet, ProvisionProvider.BlockDeviceNameFacet {
	public static final String PROVIDER_CODE = 'hyperv.provision'
	public static final String PROVISION_TYPE_CODE = 'hyperv'
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

	@Override
	String[] getDiskNameList() {
		return diskNames
	}

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts=[:]) {
		log.debug("validateServiceConfiguration:$opts")
		log.info("RAZI :: validateHost >> opts: ${opts}")
		def rtn =  ServiceResponse.success()
		try {
			def cloudId = server?.cloud?.id ?: opts.siteZoneId
//			def zone = cloudId ? ComputeZone.read(zoneId?.toLong()) : null
			def cloud = cloudId ? context.services.cloud.get(cloudId?.toLong()) : null
			log.info("RAZI :: server.computeServerType?.vmHypervisor: ${server.computeServerType?.vmHypervisor}")
			if(server.computeServerType?.vmHypervisor == true) {
				rtn =  ServiceResponse.success()
			} else {
				def validationOpts = [
						networkInterfaces: opts?.networkInterfaces
				]
				log.info("RAZI :: opts?.config?.containsKey('hypervHostId'): ${opts?.config?.containsKey('hypervHostId')}")
				if(opts?.config?.containsKey('hypervHostId')){
					log.info("RAZI :: opts.config.hypervHostId: ${opts.config.hypervHostId}")
					validationOpts.hostId = opts.config.hypervHostId
				}
				log.info("RAZI :: opts?.config?.containsKey('nodeCount'): ${opts?.config?.containsKey('nodeCount')}")
				if(opts?.config?.containsKey('nodeCount')){
					log.info("RAZI :: opts.config.nodeCount: ${opts.config.nodeCount}")
					validationOpts.nodeCount = opts.config.nodeCount
				}
				def validationResults = apiService.validateServerConfig(validationOpts)
				log.info("RAZI :: validateHost >> validationResults: ${validationResults}")
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

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: true])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)

		try {
			VirtualImage virtualImage
			log.info("RAZI :: server.typeSet?.id: ${server.typeSet?.id}")
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
				log.info("RAZI :: computeTypeSet.workloadType: ${computeTypeSet.workloadType}")
				if(computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
					virtualImage = workloadType.virtualImage
					log.info("RAZI :: if(computeTypeSet.workloadType) >> virtualImage: ${virtualImage}")
				}
			}
			log.info("RAZI :: before if(!virtualImage) >> virtualImage: ${virtualImage}")
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
		log.info("RAZI :: prepareHost last >> rtn: ${rtn}")
		return rtn
	}

	def pickHypervHypervisor(cloud) {
		def hypervServer = context.services.computeServer.find(new DataQuery()
				.withFilter('zone.id', cloud.id)
				.withFilter('computerServerType.code', 'hypervHypervisor'))
		return hypervServer
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")
		log.info("RAZI :: runHost: ${server} ${hostRequest} ${opts}")

		ProvisionResponse provisionResponse = new ProvisionResponse()
		try {
			def config = server.getConfigMap()
			log.info("RAZI :: config: ${config}")
			Cloud cloud = server.cloud
//			Account account = server.account
//			def cloudConfig = cloud.getConfigMap()
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, cloud)
			log.info("RAZI :: hypervOpts1: ${hypervOpts}")
			def imageType = config.templateTypeSelect ?: 'default'
			log.info("RAZI :: imageType: ${imageType}")
			def imageId
			def virtualImage
//			def applianceServerUrl = applianceService.getApplianceUrl(server.zone)
			log.info("RAZI :: config.hostId: ${config.hostId}")
//			log.info("RAZI :: context.services.computeServer: ${context.services.computeServer.get(config.hostId.toLong())}")
			log.info("RAZI :: pickHypervHypervisor: ${pickHypervHypervisor(cloud)}")
			def node = config.hostId ? context.services.computeServer.get(config.hostId.toLong()) : pickHypervHypervisor(cloud)
			log.info("RAZI :: node: ${node}")
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
			log.info("RAZI :: hypervOpts2: ${hypervOpts}")
			def layout = server.layout
			log.info("RAZI :: layout: ${layout}")
			def typeSet = server.typeSet
			log.info("RAZI :: typeSet: ${typeSet}")
			Long computeTypeSetId = typeSet?.id
			WorkloadType containerType
			log.info("RAZI :: computeTypeSetId: ${computeTypeSetId}")
			if (computeTypeSetId){
				ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
				log.info("RAZI :: if (computeTypeSetId) >> computeTypeSet: ${computeTypeSet}")
				WorkloadType workloadType = computeTypeSet.getWorkloadType()
				Long workloadTypeId = workloadType.id
				containerType = morpheus.services.containerType.get(workloadTypeId)
				log.info("RAZI :: containerType.imageCode: ${containerType.imageCode}")
			}
			log.info("RAZI :: before if(layout && typeSet) >> containerType: ${containerType}")
			if(layout && typeSet) {
				Long virtualImageId = containerType.virtualImage.id
				log.info("RAZI :: if(layout && typeSet) >> virtualImageId: ${virtualImageId}")
				virtualImage = morpheus.services.virtualImage.get(virtualImageId)
				log.info("RAZI :: if(layout && typeSet) >> virtualImage: ${virtualImage}")
				imageId = virtualImage.externalId
				log.info("RAZI :: if(layout && typeSet) >> imageId: ${imageId}")
			} else if(imageType == 'custom' && config.template) {
				def virtualImageId = config.template?.toLong()
				log.info("RAZI :: else if(imageType == 'custom' && config.template) >> virtualImageId: ${virtualImageId}")
				virtualImage = morpheus.services.virtualImage.get(virtualImageId)
				log.info("RAZI :: else if(imageType == 'custom' && config.template) >> virtualImage: ${virtualImage}")
				imageId = virtualImage.externalId
				log.info("RAZI :: else if(imageType == 'custom' && config.template) >> imageId: ${imageId}")
			} else {
				virtualImage = new VirtualImage(code: 'hyperv.image.morpheus.ubuntu.18.04.3-v1.ubuntu.18.04.3.amd64')
				log.info("RAZI :: } else { >> virtualImage: ${virtualImage}")
			}
			log.info("RAZI :: before if(!imageId) >> imageId: ${imageId}")
			if(!imageId) { //If its userUploaded and still needs uploaded
				def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				log.info("RAZI :: cloudFiles: ${cloudFiles}")

				def containerImage = [
						name			: virtualImage.name ?: containerType.imageCode,
						minDisk			: 5,
						minRam			: 512l * ComputeUtility.ONE_MEGABYTE,
						virtualImageId	: virtualImage.id,
						tags			: 'morpheus, ubuntu',
						imageType		: 'vhd',
						containerType	: 'vhd',
						cloudFiles		: cloudFiles,
//						cachePath		: virtualImageService.getLocalCachePath()
				]
				log.info("RAZI :: containerImage: ${containerImage}")
				hypervOpts.image = containerImage
//				hypervOpts.applianceServerUrl = applianceServerUrl
				hypervOpts.userId = server.createdBy?.id
				hypervOpts.user = server.createdBy
				hypervOpts.virtualImage = virtualImage
				hypervOpts.server = node
				log.info("RAZI :: hypervOpts.user: ${hypervOpts.user}")

				log.debug "hypervOpts:${hypervOpts}"
				def imageResults = apiService.insertContainerImage(hypervOpts)
				log.info("RAZI :: imageResults: ${imageResults}")
				if(imageResults.success == true) {
					imageId = imageResults.imageId
					log.info("RAZI :: if(imageResults.success == true) >> imageId: ${imageId}")
				}
			}
			log.info("RAZI :: before if(imageId) >> imageId: ${imageId}")
			if(imageId) {
				server.sourceImage = virtualImage
				server.serverOs = server.serverOs ?: virtualImage.osType
				server.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' :'linux') ?: virtualImage.platform
				log.info("RAZI :: server.osType: ${server.osType}")
				hypervOpts.secureBoot = virtualImage?.uefi ?: false
				hypervOpts.imageId = imageId
				hypervOpts.diskMap = context.services.virtualImage.getImageDiskMap(virtualImage)
				hypervOpts += HypervOptsUtility.getHypervServerOpts(context, server)
				hypervOpts.networkConfig = hostRequest.networkConfiguration
				hypervOpts.cloudConfigUser = hostRequest.cloudConfigUser
				hypervOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
				hypervOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
				hypervOpts.isSysprep = virtualImage?.isSysprep

//				def isoBuffer = IsoUtility.buildCloudIso(server.osType, hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser)
				log.info("RAZI :: hypervOpts.isSysprep: ${hypervOpts.isSysprep}")
				log.info("RAZI :: hypervOpts.cloudConfigMeta: ${hypervOpts.cloudConfigMeta}")
				log.info("RAZI :: hypervOpts.cloudConfigUser: ${hypervOpts.cloudConfigUser}")
				log.info("RAZI :: hypervOpts.cloudConfigNetwork: ${hypervOpts.cloudConfigNetwork}")
				def isoBuffer = context.services.provision.buildIsoOutputStream(
						hypervOpts.isSysprep, PlatformType.valueOf(server.osType), hypervOpts.cloudConfigMeta, hypervOpts.cloudConfigUser, hypervOpts.cloudConfigNetwork)

				log.info("RAZI :: isoBuffer: ${isoBuffer}")
				hypervOpts.cloudConfigBytes = isoBuffer
				server.cloudConfigUser = hypervOpts.cloudConfigUser
				server.cloudConfigMeta = hypervOpts.cloudConfigMeta

				//save the server
				context.async.computeServer.save(server).blockingGet()

				//create it in hyperv
				log.debug("create server:${hypervOpts}")
				log.info("RAZI :: before createResults >> isoBuffer: ${isoBuffer}")
				def createResults = apiService.cloneServer(hypervOpts)
				log.info("RAZI :: createResults: ${createResults}")
				log.debug("create server results:${createResults}")
				if(createResults.success == true) {
					def instance = createResults.server //lookup ip
					log.info("RAZI :: instance: ${instance}")
					if(instance) {
						log.info("RAZI :: instance.id: ${instance.id}")
						server.externalId = instance.id
						server.parentServer = node
//						opts.server.save(flush:true)
						context.async.computeServer.save(server).blockingGet()
						def serverDetails = apiService.getServerDetails(hypervOpts, server.externalId)
						log.info("RAZI :: serverDetails: ${serverDetails}")
						if(serverDetails.success == true) {
							//fill in ip address.
//							def privateIp = serverDetails.server.ipAddress
//							def publicIp = serverDetails.server.ipAddress
//							hypervProvisionService.applyComputeServerNetworkIp(opts.server, privateIp, publicIp, null, null, 0, null) //ignore it
							server.osDevice = '/dev/sda'
							server.dataDevice = '/dev/sdb'
							server.managed = true
//							opts.server.save()
							context.async.computeServer.save(server).blockingGet()
							server.capacityInfo = new ComputeCapacityInfo(maxCores:hypervOpts.maxCores, maxMemory:hypervOpts.memory, maxStorage:hypervOpts.maxTotalStorage)
//							opts.server.capacityInfo.save()
//							opts.server.save(flush:true)
							log.info("RAZI :: server.capacityInfo: ${server.capacityInfo}")
							context.async.computeServer.save(server).blockingGet()
							provisionResponse.success = true
							log.info("RAZI :: provisionResponse: ${provisionResponse}")
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
//						opts.server.save(flush:true)
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
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(morpheusContext, cloud)
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(server)
			log.info("RAZI :: waitForHost >> server.externalId: ${server.externalId}")
			def serverDetail = apiService.checkServerReady(hypervOpts, server.externalId)
			log.info("RAZI :: waitForHost >> serverDetail: ${serverDetail}")
			if (serverDetail.success == true) {
				provisionResponse.privateIp = serverDetail.ipAddress
				provisionResponse.publicIp = serverDetail.ipAddress
				provisionResponse.externalId = server.externalId
				def finalizeResults = finalizeHost(server)
				log.info("RAZI :: finalizeResults: ${finalizeResults}")
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
		log.info("RAZI :: waitForHost last >> rtn: ${rtn}")
		return rtn
	}

	def isValidIpv6Address(String address) {
		// validate the ipv6 address is an ipv6 address. There is no separate validation for ipv6 addresses, so validate that its not an ipv4 address and it is a valid ip address
		return address && NetworkUtility.validateIpAddr(address, false) == false && NetworkUtility.validateIpAddr(address, true) == true
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeHost: ${server?.id}")
		try {
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(morpheusContext, cloud)
			hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(server)
			def serverDetail = apiService.checkServerReady(hypervOpts, server.externalId)
			log.info("RAZI :: finalizeHost >> serverDetail: ${serverDetail}")

			if (serverDetail.success == true){
				serverDetail.ipAddresses.each { interfaceName, data ->
					ComputeServerInterface netInterface = server.interfaces?.find{it.name == interfaceName}
					log.info("RAZI :: netInterface: ${netInterface}")
					if(netInterface) {
						log.info("RAZI :: data.ipAddress: ${data.ipAddress}")
						if(data.ipAddress) {
							def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
							log.info("RAZI :: address.address: ${address.address}")
							log.info("RAZI :: !NetworkUtility.validateIpAddr(address.address): ${!NetworkUtility.validateIpAddr(address.address)}")
							if(!NetworkUtility.validateIpAddr(address.address)){
								log.debug("NetAddress Errors: ${address}")
							}
							netInterface.addresses << address
							netInterface.publicIpAddress = data.ipAddress
							log.info("RAZI :: netInterface.publicIpAddress: ${netInterface.publicIpAddress}")
						}
						log.info("RAZI :: data.ipv6Address: ${data.ipv6Address}")
						log.info("RAZI :: isValidIpv6Address(data.ipv6Address): ${isValidIpv6Address(data.ipv6Address)}")
						if(data.ipv6Address && isValidIpv6Address(data.ipv6Address)) {
							def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
							netInterface.addresses << address
							netInterface.publicIpv6Address = data.ipv6Address
							log.info("RAZI :: netInterface.publicIpv6Address: ${netInterface.publicIpv6Address}")
						}
						context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
						log.info("RAZI :: computeServerInterface save SUCCESS")
					}
				}
				log.info("RAZI :: serverDetail.networks: ${serverDetail.networks}")
//				setNetworkInfo(server.interfaces, serverDetail.networks)
				context.async.computeServer.save(server).blockingGet()
				rtn.success = true
			}
		} catch (e){
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeHost: ${e.message}", e)
		}
		log.info("RAZI :: finalizeHost last >> rtn: ${rtn}")
		return rtn
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
	HostType getHostType() {
		return HostType.vm
	}

	@Override
	String serverType() {
		return "vm"
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
		return "vm"
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

}
