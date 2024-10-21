package com.morpheusdata.hyperv.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncUtils
import com.morpheusdata.hyperv.HyperVApiService
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualMachineSync {

    static final String UNMANAGED_SERVER_TYPE_CODE = 'hypervUnmanaged'
    static final String HOST_SERVER_TYPE_CODE = 'hypervHypervisor'

    private Cloud cloud
    private MorpheusContext morpheusContext
    private CloudProvider cloudProvider
    private HyperVApiService apiService

    VirtualMachineSync(MorpheusContext morpheusContext, Cloud cloud, HyperVApiService apiService, CloudProvider cloudProvider) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = apiService
        this.@cloudProvider = cloudProvider
    }

    def execute() {
        try{
            def hypervServer = morpheusContext.services.computeServer.find(new DataQuery().withFilter('zone.id', cloud.id))
            def hypervOpts = HypervOptsUtility.getHypervZoneOpts(morpheusContext, cloud)
            hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(hypervServer)
            def listResults = apiService.listVirtualMachines(hypervOpts)

            Collection<ServicePlan> availablePlans =  morpheusContext.services.servicePlan.list(new DataQuery().withFilters(
                    new DataFilter('active', true),
                    new DataFilter('deleted', '!=', true),
                    new DataFilter('provisionType.code', 'hyperv')
            ))

            ServicePlan fallbackPlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilter('code', 'hyperv-hypervisor'))

            Collection<ResourcePermission> availablePlanPermissions = []
            if(availablePlans) {
                availablePlanPermissions = morpheusContext.services.resourcePermission.list(new DataQuery().withFilters(
                        new DataFilter('morpheusResourceType', 'ServicePlan'),
                        new DataFilter('morpheusResourceId', 'in', availablePlans.collect{pl -> pl.id})
                ))
            }

            def serverType = cloudProvider.computeServerTypes.find { it.code == UNMANAGED_SERVER_TYPE_CODE }

            if (listResults.success == true){

                def hosts = morpheusContext.services.computeServer.list(new DataQuery()
                        .withFilter('zone.id', cloud.id)
                        .withFilter('computerServerType.code', '!=', HOST_SERVER_TYPE_CODE))
                def consoleEnabled = cloud.getConfigProperty('enableVnc') ? true : false

                def existingVms = morpheusContext.async.computeServer.listIdentityProjections(new DataQuery()
                        .withFilter('zone.id', cloud.id))

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(existingVms, listResults.virtualMachines as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection morpheusItem, Map cloudItem ->
                    morpheusItem.externalId == cloudItem.ID
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
                    }
                }.onAdd {itemsToAdd ->
                    addMissingVirtualMachines(itemsToAdd, availablePlans, fallbackPlan, availablePlanPermissions, hosts, consoleEnabled, serverType)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedVirtualMachines(updateItems, availablePlans, fallbackPlan, hosts, consoleEnabled, serverType)
                }.onDelete { List<ComputeServerIdentityProjection> removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.observe().blockingSubscribe()
            }
        } catch (Exception ex) {
            log.error("VirtualMachineSync error: ${ex}", ex)
        }
    }

    def addMissingVirtualMachines(List addList, Collection<ServicePlan> availablePlans, ServicePlan fallbackPlan, Collection<ResourcePermission> availablePlanPermissions, List hosts, Boolean consoleEnabled, ComputeServerType defaultServerType) {
        def doInventory = cloud.getConfigProperty('importExisting')
        if (doInventory == 'on' || doInventory == 'true' || doInventory == true) {
            log.debug("addMissingVirtualMachines ${cloud} ${addList?.size()}")

            def matchedVms
            if (hosts) {
                matchedVms = morpheusContext.services.computeServer.list(new DataQuery()
                        .withFilter('parentServer', 'in', hosts)
                        .withFilter('zone.id', cloud.id)
                        .withFilter('uniqueId', '!=', null)
                        .withFilter('uniqueId', 'in', addList.collect { it?.ID }))?.collectEntries { [(it.uniqueId): it] }
            }

            for (cloudItem in addList) {
                try {
                    if (matchedVms && matchedVms[cloudItem.ID]) {
                        //another host already has this vm, a migration perhaps..
                        def vmMatch = matchedVms[cloudItem.ID]
                        //change parentServer
                        def host = hosts?.find { host -> host.name?.toLowerCase() == cloudItem?.HostName?.toLowerCase() }
                        vmMatch.parentServer = host
                        def saveServer = morpheusContext.async.computeServer.save(vmMatch).blockingGet()
                        if (!saveServer) {
                            log.error("error switching virtual machine {} to new host {}", cloudItem.Name, host.name)
                        }
                    } else {
                        log.debug "Adding new virtual machine: ${cloudItem.Name}"
                        def vmConfig = buildVmConfig(cloudItem, defaultServerType)

                        ComputeServer add = new ComputeServer(vmConfig)

                        add.maxStorage = (cloudItem.TotalSize?.toDouble() ?: 0)
                        add.usedStorage = (cloudItem.UsedStorage?.toDouble() ?: 0)
                        add.maxMemory = (cloudItem.Memory?.toDouble() ?: 0)
                        add.maxCores = cloudItem.CPUCount.toLong() ?: 1
                        add.parentServer = hosts?.find { host -> host.name?.toLowerCase() == cloudItem?.HostName?.toLowerCase() }
                        add.plan = SyncUtils.findServicePlanBySizing(availablePlans, add.maxMemory, add.maxCores, null, fallbackPlan, null, cloud.account, availablePlanPermissions)

                        if (cloudItem.IpAddress) {
                            add.externalIp = cloudItem.IpAddress
                        }
                        if (cloudItem.InternalIp) {
                            add.internalIp = cloudItem.InternalIp
                        }
                        if (!cloudItem.InternalIp && !cloudItem.IpAddress && cloudItem.Notes) {
                            add.internalIp = cloudItem.Notes
                            add.externalIp = cloudItem.Notes
                        }

                        add.sshHost = add.internalIp ?: add.externalIp

                        if (consoleEnabled) {
                            add.consoleType = 'vmrdp'
                            add.consoleHost = add.parentServer?.name
                            add.consolePort = 2179
                            add.sshUsername = cloud.getConfigMap().username
                            if (add.sshUsername.contains('\\')) {
                                add.sshUsername = add.sshUsername.tokenize('\\')[1]
                            }
                            add.sshPassword = cloud.getConfigMap().password
                        }
                        add.capacityInfo = new ComputeCapacityInfo(maxCores: add.maxCores, maxMemory: add.maxMemory, maxStorage: add.maxStorage)
                        ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()
                        if (!savedServer) {
                            log.error "Error in creating server ${add}"
                        } else {
                            if (cloudItem.NetworkAdapter && cloudItem.NetworkAdapter != '') {
                                def network = morpheusContext.services.network.find(new DataQuery().withFilter('name', cloudItem.NetworkAdapter))
                                if (network) {
                                    def interfaceName = savedServer.sourceImage?.interfaceName ?: 'eth0'
                                    def newInterface = new ComputeServerInterface(name: interfaceName, primaryInterface: true, network: network, ipAddress: savedServer.externalIp,
                                            displayOrder: (savedServer.interfaces?.size() ?: 0) + 1
                                    )
                                    morpheusContext.async.computeServer.computeServerInterface.create([newInterface], savedServer).blockingGet()
                                }
                            }
                            syncVolumes(savedServer, cloudItem.Disks)
                            morpheusContext.async.computeServer.save(savedServer).blockingGet()
                        }
                    }
                } catch (Exception ex) {
                    log.error("Error in adding VM: ${ex.message}", ex)
                }
            }
        }
    }

    def syncVolumes(server, externalVolumes) {
        log.debug "syncVolumes: ${server}, ${groovy.json.JsonOutput.prettyPrint(externalVolumes?.encodeAsJSON()?.toString())}"
        def maxStorage = 0
        def changes = false //returns if there are changes to be saved
        try {

            def existingVolumes = server.volumes
            def existingItems = Observable.fromIterable(existingVolumes)

            def masterItems = externalVolumes

            def diskNumber = masterItems.size()

            SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume> syncTask = new SyncTask<>(existingItems, masterItems as Collection<Map>)

            syncTask.addMatchFunction { StorageVolumeIdentityProjection storageVolume, Map masterItem ->
                def storagePath = "morpheus_server_${server.id}\\${storageVolume.externalId}".toLowerCase()
                masterItem.Path.toLowerCase().contains(storagePath)
            }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
                morpheusContext.async.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
            }.onAdd { itemsToAdd ->
                addMissingStorageVolumes(itemsToAdd, server, maxStorage, diskNumber, changes)
            }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                updateMatchedStorageVolumes(updateItems, maxStorage, changes)
            }.onDelete { removeItems ->
                removeMissingStorageVolumes(removeItems, server, changes)
            }.start()

            if(server instanceof ComputeServer && server.maxStorage != maxStorage) {
                log.debug "max storage changed for ${server} from ${server.maxStorage} to ${maxStorage}"
                server.maxStorage = maxStorage
                morpheusContext.async.computeServer.save(server).blockingGet()
                changes = true
            }
        } catch(e) {
            log.error("syncVolumes error: ${e}", e)
        }
        return changes
    }

    def removeMissingStorageVolumes(removeItems, server, changes) {
        removeItems?.each { currentVolume ->
            log.debug "removing volume: ${currentVolume}"
            changes = true
            currentVolume.controller = null
            currentVolume.datastore = null

            morpheusContext.async.storageVolume.save(currentVolume).blockingGet()
            morpheusContext.async.storageVolume.remove([currentVolume], server, changes).blockingGet()
            morpheusContext.async.storageVolume.remove(currentVolume).blockingGet()
        }
    }

    def updateMatchedStorageVolumes(updateItems, maxStorage, changes) {

        updateItems?.each { updateMap ->
            log.debug("updating volume: ${updateMap.masterItem}")

            StorageVolume volume = updateMap.existingItem
            def masterItem = updateMap.masterItem

            def masterDiskSize = masterItem?.TotalSize?.toLong() ?: 0
            def sizeRange = [min:(volume.maxStorage - ComputeUtility.ONE_GIGABYTE), max:(volume.maxStorage + ComputeUtility.ONE_GIGABYTE)]
            def save = false
            if (masterDiskSize && volume.maxStorage != masterDiskSize && (masterDiskSize <= sizeRange.min || masterDiskSize >= sizeRange.max)) {
                volume.maxStorage = masterDiskSize
                save = true
            }

            def isRootVolume = (masterItem.ControllerLocation == 0)
            if(volume.rootVolume != isRootVolume) {
                volume.rootVolume = isRootVolume
                save = true
            }
            if(save) {
                morpheusContext.async.storageVolume.save(volume).blockingGet()
                changes = true
            }
            maxStorage += masterDiskSize
        }

    }

    def addMissingStorageVolumes(itemsToAdd, server, maxStorage, int diskNumber, changes){
        itemsToAdd?.each { diskData ->
            log.debug("adding new volume: ${diskData}")
            changes = true

            def datastore = diskData.datastore ?: loadDatastoreForVolume(cloud, diskData.HostVolumeId, diskData.FileShareId, diskData.PartitionUniqueId) ?: null
            def provisionProvider = cloudProvider.getProvisionProvider('hyperv.provision')
            def volumeConfig = [
                    name      : diskData.Name,
                    size      : diskData.TotalSize?.toLong() ?: 0,
                    rootVolume: (diskData.ControllerLocation == 0),
                    deviceName: (diskData.deviceName ?: provisionProvider.getDiskName(diskNumber)),
                    externalId: diskData.Name,
                    internalId: diskData.Name
            ]
            if(volumeConfig.rootVolume){
                volumeConfig.name = 'root'
            }
            if(datastore){
                volumeConfig.datastoreId = "${datastore.id}"
            }
            def storageVolume = buildStorageVolume(server.account ?: cloud.account, server, volumeConfig)

            morpheusContext.async.storageVolume.create([storageVolume], server).blockingGet()
            maxStorage += storageVolume.maxStorage ?: 0l
            diskNumber++
            log.debug("added volume: ${storageVolume?.dump()}")
        }

    }

    def buildStorageVolume(account, server, volume) {
        def storageVolume = new StorageVolume()
        storageVolume.name = volume.name
        storageVolume.account = account

        def storageType = morpheusContext.services.storageVolume.storageVolumeType.find(new DataQuery()
                .withFilter('code', 'standard'))
        storageVolume.type = storageType

        storageVolume.rootVolume = volume.rootVolume == true
        if(volume.datastoreId) {
            storageVolume.datastoreOption = volume.datastoreId
            storageVolume.refType = 'Datastore'
            storageVolume.refId = volume.datastoreId
        }

        if(volume.externalId)
            storageVolume.externalId = volume.externalId
        if(volume.internalId)
            storageVolume.internalId = volume.internalId

        if(server instanceof ComputeServer) {
            storageVolume.cloudId = server.cloud?.id
        }
        else if(server instanceof VirtualImage && server.refType == 'ComputeZone') {
            storageVolume.cloudId = server.refId?.toLong()
        } else if(server instanceof VirtualImageLocation && server.refType == 'ComputeZone') {
            storageVolume.cloudId = server.refId?.toLong()
        }

        storageVolume.deviceName = volume.deviceName

        storageVolume.removable = storageVolume.rootVolume != true
        storageVolume.displayOrder = volume.displayOrder ?: server?.volumes?.size() ?: 0
        return storageVolume
    }

    def loadDatastoreForVolume(Cloud cloud, hostVolumeId=null, fileShareId=null, partitionUniqueId=null) {
        log.debug "loadDatastoreForVolume: ${hostVolumeId}, ${fileShareId}"
        if(hostVolumeId) {

            StorageVolume storageVolume = morpheusContext.services.storageVolume.find(new DataQuery().withFilter('internalId', hostVolumeId)
                    .withFilter('datastore.refType', 'ComputeZone').withFilter('datastore.refId', cloud.id))
            def ds = storageVolume?.datastore
            if(!ds && partitionUniqueId) {

                storageVolume = morpheusContext.services.storageVolume.find(new DataQuery().withFilter('externalId', partitionUniqueId)
                        .withFilter('datastore.refType', 'ComputeZone').withFilter('datastore.refId', cloud.id))
                ds = storageVolume?.datastore
            }
            return ds
        } else if(fileShareId) {

            Datastore datastore = morpheusContext.services.cloud.datastore.find(new DataQuery()
                    .withFilter('externalId', fileShareId)
                    .withFilter('refType', 'ComputeZone')
                    .withFilter('refId', cloud.id))
            return datastore
        }
        null
    }

    private buildVmConfig(Map cloudItem, ComputeServerType defaultServerType) {

        OsType unknownOs = morpheusContext.services.osType.find(new DataQuery().withFilter("code", "unknown"))

        def vmConfig = [
                account             : cloud.account,
                externalId          : cloudItem.ID,
                name                : cloudItem.Name,
                externalIp          : cloudItem.IpAddress,
                internalIp          : cloudItem.IpAddress,
                sshHost             : cloudItem.IpAddress,
                sshUsername         : 'root',
                displayName         : cloudItem.Name,
                provision           : false,
                computeServerType   : defaultServerType,
                singleTenant        : true,
                cloud               : cloud,
                lvmEnabled          : false,
                managed             : false,
                discovered          : true,
                serverType          : 'vm',
                osType              : 'unknown',
                serverOs            : unknownOs,
                status              : 'provisioned',
                uniqueId            : cloudItem.ID,
                powerState          : cloudItem.State == 2 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                apiKey              : java.util.UUID.randomUUID(),
                hotResize           : false
        ]
        return  vmConfig
    }

    protected updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, availablePlans, fallbackPlan,
                                           List<ComputeServer> hosts, consoleEnabled, ComputeServerType defaultServerType) {
        log.debug("VirtualMachineSync >> updateMatchedVirtualMachines() called")

        try {
            def matchedServers = morpheusContext.services.computeServer.list(new DataQuery().withFilter('id', 'in', updateList.collect { up -> up.existingItem.id })
                    .withJoins(['account', 'zone', 'computeServerType', 'plan', 'chassis', 'serverOs', 'sourceImage', 'folder', 'createdBy', 'userGroup',
                                'networkDomain', 'interfaces', 'interfaces.addresses', 'controllers', 'snapshots', 'metadata', 'volumes',
                                'volumes.datastore', 'resourcePool', 'parentServer', 'capacityInfo'])).collectEntries { [(it.id): it] }

            List<ComputeServer> saves = []
            for (updateMap in updateList) {
                ComputeServer currentServer = matchedServers[updateMap.existingItem.id]
                def masterItem = updateMap.masterItem
                try {
                    log.debug("Checking state of matched HyperV Server ${masterItem.ID} - ${currentServer}")
                    if (currentServer.status != 'provisioning') {
                        try {
                            Boolean save = false
                            if (currentServer.name != masterItem.Name) {
                                currentServer.name = masterItem.Name
                                save = true
                            }

                            if (currentServer.computeServerType == null) {
                                currentServer.computeServerType = defaultServerType
                                save = true
                            }
                            if (masterItem.IpAddress && currentServer.externalIp != masterItem.IpAddress) {
                                if (currentServer.externalIp == currentServer.sshHost) {
                                    currentServer.sshHost = masterItem.IpAddress
                                }
                                currentServer.externalIp = masterItem.IpAddress
                                save = true
                            }

                            if (masterItem.InternalIp && currentServer.internalIp != masterItem.InternalIp) {
                                if (currentServer.internalIp == currentServer.sshHost) {
                                    currentServer.sshHost = masterItem.InternalIp
                                }
                                currentServer.internalIp = masterItem.InternalIp

                                save = true
                            }

                            def maxCores = masterItem.CPUCount.toLong() ?: 1
                            if (currentServer.maxCores != maxCores) {
                                currentServer.maxCores = maxCores
                                save = true
                            }
                            if (currentServer.capacityInfo && currentServer.capacityInfo.maxCores != maxCores) {
                                currentServer.capacityInfo.maxCores = maxCores
                                save = true
                            }
                            def maxMemory = masterItem.Memory?.toLong() ?: 0
                            if (currentServer.maxMemory != maxMemory) {
                                currentServer.maxMemory = maxMemory
                                save = true
                            }
                            def parentServer
                            for (host in hosts) {
                                def hostName = host.name?.toLowerCase()
                                def masterName = masterItem.HostName?.toLowerCase()
                                if (hostName == masterName) {
                                    parentServer = host
                                }
                            }

                            if (parentServer != null && currentServer.parentServer != parentServer) {
                                currentServer.parentServer = parentServer
                                save = true
                            }
                            def consoleType = consoleEnabled ? 'vmrdp' : null
                            def consolePort = consoleEnabled ? 2179 : null
                            def consoleHost = consoleEnabled ? currentServer.parentServer?.name : null
                            def consoleUsername = cloud.getConfigMap().username
                            if (consoleUsername.contains('\\')) {
                                consoleUsername = consoleUsername.tokenize('\\')[1]
                            }
                            def consolePassword = cloud.getConfigMap().password
                            if (currentServer.consoleType != consoleType) {
                                currentServer.consoleType = consoleType
                                save = true
                            }
                            if (currentServer.consoleHost != consoleHost) {
                                currentServer.consoleHost = consoleHost
                            }
                            if (currentServer.consolePort != consolePort) {
                                currentServer.consolePort = consolePort
                                save = true
                            }
                            if (consoleEnabled) {
                                if (consoleUsername != currentServer.sshUsername) {
                                    currentServer.sshUsername = consoleUsername
                                    save = true
                                }
                                if (consolePassword != currentServer.sshPassword) {
                                    currentServer.sshPassword = consolePassword
                                    save = true
                                }
                            }

                            //plan
                            ServicePlan plan = SyncUtils.findServicePlanBySizing(availablePlans, currentServer.maxMemory, currentServer.maxCores,
                                    null, fallbackPlan, currentServer.plan, currentServer.account, [])
                            if (currentServer.plan?.id != plan?.id) {
                                currentServer.plan = plan
                                save = true
                            }
                            def volumeChanged = false
                            if (masterItem.Disks) {
                                if (currentServer.status != 'resizing' && currentServer.status != 'provisioning') {
                                    volumeChanged = syncVolumes(currentServer, masterItem.Disks)
                                    if (volumeChanged == true) {
                                        save = true
                                    }
                                }
                            }

                            if (save) {
                                saves << currentServer
                            }
                        } catch (ex) {
                            log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${ex}", ex)
                        }
                    }
                } catch (Exception e) {
                    log.error "Error in updating stats: ${e.message}", e
                }
            }
            if (saves) {
                morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
            }
        } catch (Exception e){
            log.error "Error in updating virtual machines: ${e.message}", e
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        def removeItems = morpheusContext.services.computeServer.list(
                new DataQuery().withFilter("id", "in", removeList.collect { it.id })
                        .withFilter("computeServerType.code", UNMANAGED_SERVER_TYPE_CODE)
        )
        morpheusContext.async.computeServer.remove(removeItems).blockingGet()
    }
}