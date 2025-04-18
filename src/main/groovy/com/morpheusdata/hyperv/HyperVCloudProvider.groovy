package com.morpheusdata.hyperv

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.hyperv.sync.NetworkSync
import com.morpheusdata.hyperv.sync.VirtualMachineSync
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'hyperv'

	protected MorpheusContext context
	protected HyperVPlugin plugin
	HyperVApiService apiService

	public HyperVCloudProvider(HyperVPlugin plugin, MorpheusContext context) {
		super()
		this.@plugin = plugin
		this.@context = context
		this.apiService = new HyperVApiService(context)
	}

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return 'Hyper-V'
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:'hyperv.svg', darkPath:'hyperv.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'hyperv-circular.svg', darkPath:'hyperv-circular.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		def displayOrder = 0
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'Hyper-V Host',
				code: 'zoneType.hyperv.host',
				fieldName: 'host',
				displayOrder: displayOrder,
				fieldCode: 'gomorpheus.optiontype.hypervHost',
				fieldLabel:'Hyper-V Host',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config'
		)
		options << new OptionType(
				name: 'Winrm Port',
				code: 'zoneType.hyperv.port',
				fieldName: 'port',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.winrmPort',
				fieldLabel:'Winrm Port',
				required: true,
				inputType: OptionType.InputType.TEXT,
				defaultValue: '5985',
				fieldContext: 'config'
		)
		options << new OptionType(
				name: 'Working Path',
				code: 'zoneType.hyperv.workingPath',
				fieldName: 'workingPath',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.WorkingPath',
				fieldLabel:'Working Path',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				placeHolderText: 'c:\\Temp'
		)
		options << new OptionType(
				name: 'VM Path',
				code: 'zoneType.hyperv.vmPath',
				fieldName: 'vmPath',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.VmPath',
				fieldLabel:'VM Path',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				placeHolderText: 'c:\\VMs'
		)
		options << new OptionType(
				name: 'Disk Path',
				code: 'zoneType.hyperv.diskPath',
				fieldName: 'diskPath',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.DiskPath',
				fieldLabel:'Disk Path',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				placeHolderText:'c:\\VirtualDisks'
		)
		options << new OptionType(
				name: 'Credentials',
				code: 'zoneType.hyperv.credential',
				fieldName: 'type',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.label.credentials',
				fieldLabel:'Credentials',
				required: true,
				defaultValue:'local',
				inputType: OptionType.InputType.CREDENTIAL,
				fieldContext: 'credential',
				optionSource:'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		options << new OptionType(
				name: 'Username',
				code: 'zoneType.hyperv.username',
				fieldName: 'username',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.Username',
				fieldLabel:'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'Password',
				code: 'zoneType.hyperv.password',
				fieldName: 'password',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.Password',
				fieldLabel:'Password',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'Inventory Existing Instances',
				code: 'zoneType.hyperv.importExisting',
				fieldName: 'importExisting',
				displayOrder: displayOrder += 10,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)
		return options
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
	    return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * Grabs available backup providers related to the target Cloud Plugin.
	 * @return Collection of BackupProvider
	 */
	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return this.@plugin.getProvidersByType(BackupProvider) as Collection<BackupProvider>
	}

	/**
	 * Provides a Collection of {@link NetworkType} related to this CloudProvider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		Collection<NetworkType> networks = context.services.network.type.list(new DataQuery().withFilter(
				'code','in', ['dockerBridge', 'childNetwork', 'overlay']))

		networks << new NetworkType([
				code				: 'hypervExternalNetwork',
				name				: 'Hyper-V External',
				description			: '',
				overlay				: false,
				externalType		: 'External',
				creatable			: false,
				cidrEditable		: true,
				dhcpServerEditable	: true,
				dnsEditable			: true,
				gatewayEditable		: true,
				cidrRequired		: false,
				vlanIdEditable		: true,
				canAssignPool		: true,
				hasNetworkServer	: false,
				hasCidr				: true,
		])

		networks << new NetworkType([
				code				: 'hypervInternalNetwork',
				name				: 'Hyper-V Internal',
				description			: '',
				overlay				: false,
				externalType		: 'Internal',
				creatable			: false,
				cidrEditable		: true,
				dhcpServerEditable	: true,
				dnsEditable			: true,
				gatewayEditable		: true,
				cidrRequired		: false,
				vlanIdEditable		: true,
				canAssignPool		: true,
				hasNetworkServer	: false,
				hasCidr				: true,
		])

		return networks
	}

	/**
	 * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
	 * @return Collection of NetworkSubnetType
	 */
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		Collection<NetworkSubnetType> subnets = []
		subnets << new NetworkSubnetType([
				code				: 'hyperv',
				name				: 'Hyper-V Subnet',
				description			: '',
				creatable			: false,
				deletable			: false,
				dhcpServerEditable	: false,
				canAssignPool		: false,
				vlanIdEditable		: false,
				cidrEditable		: false,
				cidrRequired		: false
		])
		return subnets
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		return volumeTypes
	}

	/**
	 * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
	 * @return Collection of StorageControllerType
	 */
	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		Collection<StorageControllerType> controllerTypes = []
		return controllerTypes
	}

	/**
	 * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during a sync or during a provision.
	 * @return collection of ComputeServerType
	 */
	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = []

		// Host option type is used by multiple compute server types.
		OptionType hostOptionType = new OptionType(
			code:'computeServerType.hyperv.host', inputType: OptionType.InputType.SELECT, name:'host', category:'computeServerType.hyperv',
			fieldName:'hypervHostId', fieldCode: 'gomorpheus.optiontype.Host', fieldLabel:'Host', fieldContext:'config', fieldGroup:'Options',
			required:true, enabled:true, optionSource:'hypervHost', editable:false, global:false, placeHolder:null, helpBlock:'',
			defaultValue:null, custom:false, displayOrder:10, fieldClass:null
		)

		//hyperv hypervisor
		serverTypes << new ComputeServerType(code:'hypervHypervisor', name:'Hyper-V Hypervisor', description:'', platform:PlatformType.windows,
				nodeType:'morpheus-hyperv-node', enabled:true, selectable:false, externalDelete:false, managed:false, controlPower:false,
				controlSuspend:false, creatable:true, computeService:'hypervComputeService', displayOrder:0, hasAutomation:false,
				containerHypervisor:false, bareMetalHost:true, vmHypervisor:true, agentType: ComputeServerType.AgentType.node
		)

		//vms
		serverTypes << new ComputeServerType(code:'hypervVm', name:'Hyper-V Linux VM', description:'', platform:PlatformType.linux,
				nodeType:'morpheus-vm-node', enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true, controlSuspend:false,
				creatable:false, computeService:'hypervComputeService', displayOrder: 0, hasAutomation:true, reconfigureSupported:true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.guest, guestVm:true,
				provisionTypeCode:'hyperv'
		)
		serverTypes << new ComputeServerType(code:'hypervWindowsVm', name:'Hyper-V Windows VM', description:'', platform:PlatformType.windows,
				nodeType:'morpheus-windows-vm-node', enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true,
				controlSuspend:false, creatable:false, computeService:'hypervComputeService', displayOrder: 0, hasAutomation:true,
				reconfigureSupported:true, containerHypervisor:false, bareMetalHost:false, vmHypervisor:false,
				agentType: ComputeServerType.AgentType.guest, guestVm:true, provisionTypeCode:'hyperv'
		)
		serverTypes << new ComputeServerType(code:'hypervUnmanaged', name:'Hyper-V Instance', description:'hyper-v vm', platform:PlatformType.linux,
				nodeType:'unmanaged', enabled:true, selectable:false, externalDelete:true, managed:false, controlPower:true, controlSuspend:false,
				creatable:false, computeService:'hypervComputeService', displayOrder:99, hasAutomation:false, containerHypervisor:false,
				bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.guest, managedServerType:'hypervVm',
				guestVm:true, provisionTypeCode:'hyperv'
		)

		//windows container host - not used
		serverTypes << new ComputeServerType(code:'hypervWindows', name:'Hyper-V Windows Host', description:'', platform:PlatformType.windows,
				nodeType:'morpheus-windows-node', enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true,
				controlSuspend:false, creatable:true, computeService:'hypervComputeService', displayOrder:7, hasAutomation:true, reconfigureSupported:true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.node, guestVm:true,
				provisionTypeCode:'hyperv'
		)

		//docker
		serverTypes << new ComputeServerType(code:'hypervLinux', name:'Hyper-V Docker Host', description:'', platform:PlatformType.linux,
				nodeType:'morpheus-node', enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true, controlSuspend:false,
				creatable:false, computeService:'hypervComputeService', displayOrder: 6, hasAutomation:true, reconfigureSupported:true,
				containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.node, containerEngine:'docker', provisionTypeCode:'hyperv',
				computeTypeCode:'docker-host',
				optionTypes:[hostOptionType]
		)

		//kubernetes
		serverTypes << new ComputeServerType(code:'hypervKubeMaster', name:'Hyper-V Kubernetes Master', description:'', platform:PlatformType.linux,
				nodeType:'kube-master', reconfigureSupported: true, enabled:true, selectable:false, externalDelete:true, managed:true,
				controlPower:true, controlSuspend:true, creatable:true, supportsConsoleKeymap: true,
				displayOrder:10, hasAutomation:true, containerHypervisor:true, bareMetalHost:false, vmHypervisor:false,
				agentType: ComputeServerType.AgentType.host, containerEngine:'docker',
				provisionTypeCode:'hyperv', computeTypeCode:'kube-master',
				optionTypes:[hostOptionType]
		)
		serverTypes << new ComputeServerType(code:'hypervKubeWorker', name:'Hyper-V Kubernetes Worker', description:'', platform:PlatformType.linux,
				nodeType:'kube-worker', reconfigureSupported: true, enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true,
				controlSuspend:true, creatable:true, supportsConsoleKeymap: true, computeService:'hypervComputeService', displayOrder:10, hasAutomation:true,
				containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.guest, containerEngine:'docker',
				provisionTypeCode:'hyperv', computeTypeCode:'kube-worker',
				optionTypes:[hostOptionType]
		)

		return serverTypes
	}

	/**
	 * Validates the submitted cloud information to make sure it is functioning correctly.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param cloudInfo cloud
	 * @param validateCloudRequest Additional validation information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validate(Cloud cloud, ValidateCloudRequest validateCloudRequest) {
		log.debug("validate: cloud: {}, validateCloudRequest: {}", cloud, validateCloudRequest)
		def rtn = [success: false, zone: cloud, errors: [:]]
		try {
			if (cloud) {
				def requiredFields = ['host', 'workingPath', 'vmPath', 'diskPath']
				def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, cloud)
				def cloudConfig = cloud.getConfigMap()
				rtn.errors = validateRequiredConfigFields(requiredFields, cloudConfig)
				if (!validateCloudRequest?.credentialUsername) {
					rtn.msg = 'Enter a username'
					rtn.errors.username = rtn.msg
				} else if (!validateCloudRequest?.credentialPassword) {
					rtn.msg = 'Enter your password'
					rtn.errors.password = rtn.msg
				}
				if (rtn.errors.size() == 0) {
					if (cloud.enabled == true) {
						def opts = [
								hypervisor : [:],
								sshHost    : cloudConfig.host,
								sshPort    : cloudConfig.port,
								sshUsername: validateCloudRequest?.credentialUsername,
								sshPassword: validateCloudRequest?.credentialPassword,
								zoneRoot   : cloudConfig.workingPath,
								diskRoot   : cloudConfig.diskPath,
								vmRoot     : cloudConfig.vmPath
						]
						def vmSwitches = apiService.listVmSwitches(opts)
						log.debug("validate: vmSwitches: ${vmSwitches}")
						if (vmSwitches.success == true)
							rtn.success = true
						if (rtn.success == false)
							rtn.msg = 'Error connecting to hyper-v'
					} else {
						rtn.success = true
					}
				} else {
					rtn.msg = 'Invalid configuration'
				}
			} else {
				rtn.message = 'No zone found'
			}
		} catch (e) {
			log.error("validation: An Exception Has Occurred", e)
		}
		return ServiceResponse.create(rtn)
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		log.debug ('initializing cloud: {}', cloudInfo.code)
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			if(cloudInfo) {
				if(cloudInfo.enabled == true) {
					def initResults = initializeHypervisor(cloudInfo)
					log.debug("initResults: {}", initResults)
					if(initResults.success == true) {
						refresh(cloudInfo)
						rtn.success = true
					}
				}
			} else {
				rtn.msg = 'No zone found'
			}
		} catch(e) {
			log.error("initialize cloud error: {}",e)
		}
		return rtn
	}

	def initializeHypervisor(cloud) {
		def rtn = [success: false]
		log.debug("cloud: ${cloud}")
		ComputeServer newServer
		def opts = HypervOptsUtility.getHypervInitializationOpts(cloud)
		def serverInfo = apiService.getHypervServerInfo(opts)
		if (serverInfo.success == true && serverInfo.hostname) {
			def cloudConfig = cloud.getConfigMap()
			def ipAddress = cloud.getConfigProperty('hypervHost')
			def serverType = context.async.cloud.findComputeServerTypeByCode("hypervHypervisor").blockingGet()
			newServer = findHypervHypervisor(cloud, serverInfo.hostname, ipAddress, serverType)
			if (!newServer) {
				newServer = new ComputeServer()
				newServer.account = cloud.account
				newServer.cloud = cloud
				newServer.computeServerType = serverType
				newServer.serverOs = new OsType(code: 'windows.server.2012')
				newServer.name = serverInfo.hostname
				newServer = context.services.computeServer.create(newServer)
			}
			if (serverInfo.hostname) {
				newServer.hostname = serverInfo.hostname
			}
			newServer.sshHost = cloud.getConfigProperty('host')
			newServer.sshPort = cloud.getConfigProperty('port') ? cloud.getConfigProperty('port').toInteger() : 5985
			newServer.internalIp = newServer.sshHost
			newServer.externalIp = newServer.sshHost
			newServer.sshUsername = HypervOptsUtility.getUsername(cloud)
			newServer.sshPassword = HypervOptsUtility.getPassword(cloud)
			newServer.setConfigProperty('workingPath', cloud.getConfigProperty('workingPath'))
			newServer.setConfigProperty('vmPath', cloud.getConfigProperty('vmPath'))
			newServer.setConfigProperty('diskPath', cloud.getConfigProperty('diskPath'))
		}
		// initializeHypervisor from context
		log.debug("newServer: ${newServer}")
		context.services.computeServer.save(newServer)
		if (newServer) {
			context.async.hypervisorService.initialize(newServer)
			rtn.success = true
		}
		return rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc.
	 * @param cloud cloud
	 * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
	 * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
	 * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
	 */
	@Override
	ServiceResponse refresh(Cloud cloud) {
		log.debug("refreshZone:${cloud}")
		ServiceResponse response = ServiceResponse.prepare()
		try {
			def syncDate = new Date()
			def hypervisorList = getHypervHypervisors(cloud)
			log.debug("hypervisorList?.size(): ${hypervisorList?.size()}")
			def virtualMachineList = []
			def allOnline = true
			def anyOnline = false
			for (hypervisor in hypervisorList) {
				if (hypervisor.status == 'pending' || hypervisor.status == 'pendingDeleteApproval') {
					continue
				}
				if (hypervisor.enabled == true) {
					def hypervHypervisorOpts = HypervOptsUtility.getHypervHypervisorOpts(hypervisor)
					def hostOnline = ConnectionUtils.testHostConnectivity(hypervHypervisorOpts.sshHost, 5985, false, true, null)
					log.debug("hostOnline: ${hostOnline}")
					if (hostOnline) {
						allOnline = hostOnline && allOnline
						anyOnline = hostOnline || anyOnline
						//check if this host is online
						def hostResults = loadHypervHost([zone: cloud], hypervisor)
						if (hostResults.success == true && hostResults.host && hostResults?.host['ComputerName']) {
							// This logic need to be moved in cacheVirtualMachines
							//resolveUniqueIdsToVMids([zone: cloud], hypervisor)
							def results = apiService.listVirtualMachines(hypervHypervisorOpts, 1, 1)
							log.debug("results : ${results}")
							virtualMachineList += results.virtualMachines
							log.debug("virtualMachineList.size(): ${virtualMachineList.size()}")
							if (results.success == true) {
								def now = new Date().time
								new NetworkSync(context, cloud, apiService).execute()
								log.debug("${cloud.name}: NetworkSync in ${new Date().time - now}ms")

								allOnline = results.success && allOnline
								anyOnline = results.success || anyOnline
								updateHypervisorStatus(hypervisor, 'provisioned', 'on', '')
								context.services.operationNotification.clearHypervisorAlarm(hypervisor)
							} else {
								updateHypervisorStatus(hypervisor, 'error', 'unknown', 'error connecting to hypervisor')
								context.services.operationNotification.createHypervisorAlarm(hypervisor, 'error connecting to hypervisor')
							}
						} else {
							updateHypervisorStatus(hypervisor, 'error', 'unknown', 'error connecting to hypervisor')
							context.services.operationNotification.createHypervisorAlarm(hypervisor, 'error connecting to hypervisor')
							allOnline = false
						}
					} else {
						updateHypervisorStatus(hypervisor, 'error', 'unknown', 'error connecting to hypervisor')
						context.services.operationNotification.createHypervisorAlarm(hypervisor, 'error connecting to hypervisor')
						allOnline = false
					}
				} else {
					context.services.operationNotification.clearHypervisorAlarm(hypervisor)
				}
			}
			if (anyOnline == true || allOnline == true) {
				def vmCacheOpts = [zone: cloud]
				def doInventory = cloud.getConfigProperty('importExisting')
				vmCacheOpts.createNew = (doInventory == 'on' || doInventory == 'true' || doInventory == true)

				def now = new Date().time
				new VirtualMachineSync(context, cloud, apiService, this).execute()
				log.debug("${cloud.name}: VirtualMachineSync in ${new Date().time - now}ms")
				response.success = true
			}
			if (allOnline == true && anyOnline == true) {
				context.async.cloud.updateCloudStatus(cloud, Cloud.Status.ok, null, syncDate)
				context.services.operationNotification.clearZoneAlarm(cloud)
			} else if (anyOnline == true) {
				context.services.operationNotification.clearZoneAlarm(cloud)
			} else {
				context.services.operationNotification.createZoneAlarm(cloud, 'no hypervisors online')
			}
		} catch (e) {
			log.error("refreshZone error:${e}", e)
		}
		return response
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
	}

	/**
	 * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
	 * @param cloudInfo instance of the cloud object that is being removed.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Returns whether the cloud supports {@link CloudPool}
	 * @return Boolean
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	@Override
	Boolean provisionRequiresResourcePool() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Network}
	 * @return Boolean
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

    @Override
    Boolean canCreateNetworks() {
        return true
    }

	/**
	 * Returns whether a cloud supports {@link CloudFolder}
	 * @return Boolean
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Datastore}
	 * @return Boolean
	 */
	@Override
	Boolean hasDatastores() {
		return false
	}

	/**
	 * Returns whether a cloud supports bare metal VMs
	 * @return Boolean
	 */
	@Override
	Boolean hasBareMetal() {
		return false
	}

	/**
	 * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
	 * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
	 * @return Boolean
	 */
	@Override
	Boolean hasCloudInit() {
		return true
	}

	/**
	 * Indicates if the cloud supports the distributed worker functionality
	 * @return Boolean
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	/**
	 * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
	 * @param computeServer server to start
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		HyperVProvisionProvider provisionProvider = new HyperVProvisionProvider(plugin, context)
		return provisionProvider.startServer(computeServer)
	}

	/**
	 * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
	 * @param computeServer server to stop
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		HyperVProvisionProvider provisionProvider = new HyperVProvisionProvider(plugin, context)
		return provisionProvider.stopServer(computeServer)
	}

	/**
	 * Called when a server should be deleted from the Cloud.
	 * @param computeServer server to delete
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		log.debug("deleteServer: ${computeServer}")
		def rtn = [success: false]
		try {
			def hypervOpts = HypervOptsUtility.getAllHypervServerOpts(context, computeServer)
			def stopResults = apiService.stopServer(hypervOpts + [turnOff: true], hypervOpts.name)
			if(stopResults.success == true) {
				def removeResults = apiService.removeServer(hypervOpts, hypervOpts.name)
				if(removeResults.success == true) {
					def deleteResults = apiService.deleteServer(hypervOpts)
					if(deleteResults.success == true) {
						rtn.success = true
					}
				}
			}
		} catch (e) {
			log.error("deleteServer error: ${e}", e)
			rtn.msg = e.message
		}
		return ServiceResponse.create(rtn)

	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
	 * This is only really necessary if the provision type code is the exact same as the cloud code.
	 * @return the provision provider code
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return HyperVProvisionProvider.PROVIDER_CODE
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
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
		return CLOUD_PROVIDER_CODE
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

	def validateRequiredConfigFields(fieldArray, config) {
		def errors = [:]
		fieldArray.each { field ->
			if (config[field] != null && config[field]?.size() == 0) {
				def display = field.replaceAll(/([A-Z])/, / $1/).toLowerCase()
				errors[field] = "Enter a ${display}"
			}
		}
		return errors
	}

	def findHypervHypervisor(Cloud cloud, String name, String ipAddress, ComputeServerType serverType) {
		log.debug("name: {}, ipAddress: {}", name, ipAddress)
		def rtn = context.services.computeServer.find(new DataQuery()
				.withFilter('zone.id', cloud.id.toLong())
				.withFilter('computeServerType', serverType)
				.withFilter('internalIp', ipAddress))
		if (!rtn) {
			rtn = context.services.computeServer.find(new DataQuery().withFilters(
					new DataFilter('zone.id', cloud.id.toLong()),
					new DataFilter('computeServerType', serverType),
					new DataOrFilter(
							new DataFilter('hostname', name),
							new DataFilter('name', name)
					)
			))
		}
		return rtn
	}

	def getHypervHypervisors(Cloud cloud) {
		def rtn = context.services.computeServer.list(new DataQuery().withFilter("zone", cloud).withFilter("computeServerType.code", "hypervHypervisor"))
		return rtn
	}

	def loadHypervHost(opts, node) {
		log.debug("opts: ${opts}")
		def rtn = [success: false]
		try {
			def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, opts.zone)
			def hypervisorOpts = HypervOptsUtility.getHypervHypervisorOpts(node)
			hypervOpts.hypervisorConfig = hypervisorOpts.hypervisorConfig
			hypervOpts.hypervisor = hypervisorOpts.hypervisor
			hypervOpts.sshHost = hypervisorOpts.sshHost
			hypervOpts.sshUsername = hypervisorOpts.sshUsername
			hypervOpts.sshPassword = hypervisorOpts.sshPassword
			hypervOpts.zoneRoot = hypervisorOpts.zoneRoot
			hypervOpts.diskRoot = hypervisorOpts.diskRoot
			hypervOpts.vmRoot = hypervisorOpts.vmRoot
			hypervOpts.sshPort = opts.zone.getConfigMap().port

			def hostResults = apiService.getHypervHost(hypervOpts)
			log.debug("hostResults: ${hostResults}")
			if (hostResults.success == true) {
				rtn.success = true
				rtn.host = hostResults.host
			}
		} catch (e) {
			log.error("loadHypervHost error:${e}", e)
		}
		return rtn
	}

	// move - > vm sync
	/*private resolveUniqueIdsToVMids(Map opts, node) {
		log.debug("opts: ${opts}")
		// if zone is null then returning from this method as rest of the method not going to execute if hosts value is null
		if (!opts.zone){
			return
		}
		DataQuery query = new DatasetQuery().withFilters(
				new DataFilter("zone.id", opts.zone?.id),
				new DataFilter("computeServerType.code", "hypervVm"),
				new DataOrFilter(
						new DataFilter('uniqueId', ''),
						new DataFilter('uniqueId', null)
				)
		)
		def hosts = context.services.computeServer.list(query)
		log.debug("hosts?.size(): ${hosts?.size()}")
		if (hosts.size() < 1)
			return
		def hypervOpts = HypervOptsUtility.getHypervZoneOpts(context, opts.zone)
		hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(node)
		def listResults = apiService.listVirtualMachines(hypervOpts, null, false)
		//listVirtualMachines(hypervOpts, false)
		log.debug("listResults: ${listResults}")
		if (listResults.success == true) {
			def remoteVms = listResults.virtualMachines
			for (server in hosts) {
				def match
				for (remoteVm in remoteVms) {
					if (remoteVm.Name == server.name) {
						match = remoteVm
					}
				}
				if (match) {
					server.uniqueId = match.ID
					context.services.computeServer.save(server)
				}
			}
		}
	}*/

	private updateHypervisorStatus(server, status, powerState, msg) {
		log.debug("server: {}, status: {}, powerState: {}, msg: {}", server, status, powerState, msg)
		if (server.status != status || server.powerState != powerState) {
			server.status = status
			server.powerState = powerState
			server.statusDate = new Date()
			server.statusMessage = msg
			context.services.computeServer.save(server)
		}
	}
}
